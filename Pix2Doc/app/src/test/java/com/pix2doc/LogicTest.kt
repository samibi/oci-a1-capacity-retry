package com.pix2doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class LogicTest {

    private data class P(
        override val originalIndex: Int,
        override val sortName: String?,
        override val sortDate: Long?,
    ) : Sortable

    @Test
    fun naturalSortPutsNumbersInNumericOrder() {
        val items = listOf(P(0, "img10.jpg", null), P(1, "IMG2.jpg", null), P(2, "img1.jpg", null))
        val sorted = PageSorter.sort(items, SortMode.NAME).map { it.sortName }
        assertEquals(listOf("img1.jpg", "IMG2.jpg", "img10.jpg"), sorted)
    }

    @Test
    fun dateSortKeepsMissingDatesLastAndStable() {
        val items = listOf(P(0, "a", null), P(1, "b", 300L), P(2, "c", 100L), P(3, "d", null))
        val sorted = PageSorter.sort(items, SortMode.DATE).map { it.originalIndex }
        assertEquals(listOf(2, 1, 0, 3), sorted)
    }

    @Test
    fun originalSortRestoresArrivalOrder() {
        val items = listOf(P(2, "c", 1L), P(0, "a", 3L), P(1, "b", 2L))
        assertEquals(listOf(0, 1, 2), PageSorter.sort(items, SortMode.ORIGINAL).map { it.originalIndex })
    }

    @Test
    fun portraitImageOnPortraitA4KeepsAspectRatio() {
        val p = FitMath.place(3000, 4000, PageSize.A4, 0f)
        assertEquals(FitMath.A4_W, p.pageW, 0.01f)
        assertEquals(FitMath.A4_H, p.pageH, 0.01f)
        assertEquals(3000f / 4000f, p.w / p.h, 0.001f)
        assertTrue(p.w <= p.pageW + 0.01f && p.h <= p.pageH + 0.01f)
        assertEquals(p.pageW, p.w, 0.01f) // width-limited, fills width
    }

    @Test
    fun landscapeImageGetsLandscapePageAndMargins() {
        val p = FitMath.place(4000, 3000, PageSize.A4, 10f)
        assertEquals(FitMath.A4_H, p.pageW, 0.01f)
        assertEquals(FitMath.A4_W, p.pageH, 0.01f)
        val m = FitMath.mmToPt(10f)
        assertTrue(p.x >= m - 0.01f && p.y >= m - 0.01f)
        assertEquals((p.pageW - p.w) / 2f, p.x, 0.01f)
    }

    @Test
    fun imagePageSizeIs300Dpi() {
        val p = FitMath.place(2480, 3508, PageSize.IMAGE, 5f)
        assertEquals(FitMath.A4_W, p.pageW, 0.5f)
        assertEquals(FitMath.A4_H, p.pageH, 0.5f)
    }

    @Test
    fun fileNamerFormatsAndSanitizes() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val name = FileNamer.build(FileNamer.DEFAULT_PATTERN, 0L, Locale.US)
        assertEquals("Pix2Doc_1970-01-01_00-00-00.pdf", name)
        assertEquals("a_b.pdf", FileNamer.build("'a/b'", 0L, Locale.US))
        assertTrue(FileNamer.build("bad pattern {{", 0L, Locale.US).endsWith(".pdf"))
    }
}
