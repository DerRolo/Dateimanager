package de.dateimanager.app

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fs {

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    fun roots(ctx: Context): List<Root> {
        val primary = Environment.getExternalStorageDirectory()
        val list = mutableListOf(Root("Interner Speicher", primary))
        ctx.getExternalFilesDirs(null).filterNotNull().forEach { f ->
            val p = f.absolutePath
            val idx = p.indexOf("/Android/")
            if (idx > 0) {
                val base = File(p.substring(0, idx))
                if (base.absolutePath != primary.absolutePath && list.none { it.dir.absolutePath == base.absolutePath }) {
                    list += Root(if (list.size == 1) "SD-Karte" else "Speicher ${list.size}", base)
                }
            }
        }
        return list
    }

    fun itemOf(f: File) = FileItem(
        f.absolutePath, f.name, f.isDirectory,
        if (f.isDirectory) 0L else f.length(), f.lastModified()
    )

    fun list(dir: File, showHidden: Boolean): List<FileItem> =
        dir.listFiles().orEmpty()
            .filter { showHidden || !it.name.startsWith(".") }
            .map { itemOf(it) }

    fun search(root: File, q: String, showHidden: Boolean, limit: Int = 500, alive: () -> Boolean): List<FileItem> {
        val out = ArrayList<FileItem>()
        val needle = q.lowercase()
        val walk = root.walkTopDown().onEnter { showHidden || it == root || !it.name.startsWith(".") }
        for (f in walk) {
            if (!alive()) break
            if (f != root && f.name.lowercase().contains(needle)) {
                out += itemOf(f)
                if (out.size >= limit) break
            }
        }
        return out
    }

    /** Dokumente, Musik, Bilder, Videos und Kürzlich über die Medien-Datenbank (schnell). */
    fun media(ctx: Context, section: Section): List<FileItem> {
        val (sel, args) = when (section) {
            Section.Docs -> "${FileColumns.MIME_TYPE}=?" to arrayOf("application/pdf")
            Section.Music -> "${FileColumns.MEDIA_TYPE}=?" to arrayOf(FileColumns.MEDIA_TYPE_AUDIO.toString())
            Section.Images -> "${FileColumns.MEDIA_TYPE}=?" to arrayOf(FileColumns.MEDIA_TYPE_IMAGE.toString())
            Section.Videos -> "${FileColumns.MEDIA_TYPE}=?" to arrayOf(FileColumns.MEDIA_TYPE_VIDEO.toString())
            Section.Recent -> {
                val cutoff = (System.currentTimeMillis() / 1000 - 30L * 86400).toString()
                "${FileColumns.DATE_MODIFIED}>=? OR ${FileColumns.DATE_ADDED}>=?" to arrayOf(cutoff, cutoff)
            }
            else -> return emptyList()
        }
        val out = ArrayList<FileItem>()
        val seen = HashSet<String>()
        val uri = MediaStore.Files.getContentUri("external")
        ctx.contentResolver.query(uri, arrayOf(FileColumns.DATA, FileColumns.DATE_ADDED), sel, args, null)?.use { c ->
            val iData = c.getColumnIndexOrThrow(FileColumns.DATA)
            val iAdded = c.getColumnIndexOrThrow(FileColumns.DATE_ADDED)
            while (c.moveToNext()) {
                val p = c.getString(iData) ?: continue
                if (p.contains("/.") || p.contains("/Android/data/")) continue
                val f = File(p)
                if (!f.isFile || !seen.add(p)) continue
                val mod = if (section == Section.Recent) maxOf(f.lastModified(), c.getLong(iAdded) * 1000) else f.lastModified()
                out += FileItem(p, f.name, false, f.length(), mod)
            }
        }
        if (section == Section.Recent) {
            out.sortByDescending { it.modified }
            return out.take(1500)
        }
        return out
    }

    // ---------- Sortierung & Zeit ----------

    fun sorted(items: List<FileItem>, by: SortBy, asc: Boolean, foldersFirst: Boolean): List<FileItem> {
        val base: Comparator<FileItem> = when (by) {
            SortBy.Name -> Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
            SortBy.Date -> Comparator { a, b -> a.modified.compareTo(b.modified) }
            SortBy.Size -> Comparator { a, b -> a.size.compareTo(b.size) }
        }
        val dir = if (asc) base else base.reversed()
        return if (foldersFirst) {
            items.sortedWith(Comparator<FileItem> { a, b -> b.isDir.compareTo(a.isDir) }.then(dir))
        } else items.sortedWith(dir)
    }

    fun epochDay(ts: Long): Long =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    fun dayLabel(day: Long): String {
        val diff = LocalDate.now().toEpochDay() - day
        return when {
            diff <= 0L -> "Heute"
            diff == 1L -> "Gestern"
            diff < 31L -> "Vor $diff Tagen"
            else -> LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN))
        }
    }

    fun formatDate(ts: Long): String =
        DATE_FMT.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))

    fun formatSize(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = -1
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return String.format(Locale.GERMAN, "%.1f %s", v, units[i])
    }

    // ---------- Dateioperationen ----------

    fun uniqueTarget(dir: File, name: String, isDir: Boolean): File {
        var t = File(dir, name)
        if (!t.exists()) return t
        val dot = name.lastIndexOf('.')
        val base = if (isDir || dot <= 0) name else name.substring(0, dot)
        val ext = if (isDir || dot <= 0) "" else name.substring(dot)
        var n = 1
        while (t.exists()) {
            t = File(dir, "$base ($n)$ext")
            n++
        }
        return t
    }

    fun collect(f: File): List<File> =
        if (f.isDirectory) f.walkTopDown().filter { it.isFile }.toList() else listOf(f)

    fun scan(ctx: Context, paths: List<String>) {
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
    }

    // ---------- Öffnen & Teilen ----------

    private fun uriFor(ctx: Context, f: File) =
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)

    fun mime(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    fun open(ctx: Context, f: File) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uriFor(ctx, f), mime(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Keine App zum Öffnen gefunden", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(ctx: Context, files: List<File>) {
        val fs = files.filter { it.isFile }
        if (fs.isEmpty()) {
            Toast.makeText(ctx, "Ordner können nicht geteilt werden", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList(fs.map { uriFor(ctx, it) })
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mime(fs[0]))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Teilen"))
    }
}
