package ai.carpairing.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan
import kotlin.math.pow

internal data class MapPoint(val latitude: Double, val longitude: Double) {
    val valid get() = latitude in -85.0..85.0 && longitude in -180.0..180.0 &&
        (latitude != 0.0 || longitude != 0.0)

    fun worldX(size: Double) = (longitude + 180) / 360 * size
    fun worldY(size: Double) = (1 - asinh(tan(latitude * PI / 180)) / PI) / 2 * size
}

internal class StreetMapView(
    context: Context,
    private var car: MapPoint?,
    private var destination: MapPoint?,
) : View(context) {
    private var fittedBounds: DoubleArray? = null
    private var companions = emptyList<MapPoint>()
    private var companionRoute = emptyList<MapPoint>()
    fun setCompanionRoute(points:List<MapPoint>){if(points===companionRoute)return;val safe=points.takeIf{it.all(MapPoint::valid)}?:emptyList();if(companionRoute!=safe){companionRoute=safe;fittedBounds=null;invalidate()}}
    private var route = emptyList<MapPoint>()
    fun setRoute(points:List<MapPoint>){if(points===route)return;val safe=points.takeIf{it.all(MapPoint::valid)}?:emptyList();if(route!=safe){route=safe;fittedBounds=null;invalidate()}}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val tileRect = RectF()
    private val density = resources.displayMetrics.density
    private val attributionRect = RectF()
    private var dragDistance = 0f
    private val redrawTile = {
        if (isAttachedToWindow) {
            postInvalidateOnAnimation()
            postInvalidateDelayed(30_000)
        }
    }

    init {
        contentDescription = "도로 지도. 파란색은 현재 위치, 보라색은 목적지입니다."
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private var zoomOffset=0
    private var panX=0.0
    private var panY=0.0
    private var touchX=0f
    private var touchY=0f
    fun zoomBy(delta:Int){zoomOffset=(zoomOffset+delta).coerceIn(-3,5);panX=0.0;panY=0.0;invalidate()}
    fun recenter(){zoomOffset=0;panX=0.0;panY=0.0;invalidate()}
    override fun onTouchEvent(event:MotionEvent):Boolean{
        val factor=max(1f,max(width/850f,height/500f))
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{touchX=event.x;touchY=event.y;dragDistance=0f;parent?.requestDisallowInterceptTouchEvent(true);return true}
            MotionEvent.ACTION_MOVE->{dragDistance+=kotlin.math.abs(event.x-touchX)+kotlin.math.abs(event.y-touchY);panX-=(event.x-touchX)/factor;panY-=(event.y-touchY)/factor;touchX=event.x;touchY=event.y;invalidate();return true}
            MotionEvent.ACTION_UP->{
                if(dragDistance<8*density&&attributionRect.contains(event.x,event.y)) {
                    runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.openstreetmap.org/copyright")))}
                }
                performClick();parent?.requestDisallowInterceptTouchEvent(false);return true
            }
            MotionEvent.ACTION_CANCEL->{parent?.requestDisallowInterceptTouchEvent(false);return true}
        };return true
    }
    override fun performClick():Boolean{super.performClick();return true}

    fun update(car: MapPoint?, destination: MapPoint?, companions: List<MapPoint>) {
        if (this.car == car && this.destination == destination && this.companions == companions) return
        this.car = car
        this.destination = destination
        this.companions = companions
        fittedBounds = null
        contentDescription = "도로 지도. 파란색은 현재 위치, 보라색은 목적지, 빨간색은 일행 위치입니다."
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(236, 239, 236))
        val scale = max(1f, max(width / 850f, height / 500f))
        val mapWidth = width / scale
        val mapHeight = height / scale
        // Tile arrivals and pan gestures do not change the route's geographic bounds.
        val bounds=fittedBounds ?: run {
            val points=(listOfNotNull(car,destination)+companions+route+companionRoute).filter{it.valid}.ifEmpty{listOf(PANGYO)}
            val xs=points.map{it.worldX(256.0)};val ys=points.map{it.worldY(256.0)}
            doubleArrayOf(xs.min(),xs.max(),ys.min(),ys.max()).also{fittedBounds=it}
        }
        val minX=bounds[0];val maxX=bounds[1];val minY=bounds[2];val maxY=bounds[3]
        val factor = min(mapWidth * 0.65 / (maxX - minX), mapHeight * 0.48 / (maxY - minY))
        val fittedZoom=if(factor.isFinite())floor(log2(factor)).toInt() else 15
        val zoom = (fittedZoom+zoomOffset).coerceIn(2, 17)
        val tileCount = 1 shl zoom
        val worldSize = 256.0 * tileCount
        val left = (minX + maxX) / 2 * tileCount - mapWidth / 2 + panX
        val top = (minY + maxY) / 2 * tileCount - mapHeight / 2 + panY
        canvas.save()
        canvas.scale(scale, scale)
        val firstX = floor(left / 256).toInt()
        val lastX = floor((left + mapWidth - 1) / 256).toInt()
        val firstY = floor(top / 256).toInt().coerceAtLeast(0)
        val lastY = floor((top + mapHeight - 1) / 256).toInt().coerceAtMost(tileCount - 1)
        paint.style = Paint.Style.FILL
        var tileCountVisible = 0
        var tileCountReady = 0
        var tileCountFailed = 0
        var staleTiles = false
        val readyTiles=ArrayList<Triple<Int,Int,Bitmap>>()
        for (x in firstX..lastX) for (y in firstY..lastY) {
            tileCountVisible++
            val wrappedX = Math.floorMod(x, tileCount)
            val tile = Tiles.get(context, "$zoom/$wrappedX/$y", redrawTile)
            if(tile.failed)tileCountFailed++
            val bitmap = tile.bitmap ?: continue
            tileCountReady++
            staleTiles = staleTiles || tile.stale
            readyTiles.add(Triple(x,y,bitmap))
        }
        // Opaque online tiles completely cover the fallback; draw it only for missing tiles.
        val localCoverage = if(tileCountReady < tileCountVisible)
            drawFallback(canvas, OfflineRoads.get(context,redrawTile), worldSize,left,top,mapWidth,mapHeight,zoom)
            else false
        paint.style=Paint.Style.FILL
        for((x,y,bitmap) in readyTiles){
            val dx = (x * 256 - left).toFloat()
            val dy = (y * 256 - top).toFloat()
            tileRect.set(dx, dy, dx + 256, dy + 256)
            canvas.drawBitmap(bitmap, null, tileRect, paint)
        }
        fun drawRoute(points:List<MapPoint>,color:Int){
            if(points.size<2)return
            path.rewind()
            points.forEachIndexed{i,p->val x=(p.worldX(worldSize)-left).toFloat();val y=(p.worldY(worldSize)-top).toFloat();if(i==0)path.moveTo(x,y) else path.lineTo(x,y)}
            paint.style=Paint.Style.STROKE;paint.color=Color.WHITE;paint.strokeWidth=8f;canvas.drawPath(path,paint)
            paint.color=color;paint.strokeWidth=5f;canvas.drawPath(path,paint);paint.style=Paint.Style.FILL
        }
        drawRoute(companionRoute,Color.rgb(208,55,62))
        drawRoute(route,Color.rgb(22,96,210))
        canvas.restore()
        destination?.let { drawMarker(canvas, it, worldSize, left, top, scale, Color.rgb(106, 54, 163)) }
        companions.forEach { drawMarker(canvas, it, worldSize, left, top, scale, Color.rgb(208,55,62)) }
        car?.let { drawMarker(canvas, it, worldSize, left, top, scale, Color.rgb(22,96,210)) }
        val status = when {
            tileCountReady == tileCountVisible && tileCountVisible > 0 -> if(staleTiles) "저장된 지도 · 상세지도 다시 연결 중" else null
            localCoverage -> if(tileCountFailed>0) "기본 도로 지도 · 상세지도 다시 연결 중" else "기본 도로 지도 · 상세지도 불러오는 중"
            tileCountFailed > 0 -> "지도 연결을 확인해 주세요 · 위치는 계속 표시됩니다"
            else -> "도로 지도를 불러오는 중 · 위치는 계속 표시됩니다"
        }
        status?.let { drawStatus(canvas, it, tileCountReady == 0 && !localCoverage) }
        drawAttribution(canvas)
    }

    private fun drawFallback(canvas: Canvas, fallback: List<OfflineRoads.Feature>, worldSize: Double,
                             left: Double, top: Double, mapWidth: Float, mapHeight: Float, zoom: Int): Boolean {
        val tileFactor = worldSize / 256.0
        val viewLeft=left/tileFactor; val viewTop=top/tileFactor
        val viewRight=(left+mapWidth)/tileFactor; val viewBottom=(top+mapHeight)/tileFactor
        val roadScale=2.0.pow(zoom-15).toFloat().coerceIn(0.16f,1.8f)
        var drawn=false
        for (feature in fallback) {
            if(feature.right<viewLeft||feature.left>viewRight||feature.bottom<viewTop||feature.top>viewBottom)continue
            if(zoom<12&&feature.kind==MapFeatureKind.ROAD)continue
            drawn=true
            path.rewind()
            feature.points.forEachIndexed { index, point ->
                val x = (point.x * tileFactor - left).toFloat()
                val y = (point.y * tileFactor - top).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paint.color = feature.kind.color
            if (feature.kind.width == 0f) {
                path.close()
                paint.style = Paint.Style.FILL
                canvas.drawPath(path, paint)
            } else {
                paint.style = Paint.Style.STROKE
                if (feature.kind == MapFeatureKind.MAIN || feature.kind == MapFeatureKind.ROAD) {
                    paint.strokeWidth = feature.kind.width * roadScale + 0.8f
                    paint.color = Color.rgb(177, 186, 181)
                    canvas.drawPath(path, paint)
                }
                paint.color = feature.kind.color
                paint.strokeWidth = feature.kind.width * roadScale
                canvas.drawPath(path, paint)
            }
        }
        return drawn
    }

    private fun drawStatus(canvas: Canvas, status: String, centered: Boolean) {
        paint.style=Paint.Style.FILL
        paint.textAlign=Paint.Align.LEFT
        paint.textSize=12*density
        val maxTextWidth=(width-48*density).coerceAtLeast(40*density)
        val text=android.text.TextUtils.ellipsize(status,android.text.TextPaint(paint),maxTextWidth,android.text.TextUtils.TruncateAt.END).toString()
        val textWidth=paint.measureText(text)
        val x=if(centered)(width-textWidth-24*density)/2 else 12*density
        val y=if(centered)height/2f-20*density else 12*density
        paint.color=Color.argb(240,250,252,249)
        canvas.drawRoundRect(x,y,x+textWidth+24*density,y+36*density,10*density,10*density,paint)
        paint.color=Color.rgb(54,71,68)
        canvas.drawText(text,x+12*density,y+23*density,paint)
    }

    private fun drawAttribution(canvas: Canvas) {
        paint.style=Paint.Style.FILL
        paint.textAlign=Paint.Align.LEFT
        paint.textSize=11*density
        val label="© OpenStreetMap contributors"
        val textWidth=paint.measureText(label)
        attributionRect.set((width-textWidth-20*density).coerceAtLeast(0f),height-27*density,width.toFloat(),height.toFloat())
        paint.color=Color.argb(238,255,255,255)
        canvas.drawRect(attributionRect,paint)
        paint.color=Color.rgb(40,59,64)
        canvas.drawText(label,attributionRect.left+10*density,height-9*density,paint)
    }

    private fun drawMarker(
        canvas: Canvas, point: MapPoint, worldSize: Double, left: Double, top: Double, scale: Float, color: Int,
    ) {
        if(!point.valid)return
        val x = ((point.worldX(worldSize) - left) * scale).toFloat()
        val y = ((point.worldY(worldSize) - top) * scale).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, 11 * density, paint)
        paint.color = color
        canvas.drawCircle(x, y, 8 * density, paint)
    }

    private companion object {
        val PANGYO = MapPoint(37.4130, 127.0940)
    }
}

