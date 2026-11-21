package de.dateimanager.app

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File

class PdfDoc(file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = Any()
    val pageCount: Int = renderer.pageCount

    fun render(index: Int, width: Int): Bitmap = synchronized(lock) {
        val page = renderer.openPage(index)
        try {
            val h = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } finally {
            page.close()
        }
    }

    fun close() {
        synchronized(lock) {
            try { renderer.close() } catch (e: Exception) { }
            try { pfd.close() } catch (e: Exception) { }
        }
    }
}

object PdfThumbs {
    private val cache = LruCache<String, Bitmap>(40)

    fun get(path: String): Bitmap? {
        val hit = cache.get(path)
        if (hit != null) return hit
        val bmp: Bitmap? = try {
            val doc = PdfDoc(File(path))
            try {
                doc.render(0, 320)
            } finally {
                doc.close()
            }
        } catch (e: Throwable) {
            null
        }
        if (bmp != null) cache.put(path, bmp)
        return bmp
    }
}
