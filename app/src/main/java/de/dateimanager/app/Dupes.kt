package de.dateimanager.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

object Dupes {

    private val SUFFIX = Regex("(\\s*\\(\\d+\\)|\\s*\\[\\d+\\]|[-_ ]\\d{1,2}|[-_ ]?(kopie|copy)(\\s*\\(\\d+\\))?)$", RegexOption.IGNORE_CASE)

    /** Name ohne "(1)", "(2)", "- Kopie", "_1" usw. */
    fun normName(name: String): String {
        val dot = name.lastIndexOf('.')
        var base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot).lowercase() else ""
        while (true) {
            val next = base.replace(SUFFIX, "").trimEnd()
            if (next == base || next.isEmpty()) break
            base = next
        }
        return base.lowercase() + ext
    }

    private fun hash(f: File, partial: Boolean): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(f, "r").use { raf ->
                val buf = ByteArray(64 * 1024)
                val len = raf.length()
                val chunk = 128L * 1024
                if (partial && len > 2 * chunk) {
                    for (pos in longArrayOf(0L, len - chunk)) {
                        raf.seek(pos)
                        var left = chunk
                        while (left > 0) {
                            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n <= 0) break
                            md.update(buf, 0, n)
                            left -= n
                        }
                    }
                } else {
                    while (true) {
                        val n = raf.read(buf)
                        if (n <= 0) break
                        md.update(buf, 0, n)
                    }
                }
            }
            md.digest().joinToString("") { b -> String.format("%02x", b) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Stufe 1 (schnell): gleiche Größe + ähnlicher Name -> onQuick.
     * Stufe 2 (genau): Inhalt vergleichen (Teil-Hash, dann kompletter Hash).
     */
    suspend fun scan(
        files: List<FileItem>,
        onQuick: (List<DupGroup>) -> Unit,
        onProgress: (String) -> Unit
    ): List<DupGroup> = withContext(Dispatchers.IO) {
        val sizeGroups = files.filter { !it.isDir && it.size > 0L }
            .groupBy { it.size }
            .filter { it.value.size >= 2 }

        val quick = ArrayList<DupGroup>()
        for ((size, fs) in sizeGroups) {
            for (g in fs.groupBy { normName(it.name) }.values) {
                if (g.size >= 2) quick.add(DupGroup(size, g, false))
            }
        }
        quick.sortByDescending { it.size }
        onQuick(quick)

        var total = 0
        for (g in sizeGroups.values) total += g.size
        var done = 0
        val result = ArrayList<DupGroup>()
        for ((size, fs) in sizeGroups) {
            ensureActive()
            val byPartial = fs.groupBy { hash(File(it.path), true) ?: ("err_" + it.path) }
            for (cand in byPartial.values) {
                if (cand.size < 2) continue
                val byFull = cand.groupBy { hash(File(it.path), false) ?: ("err_" + it.path) }
                for (g in byFull.values) {
                    if (g.size >= 2) result.add(DupGroup(size, g, true))
                }
            }
            done += fs.size
            onProgress("Prüfe Inhalt … $done von $total")
        }
        result.sortByDescending { it.size }
        result
    }
}
