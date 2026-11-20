package de.dateimanager.app

import java.io.File

enum class Section(val title: String) {
    Browse("Dateien"), Recent("Kürzlich"), Docs("Dokumente"),
    Music("Musik"), Images("Bilder"), Videos("Videos")
}

enum class SortBy(val label: String) { Name("Name"), Date("Datum"), Size("Größe") }

data class Root(val name: String, val dir: File)

data class Clip(val paths: List<String>, val move: Boolean)

data class FileItem(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long
) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
}

enum class Kind { Folder, Image, Video, Audio, Pdf, Archive, Apk, Text, Other }

fun FileItem.kind(): Kind = if (isDir) Kind.Folder else when (ext) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif" -> Kind.Image
    "mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v" -> Kind.Video
    "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr", "wma" -> Kind.Audio
    "pdf" -> Kind.Pdf
    "zip", "rar", "7z", "tar", "gz" -> Kind.Archive
    "apk" -> Kind.Apk
    "txt", "md", "csv", "log", "json", "xml", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt" -> Kind.Text
    else -> Kind.Other
}

sealed class Row {
    abstract val key: String

    data class Header(val label: String) : Row() {
        override val key: String get() = "h_$label"
    }

    data class Entry(val item: FileItem) : Row() {
        override val key: String get() = item.path
    }
}
