package de.dateimanager.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
import java.security.MessageDigest

class FmViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val INBOX = "::inbox"
        private val ALBUM_SECTIONS = setOf(
            Section.Docs, Section.Music, Section.Recordings, Section.Images, Section.Videos, Section.Messenger
        )
        private val INBOX_NAMES = setOf(
            "camera", "screenshots", "screenshot", "screen recordings", "screenrecorder", "screen_recorder",
            "download", "downloads", "bluetooth", "telegram", "snapchat", "instagram", "facebook",
            "messenger", "signal", "sent", "received", "dcim", "pictures", "movies", "documents",
            "music", "video", "videos", "images", "recordings"
        )
    }

    private val app: Application = application
    private val prefs = app.getSharedPreferences("settings", 0)

    private fun loadCloudRoots(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (e in prefs.getStringSet("cloud", emptySet()) ?: emptySet()) {
            val p = e.split('\t')
            if (p.size == 2) out.add(Pair(p[0], p[1]))
        }
        return out
    }

    private fun loadSet(key: String): Set<String> = prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private fun loadView(s: Section): ViewMode {
        val def = if (s == Section.Images || s == Section.Videos || s == Section.Screenshots) ViewMode.Grid else ViewMode.List
        return try {
            ViewMode.valueOf(prefs.getString("view_${s.name}", def.name) ?: def.name)
        } catch (e: Exception) {
            def
        }
    }

    var section by mutableStateOf(Section.Recent)
    var roots by mutableStateOf<List<Root>>(emptyList())
    var dir by mutableStateOf<File?>(null)
    var items by mutableStateOf<List<FileItem>>(emptyList())
    var loading by mutableStateOf(false)
    var sortBy by mutableStateOf(SortBy.Date)
    var asc by mutableStateOf(false)
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
    var viewMode by mutableStateOf(loadView(Section.Recent))
    var pdfQuick by mutableStateOf(prefs.getBoolean("pdf_quick", true))
    val marks = Marks(prefs)
    var docFilter by mutableStateOf(DocFilter.All)
    var otherFilter by mutableStateOf(OtherFilter.All)
    var trashDays by mutableStateOf(prefs.getInt("trash_days", 30))
    private var otherBack = Section.Storage
    var cleanupFilter by mutableStateOf(CleanupFilter.EmptyFolders)
    var advQuery by mutableStateOf("")
    var advType by mutableStateOf(AdvType.All)
    var advSize by mutableStateOf(AdvSize.Any)
    var advAge by mutableStateOf(AdvAge.Any)
    var simResults by mutableStateOf<Map<Section, List<DupGroup>>>(emptyMap())
    var showSimilar by mutableStateOf(false)
    var vaultHasPin by mutableStateOf(prefs.contains("vault_hash"))
    var vaultUnlocked by mutableStateOf(false)
    var cloudRoots by mutableStateOf(loadCloudRoots())
    var cloudPath by mutableStateOf<List<CloudNode>>(emptyList())
    var cloudItems by mutableStateOf<List<CloudItem>>(emptyList())
    var cloudBusy by mutableStateOf(false)
    var cloudPending by mutableStateOf<Pair<String, String>?>(null)
    val scrollMemory = HashMap<String, Pair<Int, Int>>()

    private var job: Job? = null
    private var dupJob: Job? = null
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
        if (section == Section.Messenger) return false
        if (dirPath in albumSet) return false
        if (dirPath in inboxSet) return true
        val low = dirPath.lowercase()
        if (File(dirPath).name.lowercase() in INBOX_NAMES) return true
        return low.contains("whatsapp") || low.contains("telegram")
    }

    private fun albumName(p: String): String {
        val f = File(p)
        val n = f.name
        val parent = f.parentFile?.name
        return if (parent != null && n.lowercase() in setOf("sent", "private", "received")) "$parent › $n" else n
    }

    private fun albumsOf(list: List<FileItem>): List<AlbumInfo> =
        list.groupBy { File(it.path).parent ?: "" }.map { (p, fs) ->
            val cover = fs.maxByOrNull { it.modified }!!
            var total = 0L
            for (f in fs) total += f.size
            AlbumInfo(p, albumName(p), fs.size, total, cover.modified, cover.path, isInbox(p))
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
        if (section == Section.Storage || section == Section.More || section == Section.Cloud) return emptyList()
        var list = items
        if (section == Section.Docs && docFilter != DocFilter.All) list = list.filter { docFilter.matches(it.ext) }
        if (section == Section.Other && otherFilter != OtherFilter.All) list = list.filter { otherFilter.matches(it) }
        if (section == Section.Collections) {
            val ad = albumDir
            if (ad == null) return tagRows()
            val set = marks.tags[ad.removePrefix("tag:")].orEmpty()
            list = list.filter { it.path in set }
        }
        if (hasSub(section)) {
            when (subMode) {
                SubMode.Dupes -> return dupRows()
                SubMode.Albums -> {
                    val ad = albumDir
                    if (ad == null) return albumRows(list)
                    list = if (ad == INBOX) list.filter { isInbox(File(it.path).parent ?: "") }
                    else list.filter { File(it.path).parent == ad }
                }
                SubMode.All, SubMode.Timeline -> {}
            }
        }
        if (section != Section.Browse && query.isNotBlank()) {
            val q = query.trim().lowercase()
            list = list.filter { it.name.lowercase().contains(q) }
        }
        if (hasSub(section) && subMode == SubMode.Timeline) {
            val months = list.groupBy { Fs.monthKey(it.modified) }
            return months.keys.sortedDescending().flatMap { k ->
                val g = months.getValue(k)
                listOf<Row>(Row.Header(Fs.monthLabel(k), g.map { it.path })) +
                    Fs.sorted(g, sortBy, asc, false).map { Row.Entry(it) }
            }
        }
        if (section == Section.Recent) {
            val groups = list.groupBy { Fs.epochDay(it.modified) }
            return groups.keys.sortedDescending().flatMap { day ->
                val g = groups.getValue(day)
                listOf<Row>(Row.Header(Fs.dayLabel(day), g.map { it.path })) +
                    Fs.sorted(g, sortBy, asc, false).map { Row.Entry(it) }
            }
        }
        return Fs.sorted(list, sortBy, asc, section == Section.Browse || section == Section.Favorites || section == Section.Locked)
            .map { Row.Entry(it) }
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
            out.add(Row.Header("Ohne Album – Kamera, Screenshots, Downloads …"))
            out.add(Row.Album(AlbumInfo(INBOX, "Alle Dateien ohne Album", cnt, sz, newest.newest, newest.cover, true)))
            for (a in sortAlbums(inbox)) out.add(Row.Album(a))
        }
        if (real.isNotEmpty()) {
            out.add(Row.Header("Deine Alben"))
            for (a in sortAlbums(real)) out.add(Row.Album(a))
        }
        return out
    }

    private fun tagRows(): List<Row> {
        val byPath = items.associateBy { it.path }
        val infos = ArrayList<AlbumInfo>()
        for ((tag, set) in marks.tags) {
            val fs = set.mapNotNull { byPath[it] }
            if (fs.isEmpty()) continue
            val cover = fs.maxByOrNull { it.modified }!!
            var sz = 0L
            for (f in fs) sz += f.size
            infos.add(AlbumInfo("tag:$tag", tag, fs.size, sz, cover.modified, cover.path, false))
        }
        return infos.sortedBy { it.name.lowercase() }.map { Row.Album(it) }
    }

    private fun suggestKeep(files: List<FileItem>, tagged: Set<String>): FileItem =
        files.sortedWith(
            compareBy<FileItem>(
                { if (marks.isLocked(it.path)) 0 else 1 },
                { if (it.path in marks.favorites) 0 else 1 },
                { if (it.path in tagged) 0 else 1 },
                { if (isInbox(File(it.path).parent ?: "")) 1 else 0 },
                { if (Dupes.normName(it.name) == it.name.lowercase()) 0 else 1 },
                { it.modified }
            )
        ).first()

    private fun dupRows(): List<Row> {
        val src = if (showSimilar && section == Section.Images) simResults else dupResults
        val groups = src[section].orEmpty()
        val tagged = HashSet<String>()
        for (st in marks.tags.values) tagged.addAll(st)
        return groups.flatMapIndexed { i, g ->
            val keep = suggestKeep(g.files, tagged)
            val ordered = listOf(keep) + g.files.filter { it.path != keep.path }
            val label = if (g.similar) {
                "${g.files.size} ähnliche Bilder · visuell verwandt, Größe und Qualität können abweichen"
            } else if (g.verified) {
                "${g.files.size} identische Dateien · ${Fs.formatSize(g.size)} je Datei · Inhalt geprüft"
            } else {
                "${g.files.size} mögliche Duplikate · ${Fs.formatSize(g.size)} · gleicher Name + Größe"
            }
            listOf<Row>(Row.DupHeader("g$i", label, ordered.drop(1).map { it.path }.filter { !marks.isLocked(it) })) +
                ordered.map { Row.Entry(it) }
        }
    }

    fun dupSummary(): String {
        val src = if (showSimilar && section == Section.Images) simResults else dupResults
        val g = src[section] ?: return "Sucht Dateien mit gleicher Größe und prüft danach den Inhalt."
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
        Section.Recent, Section.Images, Section.Videos, Section.Trash, Section.Downloads, Section.Screenshots,
        Section.Archives, Section.Recordings, Section.Search, Section.Vault -> SortBy.Date to false
        Section.Other, Section.Largest, Section.Cleanup -> SortBy.Size to false
        else -> SortBy.Name to true
    }

    private fun defaultSubMode(s: Section): SubMode = if (s == Section.Messenger) SubMode.Albums else SubMode.All

    private fun pathItems(paths: Collection<String>): List<FileItem> =
        paths.map { File(it) }.filter { it.exists() }.map { Fs.itemOf(it) }

    fun onResume() {
        roots = Fs.roots(app)
        if (dir == null) dir = roots.firstOrNull()?.dir
        reload()
        val days = trashDays
        if (days > 0) {
            val rs = roots
            viewModelScope.launch(Dispatchers.IO) { Trash.purge(rs, days) }
        }
    }

    fun reload() {
        job?.cancel()
        val s = section
        val q = query.trim()
        val d = dir
        val hidden = showHidden
        val rs = roots
        val cf = cleanupFilter
        val aq = advQuery
        val at = advType
        val asz = advSize
        val aa = advAge
        val vaultOpen = vaultUnlocked
        job = viewModelScope.launch {
            loading = true
            if ((s == Section.Browse && q.isNotEmpty()) || s == Section.Search) delay(300)
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
                        Section.More, Section.Cloud -> emptyList<FileItem>()
                        Section.Vault -> if (vaultOpen) Vault.list(rs) else emptyList<FileItem>()
                        Section.Cleanup -> Cleanup.find(app, rs, cf) { isActive }
                        Section.Search -> Fs.advanced(app, aq, at, asz, aa)
                        Section.Favorites -> pathItems(marks.favorites)
                        Section.Locked -> pathItems(marks.locked)
                        Section.Collections -> pathItems(marks.tags.values.flatten().distinct())
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
        if (section == Section.Vault) vaultUnlocked = false
        sortMemory[section] = sortBy to asc
        subModeMemory[section] = subMode
        section = s
        viewMode = loadView(s)
        val (by, a) = sortMemory[s] ?: defaultSort(s)
        sortBy = by
        asc = a
        subMode = subModeMemory[s] ?: defaultSubMode(s)
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

    /** Wird aufgerufen, wenn eine andere App einen Ordner mit dem Dateimanager öffnen will. */
    fun openExternalFolder(path: String) {
        val f = File(path)
        val target = if (f.isDirectory) f else f.parentFile
        if (target == null || !target.isDirectory) return
        selectSection(Section.Browse)
        openDir(target)
    }

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
            !section.inTabs

    fun back() {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            searching -> closeSearch()
            albumDir != null -> albumDir = null
            canGoUp() -> openDir(dir!!.parentFile!!)
            section == Section.Other -> selectSection(otherBack)
            section == Section.Trash -> selectSection(Section.Browse)
            section == Section.Cloud && cloudPath.isNotEmpty() -> cloudUp()
            !section.inTabs -> selectSection(Section.More)
        }
    }

    // ---------- Untermodi: Alle / Alben / Duplikate ----------

    fun chooseSubMode(m: SubMode) {
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
        toast(
            if (showHidden) "Versteckte Dateien (Name beginnt mit Punkt) sind jetzt im Reiter „Dateien“ sichtbar"
            else "Versteckte Dateien sind wieder ausgeblendet"
        )
        if (section != Section.Browse) selectSection(Section.Browse) else reload()
    }

    fun cycleTrashDays() {
        trashDays = when (trashDays) {
            7 -> 30
            30 -> 90
            90 -> 0
            else -> 7
        }
        prefs.edit().putInt("trash_days", trashDays).apply()
        toast(if (trashDays == 0) "Papierkorb wird nicht automatisch geleert" else "Papierkorb wird nach $trashDays Tagen automatisch geleert")
    }

    fun openOther(f: OtherFilter, back: Section) {
        otherFilter = f
        otherBack = back
        if (section == Section.Other) reload() else selectSection(Section.Other)
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

    // ---------- Ähnliche Bilder ----------

    fun startSimilarScan() {
        val s = section
        val list = items.filter { !it.isDir }
        dupJob?.cancel()
        dupJob = viewModelScope.launch {
            dupStatus = "Vergleiche Bilder …"
            showSimilar = true
            try {
                val res = Similar.scan(list) { st -> dupStatus = st }
                simResults = simResults + (s to res)
            } finally {
                dupStatus = ""
            }
        }
    }

    // ---------- Tresor ----------

    private fun hashPin(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray())
            .joinToString("") { b -> String.format("%02x", b) }

    fun setVaultPin(pin: String) {
        val salt = System.nanoTime().toString()
        prefs.edit().putString("vault_salt", salt).putString("vault_hash", hashPin(pin, salt)).apply()
        vaultHasPin = true
        vaultUnlocked = true
        reload()
    }

    fun unlockVault(pin: String): Boolean {
        val salt = prefs.getString("vault_salt", "") ?: ""
        val ok = hashPin(pin, salt) == prefs.getString("vault_hash", null)
        if (ok) {
            vaultUnlocked = true
            reload()
        }
        return ok
    }

    fun unlockVaultBiometric() {
        if (vaultHasPin) {
            vaultUnlocked = true
            reload()
        }
    }

    fun lockVault() {
        vaultUnlocked = false
        if (section == Section.Vault) items = emptyList()
    }

    fun moveToVault(paths: List<String>) {
        if (!vaultHasPin) {
            toast("Richte zuerst den Tresor ein (Mehr → Tresor).")
            return
        }
        launchOp {
            val files = paths.map { File(it) }
            val old = files.flatMap { Fs.collect(it) }.map { it.path }
            val n = Vault.hideIn(roots, files)
            Fs.scan(app, old)
            "$n von ${files.size} in den Tresor verschoben"
        }
    }

    fun bringOutOfVault(paths: List<String>) = launchOp {
        val restored = ArrayList<String>()
        val n = Vault.bringOut(roots, paths.toSet(), restored)
        Fs.scan(app, restored)
        "$n aus dem Tresor geholt"
    }

    // ---------- Cloud (über Android-Dokumentenzugriff) ----------

    private fun saveCloud() {
        val set = HashSet<String>()
        for (r in cloudRoots) set.add(r.first + "\t" + r.second)
        prefs.edit().putStringSet("cloud", set).apply()
    }

    fun addCloudRoot(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
        }
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { CloudFs.rootName(app, uri) }
            val a = uri.authority ?: ""
            val provider = when {
                a.contains("docs", true) -> "Google Drive"
                a.contains("dropbox", true) -> "Dropbox"
                a.contains("skydrive", true) || a.contains("onedrive", true) || a.contains("microsoft", true) -> "OneDrive"
                else -> "Cloud"
            }
            cloudPending = Pair(uri.toString(), "$provider – $name")
        }
    }

    fun confirmCloudRoot(label: String) {
        val p = cloudPending ?: return
        val l = label.trim().ifEmpty { p.second }
        cloudRoots = cloudRoots.filter { it.first != p.first } + Pair(p.first, l)
        saveCloud()
        cloudPending = null
    }

    fun removeCloudRoot(entry: Pair<String, String>) {
        try {
            app.contentResolver.releasePersistableUriPermission(
                Uri.parse(entry.first), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
        }
        cloudRoots = cloudRoots.filter { it.first != entry.first }
        saveCloud()
    }

    fun openCloudRoot(entry: Pair<String, String>) {
        val tree = Uri.parse(entry.first)
        val doc = DocumentsContract.getTreeDocumentId(tree)
        cloudPath = listOf(CloudNode(tree, doc, entry.second))
        loadCloud()
    }

    fun openCloudDir(item: CloudItem) {
        val cur = cloudPath.lastOrNull() ?: return
        cloudPath = cloudPath + CloudNode(cur.tree, item.docId, item.name)
        loadCloud()
    }

    fun cloudUp() {
        cloudPath = cloudPath.dropLast(1)
        if (cloudPath.isEmpty()) cloudItems = emptyList() else loadCloud()
    }

    fun loadCloud() {
        val node = cloudPath.lastOrNull() ?: return
        cloudBusy = true
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                try { CloudFs.list(app, node.tree, node.docId) } catch (e: Exception) { emptyList<CloudItem>() }
            }
            cloudItems = res
            cloudBusy = false
        }
    }

    fun cloudDownload(item: CloudItem) {
        val node = cloudPath.lastOrNull() ?: return
        viewModelScope.launch {
            cloudBusy = true
            val f = withContext(Dispatchers.IO) { CloudFs.download(app, node.tree, item) }
            cloudBusy = false
            toast(if (f != null) "Geladen nach Download/Cloud" else "Download fehlgeschlagen")
        }
    }

    fun cloudDelete(item: CloudItem) {
        val node = cloudPath.lastOrNull() ?: return
        viewModelScope.launch {
            cloudBusy = true
            val ok = withContext(Dispatchers.IO) { CloudFs.delete(app, node.tree, item) }
            toast(if (ok) "In der Cloud gelöscht" else "Löschen nicht möglich")
            loadCloud()
        }
    }

    fun cloudUpload(uris: List<Uri>) {
        val node = cloudPath.lastOrNull() ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            cloudBusy = true
            val n = withContext(Dispatchers.IO) {
                var ok = 0
                for (u in uris) if (CloudFs.upload(app, node.tree, node.docId, u)) ok++
                ok
            }
            toast("$n von ${uris.size} hochgeladen")
            loadCloud()
        }
    }

    // ---------- Favoriten, Sperren, Sammlungen ----------

    private fun toast(msg: String) {
        Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
    }

    fun toggleFavorites(paths: List<String>) {
        val add = !paths.all { it in marks.favorites }
        marks.toggleFavorites(paths)
        selected = emptySet()
        toast(if (add) "Zu Favoriten hinzugefügt" else "Aus Favoriten entfernt")
        if (section == Section.Favorites) reload()
    }

    fun toggleLock(paths: List<String>) {
        val add = !paths.all { it in marks.locked }
        marks.toggleLock(paths)
        selected = emptySet()
        toast(if (add) "Gesperrt: wird beim Löschen übersprungen" else "Entsperrt")
        if (section == Section.Locked) reload()
    }

    fun addToTag(tag: String, paths: List<String>) {
        val t = tag.trim()
        if (t.isEmpty()) return
        marks.addToTag(t, paths)
        selected = emptySet()
        toast("Zu Sammlung „$t“ hinzugefügt")
        if (section == Section.Collections) reload()
    }

    fun removeFromTag(paths: List<String>) {
        val tag = albumDir?.removePrefix("tag:") ?: return
        marks.removeFromTag(tag, paths)
        selected = emptySet()
        reload()
    }

    fun deleteTag(tag: String) {
        marks.deleteTag(tag)
        albumDir = null
    }

    // ---------- Operationen ----------

    private fun prune(m: Map<Section, List<DupGroup>>): Map<Section, List<DupGroup>> {
        val out = HashMap<Section, List<DupGroup>>()
        for ((sec, gs) in m) {
            val kept = ArrayList<DupGroup>()
            for (g in gs) {
                val alive = g.files.filter { File(it.path).exists() }
                if (alive.size >= 2) kept.add(g.copy(files = alive))
            }
            out[sec] = kept
        }
        return out
    }

    private fun launchOp(block: () -> String) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try { block() } catch (e: Exception) { "Fehler: ${e.message}" }
            }
            if (msg.isNotEmpty()) Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
            selected = emptySet()
            val cur1 = dupResults
            val cur2 = simResults
            val pruned = withContext(Dispatchers.IO) { Pair(prune(cur1), prune(cur2)) }
            dupResults = pruned.first
            simResults = pruned.second
            reload()
        }
    }

    /** Verschiebt in den Papierkorb (30 Tage wiederherstellbar). */
    fun trash(paths: List<String>) = launchOp {
        val allowed = paths.filter { !marks.isProtected(it) }
        val skipped = paths.size - allowed.size
        val files = allowed.map { File(it) }
        val old = files.flatMap { Fs.collect(it) }.map { it.path }
        val n = Trash.moveToTrash(roots, files)
        Fs.scan(app, old)
        "$n von ${files.size} in den Papierkorb verschoben" +
            (if (skipped > 0) " · $skipped gesperrt übersprungen" else "")
    }

    /** Löscht endgültig (im Papierkorb-Reiter aus dem Papierkorb, sonst direkt). */
    fun deleteForever(paths: List<String>) = launchOp {
        if (section == Section.Vault) {
            val n = Vault.deleteForever(roots, paths.toSet())
            "$n endgültig gelöscht"
        } else if (section == Section.Trash) {
            val n = Trash.deleteForever(roots, paths.toSet())
            "$n endgültig gelöscht"
        } else {
            val allowed = paths.filter { !marks.isProtected(it) }
            val skipped = paths.size - allowed.size
            val files = allowed.map { File(it) }
            val old = files.flatMap { Fs.collect(it) }.map { it.path }
            var ok = 0
            for (f in files) if (f.deleteRecursively()) ok++
            Fs.scan(app, old)
            "$ok von ${files.size} gelöscht" + (if (skipped > 0) " · $skipped gesperrt übersprungen" else "")
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
                    marks.remap(src.path, dst.path)
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
        val paths = selected.toList()
        clip = Clip(paths, move)
        selected = emptySet()
        goToFolderOf(paths)
    }

    /** Wechselt in den Dateien-Reiter und öffnet den Ordner, in dem die Auswahl liegt. */
    private fun goToFolderOf(paths: List<String>) {
        val start = paths.firstOrNull()?.let { File(it).parentFile }
        if (section != Section.Browse) selectSection(Section.Browse)
        if (start != null && start.isDirectory) openDir(start)
    }

    fun cancelClip() { clip = null }

    /** Einzelne Pfade (z. B. ein Ordner) zum Verschieben/Kopieren vormerken und zum Dateien-Reiter wechseln. */
    fun setClipPaths(paths: List<String>, move: Boolean) {
        clip = Clip(paths, move)
        selected = emptySet()
        goToFolderOf(paths)
    }

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
                if (move) marks.remap(src.path, dst.path)
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
