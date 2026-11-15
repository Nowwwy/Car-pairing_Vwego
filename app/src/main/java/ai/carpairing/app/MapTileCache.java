package ai.carpairing.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/** Cache only tiles the user has viewed. Never prefetch or package OSM raster tiles. */
final class MapTileCache {
    static final long FRESH_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final int MAGIC = 0x43505431;
    private static final int MAX_TILE_BYTES = 1024 * 1024;
    private final File directory;
    private final long softLimitBytes;

    static final class Entry {
        final byte[] bytes;
        final long savedAt;

        Entry(byte[] bytes, long savedAt) {
            this.bytes = bytes;
            this.savedAt = savedAt;
        }

        boolean isFresh(long now) {
            return now >= savedAt && now - savedAt < FRESH_MILLIS;
        }
    }

    MapTileCache(File directory, long softLimitBytes) {
        this.directory = directory;
        this.softLimitBytes = softLimitBytes;
    }

    private File file(String key) {
        if (!key.matches("\\d{1,2}/\\d{1,6}/\\d{1,6}")) throw new IllegalArgumentException("Invalid tile key");
        String[] parts = key.split("/");
        int z = Integer.parseInt(parts[0]);
        if (z > 19 || Integer.parseInt(parts[1]) >= (1 << z) || Integer.parseInt(parts[2]) >= (1 << z)) {
            throw new IllegalArgumentException("Tile is outside its zoom bounds");
        }
        return new File(directory, key.replace('/', '_') + ".tile");
    }

    synchronized Entry read(String key) {
        File file = file(key);
        if (!file.isFile()) return null;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new java.io.FileInputStream(file)))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid cache header");
            long savedAt = input.readLong();
            int size = input.readInt();
            if (savedAt < 0 || size < 1 || size > MAX_TILE_BYTES || file.length() != size + 16L) {
                throw new IOException("Invalid cached tile length");
            }
            byte[] bytes = new byte[size];
            input.readFully(bytes);
            return new Entry(bytes, savedAt);
        } catch (IOException ignored) {
            file.delete();
            return null;
        }
    }

    synchronized boolean write(String key, byte[] bytes, long now) {
        File destination = file(key);
        if (bytes.length < 1 || bytes.length > MAX_TILE_BYTES || now < 0) return false;
        if (!directory.isDirectory() && !directory.mkdirs()) return false;
        File temporary = null;
        try {
            temporary = File.createTempFile("tile-", ".tmp", directory);
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new java.io.FileOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeLong(now);
                output.writeInt(bytes.length);
                output.write(bytes);
            }
            // A failed download/write never replaces the last complete cached tile.
            java.nio.file.Files.move(temporary.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            destination.setLastModified(now);
            prune(now);
            return true;
        } catch (IOException ignored) {
            return false;
        } finally {
            if (temporary != null) temporary.delete();
        }
    }

    private void prune(long now) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".tile"));
        if (files == null) return;
        long bytes = 0;
        for (File file : files) bytes += file.length();
        if (bytes <= softLimitBytes) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (bytes <= softLimitBytes) break;
            // Retain at least seven days even if a heavy interactive session exceeds the soft cap.
            if (now - file.lastModified() < FRESH_MILLIS) continue;
            long size = file.length();
            if (file.delete()) bytes -= size;
        }
    }
}
