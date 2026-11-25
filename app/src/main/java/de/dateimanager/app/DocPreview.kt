package de.dateimanager.app

import android.util.Xml
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class Para(val text: String, val level: Int = 0, val bullet: Boolean = false, val mono: Boolean = false)

data class SheetData(val name: String, val rows: List<List<String>>)

sealed class Preview {
    data class Paras(val items: List<Para>) : Preview()
    data class Sheets(val sheets: List<SheetData>) : Preview()
    data class Failed(val msg: String) : Preview()
}

/** Einfache Textvorschau für Word, Excel, PowerPoint, OpenDocument und Textdateien (ohne Layout und Bilder). */
object DocPreview {
    private const val MAX_PARAS = 6000
    private val TEXT_EXTS = setOf(
        "txt", "md", "tex", "csv", "tsv", "log", "json", "xml", "ini", "yml", "yaml", "py", "java", "kt",
        "c", "cpp", "h", "js", "css", "html", "sh", "bib"
    )
    private val ZIP_EXTS = setOf("docx", "xlsx", "pptx", "odt", "ods")

    fun canPreview(ext: String): Boolean = ext in ZIP_EXTS || ext in TEXT_EXTS

    fun load(f: File): Preview {
        return try {
            when (f.extension.lowercase()) {
                "docx" -> docx(f)
                "xlsx" -> xlsx(f)
                "pptx" -> pptx(f)
                "odt", "ods" -> odf(f)
                else -> text(f)
            }
        } catch (e: Throwable) {
            Preview.Failed("Vorschau nicht möglich: " + (e.message ?: "unbekannter Fehler"))
        }
    }

    private fun parser(ins: InputStream): XmlPullParser {
        val p = Xml.newPullParser()
        p.setInput(ins, null)
        return p
    }

    private fun headingLevel(style: String): Int {
        val s = style.lowercase()
        val digits = s.filter { it.isDigit() }
        return when {
            s == "title" || s == "titel" -> 1
            s.startsWith("heading") || s.startsWith("berschrift") || s.startsWith("überschrift") -> digits.toIntOrNull() ?: 1
            else -> 0
        }
    }

