package de.dateimanager.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxSize

class MainActivity : ComponentActivity() {

    private var granted by mutableStateOf(false)
    private val vm by lazy { ViewModelProvider(this)[FmViewModel::class.java] }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = hasAccess()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        granted = hasAccess()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (granted) FileManagerApp() else PermissionScreen(onGrant = { requestAccess() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Ordner-Links anderer Apps ("Öffnen mit") und Download-Hinweise öffnen den passenden Ordner. */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val path: String? = when (i.action) {
            "android.intent.action.VIEW_DOWNLOADS" ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
            Intent.ACTION_VIEW -> i.data?.let { pathFromUri(it) }
            else -> null
        }
        if (path != null) vm.openExternalFolder(path)
    }

    private fun pathFromUri(u: Uri): String? {
        if (u.scheme == "file") return u.path
        if (u.scheme != "content") return null
        return try {
            val id = if (DocumentsContract.isTreeUri(u)) DocumentsContract.getTreeDocumentId(u)
            else DocumentsContract.getDocumentId(u)
            when (u.authority) {
                "com.android.externalstorage.documents" -> {
                    val c = id.indexOf(':')
                    if (c < 0) {
                        null
                    } else {
                        val vol = id.substring(0, c)
                        val rel = id.substring(c + 1)
                        val base = if (vol == "primary") Environment.getExternalStorageDirectory().path else "/storage/$vol"
                        if (rel.isEmpty()) base else "$base/$rel"
                    }
                }
                "com.android.providers.downloads.documents" ->
                    if (id.startsWith("raw:")) id.removePrefix("raw:")
                    else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onResume() {
        super.onResume()
        granted = hasAccess()
    }

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            permLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
