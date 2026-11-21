package de.dateimanager.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Findet visuell ähnliche Bilder (Differenz-Hash, 64 Bit). Erkennt auch andere Größe/Qualität. */
object Similar {

    private fun lum(c: Int): Int = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    private fun dhash(path: String): Long? {
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 64 && bounds.outHeight / (sample * 2) >= 64) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
            val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
            if (small !== bmp) bmp.recycle()
            var h = 0L
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val a = lum(small.getPixel(x, y))
                    val b = lum(small.getPixel(x + 1, y))
                    h = h shl 1
                    if (a > b) h = h or 1L
                }
            }
            small.recycle()
            h
        } catch (e: Throwable) {
            null
        }
    }

    suspend fun scan(files: List<FileItem>, onProgress: (String) -> Unit): List<DupGroup> =
        withContext(Dispatchers.Default) {
            val imgs = files.filter { !it.isDir && it.kind() == Kind.Image }
            val done = AtomicInteger(0)
            val gate = Semaphore(6)
            val hashed = coroutineScope {
                imgs.map { f ->
                    async(Dispatchers.IO) {
                        val h = gate.withPermit { dhash(f.path) }
                        val n = done.incrementAndGet()
                        if (n % 25 == 0) onProgress("Vergleiche Bilder … $n von ${imgs.size}")
                        Pair(f, h)
                    }
                }.awaitAll()
            }
            val items = ArrayList<Pair<FileItem, Long>>()
            for ((f, h) in hashed) {
                if (h != null && h != 0L) items.add(Pair(f, h))
            }
            onProgress("Gruppiere ähnliche Bilder …")
            val n = items.size
            val parent = IntArray(n) { it }
            fun find(x: Int): Int {
                var a = x
                while (parent[a] != a) {
                    parent[a] = parent[parent[a]]
                    a = parent[a]
                }
                return a
            }
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    if (java.lang.Long.bitCount(items[i].second xor items[j].second) <= 6) {
                        val a = find(i)
                        val b = find(j)
                        if (a != b) parent[a] = b
                    }
                }
            }
            val groups = HashMap<Int, MutableList<FileItem>>()
            for (i in 0 until n) {
                groups.getOrPut(find(i)) { ArrayList() }.add(items[i].first)
            }
            val out = ArrayList<DupGroup>()
            for (g in groups.values) {
                if (g.size >= 2) {
                    var biggest = 0L
                    for (f in g) if (f.size > biggest) biggest = f.size
                    out.add(DupGroup(biggest, g, false, true))
                }
            }
            out.sortByDescending { it.size }
            out
        }
}