private enum class MapFeatureKind(val color: Int, val width: Float = 0f) {
    GREEN(Color.rgb(201, 225, 197)), WATER(Color.rgb(171, 212, 230)),
    BUILDING(Color.rgb(207, 207, 200)), STREAM(Color.rgb(154, 204, 226), 3f),
    PATH(Color.rgb(210, 197, 177), 1f), ROAD(Color.WHITE, 4f), MAIN(Color.rgb(248, 226, 162), 7f),
}

private object OfflineRoads {
    data class ProjectedPoint(val x: Double, val y: Double)
    data class Feature(val kind: MapFeatureKind, val points: List<ProjectedPoint>) {
        val left = points.minOf { it.x }
        val right = points.maxOf { it.x }
        val top = points.minOf { it.y }
        val bottom = points.maxOf { it.y }
    }
    private var features: List<Feature>? = null
    private var loading = false
    private val callbacks = mutableSetOf<() -> Unit>()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun get(context: Context, redraw: () -> Unit): List<Feature> {
        features?.let { return it }
        callbacks.add(redraw)
        if (!loading) {
            loading = true
            val app = context.applicationContext
            executor.execute {
                val result = listOf(R.raw.pangyo_osm, R.raw.seoul_south_osm).flatMap { resource ->
                    runCatching {
                        app.resources.openRawResource(resource).use { stream ->
                            GZIPInputStream(stream).bufferedReader().useLines { rows ->
                                rows.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { row ->
                                    runCatching {
                                        val parts = row.split(' ')
                                        val kind = MapFeatureKind.valueOf(parts.first().uppercase())
                                        val points = parts.drop(1).map {
                                            val values = it.split(',')
                                            val point = MapPoint(values[0].toDouble(), values[1].toDouble())
                                            require(point.valid)
                                            ProjectedPoint(point.worldX(256.0), point.worldY(256.0))
                                        }
                                        require(points.size >= 2)
                                        Feature(kind, points)
                                    }.getOrNull()
                                }.toList()
                            }
                        }
                    }.getOrDefault(emptyList())
                }.sortedBy { it.kind.ordinal }
                main.post {
                    features = result
                    callbacks.toList().forEach { it() }
                    callbacks.clear()
                }
            }
        }
        return emptyList()
    }
}

