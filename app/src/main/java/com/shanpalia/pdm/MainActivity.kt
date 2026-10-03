package com.shanpalia.pdm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private val Green = Color(0xFF16B978)
private val Blue = Color(0xFF1769D5)
private val Dark = Color(0xFF12231E)
private val Muted = Color(0xFF6F7D77)
private val Mint = Color(0xFFE9FFF6)

private data class HistoryItem(val url: String, val time: Long)

private class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("pdm_history", Context.MODE_PRIVATE)
    fun all(): List<HistoryItem> = (prefs.getString("items", "") ?: "").split("\n").mapNotNull { line ->
        val p = line.split("\t", limit = 2)
        if (p.size == 2 && p[0].isNotBlank()) HistoryItem(p[0], p[1].toLongOrNull() ?: 0L) else null
    }
    fun add(url: String) {
        val value = url.trim()
        if (value.isBlank()) return
        val list = (listOf(HistoryItem(value, System.currentTimeMillis())) + all().filterNot { it.url == value }).take(50)
        prefs.edit().putString("items", list.joinToString("\n") { "${it.url}\t${it.time}" }).apply()
    }
    fun clear() = prefs.edit().remove("items").apply()
}

class MainActivity : ComponentActivity() {
    private var pendingDownload: String? = null
    private var waitingForStorage = false

    private val torrentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val target = File(cacheDir, "selected-${System.currentTimeMillis()}.torrent")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            startDownloadService(DownloadService.ACTION_TORRENT_FILE, null, target.absolutePath)
        } catch (_: Throwable) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PdmApp(
                this,
                ::startUrlDownload,
                ::openStorageSettings,
                ::requestStorageAndDownload,
                { torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForStorage && PdmStorage.hasPublicAccess()) {
            waitingForStorage = false
            pendingDownload?.let {
                pendingDownload = null
                startUrlDownload(it)
            }
        }
    }

    private fun requestStorageAndDownload(url: String) {
        pendingDownload = url
        waitingForStorage = true
        openStorageSettings()
    }

    private fun startUrlDownload(value: String) {
        val url = value.trim()
        if (url.isBlank()) return
        when {
            url.startsWith("magnet:", true) -> startDownloadService(DownloadService.ACTION_TORRENT_MAGNET, url, null)
            url.startsWith("http://", true) || url.startsWith("https://", true) -> startDownloadService(DownloadService.ACTION_HTTP, url, null)
        }
    }

    private fun startDownloadService(action: String, url: String?, path: String?) {
        val intent = Intent(this, DownloadService::class.java).apply {
            this.action = action
            if (url != null) putExtra(DownloadService.EXTRA_URL, url)
            if (path != null) putExtra(DownloadService.EXTRA_PATH, path)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun openStorageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Throwable) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }
}

