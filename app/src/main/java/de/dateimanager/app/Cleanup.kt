package de.dateimanager.app

import android.content.Context
import java.io.File

/** Aufräum-Vorschläge: leere Ordner, 0-Byte-Dateien, Temp-Reste, alte Screenshots, große alte Dateien. */
object Cleanup {

    private val STD = setOf(
        "android", "dcim", "download", "downloads", "pictures", "music", "movies", "documents",
        "alarms", "notifications", "ringtones", "podcasts", "audiobooks", "recordings"
    )
    private val TEMP_EXTS = setOf("tmp", "temp", "crdownload", "part", "partial", "cache", "bak")

    fun find(ctx: Context, roots: List<Root>, f: CleanupFilter, alive: () -> Boolean): List<FileItem> {
        val now = System.currentTimeMillis()
        return when (f) {
            CleanupFilter.OldShots -> {
                val limit = now - 30L * 86_400_000L
                Fs.media(ctx, Section.Screenshots).filter { it.modified < limit }
            }
            CleanupFilter.Stale -> {
                val limit = now - 365L * 86_400_000L
                Fs.media(ctx, Section.Largest).filter { it.size >= 50L * 1024 * 1024 && it.modified < limit }
            }
            else -> walk(roots, f, alive)
        }
    }

    private fun walk(roots: List<Root>, f: CleanupFilter, alive: () -> Boolean): List<FileItem> {
        val out = ArrayList<FileItem>()
        for (r in roots) {
            val base = r.dir
            val tree = base.walkTopDown().onEnter { d ->
                d == base || (
                    !(d.parentFile == base && d.name.equals("Android", ignoreCase = true)) &&
                        (!d.name.startsWith(".") || (f == CleanupFilter.Temp && d.name == ".thumbnails"))
                    )
            }
            for (x in tree) {
                if (!alive() || out.size >= 1500) return out
                when (f) {
                    CleanupFilter.EmptyFolders -> {
                        if (x.isDirectory && x != base && x.list()?.isEmpty() == true &&
                            !(x.parentFile == base && x.name.lowercase() in STD)
                        ) out.add(Fs.itemOf(x))
                    }
                    CleanupFilter.ZeroByte -> {
                        if (x.isFile && x.length() == 0L && !x.name.startsWith(".")) out.add(Fs.itemOf(x))
                    }
                    CleanupFilter.Temp -> {
                        if (x.isDirectory && x.name == ".thumbnails") {
                            var sz = 0L
                            for (t in x.walkTopDown()) if (t.isFile) sz += t.length()
                            out.add(FileItem(x.absolutePath, x.name, true, sz, x.lastModified()))
                        } else if (x.isFile && (x.extension.lowercase() in TEMP_EXTS ||
                                x.name.startsWith("~\$") || x.name.endsWith("~"))
                        ) {
                            out.add(Fs.itemOf(x))
                        }
                    }
                    else -> {}
                }
            }
        }
        return out
    }
}
