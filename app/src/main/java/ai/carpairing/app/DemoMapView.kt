package ai.carpairing.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Draws a fictional neighborhood and a shared itinerary without a map service. */
internal class DemoMapView(context: Context, private val demoRoute: DemoRoute) : View(context) {
    companion object {
        val backgroundColor = Color.rgb(22, 35, 44)
        val companionColor = Color.rgb(235, 180, 106)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routeColor = Color.rgb(157, 219, 201)
    private val route = Path().apply {
        moveTo(225f, 530f)
        lineTo(365f, 530f)
        lineTo(365f, 365f)
        lineTo(535f, 365f)
        if (demoRoute.rest) {
            lineTo(645f, 365f)
            lineTo(645f, 270f)
        } else {
            lineTo(535f, 270f)
        }
        lineTo(765f, 270f)
        lineTo(765f, 130f)
        if (demoRoute.parking) lineTo(885f, 130f)
    }
    private val primaryRoads = listOf(
        line(-20, 530, 365, 530, 460, 615, 1020, 615),
        line(-20, 365, 535, 365, 665, 440, 1020, 440),
        line(130, -20, 130, 190, 225, 270, 1020, 270),
        line(365, -20, 365, 675),
        line(765, -20, 765, 440, 685, 520, 685, 675),
        line(535, -20, 535, 365),
        line(535, 130, 1020, 130),
    )
    private val localRoads = listOf(
        line(-20, 90, 365, 90, 535, 130),
        line(-20, 190, 365, 190, 535, 215, 765, 215),
        line(-20, 445, 365, 445, 510, 510, 685, 520),
        line(225, -20, 225, 90),
        line(225, 190, 225, 675),
        line(70, 365, 70, 530),
        line(435, 130, 435, 365),
        line(645, 130, 645, 365, 665, 440),
        line(535, 365, 645, 365),
        line(885, 130, 885, 615),
        line(765, 345, 1020, 345),
        line(765, 525, 1020, 525),
    )
    private val river = Path().apply {
        moveTo(-35f, 230f)
        cubicTo(80f, 245f, 67f, 295f, 99f, 361f)
        cubicTo(131f, 422f, 159f, 450f, 159f, 518f)
        cubicTo(159f, 580f, 125f, 620f, 153f, 675f)
    }
    private val vehicleArrow = line(0, -11, 8, 7, 0, 3, -8, 7).apply { close() }

    init {
        contentDescription = buildString {
            append("시연 지도. 민트색 경로에 내 차와 일행 차량이 표시됩니다.")
            if (demoRoute.rest) append(" 가평휴게소 경유.")
            if (demoRoute.parking) append(" 공영주차장 안내.")
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scaleX = width / 1000f
        val scaleY = height / 650f
        canvas.save()
        canvas.scale(scaleX, scaleY)

        fill(Color.rgb(27, 49, 47))
        canvas.drawRoundRect(243f, 102f, 349f, 179f, 12f, 12f, paint)
        canvas.drawRoundRect(786f, 17f, 970f, 109f, 14f, 14f, paint)
        canvas.drawRoundRect(469f, 529f, 662f, 594f, 14f, 14f, paint)
        fill(Color.rgb(30, 43, 52))
        canvas.drawRoundRect(250f, 287f, 343f, 349f, 7f, 7f, paint)
        canvas.drawRoundRect(249f, 462f, 348f, 513f, 7f, 7f, paint)
        canvas.drawRoundRect(551f, 287f, 625f, 346f, 7f, 7f, paint)
        canvas.drawRoundRect(783f, 361f, 869f, 424f, 7f, 7f, paint)
        canvas.drawRoundRect(902f, 362f, 988f, 425f, 7f, 7f, paint)
        canvas.drawRoundRect(902f, 457f, 988f, 508f, 7f, 7f, paint)

        stroke(Color.rgb(33, 61, 76), 59f)
        canvas.drawPath(river, paint)
        stroke(Color.rgb(55, 71, 81), 5f)
        localRoads.forEach { canvas.drawPath(it, paint) }
        stroke(Color.rgb(45, 60, 71), 16f)
        primaryRoads.forEach { canvas.drawPath(it, paint) }
        stroke(Color.rgb(78, 93, 102), 7f)
        primaryRoads.forEach { canvas.drawPath(it, paint) }
        stroke(Color.rgb(43, 93, 90), 22f)
        canvas.drawPath(route, paint)
        stroke(routeColor, 8f)
        canvas.drawPath(route, paint)
        canvas.restore()

        val markerScale = resources.displayMetrics.density
        canvas.save()
        canvas.translate(225 * scaleX, 530 * scaleY)
        canvas.scale(markerScale, markerScale)
        fill(routeColor)
        canvas.drawCircle(0f, 0f, 17f, paint)
        fill(backgroundColor)
        canvas.rotate(90f)
        canvas.drawPath(vehicleArrow, paint)
        canvas.restore()

        fill(backgroundColor)
        val companionX = (if (demoRoute.rest) 690 else 535) * scaleX
        canvas.drawCircle(companionX, 270 * scaleY, 15 * markerScale, paint)
        fill(companionColor)
        canvas.drawCircle(companionX, 270 * scaleY, 10 * markerScale, paint)

        val destinationX = (if (demoRoute.parking) 885 else 765) * scaleX
        val destinationY = 130 * scaleY
        fill(routeColor)
        canvas.drawCircle(destinationX, destinationY, 15 * markerScale, paint)
        fill(backgroundColor)
        canvas.drawCircle(destinationX, destinationY, 6 * markerScale, paint)
        if (demoRoute.rest) {
            fill(routeColor)
            canvas.drawCircle(645 * scaleX, 365 * scaleY, 12 * markerScale, paint)
            fill(backgroundColor)
            canvas.drawCircle(645 * scaleX, 365 * scaleY, 5 * markerScale, paint)
        }
    }

    private fun fill(color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
    }

    private fun stroke(color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = width
    }

    private fun line(vararg points: Int) = Path().apply {
        moveTo(points[0].toFloat(), points[1].toFloat())
        for (index in 2 until points.size step 2) {
            lineTo(points[index].toFloat(), points[index + 1].toFloat())
        }
    }
}
