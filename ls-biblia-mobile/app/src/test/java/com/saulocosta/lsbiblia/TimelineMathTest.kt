package com.saulocosta.lsbiblia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineMathTest {
    @Test
    fun normalizeRangesMergesOverlapsAndDropsEmptyRanges() {
        val result = TimelineMath.normalizeRanges(
            listOf(
                SourceRange(5.0, 8.0),
                SourceRange(1.0, 3.0),
                SourceRange(2.5, 6.0),
                SourceRange(9.0, 9.0),
            ),
        )

        assertEquals(listOf(SourceRange(1.0, 8.0)), result)
    }

    @Test
    fun editAndSourceMappingsHonorRemovedGaps() {
        val ranges = listOf(SourceRange(10.0, 12.0), SourceRange(20.0, 23.0))

        assertEquals(20.5, TimelineMath.editToSource(2.5, ranges), 0.0001)
        assertEquals(3.5, TimelineMath.sourceToEdit(21.5, ranges)!!, 0.0001)
        assertNull(TimelineMath.sourceToEdit(15.0, ranges))
    }

    @Test
    fun speedRegionSplitsAtomsAndExtendsOutputDuration() {
        val atoms = TimelineMath.buildAtoms(
            listOf(SourceRange(10.0, 20.0)),
            listOf(SpeedRegion(1, 2.0, 6.0, 0.5f)),
        )

        assertEquals(3, atoms.size)
        assertEquals(14.0, TimelineMath.outputDuration(atoms), 0.0001)
        assertEquals(0.5f, atoms[1].speed)
    }

    @Test
    fun zoomRampsInAndClampsTheFrameCenter() {
        val region = ZoomRegion(
            id = 1,
            start = 2.0,
            end = 6.0,
            zoom = 2f,
            centerX = 0f,
            centerY = 1f,
            ramp = 1.0,
        )

        assertEquals(1f, TimelineMath.zoomAt(2.0, listOf(region)).zoom, 0.0001f)
        val middle = TimelineMath.zoomAt(4.0, listOf(region))
        assertEquals(2f, middle.zoom, 0.0001f)
        assertEquals(0.25f, middle.centerX, 0.0001f)
        assertEquals(0.75f, middle.centerY, 0.0001f)
    }
}
