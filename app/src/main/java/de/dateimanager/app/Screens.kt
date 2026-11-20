package de.dateimanager.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import java.io.File

sealed interface Dlg {
    data class Rename(val item: FileItem) : Dlg
    data class Delete(val items: List<FileItem>) : Dlg
    object NewFolder : Dlg
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
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }

    BackHandler(enabled = selMode || vm.searching || vm.canGoUp()) { vm.back() }

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
                            IconButton(onClick = { Fs.share(ctx, selectedItems.map { File(it.path) }) }) {
                                Icon(Icons.Default.Share, "Teilen")
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
                                    DropdownMenuItem(text = { Text("Kopieren") }, onClick = {
                                        vm.setClip(false); menu = false
                                    })
                                    DropdownMenuItem(text = { Text("Verschieben") }, onClick = {
                                        vm.setClip(true); menu = false
                                    })
                                    if (selectedItems.size == 1) {
                                        DropdownMenuItem(text = { Text("Umbenennen") }, onClick = {
                                            dialog = Dlg.Rename(selectedItems[0]); menu = false
                                        })
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
                        val title = if (vm.section == Section.Browse) {
                            val d = vm.dir
                            vm.roots.firstOrNull { it.dir == d }?.name ?: d?.name ?: "Dateien"
                        } else vm.section.title
                        TopAppBar(
                            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            actions = {
                                IconButton(onClick = { vm.openSearch() }) { Icon(Icons.Default.Search, "Suchen") }
                                var sortMenu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, "Sortieren") }
                                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                        SortBy.values().forEach { s ->
                                            val arrow = if (vm.sortBy == s) (if (vm.asc) "   ↑" else "   ↓") else ""
                                            DropdownMenuItem(text = { Text(s.label + arrow) }, onClick = {
                                                vm.setSort(s); sortMenu = false
                                            })
                                        }
                                    }
                                }
                                var more by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "Mehr") }
                                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                        DropdownMenuItem(
                                            text = { Text(if (vm.showHidden) "Versteckte Dateien ausblenden" else "Versteckte Dateien anzeigen") },
                                            onClick = { vm.toggleHidden(); more = false }
                                        )
                                        DropdownMenuItem(text = { Text("Aktualisieren") }, onClick = {
                                            vm.reload(); more = false
                                        })
                                    }
                                }
                            }
                        )
                    }
                }
                if (!selMode) {
                    ScrollableTabRow(selectedTabIndex = vm.section.ordinal, edgePadding = 8.dp) {
                        Section.values().forEach { s ->
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
            if (vm.section == Section.Browse && !vm.searching) StorageBar(vm)
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (rows.isEmpty() && !vm.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (vm.query.isNotBlank()) "Keine Treffer" else "Keine Dateien",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is Row.Header -> Text(
                                row.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                            )
                            is Row.Entry -> {
                                val item = row.item
                                FileRow(
                                    item = item,
                                    selected = item.path in vm.selected,
                                    showFolder = vm.section != Section.Browse || vm.query.isNotBlank(),
                                    loader = loader,
                                    onClick = {
                                        when {
                                            selMode -> vm.toggle(item.path)
                                            item.isDir -> vm.openDir(File(item.path))
                                            else -> Fs.open(ctx, File(item.path))
                                        }
                                    },
                                    onLongClick = { vm.toggle(item.path) }
                                )
                            }
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
        is Dlg.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Löschen") },
            text = {
                Text(
                    if (d.items.size == 1) "„${d.items[0].name}“ endgültig löschen?"
                    else "${d.items.size} Elemente endgültig löschen?"
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(d.items.map { it.path }); dialog = null }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Abbrechen") } }
        )
        Dlg.NewFolder -> NameDialog("Neuer Ordner", "", "Erstellen", false, { dialog = null }) {
            vm.newFolder(it); dialog = null
        }
        null -> {}
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    selectBase: Boolean,
    onDismiss: () -> Unit,
    onOk: (String) -> Unit
) {
    var tf by remember {
        val end = if (selectBase) initial.substringBeforeLast('.', initial).length else initial.length
        mutableStateOf(TextFieldValue(initial, TextRange(0, end)))
    }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = tf, onValueChange = { tf = it }, singleLine = true, modifier = Modifier.focusRequester(fr))
        },
        confirmButton = { TextButton(onClick = { onOk(tf.text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun StorageBar(vm: FmViewModel) {
    val active = vm.activeRoot()
    Column {
        if (vm.roots.size > 1) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                vm.roots.forEach { r ->
                    FilterChip(selected = active == r, onClick = { vm.openRoot(r) }, label = { Text(r.name) })
                }
            }
        }
        val d = vm.dir
        if (d != null && active != null) {
            val crumbs = remember(d, active) {
                val list = ArrayList<File>()
                var cur: File? = d
                while (cur != null) {
                    list.add(cur)
                    if (cur.path == active.dir.path) break
                    cur = cur.parentFile
                }
                list.reversed()
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState(), reverseScrolling = true).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                crumbs.forEachIndexed { i, f ->
                    if (i > 0) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (i == 0) active.name else f.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (i == crumbs.lastIndex) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { vm.openDir(f) }.padding(8.dp)
                    )
                }
            }
        }
    }
}

private fun iconFor(k: Kind): Pair<ImageVector, Color> = when (k) {
    Kind.Folder -> Icons.Default.Folder to Color(0xFFFFB300)
    Kind.Image -> Icons.Default.Image to Color(0xFF43A047)
    Kind.Video -> Icons.Default.Videocam to Color(0xFF8E24AA)
    Kind.Audio -> Icons.Default.MusicNote to Color(0xFFE53935)
    Kind.Pdf -> Icons.Default.PictureAsPdf to Color(0xFFD32F2F)
    Kind.Archive -> Icons.Default.FolderZip to Color(0xFF6D4C41)
    Kind.Apk -> Icons.Default.Android to Color(0xFF3DDC84)
    Kind.Text -> Icons.Default.Description to Color(0xFF1E88E5)
    Kind.Other -> Icons.Default.InsertDriveFile to Color(0xFF757575)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: FileItem,
    selected: Boolean,
    showFolder: Boolean,
    loader: ImageLoader,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val kind = item.kind()
    val (icon, tint) = iconFor(kind)
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        Modifier.fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (kind == Kind.Image || kind == Kind.Video) {
                AsyncImage(
                    model = File(item.path),
                    imageLoader = loader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            } else {
                Icon(icon, null, tint = tint, modifier = Modifier.size(32.dp))
            }
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp).background(MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            val parts = ArrayList<String>()
            if (!item.isDir) parts.add(Fs.formatSize(item.size))
            parts.add(Fs.formatDate(item.modified))
            if (showFolder) File(item.path).parentFile?.name?.let { parts.add(it) }
            Text(
                parts.joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Folder, null, tint = Color(0xFFFFB300), modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("Zugriff auf alle Dateien", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Damit der Dateimanager deine Ordner, den internen Speicher und die SD-Karte anzeigen kann, " +
                "braucht er die Berechtigung „Zugriff auf alle Dateien“.",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant) { Text("Berechtigung erteilen") }
    }
}
