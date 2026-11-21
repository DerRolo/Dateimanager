package de.dateimanager.app

import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FmViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application

    var section by mutableStateOf(Section.Browse)
    var roots by mutableStateOf<List<Root>>(emptyList())
    var dir by mutableStateOf<File?>(null)
    var items by mutableStateOf<List<FileItem>>(emptyList())
    var loading by mutableStateOf(false)
    var sortBy by mutableStateOf(SortBy.Name)
    var asc by mutableStateOf(true)
    var showHidden by mutableStateOf(false)
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var clip by mutableStateOf<Clip?>(null)

    private val prefs = app.getSharedPreferences("settings", 0)

    private fun loadView(s: Section): ViewMode {
        val def = if (s == Section.Images || s == Section.Videos) ViewMode.Grid else ViewMode.List
        return try {
            ViewMode.valueOf(prefs.getString("view_${s.name}", def.name) ?: def.name)
        } catch (e: Exception) {
            def
        }
    }

    var viewMode by mutableStateOf(loadView(Section.Browse))
    var pdfQuick by mutableStateOf(prefs.getBoolean("pdf_quick", true))

    private var job: Job? = null
    private val sortMemory = mutableMapOf<Section, Pair<SortBy, Boolean>>()

    val rows: List<Row> by derivedStateOf { buildRows() }

    private fun buildRows(): List<Row> {
        var list = items
        if (section != Section.Browse && query.isNotBlank()) {
            val q = query.trim().lowercase()
            list = list.filter { it.name.lowercase().contains(q) }
        }
        if (section == Section.Recent) {
            val groups = list.groupBy { Fs.epochDay(it.modified) }
            return groups.keys.sortedDescending().flatMap { day ->
                listOf<Row>(Row.Header(Fs.dayLabel(day))) +
                    Fs.sorted(groups.getValue(day), sortBy, asc, false).map { Row.Entry(it) }
            }
        }
        return Fs.sorted(list, sortBy, asc, section == Section.Browse).map { Row.Entry(it) }
    }

    private fun defaultSort(s: Section): Pair<SortBy, Boolean> = when (s) {
        Section.Recent, Section.Images, Section.Videos -> SortBy.Date to false
        else -> SortBy.Name to true
    }

    // ---------- Laden ----------

    fun onResume() {
        roots = Fs.roots(app)
        if (dir == null) dir = roots.firstOrNull()?.dir
        reload()
    }

    fun reload() {
        job?.cancel()
        val s = section
        val q = query.trim()
        val d = dir
        val hidden = showHidden
        job = viewModelScope.launch {
            loading = true
            if (s == Section.Browse && q.isNotEmpty()) delay(300)
            val res = withContext(Dispatchers.IO) {
                if (s == Section.Browse) {
                    if (d == null) emptyList<FileItem>()
                    else if (q.isEmpty()) Fs.list(d, hidden)
                    else Fs.search(d, q, hidden) { isActive }
                } else Fs.media(app, s)
            }
            items = res
            loading = false
        }
    }

    // ---------- Navigation ----------

    fun selectSection(s: Section) {
        if (s == section) return
        sortMemory[section] = sortBy to asc
        section = s
        viewMode = loadView(s)
        val (by, a) = sortMemory[s] ?: defaultSort(s)
        sortBy = by
        asc = a
        selected = emptySet()
        query = ""
        searching = false
        items = emptyList()
        reload()
    }

    fun openDir(f: File) {
        dir = f
        selected = emptySet()
        query = ""
        searching = false
        reload()
    }

    fun openRoot(r: Root) = openDir(r.dir)

    fun activeRoot(): Root? {
        val d = dir ?: return null
        return roots.firstOrNull { d.path == it.dir.path || d.path.startsWith(it.dir.path + "/") }
    }

    fun canGoUp(): Boolean {
        val d = dir ?: return false
        return section == Section.Browse && roots.none { it.dir.absolutePath == d.absolutePath } && d.parentFile != null
    }

    fun back() {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            searching -> closeSearch()
            canGoUp() -> openDir(dir!!.parentFile!!)
        }
    }

    fun toggleView() {
        viewMode = if (viewMode == ViewMode.List) ViewMode.Grid else ViewMode.List
        prefs.edit().putString("view_${section.name}", viewMode.name).apply()
    }

    fun togglePdfQuick() {
        pdfQuick = !pdfQuick
        prefs.edit().putBoolean("pdf_quick", pdfQuick).apply()
    }

    // ---------- Suche & Sortierung ----------

    fun openSearch() { searching = true }

    fun closeSearch() {
        searching = false
        val had = query.isNotEmpty()
        query = ""
        if (had && section == Section.Browse) reload()
    }

    fun updateQuery(q: String) {
        query = q
        if (section == Section.Browse) reload()
    }

    fun setSort(s: SortBy) {
        if (s == sortBy) asc = !asc else {
            sortBy = s
            asc = s == SortBy.Name
        }
    }

    fun toggleHidden() {
        showHidden = !showHidden
        reload()
    }

    // ---------- Auswahl ----------

    fun toggle(path: String) {
        selected = if (path in selected) selected - path else selected + path
    }

    fun selectAll(paths: List<String>) { selected = paths.toSet() }

    fun clearSelection() { selected = emptySet() }

    // ---------- Operationen ----------

    private fun launchOp(block: () -> String) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try { block() } catch (e: Exception) { "Fehler: ${e.message}" }
            }
            if (msg.isNotEmpty()) Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
            selected = emptySet()
            reload()
        }
    }

    fun delete(paths: List<String>) = launchOp {
        val files = paths.map { File(it) }
        val toScan = files.flatMap { Fs.collect(it) }.map { it.path }
        var ok = 0
        files.forEach { if (it.deleteRecursively()) ok++ }
        Fs.scan(app, toScan)
        "$ok von ${files.size} gelöscht"
    }

    fun rename(path: String, newName: String) = launchOp {
        val src = File(path)
        val n = newName.trim()
        if (n.isEmpty() || n.contains('/')) {
            "Ungültiger Name"
        } else {
            val dst = File(src.parentFile, n)
            if (dst.exists()) {
                "Name existiert bereits"
            } else {
                val old = Fs.collect(src).map { it.path }
                if (src.renameTo(dst)) {
                    Fs.scan(app, old + Fs.collect(dst).map { it.path })
                    "Umbenannt"
                } else "Umbenennen fehlgeschlagen"
            }
        }
    }

    fun newFolder(name: String) = launchOp {
        val d = dir
        val n = name.trim()
        if (d == null || n.isEmpty() || n.contains('/')) {
            "Ungültiger Name"
        } else {
            val f = File(d, n)
            if (f.exists()) "Existiert bereits" else if (f.mkdirs()) "Ordner erstellt" else "Fehlgeschlagen"
        }
    }

    fun setClip(move: Boolean) {
        clip = Clip(selected.toList(), move)
        selected = emptySet()
        if (section != Section.Browse) selectSection(Section.Browse)
    }

    fun cancelClip() { clip = null }

    fun paste() {
        val c = clip ?: return
        val target = dir ?: return
        clip = null
        launchOp {
            var ok = 0
            for (p in c.paths) {
                val src = File(p)
                if (!src.exists()) continue
                if (src.isDirectory && (target.absolutePath + "/").startsWith(src.absolutePath + "/")) continue
                if (c.move && src.parentFile?.absolutePath == target.absolutePath) continue
                val dst = Fs.uniqueTarget(target, src.name, src.isDirectory)
                val old = if (c.move) Fs.collect(src).map { it.path } else emptyList()
                val success = if (c.move) {
                    src.renameTo(dst) || (src.copyRecursively(dst) && src.deleteRecursively())
                } else src.copyRecursively(dst)
                if (success) {
                    ok++
                    Fs.scan(app, old + Fs.collect(dst).map { it.path })
                }
            }
            "$ok von ${c.paths.size} ${if (c.move) "verschoben" else "kopiert"}"
        }
    }
}