    private fun docx(f: File): Preview {
        ZipFile(f).use { zip ->
            val e = zip.getEntry("word/document.xml") ?: return Preview.Failed("Keine Textdaten gefunden")
            val out = ArrayList<Para>()
            zip.getInputStream(e).use { ins ->
                val p = parser(ins)
                var sb = StringBuilder()
                var level = 0
                var bullet = false
                val cells = ArrayList<String>()
                var cell = StringBuilder()
                var inCell = false
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT && out.size < MAX_PARAS) {
                    if (ev == XmlPullParser.START_TAG) {
                        when (p.name) {
                            "w:p" -> {
                                sb = StringBuilder()
                                level = 0
                                bullet = false
                            }
                            "w:pStyle" -> level = headingLevel(p.getAttributeValue(null, "w:val") ?: "")
                            "w:numPr" -> bullet = true
                            "w:t" -> sb.append(p.nextText())
                            "w:tab" -> sb.append('\t')
                            "w:br" -> sb.append('\n')
                            "w:tr" -> cells.clear()
                            "w:tc" -> {
                                inCell = true
                                cell = StringBuilder()
                            }
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        when (p.name) {
                            "w:p" -> {
                                val t = sb.toString().trim()
                                if (inCell) {
                                    if (t.isNotEmpty()) {
                                        if (cell.isNotEmpty()) cell.append(' ')
                                        cell.append(t)
                                    }
                                } else if (t.isNotEmpty()) {
                                    out.add(Para(t, level, bullet))
                                }
                            }
                            "w:tc" -> {
                                cells.add(cell.toString().trim())
                                inCell = false
                            }
                            "w:tr" -> {
                                if (cells.any { it.isNotEmpty() }) out.add(Para(cells.joinToString("  │  ")))
                            }
                        }
                    }
                    ev = p.next()
                }
            }
            return Preview.Paras(out)
        }
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break
        }
        return n - 1
    }

    private fun readSheet(zip: ZipFile, e: ZipEntry, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        zip.getInputStream(e).use { ins ->
            val p = parser(ins)
            var cur = ArrayList<String>()
            var col = 0
            var type = ""
            var value = ""
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT && rows.size < 300) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "row" -> cur = ArrayList()
                        "c" -> {
                            col = colIndex(p.getAttributeValue(null, "r") ?: "")
                            type = p.getAttributeValue(null, "t") ?: ""
                            value = ""
                        }
                        "v" -> value = p.nextText()
                        "t" -> {
                            val t = p.nextText()
                            if (type == "inlineStr") value += t
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "c" -> {
                            val text = when (type) {
                                "s" -> shared.getOrNull(value.toIntOrNull() ?: -1) ?: ""
                                "b" -> if (value == "1") "WAHR" else "FALSCH"
                                else -> value
                            }
                            if (col in 0..39) {
                                while (cur.size < col) cur.add("")
                                if (cur.size == col) {
                                    cur.add(text)
                                } else {
                                    cur[col] = text
                                }
                            }
                        }
                        "row" -> rows.add(cur)
                    }
                }
                ev = p.next()
            }
        }
        return rows
    }

    private fun xlsx(f: File): Preview {
        ZipFile(f).use { zip ->
            val shared = ArrayList<String>()
            val se = zip.getEntry("xl/sharedStrings.xml")
            if (se != null) {
                zip.getInputStream(se).use { ins ->
                    val p = parser(ins)
                    var sb: StringBuilder? = null
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG) {
                            if (p.name == "si") {
                                sb = StringBuilder()
                            } else if (p.name == "t") {
                                val t = p.nextText()
                                sb?.append(t)
                            }
                        } else if (ev == XmlPullParser.END_TAG && p.name == "si") {
                            shared.add(sb?.toString() ?: "")
                            sb = null
                        }
                        ev = p.next()
                    }
                }
            }
            val names = ArrayList<Pair<String, String>>()
            val we = zip.getEntry("xl/workbook.xml")
            if (we != null) {
                zip.getInputStream(we).use { ins ->
                    val p = parser(ins)
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG && p.name == "sheet") {
                            names.add(
                                Pair(p.getAttributeValue(null, "name") ?: "Blatt", p.getAttributeValue(null, "r:id") ?: "")
                            )
                        }
                        ev = p.next()
                    }
                }
            }
            val rels = HashMap<String, String>()
            val re = zip.getEntry("xl/_rels/workbook.xml.rels")
            if (re != null) {
                zip.getInputStream(re).use { ins ->
                    val p = parser(ins)
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                            val id = p.getAttributeValue(null, "Id") ?: ""
                            rels[id] = p.getAttributeValue(null, "Target") ?: ""
                        }
                        ev = p.next()
                    }
                }
            }
            val sheets = ArrayList<SheetData>()
            for ((name, rid) in names) {
                val target = rels[rid] ?: continue
                val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
                val entry = zip.getEntry(path) ?: continue
                sheets.add(SheetData(name, readSheet(zip, entry, shared)))
                if (sheets.size >= 12) break
            }
            if (sheets.isEmpty()) return Preview.Failed("Keine Tabellen gefunden")
            return Preview.Sheets(sheets)
        }
    }

    private fun pptx(f: File): Preview {
        ZipFile(f).use { zip ->
            val names = ArrayList<String>()
            val en = zip.entries()
            while (en.hasMoreElements()) {
                val n = en.nextElement().name
                if (n.startsWith("ppt/slides/slide") && n.endsWith(".xml")) names.add(n)
            }
            names.sortBy { it.substringAfterLast("slide").removeSuffix(".xml").toIntOrNull() ?: 0 }
            val out = ArrayList<Para>()
            for ((i, name) in names.withIndex()) {
                out.add(Para("Folie " + (i + 1), 1))
                val e = zip.getEntry(name) ?: continue
                zip.getInputStream(e).use { ins ->
                    val p = parser(ins)
                    var sb = StringBuilder()
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG) {
                            if (p.name == "a:p") {
                                sb = StringBuilder()
                            } else if (p.name == "a:t") {
                                sb.append(p.nextText())
                            }
                        } else if (ev == XmlPullParser.END_TAG && p.name == "a:p") {
                            val t = sb.toString().trim()
                            if (t.isNotEmpty()) out.add(Para(t))
                        }
                        ev = p.next()
                    }
                }
                if (out.size > MAX_PARAS) break
            }
            return Preview.Paras(out)
        }
    }

    private fun odf(f: File): Preview {
        ZipFile(f).use { zip ->
            val e = zip.getEntry("content.xml") ?: return Preview.Failed("Keine Textdaten gefunden")
            val out = ArrayList<Para>()
            zip.getInputStream(e).use { ins ->
                val p = parser(ins)
                var inP = 0
                var sb = StringBuilder()
                var level = 0
                var inCell = false
                var cell = StringBuilder()
                val cells = ArrayList<String>()
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT && out.size < MAX_PARAS) {
                    if (ev == XmlPullParser.START_TAG) {
                        when (p.name) {
                            "text:p", "text:h" -> {
                                if (inP == 0) {
                                    sb = StringBuilder()
                                    level = if (p.name == "text:h") {
                                        p.getAttributeValue(null, "text:outline-level")?.toIntOrNull() ?: 1
                                    } else 0
                                }
                                inP++
                            }
                            "text:s" -> sb.append(' ')
                            "text:tab" -> sb.append('\t')
                            "text:line-break" -> sb.append('\n')
                            "table:table-row" -> cells.clear()
                            "table:table-cell" -> {
                                inCell = true
                                cell = StringBuilder()
                            }
                        }
                    } else if (ev == XmlPullParser.TEXT) {
                        if (inP > 0) sb.append(p.text)
                    } else if (ev == XmlPullParser.END_TAG) {
                        when (p.name) {
                            "text:p", "text:h" -> {
                                inP--
                                if (inP == 0) {
                                    val t = sb.toString().trim()
                                    if (inCell) {
                                        if (t.isNotEmpty()) {
                                            if (cell.isNotEmpty()) cell.append(' ')
                                            cell.append(t)
                                        }
                                    } else if (t.isNotEmpty()) {
                                        out.add(Para(t, level))
                                    }
                                }
                            }
                            "table:table-cell" -> {
                                cells.add(cell.toString().trim())
                                inCell = false
                            }
                            "table:table-row" -> {
                                if (cells.any { it.isNotEmpty() }) out.add(Para(cells.joinToString("  │  ")))
                            }
                        }
                    }
                    ev = p.next()
                }
            }
            return Preview.Paras(out)
        }
    }

    private fun text(f: File): Preview {
        val max = 200 * 1024
        val buf = ByteArray(max)
        var n = 0
        f.inputStream().use { ins ->
            while (n < max) {
                val r = ins.read(buf, n, max - n)
                if (r <= 0) break
                n += r
            }
        }
        val txt = String(buf, 0, n, Charsets.UTF_8)
        val out = ArrayList<Para>()
        for (line in txt.lines()) {
            out.add(Para(line, 0, false, true))
            if (out.size >= 4000) break
        }
        if (f.length() > max) out.add(Para("… Vorschau gekürzt, die Datei ist größer."))
        return Preview.Paras(out)
    }
}

