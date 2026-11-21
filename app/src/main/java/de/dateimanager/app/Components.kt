package de.dateimanager.app

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

// ---------- Überschriften ----------

@Composable
fun GroupHeader(
    label: String,
    selectable: Boolean,
    allSelected: Boolean,
    pad: PaddingValues,
    onClick: () -> Unit
) {
    val clickMod = if (selectable) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        Modifier.fillMaxWidth().then(clickMod).padding(pad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectable) {
            Icon(
                if (allSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                "Alle dieses Abschnitts markieren",
                tint = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.size(12.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun DupHeaderRow(label: String, pad: PaddingValues, onMarkRest: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(pad), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onMarkRest) { Text("Rest markieren") }
    }
}

// ---------- Schnell-Scrollleiste ----------

@Composable
fun FastScroller(
    modifier: Modifier,
    total: Int,
    firstVisible: Int,
    visible: Int,
    scrolling: Boolean,
    label: (Int) -> String,
    onScrollTo: (Int) -> Unit
) {
    if (visible <= 0 || total <= visible * 2) return
    BoxWithConstraints(modifier.fillMaxHeight().width(150.dp)) {
        val density = LocalDensity.current
        val trackPx = with(density) { maxHeight.toPx() }
        val thumbPx = with(density) { 56.dp.toPx() }
        var dragging by remember { mutableStateOf(false) }
        var dragFraction by remember { mutableStateOf(0f) }
        val range = (total - visible).coerceAtLeast(1)
        val rangeNow by rememberUpdatedState(range)
        val trackNow by rememberUpdatedState(trackPx)
        val scrollNow by rememberUpdatedState(onScrollTo)
        val lastIndex = remember { IntArray(1) { -1 } }
        val frac = if (dragging) dragFraction else (firstVisible.toFloat() / range).coerceIn(0f, 1f)
        val alpha by animateFloatAsState(if (dragging || scrolling) 1f else 0.35f, label = "thumbAlpha")

        Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(24.dp).pointerInput(Unit) {
                fun go(y: Float) {
                    dragFraction = ((y - thumbPx / 2) / (trackNow - thumbPx)).coerceIn(0f, 1f)
                    val idx = (dragFraction * rangeNow).roundToInt()
                    if (idx != lastIndex[0]) {
                        lastIndex[0] = idx
                        scrollNow(idx)
                    }
                }
                detectDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        lastIndex[0] = -1
                        go(o.y)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onDrag = { change, _ ->
                        change.consume()
                        go(change.position.y)
                    }
                )
            }
        )
        Box(
            Modifier.align(Alignment.TopEnd)
                .offset { IntOffset(0, (frac * (trackPx - thumbPx)).roundToInt()) }
                .padding(end = 4.dp)
                .width(if (dragging) 10.dp else 6.dp)
                .height(56.dp)
                .alpha(alpha)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
        )
        if (dragging) {
            val text = label((dragFraction * range).roundToInt())
            if (text.isNotEmpty()) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd)
                        .offset { IntOffset(0, (frac * (trackPx - thumbPx)).roundToInt()) }
                        .padding(end = 36.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text,
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

/** Beschriftung neben der Schnellscroll-Leiste (Monat, Tag, Anfangsbuchstabe ...). */
fun rowLabel(rows: List<Row>, index: Int, sortBy: SortBy): String {
    if (rows.isEmpty()) return ""
    val i = index.coerceIn(0, rows.size - 1)
    val r = rows[i]
    if (r is Row.Header) return r.label
    if (r is Row.Album) return r.info.name.take(1).uppercase()
    if (r is Row.Entry) {
        var j = i
        while (j >= 0) {
            val x = rows[j]
            if (x is Row.Header) return x.label
            j--
        }
        return when (sortBy) {
            SortBy.Name -> r.item.name.take(1).uppercase()
            SortBy.Date -> Fs.monthLabel(Fs.monthKey(r.item.modified))
            SortBy.Size -> Fs.formatSize(r.item.size)
            SortBy.Duration -> Fs.formatDuration(r.item.duration)
        }
    }
    return ""
}

// ---------- Dialoge ----------

@Composable
fun NameDialog(
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
fun DeleteDialog(
    items: List<FileItem>,
    inTrash: Boolean,
    onTrash: () -> Unit,
    onForever: () -> Unit,
    onDismiss: () -> Unit,
    lockedCount: Int = 0,
    trashDays: Int = 30
) {
    val what = if (items.size == 1) "„${items[0].name}“" else "${items.size} Elemente"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (inTrash) "Endgültig löschen" else "Löschen") },
        text = {
            Column {
                Text(
                    if (inTrash) "$what endgültig löschen? Das lässt sich nicht rückgängig machen."
                    else if (trashDays > 0) "$what in den Papierkorb verschieben? Dort bleibt es $trashDays Tage wiederherstellbar."
                    else "$what in den Papierkorb verschieben? Dort bleibt es, bis du den Papierkorb leerst."
                )
                if (lockedCount > 0) {
                    Text(
                        "$lockedCount gesperrte Dateien werden übersprungen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (!inTrash) {
                    TextButton(onClick = onForever) { Text("Stattdessen endgültig löschen") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = if (inTrash) onForever else onTrash) {
                Text(if (inTrash) "Löschen" else "In Papierkorb")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onOk: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onOk) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
fun OptionRow(icon: ImageVector, text: String, tint: Color? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.size(16.dp))
        Text(text, color = tint ?: MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun AlbumOptionsDialog(
    info: AlbumInfo,
    locked: Boolean,
    favorite: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleFav: () -> Unit,
    onToggleInbox: () -> Unit,
    onShowInFiles: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(info.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OptionRow(Icons.Default.Folder, "Öffnen", null, onOpen)
                OptionRow(Icons.Default.Edit, "Umbenennen", null, onRename)
                OptionRow(Icons.AutoMirrored.Filled.ArrowForward, "Verschieben …", null, onMove)
                OptionRow(
                    Icons.Default.Lock,
                    if (locked) "Entsperren" else "Sperren – alles darin vor dem Löschen schützen",
                    null,
                    onToggleLock
                )
                OptionRow(Icons.Default.Star, if (favorite) "Aus Favoriten entfernen" else "Zu Favoriten", null, onToggleFav)
                OptionRow(Icons.Default.FolderOpen, "Im Reiter „Dateien“ ansehen", null, onShowInFiles)
                OptionRow(
                    Icons.Default.Bookmark,
                    if (info.inbox) "Als Album behandeln" else "Zu „Ohne Album“ zählen",
                    null,
                    onToggleInbox
                )
                OptionRow(Icons.Default.Delete, "Löschen (in den Papierkorb)", Color(0xFFE53935), onDelete)
                Text(
                    if (info.inbox) {
                        "Dieser Ordner zählt als „Ohne Album“ (typisch: Kamera, Screenshots, Downloads, WhatsApp). " +
                            "Als Album behandelt erscheint er unter „Deine Alben“."
                    } else {
                        "Dieser Ordner ist ein Album. Mit „Zu Ohne Album zählen“ landet er stattdessen bei den " +
                            "Dateien, die du noch einsortieren willst."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } }
    )
}

@Composable
fun AlbumPickerDialog(
    albums: List<AlbumInfo>,
    onPick: (AlbumInfo) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "In Album verschieben",
    newLabel: String = "Neues Album"
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(newLabel) },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { if (name.isNotBlank()) onCreate(name) }) { Text("Erstellen & verschieben") }
                if (albums.isNotEmpty()) {
                    Text("Vorhanden", style = MaterialTheme.typography.labelLarge)
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        for (a in albums) {
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(a.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${a.count}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

// ---------- PDF-Schnellansicht ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfSheet(path: String, onClose: () -> Unit, onOpen: () -> Unit) {
    val file = remember(path) { File(path) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var doc by remember(path) { mutableStateOf<PdfDoc?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
        .coerceAtMost(1200)

    LaunchedEffect(path) {
        val d = withContext(Dispatchers.IO) {
            try { PdfDoc(file) } catch (e: Throwable) { null }
        }
        if (d == null) failed = true else doc = d
    }
    DisposableEffect(path) { onDispose { doc?.close() } }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
            val d = doc
            when {
                failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Text(
                        "Vorschau nicht möglich (Datei beschädigt oder passwortgeschützt). " +
                            "Nutze „Vollständig öffnen“.",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
                d == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    CircularProgressIndicator(Modifier.padding(24.dp))
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(d.pageCount) { i -> PdfPage(d, i, widthPx) }
                }
            }
        }
    }
}

@Composable
fun PdfPage(doc: PdfDoc, index: Int, widthPx: Int) {
    val bmp by produceState<Bitmap?>(null, doc, index) {
        value = withContext(Dispatchers.IO) { runCatching { doc.render(index, widthPx) }.getOrNull() }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = "Seite ${index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        Box(Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

// ---------- Pfadleiste ----------

@Composable
fun PathBar(vm: FmViewModel) {
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
        val favDirs = vm.marks.favorites.filter { File(it).isDirectory }.sorted()
        if (favDirs.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                favDirs.forEach { p ->
                    FilterChip(selected = false, onClick = { vm.openDir(File(p)) }, label = { Text("★ " + File(p).name) })
                }
            }
        }
        if (vm.showHidden) {
            Row(Modifier.padding(horizontal = 12.dp)) {
                FilterChip(
                    selected = true,
                    onClick = { vm.toggleHidden() },
                    label = { Text("Versteckte Dateien sichtbar  ✕") }
                )
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

// ---------- Untermodi & Duplikat-Leiste ----------

@Composable
fun SubModeChips(vm: FmViewModel) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SubMode.values().filter {
            it != SubMode.Timeline || vm.section == Section.Images || vm.section == Section.Videos
        }.forEach { m ->
            FilterChip(selected = vm.subMode == m, onClick = { vm.chooseSubMode(m) }, label = { Text(m.label) })
        }
    }
}

@Composable
fun DupBar(vm: FmViewModel) {
    val images = vm.section == Section.Images
    val simMode = images && vm.showSimilar
    val groups = (if (simMode) vm.simResults else vm.dupResults)[vm.section]
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (images) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !vm.showSimilar, onClick = { vm.showSimilar = false }, label = { Text("Identisch") })
                FilterChip(selected = vm.showSimilar, onClick = { vm.showSimilar = true }, label = { Text("Ähnlich") })
            }
            Spacer(Modifier.height(8.dp))
        }
        if (vm.dupStatus.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                Text(vm.dupStatus, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { vm.cancelDupScan() }) { Text("Abbrechen") }
            }
        } else {
            Text(
                if (simMode && groups == null) "Findet Bilder, die gleich aussehen, auch bei anderer Größe oder Qualität."
                else vm.dupSummary(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (simMode) vm.startSimilarScan() else vm.startDupScan() }) {
                    Text(
                        if (groups != null) "Neu suchen"
                        else if (simMode) "Ähnliche Bilder suchen"
                        else "Duplikate suchen"
                    )
                }
                if (!groups.isNullOrEmpty()) {
                    OutlinedButton(onClick = { vm.selectAllDupRest() }) { Text("Überflüssige markieren") }
                }
            }
        }
    }
}

// ---------- Speicher-Übersicht ----------

@Composable
fun StorageScreen(vm: FmViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (vm.storage.isEmpty() && !vm.loading) {
            Text("Keine Speicherdaten verfügbar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        vm.storage.forEach { v ->
            VolumeCard(v) { sec, f ->
                if (f != null) vm.openOther(f, Section.Storage) else vm.selectSection(sec)
            }
        }
        TextButton(onClick = { vm.selectSection(Section.Trash) }) { Text("Papierkorb öffnen") }
    }
}

private fun pct(part: Long, total: Long): String {
    if (total <= 0L || part <= 0L) return "0 %"
    val p = part * 100.0 / total
    return if (p < 1.0) "<1 %" else String.format(java.util.Locale.GERMAN, "%.0f %%", p)
}

@Composable
fun VolumeCard(v: VolumeStats, onOpen: (Section, OtherFilter?) -> Unit) {
    val segs = listOf(
        Triple("Bilder", Color(0xFF43A047), v.images),
        Triple("Videos", Color(0xFF8E24AA), v.videos),
        Triple("Musik", Color(0xFFE53935), v.music),
        Triple("Aufnahmen", Color(0xFF00897B), v.recordings),
        Triple("Dokumente", Color(0xFF1E88E5), v.docs),
        Triple("Apps & Archive", Color(0xFF6D4C41), v.apps),
        Triple("Backups & Datenbanken", Color(0xFFFB8C00), v.backups),
        Triple("Sonstiges", Color(0xFF757575), v.other)
    )
    val targets = listOf<Pair<Section, OtherFilter?>>(
        Pair(Section.Images, null), Pair(Section.Videos, null), Pair(Section.Music, null),
        Pair(Section.Recordings, null), Pair(Section.Docs, null), Pair(Section.Archives, null),
        Pair(Section.Other, OtherFilter.Backups), Pair(Section.Other, OtherFilter.All)
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(v.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "${Fs.formatSize(v.used)} von ${Fs.formatSize(v.total)} belegt · ${Fs.formatSize(v.free)} frei",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                for ((_, color, bytes) in segs) {
                    if (bytes > 0L) Box(Modifier.weight(bytes.toFloat()).fillMaxHeight().background(color))
                }
                if (v.free > 0L) Spacer(Modifier.weight(v.free.toFloat()))
            }
            Spacer(Modifier.height(8.dp))
            segs.forEachIndexed { i, (label, color, bytes) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(targets[i].first, targets[i].second) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(12.dp).background(color, CircleShape))
                    Spacer(Modifier.size(12.dp))
                    Text(label, Modifier.weight(1f))
                    Text(Fs.formatSize(bytes))
                    Text(
                        pct(bytes, v.total),
                        Modifier.width(56.dp),
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "„Sonstiges“ enthält Systemdaten und App-Daten, die Android nicht einzeln anzeigt, sowie Bilder und Videos ohne Galerie-Eintrag. Tippe darauf, um die sichtbaren Dateien zu sortieren.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------- Listen-, Kachel- und Album-Ansicht ----------

fun iconFor(k: Kind): Pair<ImageVector, Color> = when (k) {
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

private val DUP_COLOR = Color(0xFFFB8C00)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    item: FileItem,
    selected: Boolean,
    showFolder: Boolean,
    dup: Boolean,
    lock: Boolean,
    fav: Boolean,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Fs.middleEllipsis(item.name, 40),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = false)
                )
                BadgeIcons(dup, lock, fav, false, Modifier.padding(start = 6.dp))
            }
            val parts = ArrayList<String>()
            if (item.duration > 0) parts.add(Fs.formatDuration(item.duration))
            if (!item.isDir) parts.add(Fs.formatSize(item.size))
            parts.add(Fs.formatDate(item.modified))
            val note = item.note
            if (note != null) {
                parts.add("aus " + note.removePrefix("/storage/emulated/0/"))
            } else if (showFolder) {
                File(item.path).parentFile?.name?.let { parts.add(it) }
            }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileTile(
    item: FileItem,
    selected: Boolean,
    dup: Boolean,
    lock: Boolean,
    fav: Boolean,
    loader: ImageLoader,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val kind = item.kind()
    val (icon, tint) = iconFor(kind)
    Column(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            when (kind) {
                Kind.Image, Kind.Video -> AsyncImage(
                    model = File(item.path),
                    imageLoader = loader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Kind.Pdf -> PdfThumb(item.path, icon, tint)
                else -> Icon(icon, null, tint = tint, modifier = Modifier.size(48.dp))
            }
            if (item.duration > 0 && (kind == Kind.Video || kind == Kind.Audio)) {
                Text(
                    Fs.formatDuration(item.duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .background(Color(0x99000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp)
                )
            }
            BadgeIcons(dup, lock, fav, true, Modifier.align(Alignment.TopEnd).padding(4.dp))
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(24.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }
        Text(
            Fs.middleEllipsis(item.name, 30),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp)
        )
    }
}

@Composable
fun PdfThumb(path: String, icon: ImageVector, tint: Color) {
    val bmp by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { PdfThumbs.get(path) }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Icon(icon, null, tint = tint, modifier = Modifier.size(48.dp))
    }
}

@Composable
fun CoverImage(path: String, loader: ImageLoader, modifier: Modifier) {
    val kind = remember(path) { FileItem(path, File(path).name, false, 0L, 0L).kind() }
    val (icon, tint) = iconFor(kind)
    when (kind) {
        Kind.Image, Kind.Video -> AsyncImage(
            model = File(path),
            imageLoader = loader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
        Kind.Pdf -> Box(modifier, contentAlignment = Alignment.Center) { PdfThumb(path, icon, tint) }
        else -> Box(modifier, contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(32.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumRow(
    info: AlbumInfo,
    loader: ImageLoader,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    lock: Boolean = false,
    fav: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CoverImage(info.cover, loader, Modifier.fillMaxSize())
        }
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Fs.middleEllipsis(info.name, 40),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = false)
                )
                BadgeIcons(false, lock, fav, false, Modifier.padding(start = 6.dp))
            }
            Text(
                "${info.count} Dateien · ${Fs.formatSize(info.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumTile(
    info: AlbumInfo,
    loader: ImageLoader,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    lock: Boolean = false,
    fav: Boolean = false
) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            CoverImage(info.cover, loader, Modifier.fillMaxSize())
            BadgeIcons(false, lock, fav, true, Modifier.align(Alignment.TopEnd).padding(4.dp))
            Text(
                "${info.count}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp)
            )
        }
        Text(
            Fs.middleEllipsis(info.name, 30),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp)
        )
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

// ---------- Markierungs-Symbole ----------

@Composable
fun BadgeIcons(dup: Boolean, lock: Boolean, fav: Boolean, onImage: Boolean, modifier: Modifier = Modifier) {
    if (!dup && !lock && !fav) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (fav) BadgeIcon(Icons.Default.Star, "Favorit", Color(0xFFFFB300), onImage)
        if (lock) BadgeIcon(Icons.Default.Lock, "Gesperrt", Color(0xFF42A5F5), onImage)
        if (dup) BadgeIcon(Icons.Default.ContentCopy, "Duplikat", DUP_COLOR, onImage)
    }
}

@Composable
fun BadgeIcon(icon: ImageVector, desc: String, tint: Color, onImage: Boolean) {
    val m = if (onImage) Modifier.size(20.dp).background(Color(0xAA000000), CircleShape).padding(3.dp)
    else Modifier.size(14.dp)
    Icon(icon, desc, tint = tint, modifier = m)
}

// ---------- Dokument-Filter ----------

@Composable
fun DocFilterChips(vm: FmViewModel) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DocFilter.values().forEach { f ->
            FilterChip(selected = vm.docFilter == f, onClick = { vm.docFilter = f }, label = { Text(f.label) })
        }
    }
}

// ---------- Mehr: weitere Kategorien ----------

private data class MoreEntry(
    val icon: ImageVector,
    val title: String,
    val desc: String,
    val section: Section,
    val filter: OtherFilter? = null
)

@Composable
fun MoreScreen(onOpen: (Section, OtherFilter?) -> Unit) {
    val list = listOf(
        MoreEntry(Icons.Default.Download, "Downloads", "Alles aus dem Download-Ordner", Section.Downloads),
        MoreEntry(Icons.Default.Smartphone, "Screenshots & Bildschirmaufnahmen", "Getrennt von deinen Fotos", Section.Screenshots),
        MoreEntry(Icons.Default.FolderZip, "Archive & Apps", "zip, rar, 7z, apk …", Section.Archives),
        MoreEntry(Icons.Default.ChatBubble, "Messenger-Medien", "WhatsApp, Telegram & Co. nach Ordnern", Section.Messenger),
        MoreEntry(Icons.Default.Mic, "Aufnahmen & Sprachnachrichten", "Getrennt von der Musik", Section.Recordings),
        MoreEntry(Icons.Default.Storage, "Backups & Datenbanken", "z. B. WhatsApp-Backups (crypt), db-Dateien", Section.Other, OtherFilter.Backups),
        MoreEntry(Icons.Default.DateRange, "Kalender & Kontakte", "ics- und vcf-Dateien", Section.Other, OtherFilter.Calendar),
        MoreEntry(Icons.Default.Storage, "Größte Dateien", "Die größten Platzfresser zuerst", Section.Largest),
        MoreEntry(Icons.Default.Bookmark, "Sammlungen", "Eigene Gruppen, ohne Dateien zu verschieben", Section.Collections),
        MoreEntry(Icons.Default.Lock, "Gesperrte Dateien", "Vor dem Löschen geschützt", Section.Locked),
        MoreEntry(Icons.Default.Security, "Tresor", "Versteckter Bereich mit PIN oder Fingerabdruck", Section.Vault),
        MoreEntry(Icons.Default.Cloud, "Cloud", "OneDrive, Google Drive, Dropbox", Section.Cloud),
        MoreEntry(Icons.Default.Settings, "Aufräumen", "Leere Ordner, Temp-Reste, alte Screenshots", Section.Cleanup),
        MoreEntry(Icons.Default.Search, "Erweiterte Suche", "Name, Typ, Größe und Alter kombinieren", Section.Search),
        MoreEntry(Icons.Default.Delete, "Papierkorb", "Gelöschtes bleibt wiederherstellbar", Section.Trash)
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        list.forEach { e ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(e.section, e.filter) }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(e.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.size(16.dp))
                Column {
                    Text(e.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        e.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------- Aufräumen & erweiterte Suche ----------

@Composable
fun CleanupChips(vm: FmViewModel) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CleanupFilter.values().forEach { f ->
            FilterChip(
                selected = vm.cleanupFilter == f,
                onClick = { vm.cleanupFilter = f; vm.reload() },
                label = { Text(f.label) }
            )
        }
    }
}

@Composable
fun AdvDropdown(current: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = false, onClick = { open = true }, label = { Text(current) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { onPick(i); open = false })
            }
        }
    }
}

@Composable
fun AdvancedSearchBar(vm: FmViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = vm.advQuery,
            onValueChange = { vm.advQuery = it; vm.reload() },
            singleLine = true,
            label = { Text("Dateiname enthält …") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdvDropdown(vm.advType.label, AdvType.values().map { it.label }) { i ->
                vm.advType = AdvType.values()[i]; vm.reload()
            }
            AdvDropdown(vm.advSize.label, AdvSize.values().map { it.label }) { i ->
                vm.advSize = AdvSize.values()[i]; vm.reload()
            }
            AdvDropdown(vm.advAge.label, AdvAge.values().map { it.label }) { i ->
                vm.advAge = AdvAge.values()[i]; vm.reload()
            }
        }
    }
}

// ---------- Tresor ----------

@Composable
fun VaultGate(vm: FmViewModel) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var resetOk by remember { mutableStateOf(false) }
    var forgot by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            resetOk = true
            error = ""
        } else {
            error = "Bestätigung abgebrochen."
        }
    }
    val setupMode = !vm.vaultHasPin || resetOk

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Text(
            if (resetOk) "Neue PIN setzen" else if (setupMode) "Tresor einrichten" else "Tresor entsperren",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            "Der Tresor versteckt Dateien in einem unsichtbaren Ordner. Er ist nicht verschlüsselt.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        OutlinedTextField(
            value = pin,
            onValueChange = { v -> pin = v.filter { c -> c.isDigit() }.take(12) },
            label = { Text("PIN (mindestens 4 Ziffern)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        )
        if (setupMode) {
            OutlinedTextField(
                value = pin2,
                onValueChange = { v -> pin2 = v.filter { c -> c.isDigit() }.take(12) },
                label = { Text("PIN wiederholen") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            if (setupMode) {
                if (pin.length < 4) {
                    error = "Mindestens 4 Ziffern."
                } else if (pin != pin2) {
                    error = "Die PINs sind nicht gleich."
                } else {
                    vm.setVaultPin(pin)
                    resetOk = false
                    pin = ""
                    pin2 = ""
                    error = ""
                }
            } else if (vm.unlockVault(pin)) {
                pin = ""
                error = ""
            } else {
                error = "Falsche PIN."
            }
        }) { Text(if (setupMode) "PIN speichern" else "Entsperren") }
        if (!setupMode) {
            val act = Auth.activityOf(ctx)
            if (act != null && Auth.biometricAvailable(ctx) && Build.VERSION.SDK_INT >= 28) {
                OutlinedButton(onClick = {
                    Auth.biometric(act, "Tresor entsperren", { vm.unlockVaultBiometric() }, { })
                }) { Text("Mit Fingerabdruck entsperren") }
            }
            TextButton(onClick = { forgot = true }) { Text("PIN vergessen?") }
        }
    }

    if (forgot) {
        AlertDialog(
            onDismissRequest = { forgot = false },
            title = { Text("PIN vergessen") },
            text = {
                Text(
                    "Du kannst eine neue PIN setzen, wenn du dich mit der Sperre deines Handys bestätigst " +
                        "(PIN, Muster, Passwort oder Fingerabdruck). Deine Dateien bleiben erhalten. " +
                        "Sie liegen im versteckten Ordner „.DM_Tresor“ deines Speichers und sind auch über " +
                        "„Versteckte Dateien anzeigen“ im Reiter „Dateien“ erreichbar."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    forgot = false
                    val i = Auth.deviceCredentialIntent(ctx)
                    if (i != null) {
                        launcher.launch(i)
                    } else {
                        error = "Dein Handy hat keine Bildschirmsperre. Nutze den Ordner „.DM_Tresor“ im Reiter „Dateien“."
                    }
                }) { Text("Mit Handy-Sperre bestätigen") }
            },
            dismissButton = { TextButton(onClick = { forgot = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
fun OtherChips(vm: FmViewModel) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OtherFilter.values().forEach { f ->
            FilterChip(selected = vm.otherFilter == f, onClick = { vm.otherFilter = f }, label = { Text(f.label) })
        }
    }
}
