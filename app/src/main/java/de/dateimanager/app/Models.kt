package de.dateimanager.app

import java.io.File

enum class Section(val title: String, val inTabs: Boolean = true) {
    Browse("Dateien"), Recent("Kürzlich"), Docs("Dokumente"),
    Music("Musik"), Images("Bilder"), Videos("Videos"),
    Storage("Speicher"), Other("Sonstiges", false), Trash("Papierkorb", false)
}

enum class SortBy(val label: String) { Name("Name"), Date("Datum"), Size("Größe"), Duration("Länge") }

enum class ViewMode { List, Grid }

enum class SubMode(val label: String) { All("Alle"), Albums("Alben & Ordner"), Dupes("Duplikate") }

data class Root(val name: String, val dir: File)

data class Clip(val paths: List<String>, val move: Boolean)

data class FileItem(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val duration: Long = 0L,
    val note: String? = null
) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
}

data class AlbumInfo(
    val path: String,
    val name: String,
    val count: Int,
    val size: Long,
    val newest: Long,
    val cover: String,
    val inbox: Boolean
)

data class DupGroup(val size: Long, val files: List<FileItem>, val verified: Boolean)

data class VolumeStats(
    val name: String,
    val total: Long,
    val free: Long,
    val images: Long,
    val videos: Long,
    val music: Long,
    val docs: Long
) {
    val used: Long get() = (total - free).coerceAtLeast(0L)
    val other: Long get() = (used - images - videos - music - docs).coerceAtLeast(0L)
}

enum class Kind { Folder, Image, Video, Audio, Pdf, Archive, Apk, Text, Other }

fun FileItem.kind(): Kind = if (isDir) Kind.Folder else when (ext) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif" -> Kind.Image
    "mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v" -> Kind.Video
    "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr", "wma" -> Kind.Audio
    "pdf" -> Kind.Pdf
    "zip", "rar", "7z", "tar", "gz" -> Kind.Archive
    "apk" -> Kind.Apk
    "txt", "md", "csv", "log", "json", "xml", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub" -> Kind.Text
    else -> Kind.Other
}

sealed class Row {
    abstract val key: String

    data class Header(val label: String, val paths: List<String> = emptyList()) : Row() {
        override val key: String get() = "h_$label"
    }

    data class DupHeader(val id: String, val label: String, val rest: List<String>) : Row() {
        override val key: String get() = "d_$id"
    }

    data class Album(val info: AlbumInfo) : Row() {
        override val key: String get() = "a_${info.path}"
    }

    data class Entry(val item: FileItem) : Row() {
        override val key: String get() = item.path
    }
}
