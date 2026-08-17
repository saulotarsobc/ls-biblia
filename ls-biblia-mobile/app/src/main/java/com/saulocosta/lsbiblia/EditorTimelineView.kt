package com.saulocosta.lsbiblia

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

class EditorTimelineView(context: Context) : View(context) {
    var durationSeconds: Double = 1.0
        set(value) {
            field = value.coerceAtLeast(0.001)
            invalidate()
        }
    var positionSeconds: Double = 0.0
        set(value) {
            val next = value.coerceIn(0.0, durationSeconds)
            if (abs(field - next) >= 0.005) {
                field = next
                invalidate()
            }
        }
    var speedRegions: List<SpeedRegion> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var zoomRegions: List<ZoomRegion> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var cutPositions: List<Double> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var onSeek: ((Double) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(147, 155, 171)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            10f,
            resources.displayMetrics,
        )
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 34, 43) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(55, 61, 74)
        strokeWidth = density
    }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(126, 79, 31) }
    private val zoomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(29, 105, 88) }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(79, 140, 255)
        strokeWidth = 2f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 2f * density
        val right = width - 2f * density
        val rulerY = 20f * density
        val laneHeight = 26f * density
        val speedTop = 39f * density
        val zoomTop = 80f * density
        canvas.drawText("CÂMERA LENTA", left, speedTop - 7f * density, textPaint)
        canvas.drawText("ZOOM", left, zoomTop - 7f * density, textPaint)
        canvas.drawRoundRect(left, speedTop, right, speedTop + laneHeight, 7f * density, 7f * density, lanePaint)
        canvas.drawRoundRect(left, zoomTop, right, zoomTop + laneHeight, 7f * density, 7f * density, lanePaint)

        val tickCount = 4
        repeat(tickCount + 1) { index ->
            val fraction = index / tickCount.toFloat()
            val x = left + (right - left) * fraction
            canvas.drawLine(x, rulerY, x, rulerY + 6f * density, linePaint)
            val time = durationSeconds * fraction
            val label = String.format("%d:%02d", (time / 60).toInt(), (time % 60).toInt())
            canvas.drawText(label, x.coerceAtMost(right - 25f * density), rulerY - 3f * density, textPaint)
        }

        cutPositions.forEach { time ->
            val x = xFor(time, left, right)
            canvas.drawLine(x, speedTop, x, speedTop + laneHeight, linePaint)
            canvas.drawLine(x, zoomTop, x, zoomTop + laneHeight, linePaint)
        }
        speedRegions.forEach { region ->
            drawRegion(canvas, region.start, region.end, speedTop, laneHeight, speedPaint, left, right)
        }
        zoomRegions.forEach { region ->
            drawRegion(canvas, region.start, region.end, zoomTop, laneHeight, zoomPaint, left, right)
        }

        val playheadX = xFor(positionSeconds, left, right)
        canvas.drawLine(playheadX, rulerY, playheadX, zoomTop + laneHeight, playheadPaint)
        canvas.drawCircle(playheadX, rulerY, 4f * density, playheadPaint)
    }

    private fun drawRegion(
        canvas: Canvas,
        start: Double,
        end: Double,
        top: Float,
        height: Float,
        paint: Paint,
        left: Float,
        right: Float,
    ) {
        val startX = xFor(start, left, right)
        val endX = xFor(end, left, right).coerceAtLeast(startX + 3f * density)
        canvas.drawRoundRect(startX, top, endX, top + height, 6f * density, 6f * density, paint)
    }

    private fun xFor(time: Double, left: Float, right: Float): Float =
        left + (right - left) * (time / durationSeconds).coerceIn(0.0, 1.0).toFloat()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_MOVE &&
            event.action != MotionEvent.ACTION_UP) return super.onTouchEvent(event)
        val fraction = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        val time = (durationSeconds * fraction * 100.0).roundToInt() / 100.0
        positionSeconds = time
        onSeek?.invoke(time)
        if (event.action == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