@Composable
fun ParaText(p: Para) {
    val style = when {
        p.level == 1 -> MaterialTheme.typography.titleLarge
        p.level == 2 -> MaterialTheme.typography.titleMedium
        p.level >= 3 -> MaterialTheme.typography.titleSmall
        p.mono -> MaterialTheme.typography.bodySmall
        else -> MaterialTheme.typography.bodyMedium
    }
    Text(
        (if (p.bullet) "•  " else "") + p.text,
        style = style,
        fontFamily = if (p.mono) FontFamily.Monospace else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = if (p.mono) 0.dp else 4.dp)
    )
}

@Composable
fun SheetView(pv: Preview.Sheets, modifier: Modifier) {
    var idx by remember { mutableStateOf(0) }
    Column(modifier) {
        if (pv.sheets.size > 1) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pv.sheets.forEachIndexed { i, s ->
                    FilterChip(selected = idx == i, onClick = { idx = i }, label = { Text(s.name) })
                }
            }
        }
        val sheet = pv.sheets[idx.coerceIn(0, pv.sheets.size - 1)]
        var cols = 0
        for (r in sheet.rows) if (r.size > cols) cols = r.size
        if (sheet.rows.isEmpty()) {
            Text("Dieses Blatt ist leer.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                LazyColumn(Modifier.fillMaxHeight()) {
                    items(sheet.rows.size) { r ->
                        Row {
                            for (c in 0 until cols) {
                                Text(
                                    sheet.rows[r].getOrElse(c) { "" },
                                    Modifier.width(120.dp)
                                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                        .padding(6.dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocSheet(path: String, onClose: () -> Unit, onOpen: () -> Unit) {
    val file = remember(path) { File(path) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val preview by produceState<Preview?>(null, path) {
        value = withContext(Dispatchers.IO) { DocPreview.load(file) }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    file.name,
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onOpen) { Text("Vollständig öffnen") }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Schließen") }
            }
            val pv = preview
            when (pv) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    CircularProgressIndicator(Modifier.padding(24.dp))
                }
                is Preview.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Text(
                        pv.msg + "\nNutze „Vollständig öffnen“.",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
                is Preview.Paras -> Column(Modifier.weight(1f)) {
                    Text(
                        "Textvorschau ohne Layout und Bilder.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(pv.items) { para -> ParaText(para) }
                    }
                }
                is Preview.Sheets -> SheetView(pv, Modifier.weight(1f))
            }
        }
    }
}