@Composable
private fun PdmApp(
    activity: ComponentActivity,
    onDownload: (String) -> Unit,
    onStorageSettings: () -> Unit,
    requestStorage: (String) -> Unit,
    onPickTorrent: () -> Unit
) {
    val context = activity
    val history = remember(context) { HistoryStore(context) }
    var historyItems by remember { mutableStateOf(history.all()) }
    var screen by rememberSaveable { mutableStateOf("Home") }
    var address by rememberSaveable { mutableStateOf("") }
    var browserUrl by rememberSaveable { mutableStateOf("https://www.google.com") }
    var splash by remember { mutableStateOf(true) }
    var permissionStep by rememberSaveable { mutableIntStateOf(0) }
    var permissionsReady by rememberSaveable { mutableStateOf(false) }
    var exitDialog by remember { mutableStateOf(false) }
    var dialogUrl by remember { mutableStateOf<String?>(null) }
    var dialogName by remember { mutableStateOf("") }
    var dialogSize by remember { mutableStateOf<Long?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun finishPermissions() {
        permissionStep = 0
        permissionsReady = true
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !PdmStorage.hasPublicAccess()) permissionStep = 2 else finishPermissions()
    }

    LaunchedEffect(Unit) {
        delay(900)
        splash = false
        when {
            Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> permissionStep = 1
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !PdmStorage.hasPublicAccess() -> permissionStep = 2
            else -> finishPermissions()
        }
    }

    LaunchedEffect(permissionStep) {
        if (permissionStep == 2) {
            while (!PdmStorage.hasPublicAccess()) delay(400)
            finishPermissions()
        }
    }

    BackHandler(enabled = !splash) {
        if (permissionStep != 0) return@BackHandler
        if (dialogUrl != null) { dialogUrl = null; return@BackHandler }
        if (drawerState.isOpen) { scope.launch { drawerState.close() }; return@BackHandler }
        if (screen == "Browser") screen = "Home" else if (screen != "Home") screen = "Home" else exitDialog = true
    }

    fun saveHistory(url: String) {
        history.add(url)
        historyItems = history.all()
    }

    fun showDownload(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        dialogUrl = clean
        dialogName = clean.substringBefore('?').substringBefore('#').substringAfterLast('/').ifBlank { "download.bin" }
        dialogSize = null
        saveHistory(clean)
    }

    fun goFromAddress(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        address = clean
        when {
            clean.startsWith("magnet:", true) -> showDownload(clean)
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> {
                browserUrl = clean
                screen = "Browser"
            }
            else -> {
                browserUrl = "https://www.google.com/search?q=${Uri.encode(clean)}"
                screen = "Browser"
            }
        }
    }

    fun startConfirmed() {
        val url = dialogUrl ?: return
        dialogUrl = null
        screen = "Downloads"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !PdmStorage.hasPublicAccess()) requestStorage(url) else onDownload(url)
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White, surface = Color.White)) {
        if (splash) {
            SplashScreen()
            return@MaterialTheme
        }
        if (!permissionsReady || permissionStep != 0) {
            PermissionScreen(
                permissionStep,
                { if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else permissionStep = 2 },
                onStorageSettings,
                { if (permissionStep == 1) permissionStep = 2 else finishPermissions() }
            )
            return@MaterialTheme
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = Color.White) {
                    Column(Modifier.fillMaxHeight().padding(vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { scope.launch { drawerState.close() } }) { Icon(Icons.Default.Close, "Close") }
                            Icon(Icons.Default.Download, null, Modifier.size(34.dp), tint = Green)
                            Spacer(Modifier.width(10.dp))
                            Text("Palia Download Manager", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                        }
                        HorizontalDivider()
                        DrawerItem("Home", Icons.Default.Home, screen == "Home") { screen = "Home"; scope.launch { drawerState.close() } }
                        DrawerItem("Downloads", Icons.Default.Download, screen == "Downloads") { screen = "Downloads"; scope.launch { drawerState.close() } }
                        DrawerItem("Browser", Icons.Default.Web, screen == "Browser") { screen = "Browser"; scope.launch { drawerState.close() } }
                        DrawerItem("Settings", Icons.Default.Settings, screen == "Settings") { screen = "Settings"; scope.launch { drawerState.close() } }
                        Spacer(Modifier.height(12.dp))
                        Text("RECENT TABS", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                        LazyColumn(Modifier.weight(1f)) {
                            items(historyItems, key = { it.url }) { item ->
                                NavigationDrawerItem(
                                    icon = { Icon(Icons.Default.Language, null) },
                                    label = { Text(item.url, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    selected = false,
                                    onClick = { address = item.url; browserUrl = item.url; screen = "Browser"; scope.launch { drawerState.close() } },
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                                )
                            }
                        }
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.DeleteSweep, null) },
                            label = { Text("Clear recent") },
                            selected = false,
                            onClick = { history.clear(); historyItems = emptyList() },
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        ) {
            Scaffold(
                containerColor = Color.White,
                bottomBar = {
                    NavigationBar(containerColor = Color(0xFFF4FFFB), tonalElevation = 0.dp) {
                        PdmNavigationBarItem("Home", Icons.Default.Home, screen == "Home") { screen = "Home" }
                        PdmNavigationBarItem("Downloads", Icons.Default.Download, screen == "Downloads") { screen = "Downloads" }
                        PdmNavigationBarItem("Browser", Icons.Default.Web, screen == "Browser") { screen = "Browser" }
                        PdmNavigationBarItem("Settings", Icons.Default.Settings, screen == "Settings") { screen = "Settings" }
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (screen) {
                        "Home" -> HomeScreen(address, { address = it }, { goFromAddress(address) }, { showDownload(address) }, { screen = "Browser" }, { screen = "Downloads" }, onPickTorrent) { scope.launch { drawerState.open() } }
                        "Downloads" -> DownloadsScreen(context) { scope.launch { drawerState.open() } }
                        "Browser" -> BrowserScreen(browserUrl, { address = it; browserUrl = it; saveHistory(it) }, { showDownload(it) }, { browserUrl = it; address = it }, { scope.launch { drawerState.open() } })
                        else -> SettingsScreen(historyItems, { history.clear(); historyItems = emptyList() }, onStorageSettings) { scope.launch { drawerState.open() } }
                    }
                }
            }
        }

        if (dialogUrl != null) {
            LaunchedEffect(dialogUrl) {
                val u = dialogUrl ?: return@LaunchedEffect
                dialogSize = withContext(Dispatchers.IO) {
                    try {
                        val c = (URL(u).openConnection() as HttpURLConnection).apply {
                            requestMethod = "HEAD"
                            instanceFollowRedirects = true
                            connectTimeout = 7000
                            readTimeout = 7000
                        }
                        c.connect()
                        val size = c.contentLengthLong
                        c.disconnect()
                        if (size > 0) size else null
                    } catch (_: Throwable) { null }
                }
            }
            AlertDialog(
                onDismissRequest = { dialogUrl = null },
                title = { Text("Download file", fontWeight = FontWeight.ExtraBold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(dialogName, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(dialogSize?.let(::formatSize) ?: "Size: checking…", color = Muted)
                        Text(dialogUrl ?: "", color = Muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text("Save location: Download/PDM", color = Muted, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    Button(onClick = { startConfirmed() }) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("START")
                    }
                },
                dismissButton = { TextButton(onClick = { dialogUrl = null }) { Text("CANCEL") } }
            )
        }

        if (exitDialog) {
            AlertDialog(
                onDismissRequest = { exitDialog = false },
                title = { Text("Exit Palia Download Manager?") },
                text = { Text("Are you sure you want to exit?") },
                confirmButton = { TextButton(onClick = { activity.finish() }) { Text("YES") } },
                dismissButton = { TextButton(onClick = { exitDialog = false }) { Text("NO") } }
            )
        }
    }
}

@Composable
private fun DrawerItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, null) },
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
    )
}

@Composable
private fun HomeScreen(
    url: String,
    onUrl: (String) -> Unit,
    onGo: () -> Unit,
    onStart: () -> Unit,
    onBrowser: () -> Unit,
    onDownloads: () -> Unit,
    onTorrent: () -> Unit,
    onMenu: () -> Unit
) {
    var field by remember(url) { mutableStateOf(TextFieldValue(url, TextRange(url.length))) }