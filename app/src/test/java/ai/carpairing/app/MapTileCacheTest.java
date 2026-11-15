package ai.carpairing.app;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class MapTileCacheTest {
    private MapTileCache cache(File dir, long size) { return new MapTileCache(dir, size); }

    @Test public void survivesProcessRestartAndKeepsStaleTileForNetworkFailure() throws Exception {
        File dir = Files.createTempDirectory("map-cache").toFile();
        byte[] original = {1, 2, 3, 4};
        assertTrue(cache(dir, 10000).write("12/3493/1587", original, 1000));
        MapTileCache.Entry restored = cache(dir, 10000).read("12/3493/1587");
        assertArrayEquals(original, restored.bytes);
        assertTrue(restored.isFresh(1001));
        assertFalse(restored.isFresh(1000 + MapTileCache.FRESH_MILLIS));
        assertArrayEquals(original, cache(dir, 10000).read("12/3493/1587").bytes);
    }

    @Test public void malformedOrTruncatedCacheDoesNotCrash() throws Exception {
        File dir = Files.createTempDirectory("map-corrupt").toFile();
        File corrupt = new File(dir, "12_3493_1587.tile");
        Files.write(corrupt.toPath(), new byte[]{1, 2});
        assertNull(cache(dir, 10000).read("12/3493/1587"));
        assertFalse(corrupt.exists());
    }

    @Test public void unsuccessfulReplacementPreservesPreviousTile() throws Exception {
        File dir = Files.createTempDirectory("map-replace").toFile();
        MapTileCache cache = cache(dir, 10000);
        assertTrue(cache.write("12/3493/1587", new byte[]{9}, 1000));
        assertFalse(cache.write("12/3493/1587", new byte[1024 * 1024 + 1], 2000));
        assertArrayEquals(new byte[]{9}, cache.read("12/3493/1587").bytes);
    }

    @Test public void evictionRetainsSevenDaysAndRemovesOldestExpiredTile() throws Exception {
        File dir = Files.createTempDirectory("map-prune").toFile();
        MapTileCache cache = cache(dir, 18);
        cache.write("12/3493/1587", new byte[]{1}, 1000);
        cache.write("12/3493/1588", new byte[]{2}, 2000);
        assertNotNull(cache.read("12/3493/1587"));
        cache.write("12/3493/1589", new byte[]{3}, 2000 + MapTileCache.FRESH_MILLIS);
        assertNull(cache.read("12/3493/1587"));
        assertNull(cache.read("12/3493/1588"));
        assertNotNull(cache.read("12/3493/1589"));
    }

    @Test public void rejectsTraversalAndOutOfBoundsKeys() throws Exception {
        MapTileCache cache = cache(Files.createTempDirectory("map-key").toFile(), 10000);
        for (String key : new String[]{"../../secret", "20/0/0", "2/4/1", "2/1/4", "-1/0/0"}) {
            try { cache.read(key); fail("Accepted " + key); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
