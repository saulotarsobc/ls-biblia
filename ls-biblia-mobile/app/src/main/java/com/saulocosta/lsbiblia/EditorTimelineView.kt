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
    enum class Lane { SPEED, ZOOM }

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
    var selectedSpeedRegionId: Long? = null
        set(value) {
            field = value
            invalidate()
        }
    var selectedZoomRegionId: Long? = null
        set(value) {
            field = value
            invalidate()
        }

    var onSeek: ((Double) -> Unit)? = null
    var onSelectRegion: ((Lane, Long) -> Unit)? = null
    var onCreateRegion: ((Lane, Double, Double) -> Unit)? = null
    var onUpdateRegion: ((Lane, Long, Double, Double) -> Boolean)? = null
    var onInteractionFinished: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(147, 155, 171)
        textSize = sp(9f)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val regionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 34, 43) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(55, 61, 74)
        strokeWidth = density
    }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(126, 79, 31) }
    private val zoomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(29, 105, 88) }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255) }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(79, 140, 255)
        strokeWidth = 2f * density
    }

    private sealed interface TouchMode {
        data object Scrub : TouchMode
        data class New(val lane: Lane, val from: Double, var to: Double) : TouchMode
        data class Move(val lane: Lane, val id: Long, val grabOffset: Double, val width: Double) : TouchMode
        data class Edge(val lane: Lane, val id: Long, val left: Boolean) : TouchMode
    }

    private data class RegionRef(val id: Long, val start: Double, val end: Double)

    private var touchMode: TouchMode? = null

    private val left get() = 2f * density
    private val right get() = width - 2f * density
    private val rulerY get() = 18f * density
    private val laneHeight get() = 29f * density
    private val speedTop get() = 41f * density
    private val zoomTop get() = 86f * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawText("CÂMERA LENTA  •  ARRASTE PARA CRIAR", left, speedTop - 7f * density, textPaint)
        canvas.drawText("ZOOM  •  ARRASTE PARA CRIAR", left, zoomTop - 7f * density, textPaint)
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
            val x = xFor(time)
            canvas.drawLine(x, speedTop, x, speedTop + laneHeight, linePaint)
            canvas.drawLine(x, zoomTop, x, zoomTop + laneHeight, linePaint)
        }
        speedRegions.forEach { region ->
            drawRegion(canvas, region.start, region.end, speedTop, speedPaint, region.id == selectedSpeedRegionId, "${region.speed}×")
        }
        zoomRegions.forEach { region ->
            drawRegion(
                canvas,
                region.start,
                region.end,
                zoomTop,
                zoomPaint,
                region.id == selectedZoomRegionId,
                String.format("%.1f×", region.zoom),
            )
        }

        val creating = touchMode as? TouchMode.New
        if (creating != null) {
            val paint = if (creating.lane == Lane.SPEED) speedPaint else zoomPaint
            val oldAlpha = paint.alpha
            paint.alpha = 150
            drawRegion(
                canvas,
                minOf(creating.from, creating.to),
                maxOf(creating.from, creating.to),
                if (creating.lane == Lane.SPEED) speedTop else zoomTop,
                paint,
                selected = false,
                label = "",
            )
            paint.alpha = oldAlpha
        }

        val playheadX = xFor(positionSeconds)
        canvas.drawLine(playheadX, rulerY, playheadX, zoomTop + laneHeight, playheadPaint)
        canvas.drawCircle(playheadX, rulerY, 4f * density, playheadPaint)
    }

    private fun drawRegion(
        canvas: Canvas,
        start: Double,
        end: Double,
        top: Float,
        paint: Paint,
        selected: Boolean,
        label: String,
    ) {
        val startX = xFor(start)
        val endX = xFor(end).coerceAtLeast(startX + 3f * density)
        canvas.drawRoundRect(startX, top, endX, top + laneHeight, 6f * density, 6f * density, paint)
        if (selected) {
            canvas.drawRoundRect(startX, top, endX, top + laneHeight, 6f * density, 6f * density, selectedPaint)
        }
        val handleWidth = if (selected) 4f * density else 2f * density
        canvas.drawRoundRect(startX, top + 5f * density, startX + handleWidth, top + laneHeight - 5f * density, 2f * density, 2f * density, handlePaint)
        canvas.drawRoundRect(endX - handleWidth, top + 5f * density, endX, top + laneHeight - 5f * density, 2f * density, 2f * density, handlePaint)
        if (label.isNotEmpty() && endX - startX > 35f * density) {
            val baseline = top + laneHeight / 2f - (regionTextPaint.ascent() + regionTextPaint.descent()) / 2f
            canvas.drawText(label, (startX + endX) / 2f, baseline, regionTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val time = timeAt(event.x)
                val lane = laneAt(event.y)
                if (lane == null) {
                    touchMode = TouchMode.Scrub
                    seek(time)
                    return true
                }

                val hit = regionAt(lane, time)
                if (hit == null) {
                    touchMode = TouchMode.New(lane, time, time)
                    seek(time)
                } else {
                    select(lane, hit.id)
                    val startX = xFor(hit.start)
                    val endX = xFor(hit.end)
                    val edgeTouch = 18f * density
                    touchMode = when {
                        abs(event.x - startX) <= edgeTouch && abs(event.x - startX) <= abs(event.x - endX) ->
                            TouchMode.Edge(lane, hit.id, left = true)
                        abs(event.x - endX) <= edgeTouch -> TouchMode.Edge(lane, hit.id, left = false)
                        else -> TouchMode.Move(lane, hit.id, time - hit.start, hit.end - hit.start)
                    }
                    seek(time)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val time = timeAt(event.x)
                when (val mode = touchMode) {
                    TouchMode.Scrub -> seek(time)
                    is TouchMode.New -> {
                        mode.to = time
                        seek(time)
                        invalidate()
                    }
                    is TouchMode.Move -> {
                        val start = (time - mode.grabOffset).coerceIn(0.0, (durationSeconds - mode.width).coerceAtLeast(0.0))
                        if (onUpdateRegion?.invoke(mode.lane, mode.id, start, start + mode.width) == true) seek(time)
                    }
                    is TouchMode.Edge -> {
                        val region = findRegion(mode.lane, mode.id) ?: return true
                        val start = if (mode.left) time.coerceAtMost(region.end - MIN_REGION_SECONDS) else region.start
                        val end = if (mode.left) region.end else time.coerceAtLeast(region.start + MIN_REGION_SECONDS)
                        if (onUpdateRegion?.invoke(mode.lane, mode.id, start, end) == true) seek(time)
                    }
                    null -> Unit
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val mode = touchMode
                touchMode = null
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    when (mode) {
                        is TouchMode.New -> {
                            val start = minOf(mode.from, mode.to)
                            val end = maxOf(mode.from, mode.to)
                            if (end - start >= MIN_REGION_SECONDS) onCreateRegion?.invoke(mode.lane, start, end)
                        }
                        is TouchMode.Move, is TouchMode.Edge -> onInteractionFinished?.invoke()
                        else -> Unit
                    }
                    performClick()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun select(lane: Lane, id: Long) {
        if (lane == Lane.SPEED) {
            selectedSpeedRegionId = id
            selectedZoomRegionId = null
        } else {
            selectedZoomRegionId = id
            selectedSpeedRegionId = null
        }
        onSelectRegion?.invoke(lane, id)
    }

    private fun seek(time: Double) {
        positionSeconds = time
        onSeek?.invoke(time)
    }

    private fun laneAt(y: Float): Lane? = when {
        y >= speedTop - 4f * density && y <= speedTop + laneHeight + 4f * density -> Lane.SPEED
        y >= zoomTop - 4f * density && y <= zoomTop + laneHeight + 4f * density -> Lane.ZOOM
        else -> null
    }

    private fun regionAt(lane: Lane, time: Double): RegionRef? = regions(lane).lastOrNull { time >= it.start && time <= it.end }

    private fun findRegion(lane: Lane, id: Long): RegionRef? = regions(lane).firstOrNull { it.id == id }

    private fun regions(lane: Lane): List<RegionRef> = if (lane == Lane.SPEED) {
        speedRegions.map { RegionRef(it.id, it.start, it.end) }
    } else {
        zoomRegions.map { RegionRef(it.id, it.start, it.end) }
    }

    private fun timeAt(x: Float): Double {
        val fraction = ((x - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
        return (durationSeconds * fraction * 100.0).roundToInt() / 100.0
    }

    private fun xFor(time: Double): Float =
        left + (right - left) * (time / durationSeconds).coerceIn(0.0, 1.0).toFloat()

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private const val MIN_REGION_SECONDS = 0.25
    }
}
