package de.dateimanager.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
    object NewFolder : Dlg
    object EmptyTrash : Dlg
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerApp(vm: FmViewModel = viewModel()) {
    val ctx = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    val rows = vm.rows
    val entries = remember(rows) { rows.filterIsInstance<Row.Entry>().map { it.item } }
    val selectedItems = entries.filter { it.path in vm.selected }
    val selMode = vm.selected.isNotEmpty()
    val inTrash = vm.section == Section.Trash
    val special = vm.section == Section.Other || inTrash
    val hasSub = vm.hasSub(vm.section)
    val dupMode = hasSub && vm.subMode == SubMode.Dupes
    val tabs = remember { Section.values().filter { it.inTabs } }
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var pdfPath by rememberSaveable { mutableStateOf<String?>(null) }
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(vm.section, vm.dir, vm.albumDir, vm.subMode) {
        listState.scrollToItem(0)
        gridState.scrollToItem(0)
    }

    BackHandler(enabled = vm.canBack()) { vm.back() }

    val onItemClick: (FileItem) -> Unit = { item ->
        when {
            selMode -> vm.toggle(item.path)
            item.isDir -> vm.openDir(File(item.path))
            item.ext == "pdf" && vm.pdfQuick -> pdfPath = item.path
            else -> Fs.open(ctx, File(item.path))
        }
    }

    Scaffold(
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
                                Icon(Icons.Default.Delete, "Löschen")
                            }
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Mehr") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Alles auswählen") }, onClick = {
                                        vm.selectAll(entries.map { it.path }); menu = false
                                    })
                                    if (inTrash) {
                                        DropdownMenuItem(text = { Text("Wiederherstellen") }, onClick = {
                                            vm.restore(selectedItems.map { it.path }); menu = false
                                        })
                                    } else {
                                        DropdownMenuItem(text = { Text("Kopieren") }, onClick = {
                                            vm.setClip(false); menu = false
                                        })
                                        DropdownMenuItem(text = { Text("Verschieben") }, onClick = {
                                            vm.setClip(true); menu = false
                                        })
                                        if (hasSub) {
                                            DropdownMenuItem(text = { Text("In Album verschieben …") }, onClick = {
                                                dialog = Dlg.MoveToAlbum(selectedItems.map { it.path }); menu = false
                                            })
                                        }
                                        if (selectedItems.size == 1) {
                                            DropdownMenuItem(text = { Text("Umbenennen") }, onClick = {
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
                            ad != null -> if (ad == FmViewModel.INBOX) "Nicht zugeordnet" else File(ad).name
                            else -> vm.section.title
                        }
                        val showTools = vm.section != Section.Storage && !dupMode
                        TopAppBar(
                            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            navigationIcon = {
                                if (ad != null || special) {
                                    IconButton(onClick = { vm.back() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                                    }
                                }
                            },
                            actions = {
                                if (vm.section != Section.Storage) {
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
                                        } else {
                                            DropdownMenuItem(
                                                text = { Text(if (vm.pdfQuick) "PDF-Schnellansicht: an" else "PDF-Schnellansicht: aus") },
                                                onClick = { vm.togglePdfQuick(); more = false }
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
                    if (hasSub && !vm.searching) SubModeChips(vm)
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
            val c = vm.clip
            if (c != null && vm.section == Section.Browse) {
                Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${c.paths.size} Element(e) zum ${if (c.move) "Verschieben" else "Kopieren"}",
                            Modifier.weight(1f)
                        )
                        TextButton(onClick = { vm.cancelClip() }) { Text("Abbrechen") }
                        Button(onClick = { vm.paste() }) { Text("Hier einfügen") }
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
            } else {
                if (dupMode) DupBar(vm)
                if (rows.isEmpty()) {
                    if (!vm.loading && !dupMode) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (vm.query.isNotBlank()) "Keine Treffer" else "Keine Dateien",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    val showFolder = vm.section != Section.Browse || vm.query.isNotBlank()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (vm.viewMode == ViewMode.List) {
                            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                                items(rows, key = { it.key }) { row ->
                                    when (row) {
                                        is Row.Header -> GroupHeader(
                                            label = row.label,
                                            selectable = row.paths.isNotEmpty(),
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
                                            loader = loader,
                                            onClick = { vm.openAlbum(row.info.path) },
                                            onLongClick = {
                                                if (row.info.path != FmViewModel.INBOX) dialog = Dlg.AlbumOptions(row.info)
                                            }
                                        )
                                        is Row.Entry -> FileRow(
                                            item = row.item,
                                            selected = row.item.path in vm.selected,
                                            showFolder = showFolder,
                                            dup = row.item.path in vm.dupPaths,
                                            loader = loader,
                                            onClick = { onItemClick(row.item) },
                                            onLongClick = { vm.toggle(row.item.path) }
                                        )
                                    }
                                }
                            }
                            FastScroller(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                total = rows.size,
                                firstVisible = listState.firstVisibleItemIndex,
                                visible = listState.layoutInfo.visibleItemsInfo.size,
                                scrolling = listState.isScrollInProgress
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
                                            selectable = row.paths.isNotEmpty(),
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
                                            loader = loader,
                                            onClick = { vm.openAlbum(row.info.path) },
                                            onLongClick = {
                                                if (row.info.path != FmViewModel.INBOX) dialog = Dlg.AlbumOptions(row.info)
                                            }
                                        )
                                        is Row.Entry -> FileTile(
                                            item = row.item,
                                            selected = row.item.path in vm.selected,
                                            dup = row.item.path in vm.dupPaths,
                                            loader = loader,
                                            onClick = { onItemClick(row.item) },
                                            onLongClick = { vm.toggle(row.item.path) }
                                        )
                                    }
                                }
                            }
                            FastScroller(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                total = rows.size,
                                firstVisible = gridState.firstVisibleItemIndex,
                                visible = gridState.layoutInfo.visibleItemsInfo.size,
                                scrolling = gridState.isScrollInProgress
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
            inTrash = inTrash,
            onTrash = { vm.trash(d.items.map { it.path }); dialog = null },
            onForever = { vm.deleteForever(d.items.map { it.path }); dialog = null },
            onDismiss = { dialog = null }
        )
        is Dlg.AlbumOptions -> AlbumOptionsDialog(
            info = d.info,
            onDismiss = { dialog = null },
            onOpen = { vm.openAlbum(d.info.path); dialog = null },
            onToggleInbox = { vm.markAlbum(d.info.path, !d.info.inbox); dialog = null },
            onShowInFiles = { vm.showInFiles(d.info.path); dialog = null },
            onRename = {
                dialog = Dlg.Rename(FileItem(d.info.path, d.info.name, true, 0L, 0L))
            }
        )
        is Dlg.MoveToAlbum -> AlbumPickerDialog(
            albums = vm.albumInfos.filter { !it.inbox },
            onPick = { vm.moveInto(d.paths, File(it.path)); dialog = null },
            onCreate = { vm.moveInto(d.paths, File(vm.newAlbumBase(), it.trim())); dialog = null },
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

    val pdf = pdfPath
    if (pdf != null) {
        PdfSheet(
            path = pdf,
            onClose = { pdfPath = null },
            onOpen = { Fs.open(ctx, File(pdf)); pdfPath = null }
        )
    }
}
