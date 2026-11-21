package de.dateimanager.app

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
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

    val DOC_EXTS = listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "txt", "epub", "md", "tex")
    private val ARCHIVE_EXTS = listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "apk", "xapk", "apks", "aab", "iso")
    private val MESSENGER_KEYS = listOf("whatsapp", "telegram", "signal", "snapchat", "instagram", "messenger", "threema", "viber")
    private val RECORDING_KEYS = listOf(
        "whatsapp", "telegram", "/recordings/", "/recorder", "/voice recorder", "/voice_recorder", "/voicerecorder",
        "/voice memos", "/voicememos", "/voice notes", "/sprach", "/aufnahme", "/call recordings", "/callrecord",
        "/sound_recorder", "/soundrecorder", "/ringtones/", "/notifications/", "/alarms/"
    )

    /** Sprachnachrichten, Aufnahmen und Töne – gehören nicht zur Musik. */
    fun isRecordingPath(p: String): Boolean {
        val low = p.lowercase()
        for (k in RECORDING_KEYS) if (low.contains(k)) return true
        val n = low.substringAfterLast('/')
        return n.startsWith("ptt-") || n.startsWith("aud-") || n.startsWith("voice ") || n.startsWith("recording") ||
            n.startsWith("aufnahme") || n.startsWith("sprachnachricht")
    }

    private fun likeAny(pats: List<String>): Pair<String, Array<String>> =
        pats.joinToString(" OR ", "(", ")") { "${FileColumns.DATA} LIKE ?" } to pats.toTypedArray()

    fun monthKey(ts: Long): Long {
        val d = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()
        return d.year * 12L + (d.monthValue - 1)
    }

    fun monthLabel(k: Long): String =
        LocalDate.of((k / 12).toInt(), (k % 12).toInt() + 1, 1)
            .format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN))

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

    fun defaultAlbumBase(section: Section): File = File(
        Environment.getExternalStorageDirectory(),
        when (section) {
            Section.Images -> "Pictures"
            Section.Videos -> "Movies"
            Section.Music -> "Music"
            else -> "Documents"
        }
    )

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

    /** Alle Kategorien über die Medien-Datenbank (schnell). */
    fun media(ctx: Context, section: Section): List<FileItem> {
        val (sel, args) = when (section) {
            Section.Docs -> likeAny(DOC_EXTS.map { "%.$it" })
            Section.Music, Section.Recordings ->
                "${FileColumns.MEDIA_TYPE}=?" to arrayOf(FileColumns.MEDIA_TYPE_AUDIO.toString())
            Section.Images -> "${FileColumns.MEDIA_TYPE}=?" to arrayOf(FileColumns.MEDIA_TYPE_IMAGE.toString())
            Section.Videos -> "${FileColumns.MEDIA_TYPE}=?" to arrayOf(FileColumns.MEDIA_TYPE_VIDEO.toString())
            Section.Other -> "${FileColumns.MEDIA_TYPE} NOT IN (1,2,3)" to emptyArray<String>()
            Section.Downloads -> likeAny(listOf("%/Download/%", "%/Downloads/%"))
            Section.Screenshots -> likeAny(listOf("%/screenshot%", "%/screen%record%"))
            Section.Archives -> likeAny(ARCHIVE_EXTS.map { "%.$it" })
            Section.Messenger -> likeAny(MESSENGER_KEYS.map { "%$it%" })
            Section.Largest -> "${FileColumns.SIZE}>=?" to arrayOf((5L * 1024 * 1024).toString())
            Section.Recent -> {
                val cutoff = (System.currentTimeMillis() / 1000 - 30L * 86400).toString()
                "${FileColumns.DATE_MODIFIED}>=? OR ${FileColumns.DATE_ADDED}>=?" to arrayOf(cutoff, cutoff)
            }
            else -> return emptyList()
        }
        val out = ArrayList<FileItem>()
        val seen = HashSet<String>()
        val uri = MediaStore.Files.getContentUri("external")
        val wantDuration = section == Section.Music || section == Section.Videos || section == Section.Recordings
        val cursor = try {
            val proj = if (wantDuration) arrayOf(FileColumns.DATA, FileColumns.DATE_ADDED, "duration")
            else arrayOf(FileColumns.DATA, FileColumns.DATE_ADDED)
            ctx.contentResolver.query(uri, proj, sel, args, null)
        } catch (e: Exception) {
            ctx.contentResolver.query(uri, arrayOf(FileColumns.DATA, FileColumns.DATE_ADDED), sel, args, null)
        }
        cursor?.use { c ->
            val iData = c.getColumnIndexOrThrow(FileColumns.DATA)
            val iAdded = c.getColumnIndexOrThrow(FileColumns.DATE_ADDED)
            val iDur = c.getColumnIndex("duration")
            while (c.moveToNext()) {
                val p = c.getString(iData) ?: continue
                if (p.contains("/.") || p.contains("/Android/data/")) continue
                if (section == Section.Music && isRecordingPath(p)) continue
                if (section == Section.Recordings && !isRecordingPath(p)) continue
                val f = File(p)
                if (!f.isFile || !seen.add(p)) continue
                if (section == Section.Other && f.extension.lowercase() in DOC_EXTS) continue
                val mod = if (section == Section.Recent) maxOf(f.lastModified(), c.getLong(iAdded) * 1000) else f.lastModified()
                out += FileItem(p, f.name, false, f.length(), mod, if (iDur >= 0) c.getLong(iDur) else 0L)
            }
        }
        if (section == Section.Recent) {
            out.sortByDescending { it.modified }
            return out.take(1500)
        }
        if (section == Section.Largest) {
            out.sortByDescending { it.size }
            return out.take(300)
        }
        return out
    }

    /** Erweiterte Suche: Name, Typ, Größe und Alter kombinierbar (über die Medien-Datenbank). */
    fun advanced(ctx: Context, q: String, type: AdvType, size: AdvSize, age: AdvAge): List<FileItem> {
        val parts = ArrayList<String>()
        val args = ArrayList<String>()
        if (q.isNotBlank()) {
            parts.add("${FileColumns.DISPLAY_NAME} LIKE ?")
            args.add("%" + q.trim() + "%")
        }
        when (type) {
            AdvType.Images -> {
                parts.add("${FileColumns.MEDIA_TYPE}=?")
                args.add(FileColumns.MEDIA_TYPE_IMAGE.toString())
            }
            AdvType.Videos -> {
                parts.add("${FileColumns.MEDIA_TYPE}=?")
                args.add(FileColumns.MEDIA_TYPE_VIDEO.toString())
            }
            AdvType.Audio -> {
                parts.add("${FileColumns.MEDIA_TYPE}=?")
                args.add(FileColumns.MEDIA_TYPE_AUDIO.toString())
            }
            AdvType.Docs -> {
                val (s1, a1) = likeAny(DOC_EXTS.map { "%.$it" })
                parts.add(s1)
                args.addAll(a1)
            }
            AdvType.Archives -> {
                val (s2, a2) = likeAny(ARCHIVE_EXTS.map { "%.$it" })
                parts.add(s2)
                args.addAll(a2)
            }
            AdvType.All -> {}
        }
        if (size.mb > 0L) {
            parts.add("${FileColumns.SIZE}>=?")
            args.add((size.mb * 1024L * 1024L).toString())
        }
        val nowSec = System.currentTimeMillis() / 1000
        if (age.days > 0) {
            parts.add("${FileColumns.DATE_MODIFIED}>=?")
            args.add((nowSec - age.days * 86400L).toString())
        } else if (age.days < 0) {
            parts.add("${FileColumns.DATE_MODIFIED}<?")
            args.add((nowSec + age.days * 86400L).toString())
        }
        if (parts.isEmpty()) return emptyList()
        val out = ArrayList<FileItem>()
        val seen = HashSet<String>()
        val uri = MediaStore.Files.getContentUri("external")
        ctx.contentResolver.query(
            uri, arrayOf(FileColumns.DATA), parts.joinToString(" AND "), args.toTypedArray(), null
        )?.use { c ->
            val iData = c.getColumnIndexOrThrow(FileColumns.DATA)
            while (c.moveToNext() && out.size < 2000) {
                val p = c.getString(iData) ?: continue
                if (p.contains("/.") || p.contains("/Android/data/")) continue
                val f = File(p)
                if (!f.isFile || !seen.add(p)) continue
                out += itemOf(f)
            }
        }
        return out
    }

    /** Belegung pro Speicher, aufgeteilt nach Bildern, Videos, Musik, Aufnahmen, Dokumenten. */
    fun storageStats(ctx: Context, roots: List<Root>): List<VolumeStats> {
        val n = roots.size
        val img = LongArray(n)
        val vid = LongArray(n)
        val mus = LongArray(n)
        val rec = LongArray(n)
        val doc = LongArray(n)
        val app = LongArray(n)
        val bak = LongArray(n)
        val uri = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(FileColumns.DATA, FileColumns.SIZE, FileColumns.MEDIA_TYPE)
        ctx.contentResolver.query(uri, proj, null, null, null)?.use { c ->
            val iD = c.getColumnIndexOrThrow(FileColumns.DATA)
            val iS = c.getColumnIndexOrThrow(FileColumns.SIZE)
            val iT = c.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
            while (c.moveToNext()) {
                val p = c.getString(iD) ?: continue
                val size = c.getLong(iS)
                if (size <= 0L) continue
                val vi = roots.indexOfFirst { p.startsWith(it.dir.path + "/") }
                if (vi < 0) continue
                val type = c.getInt(iT)
                if (type == FileColumns.MEDIA_TYPE_IMAGE) img[vi] += size
                else if (type == FileColumns.MEDIA_TYPE_VIDEO) vid[vi] += size
                else if (type == FileColumns.MEDIA_TYPE_AUDIO) {
                    if (isRecordingPath(p)) rec[vi] += size else mus[vi] += size
                } else if (p.substringAfterLast('.', "").lowercase() in DOC_EXTS) {
                    doc[vi] += size
                } else {
                    val cls = OtherFilter.classifyPath(p)
                    if (cls == OtherFilter.Backups) bak[vi] += size
                    else if (cls == OtherFilter.Apps) app[vi] += size
                }
            }
        }
        val out = ArrayList<VolumeStats>()
        roots.forEachIndexed { i, r ->
            try {
                val st = StatFs(r.dir.path)
                out.add(VolumeStats(r.name, st.totalBytes, st.availableBytes, img[i], vid[i], mus[i], rec[i], doc[i], app[i], bak[i]))
            } catch (e: Exception) {
            }
        }
        return out
    }

    // ---------- Sortierung & Zeit ----------

    fun sorted(items: List<FileItem>, by: SortBy, asc: Boolean, foldersFirst: Boolean): List<FileItem> {
        val base: Comparator<FileItem> = when (by) {
            SortBy.Name -> Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
            SortBy.Date -> Comparator { a, b -> a.modified.compareTo(b.modified) }
            SortBy.Size -> Comparator { a, b -> a.size.compareTo(b.size) }
            SortBy.Duration -> Comparator { a, b -> a.duration.compareTo(b.duration) }
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

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.GERMAN, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.GERMAN, "%d:%02d", m, sec)
    }

    /** Kürzt lange Namen in der Mitte, Anfang und Ende (mit Endung) bleiben sichtbar. */
    fun middleEllipsis(name: String, max: Int): String {
        if (name.length <= max) return name
        val tail = minOf(10, max / 3)
        val head = max - tail - 1
        return name.take(head) + "…" + name.takeLast(tail)
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