private object Tiles {
    data class State(val bitmap: Bitmap?, val stale: Boolean, val failed: Boolean)
    private data class Image(val bitmap: Bitmap, val savedAt: Long)
    private val images = object : LruCache<String, Image>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Image) = value.bitmap.allocationByteCount
    }
    private val retryAt = LruCache<String, Long>(192)
    private val pending = mutableMapOf<String, MutableSet<() -> Unit>>()
    private val executor = Executors.newFixedThreadPool(2)
    private val diskReader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var disk: MapTileCache? = null

    // Called on the UI thread. Disk and HTTP work is always off the drawing thread.
    fun get(context: Context, key: String, redraw: () -> Unit): State {
        val image = images.get(key)
        val now = System.currentTimeMillis()
        val stale = image != null && (now < image.savedAt || now - image.savedAt >= MapTileCache.FRESH_MILLIS)
        val failed = (retryAt.get(key) ?: 0L) > android.os.SystemClock.elapsedRealtime()
        if (image != null && !stale) return State(image.bitmap, false, false)
        if (failed) return State(image?.bitmap, stale, true)
        pending[key]?.let { it.add(redraw); return State(image?.bitmap, stale, false) }
        // Rapid pans must not create an unbounded background download queue.
        if(pending.size>=48)return State(image?.bitmap,stale,false)
        pending[key] = mutableSetOf(redraw)
        if (disk == null) {
            // filesDir survives process restarts and is not an OS-evictable temporary cache.
            disk = MapTileCache(File(context.filesDir, "viewed-map-tiles"), 64L * 1024 * 1024)
        }
        val cache = disk!!
        diskReader.execute {
            val stored = cache.read(key)
            val storedBitmap = stored?.let { decodeTile(it.bytes) }
            if (stored != null && storedBitmap != null) {
                main.post {
                    images.put(key, Image(storedBitmap, stored.savedAt))
                    pending[key]?.toList()?.forEach { it() }
                }
                if (stored.isFresh(System.currentTimeMillis())) {
                    main.post { pending.remove(key)?.forEach { it() } }
                    return@execute
                }
            }
            executor.execute {
            val bytes = runCatching {
                val connection = URL("https://tile.openstreetmap.org/$key.png").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 4_000
                    connection.readTimeout = 5_000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", "CarPairing/1.0 (ai.carpairing.app; Pleos Android map preview)")
                    connection.setRequestProperty("Accept", "image/png")
                    if (stored != null && storedBitmap != null) connection.ifModifiedSince = stored.savedAt
                    when (connection.responseCode) {
                        HttpURLConnection.HTTP_NOT_MODIFIED -> stored?.bytes
                        HttpURLConnection.HTTP_OK -> connection.inputStream.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= 1024 * 1024) { "Tile response exceeds size limit" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        else -> null
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            val bitmap = bytes?.let { decodeTile(it) }
            val fetchedAt = System.currentTimeMillis()
            if (bitmap != null && bytes != null) cache.write(key, bytes, fetchedAt)
            main.post {
                if (bitmap != null) {
                    images.put(key, Image(bitmap, fetchedAt))
                    retryAt.remove(key)
                } else {
                    // Keep any stale bitmap; a network failure must not erase a usable map.
                    retryAt.put(key, android.os.SystemClock.elapsedRealtime() + 30_000)
                }
                pending.remove(key)?.forEach { it() }
            }
            }
        }
        return State(image?.bitmap, stale, false)
    }

    private fun decodeTile(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // Reject HTML/error payloads and oversized images before allocating a bitmap.
        if (bounds.outWidth != 256 || bounds.outHeight != 256) null
        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
