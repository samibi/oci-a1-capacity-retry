package com.pix2doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix as AMatrix
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

data class SourceImage(
    val uri: Uri,
    override val originalIndex: Int,
    val name: String,
    val dateMillis: Long?,
) : Sortable {
    override val sortName: String get() = name
    override val sortDate: Long? get() = dateMillis
}

data class BuildSettings(
    val pageSize: PageSize,
    val marginMm: Float,
    val capDpi: Boolean,
) {
    companion object {
        fun from(ctx: Context) = BuildSettings(Prefs.pageSize(ctx), Prefs.marginMm(ctx), Prefs.capDpi(ctx))
    }
}

object PdfMeta {
    val KEY_NAME: COSName = COSName.getPDFName("Pix2DocName")
    val KEY_DATE: COSName = COSName.getPDFName("Pix2DocDate")
}

class BuildCancelledException : Exception("cancelled")

class PdfBuilder(private val context: Context, private val settings: BuildSettings) {

    private val resolver get() = context.contentResolver

    fun describe(uris: List<Uri>): List<SourceImage> = uris.mapIndexed { i, uri ->
        SourceImage(uri, i, queryName(uri) ?: "image_${i + 1}", readDate(uri))
    }

    /** Builds the PDF into [out]. Returns the page count. */
    fun build(
        images: List<SourceImage>,
        out: File,
        onProgress: (done: Int, total: Int, name: String) -> Unit,
        isCancelled: () -> Boolean,
    ): Int {
        return PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            doc.documentInformation.creator = "Pix2Doc"
            doc.documentInformation.producer = "Pix2Doc"
            images.forEachIndexed { i, img ->
                if (isCancelled()) throw BuildCancelledException()
                onProgress(i, images.size, img.name)
                addPage(doc, img)
            }
            if (isCancelled()) throw BuildCancelledException()
            onProgress(images.size, images.size, "")
            doc.save(out)
            images.size
        }
    }

    private fun addPage(doc: PDDocument, img: SourceImage) {
        val bytes = resolver.openInputStream(img.uri)?.use { it.readBytes() }
            ?: throw IOException("Cannot read ${img.name}")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val storedW = bounds.outWidth
        val storedH = bounds.outHeight
        if (storedW <= 0 || storedH <= 0) throw IOException("Unsupported image: ${img.name}")

        val rotation = exifRotation(bytes)
        val swap = rotation == 90 || rotation == 270
        val dispW = if (swap) storedH else storedW
        val dispH = if (swap) storedW else storedH
        val placement = FitMath.place(dispW, dispH, settings.pageSize, settings.marginMm)

        val isJpeg = bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val isPng = bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()

        var scale = 1f
        if (settings.capDpi && settings.pageSize != PageSize.IMAGE) {
            val needW = FitMath.pixelsFor(placement.w, 300)
            if (dispW > needW * 1.05f) scale = needW.toFloat() / dispW
        }

        val image: PDImageXObject
        val drawRotation: Int
        if (isJpeg && scale == 1f) {
            // Original JPEG bytes embedded untouched (DCTDecode): no re-compression, no resampling.
            image = JPEGFactory.createFromByteArray(doc, bytes)
            drawRotation = rotation
        } else {
            val bmp = decodeOriented(bytes, storedW, storedH, rotation, scale)
            try {
                image = if (isPng || bmp.hasAlpha()) {
                    LosslessFactory.createFromImage(doc, bmp)
                } else {
                    JPEGFactory.createFromImage(doc, bmp, 0.95f)
                }
            } finally {
                bmp.recycle()
            }
            drawRotation = 0
        }

        val page = PDPage(PDRectangle(placement.pageW, placement.pageH))
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            if (drawRotation == 0) {
                cs.drawImage(image, placement.x, placement.y, placement.w, placement.h)
            } else {
                val cx = placement.x + placement.w / 2f
                val cy = placement.y + placement.h / 2f
                // EXIF rotation is clockwise; PDF rotation is counter-clockwise (y axis up).
                val theta = Math.toRadians(-drawRotation.toDouble())
                val iw = if (swap) placement.h else placement.w
                val ih = if (swap) placement.w else placement.h
                cs.saveGraphicsState()
                cs.transform(Matrix.getRotateInstance(theta, cx, cy))
                cs.drawImage(image, -iw / 2f, -ih / 2f, iw, ih)
                cs.restoreGraphicsState()
            }
        }
        page.cosObject.setString(PdfMeta.KEY_NAME, img.name)
        img.dateMillis?.let { page.cosObject.setLong(PdfMeta.KEY_DATE, it) }
    }

    private fun decodeOriented(bytes: ByteArray, w: Int, h: Int, rotation: Int, scale: Float): Bitmap {
        val targetW = max(1, (w * scale).roundToInt())
        val targetH = max(1, (h * scale).roundToInt())
        var sample = 1
        while (w / (sample * 2) >= targetW && h / (sample * 2) >= targetH) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IOException("Cannot decode image")
        if (bmp.width > targetW * 1.01f) {
            val scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        if (rotation != 0) {
            val m = AMatrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        return bmp
    }

    private fun exifRotation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees
    } catch (e: Exception) {
        0
    }

    private fun queryName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment

    private fun readDate(uri: Uri): Long? {
        try {
            resolver.openInputStream(uri)?.use { s ->
                val exif = ExifInterface(s)
                val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (raw != null) {
                    val parsed = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw.trim())
                    if (parsed != null) return parsed.time
                }
            }
        } catch (e: Exception) {
            // no EXIF; fall back to provider columns
        }
        try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                fun long(col: String): Long? {
                    val i = c.getColumnIndex(col)
                    return if (i >= 0 && !c.isNull(i)) c.getLong(i).takeIf { it > 0 } else null
                }
                return long(MediaStore.MediaColumns.DATE_TAKEN)
                    ?: long(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    ?: long(MediaStore.MediaColumns.DATE_MODIFIED)?.let { it * 1000L }
            }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }
}
