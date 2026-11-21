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

    companion object {
        const val INBOX = "::inbox"
        private val ALBUM_SECTIONS = setOf(Section.Docs, Section.Music, Section.Images, Section.Videos)
        private val INBOX_NAMES = setOf(
            "camera", "screenshots", "screenshot", "screen recordings", "screenrecorder", "screen_recorder",
            "download", "downloads", "bluetooth", "telegram", "snapchat", "instagram", "facebook",
            "messenger", "signal", "sent", "received", "dcim", "pictures", "movies", "documents",
            "music", "video", "videos", "images", "recordings"
        )
    }

    private val app: Application = application
    private val prefs = app.getSharedPreferences("settings", 0)

    private fun loadSet(key: String): Set<String> = prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private fun loadView(s: Section): ViewMode {
        val def = if (s == Section.Images || s == Section.Videos) ViewMode.Grid else ViewMode.List
        return try {
            ViewMode.valueOf(prefs.getString("view_${s.name}", def.name) ?: def.name)
        } catch (e: Exception) {
            def
        }
    }

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
    var storage by mutableStateOf<List<VolumeStats>>(emptyList())
    var subMode by mutableStateOf(SubMode.All)
    var albumDir by mutableStateOf<String?>(null)
    var dupResults by mutableStateOf<Map<Section, List<DupGroup>>>(emptyMap())
    var dupStatus by mutableStateOf("")
    var inboxSet by mutableStateOf(loadSet("inbox"))
    var albumSet by mutableStateOf(loadSet("albums"))
    var viewMode by mutableStateOf(loadView(Section.Browse))
    var pdfQuick by mutableStateOf(prefs.getBoolean("pdf_quick", true))

    private var job: Job? = null
    private var dupJob: Job? = null
    private var purged = false
    private val sortMemory = mutableMapOf<Section, Pair<SortBy, Boolean>>()
    private val subModeMemory = mutableMapOf<Section, SubMode>()

    val rows: List<Row> by derivedStateOf<List<Row>> { buildRows() }

    val albumInfos: List<AlbumInfo> by derivedStateOf<List<AlbumInfo>> {
        if (hasSub(section)) albumsOf(items) else emptyList()
    }

    val dupPaths: Set<String> by derivedStateOf<Set<String>> {
        val out = HashSet<String>()
        for (gs in dupResults.values) {
            for (g in gs) {
                if (g.verified) for (f in g.files) out.add(f.path)
            }
        }
        out
    }

    fun hasSub(s: Section): Boolean = s in ALBUM_SECTIONS

    // ---------- Klassifizierung: Eingang oder Album ----------

    fun isInbox(dirPath: String): Boolean {
        if (dirPath in albumSet) return false
        if (dirPath in inboxSet) return true
        val low = dirPath.lowercase()
        if (File(dirPath).name.lowercase() in INBOX_NAMES) return true
        return low.contains("whatsapp") || low.contains("telegram")
    }

    private fun albumsOf(list: List<FileItem>): List<AlbumInfo> =
        list.groupBy { File(it.path).parent ?: "" }.map { (p, fs) ->
            val cover = fs.maxByOrNull { it.modified }!!
            var total = 0L
            for (f in fs) total += f.size
            AlbumInfo(p, File(p).name, fs.size, total, cover.modified, cover.path, isInbox(p))
        }

    private fun sortAlbums(l: List<AlbumInfo>): List<AlbumInfo> {
        val c: Comparator<AlbumInfo> = when (sortBy) {
            SortBy.Date -> Comparator { a, b -> a.newest.compareTo(b.newest) }
            SortBy.Size -> Comparator { a, b -> a.size.compareTo(b.size) }
            else -> Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
        }
        return l.sortedWith(if (asc) c else c.reversed())
    }

    // ---------- Zeilen für die Anzeige ----------

    private fun buildRows(): List<Row> {
        if (section == Section.Storage) return emptyList()
        var list = items
        if (hasSub(section)) {
            when (subMode) {
                SubMode.Dupes -> return dupRows()
                SubMode.Albums -> {
                    val ad = albumDir
                    if (ad == null) return albumRows(list)
                    list = if (ad == INBOX) list.filter { isInbox(File(it.path).parent ?: "") }
                    else list.filter { File(it.path).parent == ad }
                }
                SubMode.All -> {}
            }
        }
        if (section != Section.Browse && query.isNotBlank()) {
            val q = query.trim().lowercase()
            list = list.filter { it.name.lowercase().contains(q) }
        }
        if (section == Section.Recent) {
            val groups = list.groupBy { Fs.epochDay(it.modified) }
            return groups.keys.sortedDescending().flatMap { day ->
                val g = groups.getValue(day)
                listOf<Row>(Row.Header(Fs.dayLabel(day), g.map { it.path })) +
                    Fs.sorted(g, sortBy, asc, false).map { Row.Entry(it) }
            }
        }
        return Fs.sorted(list, sortBy, asc, section == Section.Browse).map { Row.Entry(it) }
    }

    private fun albumRows(list: List<FileItem>): List<Row> {
        val all = albumsOf(list)
        val inbox = all.filter { it.inbox }
        val real = all.filter { !it.inbox }
        val out = ArrayList<Row>()
        if (inbox.isNotEmpty()) {
            val newest = inbox.maxByOrNull { it.newest }!!
            var cnt = 0
            var sz = 0L
            for (a in inbox) {
                cnt += a.count
                sz += a.size
            }
            out.add(Row.Header("Nicht zugeordnet"))
            out.add(Row.Album(AlbumInfo(INBOX, "Alle nicht zugeordneten", cnt, sz, newest.newest, newest.cover, true)))
            for (a in sortAlbums(inbox)) out.add(Row.Album(a))
        }
        if (real.isNotEmpty()) {
            out.add(Row.Header("Alben"))
            for (a in sortAlbums(real)) out.add(Row.Album(a))
        }
        return out
    }

    private fun suggestKeep(files: List<FileItem>): FileItem =
        files.sortedWith(
            compareBy<FileItem>(
                { if (isInbox(File(it.path).parent ?: "")) 1 else 0 },
                { if (Dupes.normName(it.name) == it.name.lowercase()) 0 else 1 },
                { it.modified }
            )
        ).first()

    private fun dupRows(): List<Row> {
        val groups = dupResults[section].orEmpty()
        return groups.flatMapIndexed { i, g ->
            val keep = suggestKeep(g.files)
            val ordered = listOf(keep) + g.files.filter { it.path != keep.path }
            val label = if (g.verified) {
                "${g.files.size} identische Dateien · ${Fs.formatSize(g.size)} je Datei · Inhalt geprüft"
            } else {
                "${g.files.size} mögliche Duplikate · ${Fs.formatSize(g.size)} · gleicher Name + Größe"
            }
            listOf<Row>(Row.DupHeader("g$i", label, ordered.drop(1).map { it.path })) +
                ordered.map { Row.Entry(it) }
        }
    }

    fun dupSummary(): String {
        val g = dupResults[section] ?: return "Sucht Dateien mit gleicher Größe und prüft danach den Inhalt."
        if (g.isEmpty()) return "Keine Duplikate gefunden."
        var extra = 0
        var bytes = 0L
        for (x in g) {
            extra += x.files.size - 1
            bytes += x.size * (x.files.size - 1)
        }
        return "${g.size} Gruppen · $extra überflüssige Dateien · ${Fs.formatSize(bytes)}"
    }

    // ---------- Laden ----------

    private fun defaultSort(s: Section): Pair<SortBy, Boolean> = when (s) {
        Section.Recent, Section.Images, Section.Videos, Section.Trash -> SortBy.Date to false
        Section.Other -> SortBy.Size to false
        else -> SortBy.Name to true
    }

    fun onResume() {
        roots = Fs.roots(app)
        if (dir == null) dir = roots.firstOrNull()?.dir
        reload()
        if (!purged) {
            purged = true
            val rs = roots
            viewModelScope.launch(Dispatchers.IO) { Trash.purge(rs, 30) }
        }
    }

    fun reload() {
        job?.cancel()
        val s = section
        val q = query.trim()
        val d = dir
        val hidden = showHidden
        val rs = roots
        job = viewModelScope.launch {
            loading = true
            if (s == Section.Browse && q.isNotEmpty()) delay(300)
            if (s == Section.Storage) {
                storage = withContext(Dispatchers.IO) { Fs.storageStats(app, rs) }
            } else {
                val res = withContext(Dispatchers.IO) {
                    when (s) {
                        Section.Browse -> {
                            if (d == null) emptyList<FileItem>()
                            else if (q.isEmpty()) Fs.list(d, hidden)
                            else Fs.search(d, q, hidden) { isActive }
                        }
                        Section.Trash -> Trash.list(rs)
                        else -> Fs.media(app, s)
                    }
                }
                items = res
            }
            loading = false
        }
    }

    // ---------- Navigation ----------

    fun selectSection(s: Section) {
        if (s == section) return
        sortMemory[section] = sortBy to asc
        subModeMemory[section] = subMode
        section = s
        viewMode = loadView(s)
        val (by, a) = sortMemory[s] ?: defaultSort(s)
        sortBy = by
        asc = a
        subMode = subModeMemory[s] ?: SubMode.All
        albumDir = null
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

    fun canBack(): Boolean =
        selected.isNotEmpty() || searching || albumDir != null || canGoUp() ||
            section == Section.Other || section == Section.Trash

    fun back() {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            searching -> closeSearch()
            albumDir != null -> albumDir = null
            canGoUp() -> openDir(dir!!.parentFile!!)
            section == Section.Other -> selectSection(Section.Storage)
            section == Section.Trash -> selectSection(Section.Browse)
        }
    }

    // ---------- Untermodi: Alle / Alben / Duplikate ----------

    fun setSubMode(m: SubMode) {
        subMode = m
        albumDir = null
        selected = emptySet()
        query = ""
        searching = false
    }

    fun openAlbum(path: String) {
        albumDir = path
        selected = emptySet()
    }

    fun markAlbum(path: String, asInbox: Boolean) {
        if (asInbox) {
            inboxSet = inboxSet + path
            albumSet = albumSet - path
        } else {
            albumSet = albumSet + path
            inboxSet = inboxSet - path
        }
        prefs.edit().putStringSet("inbox", inboxSet).putStringSet("albums", albumSet).apply()
    }

    fun showInFiles(path: String) {
        selectSection(Section.Browse)
        openDir(File(path))
    }

    fun newAlbumBase(): File = Fs.defaultAlbumBase(section)

    // ---------- Duplikate ----------

    fun startDupScan() {
        val s = section
        val list = items.filter { !it.isDir }
        dupJob?.cancel()
        dupJob = viewModelScope.launch {
            dupStatus = "Suche nach gleicher Größe …"
            try {
                val res = Dupes.scan(list, { q -> dupResults = dupResults + (s to q) }, { st -> dupStatus = st })
                dupResults = dupResults + (s to res)
            } finally {
                dupStatus = ""
            }
        }
    }

    fun cancelDupScan() {
        dupJob?.cancel()
        dupStatus = ""
    }

    fun selectAllDupRest() {
        val out = HashSet<String>()
        for (r in rows) {
            if (r is Row.DupHeader) out.addAll(r.rest)
        }
        selected = out
    }

    // ---------- Suche, Sortierung, Ansicht ----------

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

    fun toggleView() {
        viewMode = if (viewMode == ViewMode.List) ViewMode.Grid else ViewMode.List
        prefs.edit().putString("view_${section.name}", viewMode.name).apply()
    }

    fun togglePdfQuick() {
        pdfQuick = !pdfQuick
        prefs.edit().putBoolean("pdf_quick", pdfQuick).apply()
    }

    // ---------- Auswahl ----------

    fun toggle(path: String) {
        selected = if (path in selected) selected - path else selected + path
    }

    fun toggleGroup(paths: List<String>) {
        if (paths.isEmpty()) return
        selected = if (selected.containsAll(paths)) selected - paths.toSet() else selected + paths
    }

    fun selectPaths(paths: List<String>) { selected = selected + paths }

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
            val current = dupResults
            dupResults = withContext(Dispatchers.IO) {
                val out = HashMap<Section, List<DupGroup>>()
                for ((sec, gs) in current) {
                    val kept = ArrayList<DupGroup>()
                    for (g in gs) {
                        val alive = g.files.filter { File(it.path).exists() }
                        if (alive.size >= 2) kept.add(g.copy(files = alive))
                    }
                    out[sec] = kept
                }
                out
            }
            reload()
        }
    }

    /** Verschiebt in den Papierkorb (30 Tage wiederherstellbar). */
    fun trash(paths: List<String>) = launchOp {
        val files = paths.map { File(it) }
        val old = files.flatMap { Fs.collect(it) }.map { it.path }
        val n = Trash.moveToTrash(roots, files)
        Fs.scan(app, old)
        "$n von ${files.size} in den Papierkorb verschoben"
    }

    /** Löscht endgültig (im Papierkorb-Reiter aus dem Papierkorb, sonst direkt). */
    fun deleteForever(paths: List<String>) = launchOp {
        if (section == Section.Trash) {
            val n = Trash.deleteForever(roots, paths.toSet())
            "$n endgültig gelöscht"
        } else {
            val files = paths.map { File(it) }
            val old = files.flatMap { Fs.collect(it) }.map { it.path }
            var ok = 0
            for (f in files) if (f.deleteRecursively()) ok++
            Fs.scan(app, old)
            "$ok von ${files.size} gelöscht"
        }
    }

    fun restore(paths: List<String>) = launchOp {
        val restored = ArrayList<String>()
        val n = Trash.restore(roots, paths.toSet(), restored)
        Fs.scan(app, restored)
        "$n wiederhergestellt"
    }

    fun emptyTrash() = launchOp {
        val n = Trash.emptyAll(roots)
        "Papierkorb geleert ($n)"
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

    private fun transfer(paths: List<String>, target: File, move: Boolean): String {
        var ok = 0
        for (p in paths) {
            val src = File(p)
            if (!src.exists()) continue
            if (src.isDirectory && (target.absolutePath + "/").startsWith(src.absolutePath + "/")) continue
            if (move && src.parentFile?.absolutePath == target.absolutePath) continue
            val dst = Fs.uniqueTarget(target, src.name, src.isDirectory)
            val old = if (move) Fs.collect(src).map { it.path } else emptyList()
            val success = if (move) {
                src.renameTo(dst) || (src.copyRecursively(dst) && src.deleteRecursively())
            } else src.copyRecursively(dst)
            if (success) {
                ok++
                Fs.scan(app, old + Fs.collect(dst).map { it.path })
            }
        }
        return "$ok von ${paths.size} ${if (move) "verschoben" else "kopiert"}"
    }

    fun paste() {
        val c = clip ?: return
        val target = dir ?: return
        clip = null
        launchOp { transfer(c.paths, target, c.move) }
    }

    fun moveInto(paths: List<String>, target: File) = launchOp {
        target.mkdirs()
        transfer(paths, target, true)
    }
}
