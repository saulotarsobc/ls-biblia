package com.saulocosta.lsbiblia

import kotlin.math.max
import kotlin.math.min

data class SourceRange(val start: Double, val end: Double)

data class SpeedRegion(
    val id: Long,
    var start: Double,
    var end: Double,
    var speed: Float = 0.5f,
)

data class ZoomRegion(
    val id: Long,
    var start: Double,
    var end: Double,
    var zoom: Float = 1.8f,
    var centerX: Float = 0.5f,
    var centerY: Float = 0.45f,
    var ramp: Double = 0.4,
)

data class EditState(
    val ranges: List<SourceRange>,
    val speedRegions: MutableList<SpeedRegion> = mutableListOf(),
    val zoomRegions: MutableList<ZoomRegion> = mutableListOf(),
)

data class EditAtom(
    val sourceStart: Double,
    val sourceEnd: Double,
    val speed: Float,
    val editStart: Double,
)

data class ZoomValue(val zoom: Float, val centerX: Float, val centerY: Float)

object TimelineMath {
    private const val EPS = 0.0001

    fun versesToRanges(verses: List<Verse>): List<SourceRange> {
        if (verses.isEmpty()) return emptyList()
        val sorted = verses.sortedBy(Verse::start)
        val result = mutableListOf(SourceRange(sorted.first().start, sorted.first().end))
        sorted.drop(1).forEach { verse ->
            val last = result.last()
            if (verse.start - last.end <= 0.05) {
                result[result.lastIndex] = last.copy(end = max(last.end, verse.end))
            } else {
                result += SourceRange(verse.start, verse.end)
            }
        }
        return result
    }

    fun normalizeRanges(ranges: List<SourceRange>): List<SourceRange> {
        val valid = ranges.filter { it.end - it.start > EPS }.sortedBy(SourceRange::start)
        val result = mutableListOf<SourceRange>()
        valid.forEach { range ->
            val last = result.lastOrNull()
            if (last != null && range.start <= last.end + EPS) {
                result[result.lastIndex] = last.copy(end = max(last.end, range.end))
            } else {
                result += range.copy()
            }
        }
        return result
    }

    fun editDuration(ranges: List<SourceRange>): Double =
        normalizeRanges(ranges).sumOf { it.end - it.start }

    fun editToSource(time: Double, ranges: List<SourceRange>): Double {
        val normalized = normalizeRanges(ranges)
        if (normalized.isEmpty()) return time
        var editStart = 0.0
        normalized.forEach { range ->
            val editEnd = editStart + range.end - range.start
            if (time < editEnd - EPS) return range.start + max(0.0, time - editStart)
            editStart = editEnd
        }
        return normalized.last().end
    }

    fun sourceToEdit(sourceTime: Double, ranges: List<SourceRange>): Double? {
        var editStart = 0.0
        normalizeRanges(ranges).forEach { range ->
            if (sourceTime >= range.start - EPS && sourceTime <= range.end + EPS) {
                return editStart + sourceTime - range.start
            }
            editStart += range.end - range.start
        }
        return null
    }

    fun speedAt(time: Double, regions: List<SpeedRegion>): Float =
        regions.firstOrNull { time >= it.start - EPS && time < it.end - EPS }?.speed ?: 1f

    fun buildAtoms(ranges: List<SourceRange>, regions: List<SpeedRegion>): List<EditAtom> {
        val normalized = normalizeRanges(ranges)
        if (normalized.isEmpty()) return emptyList()

        data class Span(val editStart: Double, val editEnd: Double, val source: SourceRange)

        var accumulated = 0.0
        val spans = normalized.map { range ->
            Span(accumulated, accumulated + range.end - range.start, range).also {
                accumulated = it.editEnd
            }
        }
        val cuts = sortedSetOf(0.0, accumulated)
        spans.forEach {
            cuts += it.editStart
            cuts += it.editEnd
        }
        regions.forEach { region ->
            if (region.start > EPS && region.start < accumulated - EPS) cuts += region.start
            if (region.end > EPS && region.end < accumulated - EPS) cuts += region.end
        }

        return cuts.zipWithNext().mapNotNull { (start, end) ->
            if (end - start < EPS) return@mapNotNull null
            val middle = (start + end) / 2.0
            val span = spans.firstOrNull { middle >= it.editStart && middle < it.editEnd }
                ?: return@mapNotNull null
            EditAtom(
                sourceStart = span.source.start + start - span.editStart,
                sourceEnd = span.source.start + end - span.editStart,
                speed = speedAt(middle, regions),
                editStart = start,
            )
        }
    }

    fun outputDuration(atoms: List<EditAtom>): Double =
        atoms.sumOf { (it.sourceEnd - it.sourceStart) / it.speed }

    /**
     * Maps the continuous exported-video clock back to the editor clock.
     *
     * Speed regions stretch or compress the output duration of an atom, but zoom
     * regions are authored against the editor clock. Keeping this conversion in
     * one place prevents visual effects from restarting at atom boundaries.
     */
    fun outputToEdit(time: Double, atoms: List<EditAtom>): Double {
        if (atoms.isEmpty()) return max(0.0, time)

        val outputTime = max(0.0, time)
        var outputStart = 0.0
        atoms.forEach { atom ->
            val editDuration = atom.sourceEnd - atom.sourceStart
            val outputEnd = outputStart + editDuration / atom.speed
            if (outputTime < outputEnd) {
                return atom.editStart + (outputTime - outputStart) * atom.speed
            }
            outputStart = outputEnd
        }

        val last = atoms.last()
        return last.editStart + last.sourceEnd - last.sourceStart
    }

    fun zoomAt(time: Double, regions: List<ZoomRegion>): ZoomValue {
        var zoom = 1f
        var centerX = 0.5f
        var centerY = 0.5f
        regions.forEach { region ->
            val weight = zoomWeight(time, region).toFloat()
            zoom += (region.zoom - 1f) * weight
            centerX += (region.centerX - 0.5f) * weight
            centerY += (region.centerY - 0.5f) * weight
        }
        return clampCenter(ZoomValue(zoom, centerX, centerY))
    }

    private fun zoomWeight(time: Double, region: ZoomRegion): Double {
        if (time <= region.start || time >= region.end) return 0.0
        val effectiveRamp = max(0.001, min(region.ramp, (region.end - region.start) / 2.0))
        val fraction = min(
            1.0,
            max(0.0, min((time - region.start) / effectiveRamp, (region.end - time) / effectiveRamp)),
        )
        return fraction * fraction * (3.0 - 2.0 * fraction)
    }

    fun clampCenter(value: ZoomValue): ZoomValue {
        if (value.zoom <= 1f) return ZoomValue(value.zoom, 0.5f, 0.5f)
        val half = 0.5f / value.zoom
        return value.copy(
            centerX = value.centerX.coerceIn(half, 1f - half),
            centerY = value.centerY.coerceIn(half, 1f - half),
        )
    }
}
