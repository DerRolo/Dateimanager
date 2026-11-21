package de.dateimanager.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

data class CloudItem(
    val docId: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val mime: String
)

data class CloudNode(val tree: Uri, val docId: String, val name: String)

/** Cloud über die Dokumenten-Schnittstelle von Android (OneDrive, Google Drive, Dropbox ... als installierte Apps). */
object CloudFs {

    fun docUri(tree: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    fun rootName(ctx: Context, tree: Uri): String {
        return try {
            val uri = docUri(tree, DocumentsContract.getTreeDocumentId(tree))
            ctx.contentResolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "Cloud"
        } catch (e: Exception) {
            "Cloud"
        }
    }

    fun list(ctx: Context, tree: Uri, docId: String): List<CloudItem> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val proj = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
        )
        val out = ArrayList<CloudItem>()
        ctx.contentResolver.query(children, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(2) ?: ""
                out.add(
                    CloudItem(
                        c.getString(0), c.getString(1) ?: "?", mime == Document.MIME_TYPE_DIR,
                        if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4), mime
                    )
                )
            }
        }
        return out.sortedWith(Comparator { a, b ->
            if (a.isDir != b.isDir) b.isDir.compareTo(a.isDir) else a.name.compareTo(b.name, ignoreCase = true)
        })
    }

    fun download(ctx: Context, tree: Uri, item: CloudItem): File? {
        return try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Cloud")
            dir.mkdirs()
            val dst = Fs.uniqueTarget(dir, item.name, false)
            val src = docUri(tree, item.docId)
            ctx.contentResolver.openInputStream(src)?.use { input ->
                dst.outputStream().use { out -> input.copyTo(out) }
            }
            Fs.scan(ctx, listOf(dst.path))
            dst
        } catch (e: Exception) {
            null
        }
    }

    fun upload(ctx: Context, tree: Uri, parentDocId: String, source: Uri): Boolean {
        return try {
            val r = ctx.contentResolver
            var name = "Datei"
            r.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
            val mime = r.getType(source) ?: "application/octet-stream"
            val dst = DocumentsContract.createDocument(r, docUri(tree, parentDocId), mime, name) ?: return false
            r.openInputStream(source)?.use { input ->
                r.openOutputStream(dst)?.use { out -> input.copyTo(out) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun delete(ctx: Context, tree: Uri, item: CloudItem): Boolean {
        return try {
            DocumentsContract.deleteDocument(ctx.contentResolver, docUri(tree, item.docId))
        } catch (e: Exception) {
            false
        }
    }

    fun view(ctx: Context, tree: Uri, item: CloudItem) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(docUri(tree, item.docId), if (item.mime.isEmpty()) "*/*" else item.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Keine App zum Öffnen gefunden", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CloudScreen(vm: FmViewModel, onAddRoot: () -> Unit, onUpload: () -> Unit) {
    val ctx = LocalContext.current
    var picked by remember { mutableStateOf<CloudItem?>(null) }
    var dropRoot by remember { mutableStateOf<Pair<String, String>?>(null) }

    if (vm.cloudPath.isEmpty()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Cloud-Speicher", style = MaterialTheme.typography.titleMedium)
            Text(
                "So verbindest du Google Drive:\n" +
                    "1. Tippe unten auf „Cloud-Speicher hinzufügen“.\n" +
                    "2. Öffne im Systemfenster links oben das Menü (☰) und wähle dein Drive-Konto.\n" +
                    "3. Gehe in den Ordner „Meine Ablage“. Die oberste Ebene lässt Android nicht zu.\n" +
                    "4. Tippe unten auf „Diesen Ordner verwenden“ und gib der Verbindung einen Namen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Mehrere Konten: jedes einzeln hinzufügen und eigenen Namen vergeben. " +
                    "Dropbox erscheint im Systemfenster, wenn die Dropbox-App installiert ist. " +
                    "OneDrive erscheint nur, wenn die OneDrive-App diese Schnittstelle anbietet. " +
                    "Auf deinem Handy ist das nicht der Fall, darum kann ich OneDrive so nicht anbinden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            vm.cloudRoots.forEach { r ->
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { vm.openCloudRoot(r) }, onLongClick = { dropRoot = r })
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.size(16.dp))
                    Text(r.second, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (vm.cloudRoots.isNotEmpty()) {
                Text(
                    "Langer Druck auf einen Eintrag trennt die Verbindung.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onAddRoot) { Text("Cloud-Speicher hinzufügen") }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    vm.cloudPath.joinToString(" › ") { it.name },
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedButton(onClick = onUpload) { Text("Hochladen") }
            }
            if (vm.cloudBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.cloudItems.isEmpty() && !vm.cloudBusy) {
                Text(
                    "Dieser Ordner ist leer.",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.cloudItems, key = { c -> c.docId }) { ci ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                if (ci.isDir) {
                                    vm.openCloudDir(ci)
                                } else {
                                    picked = ci
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (ci.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                            null,
                            tint = if (ci.isDir) Color(0xFFFFB300) else Color(0xFF757575),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.size(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                Fs.middleEllipsis(ci.name, 40),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            val parts = ArrayList<String>()
                            if (!ci.isDir && ci.size > 0) parts.add(Fs.formatSize(ci.size))
                            if (ci.modified > 0) parts.add(Fs.formatDate(ci.modified))
                            Text(
                                parts.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    val pend = vm.cloudPending
    if (pend != null) {
        NameDialog("Name der Verbindung", pend.second, "Speichern", false, { vm.cloudPending = null }) {
            vm.confirmCloudRoot(it)
        }
    }

    val p = picked
    if (p != null) {
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = { CloudFs.view(ctx, vm.cloudPath.last().tree, p); picked = null }) { Text("Öffnen") }
                    TextButton(onClick = { vm.cloudDownload(p); picked = null }) { Text("Auf das Handy laden (Download/Cloud)") }
                    TextButton(onClick = { vm.cloudDelete(p); picked = null }) { Text("In der Cloud löschen") }
                }
            },
            confirmButton = { TextButton(onClick = { picked = null }) { Text("Schließen") } }
        )
    }
    val dr = dropRoot
    if (dr != null) {
        ConfirmDialog(
            title = "Verbindung trennen",
            text = "„${dr.second}“ aus der Liste entfernen? Die Dateien in der Cloud bleiben unverändert.",
            confirm = "Trennen",
            onOk = { vm.removeCloudRoot(dr); dropRoot = null },
            onDismiss = { dropRoot = null }
        )
    }
}
