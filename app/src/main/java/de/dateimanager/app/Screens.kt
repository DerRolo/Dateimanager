package de.dateimanager.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.clickable
import coil.compose.AsyncImage
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.launch
import java.io.File

sealed interface Dlg {
    data class Rename(val item: FileItem) : Dlg
    data class Delete(val items: List<FileItem>) : Dlg
    data class AlbumOptions(val info: AlbumInfo) : Dlg
    data class MoveToAlbum(val paths: List<String>) : Dlg
    data class AddToTag(val paths: List<String>) : Dlg
    data class TagOptions(val info: AlbumInfo) : Dlg
    object NewFolder : Dlg
    object EmptyTrash : Dlg
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerApp(vm: FmViewModel = viewModel()) {
    val ctx = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.lockVault() }

    val rows = vm.rows
    val entries = remember(rows) { rows.filterIsInstance<Row.Entry>().map { it.item } }
    val selectedItems = entries.filter { it.path in vm.selected }
    val selMode = vm.selected.isNotEmpty()
    val inTrash = vm.section == Section.Trash
    val special = !vm.section.inTabs
    val hasSub = vm.hasSub(vm.section)
    val dupMode = hasSub && vm.subMode == SubMode.Dupes
    val headerOn = headerNeeded(vm)
    val swipeOn = !selMode && !inTrash && vm.section != Section.Vault
    val tabs = remember { Section.values().filter { it.inTabs } }
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var pdfPath by rememberSaveable { mutableStateOf<String?>(null) }
    var docPath by rememberSaveable { mutableStateOf<String?>(null) }
    var imagePreviewPath by rememberSaveable { mutableStateOf<String?>(null) }
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun showUndo(msg: String, onUndo: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(msg, actionLabel = "Rückgängig", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onUndo()
        }
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addCloudRoot(uri)
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.cloudUpload(uris)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var playerSheet by remember { mutableStateOf(false) }

    fun startMedia(item: FileItem) {
        val kind = item.kind()
        val list = entries.filter { it.kind() == kind }.map { it.path }
        val idx = list.indexOf(item.path).coerceAtLeast(0)
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.player.play(list, idx, kind == Kind.Video)
    }

    // Scrollposition pro Ansicht merken, damit man beim Zurückgehen wieder an derselben Stelle ist
    val viewKey = vm.section.name + "|" + vm.dir?.path + "|" + vm.albumDir + "|" + vm.subMode.name + "|" +
        vm.viewMode.name + "|" + vm.query.trim() + "|" +
        vm.recentType.name + vm.docFilter.name + vm.otherFilter.name
    var restoredKey by remember { mutableStateOf("") }
    LaunchedEffect(viewKey, vm.loading) {
        if (!vm.loading && restoredKey != viewKey) {
            val saved = vm.scrollMemory[viewKey]
            val idx = saved?.first ?: 0
            val off = saved?.second ?: 0
            listState.scrollToItem(idx, off)
            gridState.scrollToItem(idx, off)
            restoredKey = viewKey
        }
    }
    LaunchedEffect(viewKey) {
        snapshotFlow {
            if (vm.viewMode == ViewMode.List) {
                Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            } else {
                Pair(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
            }
        }.collect { p ->
            if (restoredKey == viewKey) vm.scrollMemory[viewKey] = p
        }
    }

    BackHandler(enabled = vm.canBack()) { vm.back() }

    val onItemClick: (FileItem) -> Unit = { item ->
        when {
            selMode -> vm.toggle(item.path)
            item.isDir && vm.section == Section.Browse -> vm.openDir(File(item.path))
            item.isDir -> vm.showInFiles(item.path)
            vm.playInApp && (item.kind() == Kind.Audio || item.kind() == Kind.Video) -> startMedia(item)
            item.kind() == Kind.Image -> imagePreviewPath = item.path
            item.ext == "pdf" && vm.pdfQuick -> pdfPath = item.path
            DocPreview.canPreview(item.ext) && vm.pdfQuick -> docPath = item.path
            else -> Fs.open(ctx, File(item.path))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                when {
                    selMode -> TopAppBar(
                        title = { Text("${vm.selected.size} ausgewählt") },
                        navigationIcon = {
                            IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Default.Close, "Auswahl aufheben") }
                        },
                        actions = {
                            if (!inTrash) {
                                IconButton(onClick = { Fs.share(ctx, selectedItems.map { File(it.path) }) }) {
                                    Icon(Icons.Default.Share, "Teilen")
                                }
                            }
                            IconButton(onClick = { dialog = Dlg.Delete(selectedItems) }) {
                                Icon(Icons.Default.Delete, "Löschen", tint = Color(0xFFE53935))
                            }
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Mehr") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Alles auswählen") }, leadingIcon = { Icon(Icons.Default.CheckCircle, null) }, onClick = {
                                        vm.selectAll(entries.map { it.path }); menu = false
                                    })
                                    if (inTrash) {
                                        DropdownMenuItem(text = { Text("Wiederherstellen") }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, onClick = {
                                            vm.restore(selectedItems.map { it.path }); menu = false
                                        })
                                    } else if (vm.section == Section.Vault) {
                                        DropdownMenuItem(text = { Text("Aus Tresor holen") }, leadingIcon = { Icon(Icons.Default.Security, null) }, onClick = {
                                            vm.bringOutOfVault(selectedItems.map { it.path }); menu = false
                                        })
                                    } else {
                                        DropdownMenuItem(text = { Text("Kopieren") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = {
                                            vm.setClip(false); menu = false
                                        })
                                        DropdownMenuItem(text = { Text("Verschieben") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }, onClick = {
                                            vm.setClip(true); menu = false
                                        })
                                        if (hasSub) {
                                            DropdownMenuItem(text = { Text("In Album verschieben …") }, leadingIcon = { Icon(Icons.Default.FolderOpen, null) }, onClick = {
                                                dialog = Dlg.MoveToAlbum(selectedItems.map { it.path }); menu = false
                                            })
                                        }
                                        val allFav = selectedItems.all { it.path in vm.marks.favorites }
                                        DropdownMenuItem(text = { Text(if (allFav) "Aus Favoriten entfernen" else "Zu Favoriten") }, leadingIcon = { Icon(Icons.Default.Star, null) }, onClick = {
                                            vm.toggleFavorites(selectedItems.map { it.path }); menu = false
                                        })
                                        val allLocked = selectedItems.all { it.path in vm.marks.locked }
                                        DropdownMenuItem(text = { Text(if (allLocked) "Entsperren" else "Sperren (vor Löschen schützen)") }, leadingIcon = { Icon(Icons.Default.Lock, null) }, onClick = {
                                            vm.toggleLock(selectedItems.map { it.path }); menu = false
                                        })
                                        DropdownMenuItem(text = { Text("In Tresor verschieben") }, leadingIcon = { Icon(Icons.Default.Security, null) }, onClick = {
                                            vm.moveToVault(selectedItems.map { it.path }); menu = false
                                        })
                                        DropdownMenuItem(text = { Text("Zu Sammlung hinzufügen …") }, leadingIcon = { Icon(Icons.Default.Bookmark, null) }, onClick = {
                                            dialog = Dlg.AddToTag(selectedItems.map { it.path }); menu = false
                                        })
                                        if (vm.section == Section.Collections && vm.albumDir != null) {
                                            DropdownMenuItem(text = { Text("Aus Sammlung entfernen") }, leadingIcon = { Icon(Icons.Default.Close, null) }, onClick = {
                                                vm.removeFromTag(selectedItems.map { it.path }); menu = false
                                            })
                                        }
                                        if (selectedItems.size == 1) {
                                            DropdownMenuItem(text = { Text("Umbenennen") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = {
                                                dialog = Dlg.Rename(selectedItems[0]); menu = false
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    )

                    vm.searching -> {
                        val fr = remember { FocusRequester() }
                        LaunchedEffect(Unit) { fr.requestFocus() }
                        TopAppBar(
                            title = {
                                TextField(
                                    value = vm.query,
                                    onValueChange = { vm.updateQuery(it) },
                                    placeholder = { Text("Suchen…") },
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    ),
                                    modifier = Modifier.fillMaxWidth().focusRequester(fr)
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { vm.closeSearch() }) { Icon(Icons.Default.Close, "Suche schließen") }
                            }
                        )
                    }

                    else -> {
                        val ad = vm.albumDir
                        val title = when {
                            vm.section == Section.Browse -> {
                                val d = vm.dir
                                vm.roots.firstOrNull { it.dir == d }?.name ?: d?.name ?: "Dateien"
                            }
                            ad != null -> when {
                                ad == FmViewModel.INBOX -> "Ohne Album"
                                ad.startsWith("tag:") -> ad.removePrefix("tag:")
                                else -> File(ad).name
                            }
                            else -> vm.section.title
                        }
                        val showTools = vm.section != Section.Storage && vm.section != Section.More && vm.section != Section.Cloud &&
                            !(vm.section == Section.Vault && !vm.vaultUnlocked) && !dupMode
                        TopAppBar(
                            title = {
                                Text(
                                    title,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip,
                                    modifier = Modifier.basicMarquee(iterations = 1, initialDelayMillis = 2000)
                                )
                            },
                            navigationIcon = {
                                if (ad != null || special) {
                                    IconButton(onClick = { vm.back() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                                    }
                                }
                            },
                            actions = {
                                if (vm.section != Section.Storage && vm.section != Section.More && vm.section != Section.Cloud &&
                                    vm.section != Section.Search && !(vm.section == Section.Vault && !vm.vaultUnlocked)
                                ) {
                                    IconButton(onClick = { vm.openSearch() }) { Icon(Icons.Default.Search, "Suchen") }
                                }
                                if (showTools) {
                                    IconButton(onClick = { vm.toggleView() }) {
                                        if (vm.viewMode == ViewMode.Grid) {
                                            Icon(Icons.AutoMirrored.Filled.ViewList, "Als Liste anzeigen")
                                        } else {
                                            Icon(Icons.Default.GridView, "Als Kacheln anzeigen")
                                        }
                                    }
                                    var sortMenu by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, "Sortieren") }
                                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                            val hasLength = vm.section == Section.Music || vm.section == Section.Videos
                                            SortBy.values().filter { it != SortBy.Duration || hasLength }.forEach { s ->
                                                val arrow = if (vm.sortBy == s) (if (vm.asc) "   ↑" else "   ↓") else ""
                                                DropdownMenuItem(text = { Text(s.label + arrow) }, onClick = {
                                                    vm.setSort(s); sortMenu = false
                                                })
                                            }
                                        }
                                    }
                                }
                                var more by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "Mehr") }
                                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                        if (inTrash) {
                                            DropdownMenuItem(text = { Text("Papierkorb leeren") }, onClick = {
                                                dialog = Dlg.EmptyTrash; more = false
                                            })
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (vm.trashDays == 0) "Automatisch leeren: aus"
                                                        else "Automatisch leeren nach ${vm.trashDays} Tagen"
                                                    )
                                                },
                                                onClick = { vm.cycleTrashDays(); more = false }
                                            )
                                        } else if (vm.section == Section.Vault) {
                                            DropdownMenuItem(text = { Text("Tresor sperren") }, onClick = {
                                                vm.lockVault(); more = false
                                            })
                                        } else {
                                            DropdownMenuItem(
                                                text = { Text(if (vm.pdfQuick) "Schnellansicht (PDF, Office, Text): an" else "Schnellansicht (PDF, Office, Text): aus") },
                                                onClick = { vm.togglePdfQuick(); more = false }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(if (vm.playInApp) "Musik & Videos in der App abspielen: an" else "Musik & Videos in der App abspielen: aus") },
                                                onClick = { vm.togglePlayInApp(); more = false }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(if (vm.showHidden) "Versteckte Dateien ausblenden" else "Versteckte Dateien anzeigen") },
                                                onClick = { vm.toggleHidden(); more = false }
                                            )
                                            DropdownMenuItem(text = { Text("Papierkorb") }, onClick = {
                                                vm.selectSection(Section.Trash); more = false
                                            })
                                        }
                                        DropdownMenuItem(text = { Text("Aktualisieren") }, onClick = {
                                            vm.reload(); more = false
                                        })
                                    }
                                }
                            }
                        )
                    }
                }
                if (!selMode && !special) {
                    ScrollableTabRow(selectedTabIndex = tabs.indexOf(vm.section).coerceAtLeast(0), edgePadding = 8.dp) {
                        tabs.forEach { s ->
                            Tab(selected = vm.section == s, onClick = { vm.selectSection(s) }, text = { Text(s.title) })
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (vm.section == Section.Browse && !selMode && !vm.searching && vm.clip == null) {
                FloatingActionButton(onClick = { dialog = Dlg.NewFolder }) {
                    Icon(Icons.Default.CreateNewFolder, "Neuer Ordner")
                }
            }
        },
        bottomBar = {
            Column {
            if (vm.player.hasMedia && !vm.player.isVideo) {
                MiniPlayer(vm.player) { playerSheet = true }
            }
            val c = vm.clip
            if (c != null && vm.section == Section.Browse) {
                Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${c.paths.size} Element(e) zum ${if (c.move) "Verschieben" else "Kopieren"}: Zielordner öffnen und „Hier einfügen“ tippen",
                            Modifier.weight(1f)
                        )
                        TextButton(onClick = { vm.cancelClip() }) { Text("Abbrechen") }
                        Button(onClick = { vm.paste() }) { Text("Hier einfügen") }
                    }
                }
            }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.section == Section.Browse && !vm.searching) PathBar(vm)
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.section == Section.Storage) {
                StorageScreen(vm)
            } else if (vm.section == Section.More) {
                MoreScreen { sec, f -> if (f != null) vm.openOther(f, Section.More) else vm.selectSection(sec) }
            } else if (vm.section == Section.Vault && !vm.vaultUnlocked) {
                VaultGate(vm)
            } else if (vm.section == Section.Cloud) {
                CloudScreen(vm, { treeLauncher.launch(null) }, { pickLauncher.launch(arrayOf("*/*")) })
            } else {
                if (dupMode) DupBar(vm)
                if (rows.isEmpty()) {
                    if (headerOn) ListHeader(vm)
                    if (!vm.loading && !dupMode) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                when {
                                    vm.query.isNotBlank() -> "Keine Treffer"
                                    vm.section == Section.Collections -> "Noch keine Sammlungen. Markiere Dateien und wähle ⋮ → „Zu Sammlung hinzufügen“."
                                    vm.section == Section.Favorites -> "Noch keine Favoriten. Markiere Dateien oder Ordner und wähle ⋮ → „Zu Favoriten“."
                                    vm.section == Section.Locked -> "Keine gesperrten Dateien."
                                    vm.section == Section.Vault -> "Der Tresor ist leer. Markiere Dateien und wähle ⋮ → „In Tresor verschieben“."
                                    vm.section == Section.Cleanup -> "Nichts gefunden – hier ist alles sauber."
                                    vm.section == Section.Search -> "Gib einen Namen ein oder wähle Filter."
                                    else -> "Keine Dateien"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    val showFolder = vm.section != Section.Browse || vm.query.isNotBlank()
                    val hc = if (headerOn) 1 else 0
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val dayHeaders = remember(rows) { rows.mapIndexedNotNull { i, r -> if (r is Row.Header && vm.section == Section.Recent) i to r else null } }
                        val visibleIndex = (if (vm.viewMode == ViewMode.List) listState.firstVisibleItemIndex else gridState.firstVisibleItemIndex) - hc
                        val scrollIndex = if (vm.viewMode == ViewMode.List) listState.firstVisibleItemIndex else gridState.firstVisibleItemIndex
                        var previousScrollIndex by remember { mutableIntStateOf(scrollIndex) }
                        var scrollDirection by remember { mutableIntStateOf(0) } // -1 up, +1 down
                        LaunchedEffect(scrollIndex) {
                            if (scrollIndex != previousScrollIndex) scrollDirection = if (scrollIndex > previousScrollIndex) 1 else -1
                            previousScrollIndex = scrollIndex
                        }
                        val activeHeaderIndex = dayHeaders.indexOfLast { it.first <= visibleIndex }.let { if (it >= 0) it else 0 }
                        val teaserHeader = when (scrollDirection) {
                            1 -> dayHeaders.getOrNull(activeHeaderIndex + 1)
                            -1 -> dayHeaders.getOrNull(activeHeaderIndex - 1)
                            else -> null
                        }
                        if (vm.section == Section.Recent && teaserHeader != null) {
                            val (targetIndex, targetHeader) = teaserHeader
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                shape = MaterialTheme.shapes.large,
                                shadowElevation = 5.dp,
                                modifier = Modifier
                                    .align(if (scrollDirection > 0) Alignment.BottomCenter else Alignment.TopCenter)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            if (vm.viewMode == ViewMode.List) listState.animateScrollToItem(targetIndex + hc)
                                            else gridState.animateScrollToItem(targetIndex + hc)
                                        }
                                    }
                            ) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (scrollDirection > 0) "↓" else "↑", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.size(10.dp))
                                    Text(targetHeader.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    if (targetHeader.paths.isNotEmpty()) Text("${targetHeader.paths.size} Dateien", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        if (vm.viewMode == ViewMode.List) {
                            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                                if (headerOn) {
                                    item(key = "list_header") { ListHeader(vm) }
                                }
                                items(rows, key = { it.key }) { row ->
                                    when (row) {
                                        is Row.Header -> GroupHeader(
                                            label = row.label,
                                            selectable = row.paths.isNotEmpty() && selMode,
                                            allSelected = row.paths.isNotEmpty() && vm.selected.containsAll(row.paths),
                                            pad = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                                            onClick = { vm.toggleGroup(row.paths) }
                                        )
                                        is Row.DupHeader -> DupHeaderRow(
                                            label = row.label,
                                            pad = PaddingValues(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 0.dp),
                                            onMarkRest = { vm.selectPaths(row.rest) }
                                        )
                                        is Row.Album -> AlbumRow(
                                            info = row.info,
                                            lock = vm.marks.isLocked(row.info.path),
                                            fav = row.info.path in vm.marks.favorites,
                                            loader = loader,
                                            onClick = { vm.openAlbum(row.info.path) },
                                            onLongClick = {
                                                if (row.info.path.startsWith("tag:")) {
                                                    dialog = Dlg.TagOptions(row.info)
                                                } else if (row.info.path != FmViewModel.INBOX) {
                                                    dialog = Dlg.AlbumOptions(row.info)
                                                }
                                            }
                                        )
                                        is Row.Entry -> SwipeRow(
                                            enabled = swipeOn && !listState.isScrollInProgress,
                                            isFav = row.item.path in vm.marks.favorites,
                                            onFavorite = {
                                                val was = vm.swipeFavorite(row.item.path)
                                                showUndo(if (was) "Aus Favoriten entfernt" else "Zu Favoriten hinzugefügt") {
                                                    vm.swipeFavorite(row.item.path)
                                                }
                                            },
                                            onTrash = {
                                                vm.swipeTrash(row.item.path) { tp ->
                                                    showUndo("In den Papierkorb verschoben") { vm.undoTrash(tp) }
                                                }
                                            }
                                        ) {
                                            FileRow(
                                            item = row.item,
                                            selected = row.item.path in vm.selected,
                                            showFolder = showFolder,
                                            dup = row.item.path in vm.dupPaths,
                                            lock = vm.marks.isLocked(row.item.path),
                                            fav = row.item.path in vm.marks.favorites,
                                            loader = loader,
                                            onClick = { onItemClick(row.item) },
                                            onLongClick = { vm.toggle(row.item.path) }
                                            )
                                        }
                                    }
                                }
                            }
                            FastScroller(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                total = rows.size + hc,
                                firstVisible = listState.firstVisibleItemIndex,
                                visible = listState.layoutInfo.visibleItemsInfo.size,
                                scrolling = listState.isScrollInProgress,
                                label = { idx -> rowLabel(rows, idx - hc, vm.sortBy) }
                            ) { i -> scope.launch { listState.scrollToItem(i) } }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(104.dp),
                                state = gridState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (headerOn) {
                                    item(key = "list_header", span = { GridItemSpan(maxLineSpan) }) { ListHeader(vm) }
                                }
                                items(
                                    rows,
                                    key = { it.key },
                                    span = { row ->
                                        if (row is Row.Header || row is Row.DupHeader) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                                    }
                                ) { row ->
                                    when (row) {
                                        is Row.Header -> GroupHeader(
                                            label = row.label,
                                            selectable = row.paths.isNotEmpty() && selMode,
                                            allSelected = row.paths.isNotEmpty() && vm.selected.containsAll(row.paths),
                                            pad = PaddingValues(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 2.dp),
                                            onClick = { vm.toggleGroup(row.paths) }
                                        )
                                        is Row.DupHeader -> DupHeaderRow(
                                            label = row.label,
                                            pad = PaddingValues(start = 6.dp, end = 0.dp, top = 8.dp, bottom = 0.dp),
                                            onMarkRest = { vm.selectPaths(row.rest) }
                                        )
                                        is Row.Album -> AlbumTile(
                                            info = row.info,
                                            lock = vm.marks.isLocked(row.info.path),
                                            fav = row.info.path in vm.marks.favorites,
                                            loader = loader,
                                            onClick = { vm.openAlbum(row.info.path) },
                                            onLongClick = {
                                                if (row.info.path.startsWith("tag:")) {
                                                    dialog = Dlg.TagOptions(row.info)
                                                } else if (row.info.path != FmViewModel.INBOX) {
                                                    dialog = Dlg.AlbumOptions(row.info)
                                                }
                                            }
                                        )
                                        is Row.Entry -> FileTile(
                                            item = row.item,
                                            selected = row.item.path in vm.selected,
                                            dup = row.item.path in vm.dupPaths,
                                            lock = vm.marks.isLocked(row.item.path),
                                            fav = row.item.path in vm.marks.favorites,
                                            loader = loader,
                                            onClick = { onItemClick(row.item) },
                                            onLongClick = { vm.toggle(row.item.path) }
                                        )
                                    }
                                }
                            }
                            FastScroller(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                total = rows.size + hc,
                                firstVisible = gridState.firstVisibleItemIndex,
                                visible = gridState.layoutInfo.visibleItemsInfo.size,
                                scrolling = gridState.isScrollInProgress,
                                label = { idx -> rowLabel(rows, idx - hc, vm.sortBy) }
                            ) { i -> scope.launch { gridState.scrollToItem(i) } }
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is Dlg.Rename -> NameDialog("Umbenennen", d.item.name, "OK", !d.item.isDir, { dialog = null }) {
            vm.rename(d.item.path, it); dialog = null
        }
        is Dlg.Delete -> DeleteDialog(
            items = d.items,
            inTrash = inTrash || vm.section == Section.Vault,
            onTrash = { vm.trash(d.items.map { it.path }); dialog = null },
            onForever = { vm.deleteForever(d.items.map { it.path }); dialog = null },
            onDismiss = { dialog = null },
            lockedCount = d.items.count { vm.marks.isProtected(it.path) },
            trashDays = vm.trashDays
        )
        is Dlg.AlbumOptions -> AlbumOptionsDialog(
            info = d.info,
            locked = vm.marks.isLocked(d.info.path),
            favorite = d.info.path in vm.marks.favorites,
            onDismiss = { dialog = null },
            onOpen = { vm.openAlbum(d.info.path); dialog = null },
            onRename = {
                dialog = Dlg.Rename(FileItem(d.info.path, File(d.info.path).name, true, 0L, 0L))
            },
            onMove = { vm.setClipPaths(listOf(d.info.path), true); dialog = null },
            onToggleLock = { vm.toggleLock(listOf(d.info.path)); dialog = null },
            onToggleFav = { vm.toggleFavorites(listOf(d.info.path)); dialog = null },
            onToggleInbox = { vm.markAlbum(d.info.path, !d.info.inbox); dialog = null },
            onShowInFiles = { vm.showInFiles(d.info.path); dialog = null },
            onDelete = {
                dialog = Dlg.Delete(listOf(FileItem(d.info.path, File(d.info.path).name, true, 0L, 0L)))
            }
        )
        is Dlg.MoveToAlbum -> AlbumPickerDialog(
            albums = vm.albumInfos.filter { !it.inbox },
            onPick = { vm.moveInto(d.paths, File(it.path)); dialog = null },
            onCreate = { vm.moveInto(d.paths, File(vm.newAlbumBase(), it.trim())); dialog = null },
            onDismiss = { dialog = null }
        )
        is Dlg.AddToTag -> AlbumPickerDialog(
            albums = vm.marks.tags.map { (k, v) -> AlbumInfo(k, k, v.size, 0L, 0L, "", false) },
            onPick = { vm.addToTag(it.name, d.paths); dialog = null },
            onCreate = { vm.addToTag(it, d.paths); dialog = null },
            onDismiss = { dialog = null },
            title = "Zu Sammlung hinzufügen",
            newLabel = "Neue Sammlung"
        )
        is Dlg.TagOptions -> ConfirmDialog(
            title = "Sammlung auflösen",
            text = "„${d.info.name}“ auflösen? Die Dateien bleiben unverändert.",
            confirm = "Auflösen",
            onOk = { vm.deleteTag(d.info.name); dialog = null },
            onDismiss = { dialog = null }
        )
        Dlg.NewFolder -> NameDialog("Neuer Ordner", "", "Erstellen", false, { dialog = null }) {
            vm.newFolder(it); dialog = null
        }
        Dlg.EmptyTrash -> ConfirmDialog(
            title = "Papierkorb leeren",
            text = "Alle Dateien im Papierkorb werden endgültig gelöscht.",
            confirm = "Leeren",
            onOk = { vm.emptyTrash(); dialog = null },
            onDismiss = { dialog = null }
        )
        null -> {}
    }

    if (vm.player.videoOpen) {
        VideoDialog(vm.player) { vm.player.stop() }
    }
    if (playerSheet && vm.player.hasMedia && !vm.player.isVideo) {
        PlayerSheet(vm.player) { playerSheet = false }
    }

    val imagePath = imagePreviewPath
    if (imagePath != null) {
        BackHandler { imagePreviewPath = null }
        Dialog(onDismissRequest = { imagePreviewPath = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxSize(), color = Color.Black) {
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(model = File(imagePath), contentDescription = File(imagePath).name, modifier = Modifier.fillMaxSize())
                    Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { imagePreviewPath = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = Color.White) }
                        Text(File(imagePath).name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    val docp = docPath
    if (docp != null) {
        DocSheet(
            path = docp,
            onClose = { docPath = null },
            onOpen = { Fs.open(ctx, File(docp)); docPath = null }
        )
    }

    val pdf = pdfPath
    if (pdf != null) {
        PdfSheet(
            path = pdf,
            onClose = { pdfPath = null },
            onOpen = { Fs.open(ctx, File(pdf)); pdfPath = null }
        )
    }
}
