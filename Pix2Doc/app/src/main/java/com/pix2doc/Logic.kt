package com.pix2doc

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

enum class SortMode { ORIGINAL, NAME, DATE;
    companion object {
        fun fromKey(key: String?): SortMode = when (key) {
            "name" -> NAME
            "date" -> DATE
            else -> ORIGINAL
        }
    }
}

enum class PageSize { A4, LETTER, IMAGE;
    companion object {
        fun fromKey(key: String?): PageSize = when (key) {
            "letter" -> LETTER
            "image" -> IMAGE
            else -> A4
        }
    }
}

interface Sortable {
    val originalIndex: Int
    val sortName: String?
    val sortDate: Long?
}

object PageSorter {

    fun <T : Sortable> sort(items: List<T>, mode: SortMode): List<T> = when (mode) {
        SortMode.ORIGINAL -> items.sortedBy { it.originalIndex }
        SortMode.NAME -> items.sortedWith { a, b ->
            val an = a.sortName
            val bn = b.sortName
            when {
                an == null && bn == null -> a.originalIndex.compareTo(b.originalIndex)
                an == null -> 1
                bn == null -> -1
                else -> naturalCompare(an, bn).takeIf { it != 0 }
                    ?: a.originalIndex.compareTo(b.originalIndex)
            }
        }
        SortMode.DATE -> items.sortedWith(
            compareBy<T>({ it.sortDate == null }, { it.sortDate ?: 0L }, { it.originalIndex })
        )
    }

    /** Case-insensitive compare where digit runs compare by numeric value (img2 < img10). */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length.compareTo(nb.length)
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}

data class Placement(
    val pageW: Float,
    val pageH: Float,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

object FitMath {
    const val A4_W = 595.2756f
    const val A4_H = 841.8898f
    const val LETTER_W = 612f
    const val LETTER_H = 792f
    const val IMAGE_PAGE_DPI = 300f

    fun mmToPt(mm: Float): Float = mm * 72f / 25.4f

    /**
     * Places an image of [imgW]x[imgH] (displayed orientation) on a page.
     * A4/Letter: orientation follows the image, image scaled to fit inside the margins, centered,
     * aspect ratio preserved. IMAGE: page equals the image at 300 dpi.
     */
    fun place(imgW: Int, imgH: Int, size: PageSize, marginMm: Float): Placement {
        require(imgW > 0 && imgH > 0) { "Invalid image size $imgW x $imgH" }
        if (size == PageSize.IMAGE) {
            val w = imgW * 72f / IMAGE_PAGE_DPI
            val h = imgH * 72f / IMAGE_PAGE_DPI
            return Placement(w, h, 0f, 0f, w, h)
        }
        val (shortSide, longSide) = if (size == PageSize.A4) A4_W to A4_H else LETTER_W to LETTER_H
        val landscape = imgW > imgH
        val pw = if (landscape) longSide else shortSide
        val ph = if (landscape) shortSide else longSide
        val m = mmToPt(marginMm.coerceAtLeast(0f)).coerceAtMost(min(pw, ph) / 4f)
        val aw = pw - 2 * m
        val ah = ph - 2 * m
        val s = min(aw / imgW, ah / imgH)
        val w = imgW * s
        val h = imgH * s
        return Placement(pw, ph, (pw - w) / 2f, (ph - h) / 2f, w, h)
    }

    /** Pixels needed to cover [points] at [dpi]. */
    fun pixelsFor(points: Float, dpi: Int): Int = ceil(points / 72f * dpi).toInt()
}

object FileNamer {
    const val DEFAULT_PATTERN = "'Pix2Doc_'yyyy-MM-dd_HH-mm-ss"
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    fun build(pattern: String?, millis: Long, locale: Locale = Locale.US): String {
        val p = pattern?.takeIf { it.isNotBlank() } ?: DEFAULT_PATTERN
        val formatted = try {
            SimpleDateFormat(p, locale).format(Date(millis))
        } catch (e: IllegalArgumentException) {
            SimpleDateFormat(DEFAULT_PATTERN, locale).format(Date(millis))
        }
        val clean = formatted.replace(illegal, "_").trim().trim('.').ifEmpty { "Pix2Doc" }
        return if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
    }
}
