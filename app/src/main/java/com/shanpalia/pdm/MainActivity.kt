package com.shanpalia.pdm

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.DownloadListener
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardActions
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val Green = Color(0xFF16B978)
private val Blue = Color(0xFF0B62D6)
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
        val v = url.trim(); if (v.isBlank()) return
        val list = (listOf(HistoryItem(v, System.currentTimeMillis())) + all().filterNot { it.url == v }).take(100)
        prefs.edit().putString("items", list.joinToString("\n") { "${it.url}\t${it.time}" }).apply()
    }
    fun remove(url: String) { prefs.edit().putString("items", all().filterNot { it.url == url }.joinToString("\n") { "${it.url}\t${it.time}" }).apply() }
    fun clear() { prefs.edit().remove("items").apply() }
}

class MainActivity : ComponentActivity() {
    private var pendingDownload: String? = null
    private var waitingForStorage = false

    private val torrentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val target = File(cacheDir, "selected-${System.currentTimeMillis()}.torrent")
            contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            startDownloadService(DownloadService.ACTION_TORRENT_FILE, null, target.absolutePath)
        } catch (_: Throwable) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PdmApp(
                activity = this,
                incomingUrl = extractIncoming(intent),
                onDownload = ::startUrlDownload,
                onPickTorrent = { torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) },
                onStorageSettings = ::openStorageSettings,
                requestStorage = ::requestStorageAndDownload
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForStorage && PdmStorage.hasPublicAccess()) {
            waitingForStorage = false
            pendingDownload?.let { pendingDownload = null; startUrlDownload(it) }
        }
    }

    private fun requestStorageAndDownload(url: String) { pendingDownload = url; waitingForStorage = true; openStorageSettings() }

    private fun extractIncoming(i: Intent?): String? = when {
        i?.action == Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
        else -> i?.data?.takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) || it.scheme.equals("magnet", true) }?.toString()
    }

    private fun startUrlDownload(value: String) {
        val url = value.trim(); if (url.isBlank()) return
        when {
            url.startsWith("magnet:", true) -> startDownloadService(DownloadService.ACTION_TORRENT_MAGNET, url, null)
            isTorrentUrl(url) -> startDownloadService(DownloadService.ACTION_TORRENT_URL, url, null)
            url.startsWith("http://", true) || url.startsWith("https://", true) -> startDownloadService(DownloadService.ACTION_HTTP, url, null)
        }
    }

    private fun startDownloadService(action: String, url: String?, path: String?) {
        val i = Intent(this, DownloadService::class.java).apply {
            this.action = action
            if (url != null) putExtra(DownloadService.EXTRA_URL, url)
            if (path != null) putExtra(DownloadService.EXTRA_PATH, path)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun openStorageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }
}

@Composable
private fun PdmApp(
    activity: ComponentActivity,
    incomingUrl: String?,
    onDownload: (String) -> Unit,
    onPickTorrent: () -> Unit,
    onStorageSettings: () -> Unit,
    requestStorage: (String) -> Unit
) {
    val context = activity
    val history = remember(context) { HistoryStore(context) }
    var historyItems by remember { mutableStateOf(history.all()) }
    var screen by rememberSaveable { mutableStateOf("Home") }
    var address by rememberSaveable { mutableStateOf(incomingUrl.orEmpty()) }
    var browserUrl by rememberSaveable { mutableStateOf("https://www.google.com") }
    var splash by remember { mutableStateOf(true) }
    var permissionStep by rememberSaveable { mutableIntStateOf(0) }
    var permissionsReady by rememberSaveable { mutableStateOf(false) }
    var exitDialog by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    fun finishPermissionFlow() {
        permissionStep = 0
        permissionsReady = true
        incomingUrl?.let {
            history.add(it)
            historyItems = history.all()
            address = it
            screen = "Downloads"
            onDownload(it)
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !PdmStorage.hasPublicAccess()) permissionStep = 2
        else finishPermissionFlow()
    }

    LaunchedEffect(Unit) {
        delay(1200)
        splash = false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissionStep = 1
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !PdmStorage.hasPublicAccess()) permissionStep = 2
        else finishPermissionFlow()
    }

    LaunchedEffect(permissionStep) {
        if (permissionStep == 2) {
            while (true) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || PdmStorage.hasPublicAccess()) {
                    finishPermissionFlow()
                    break
                }
                delay(400)
            }
        }
    }

    BackHandler(enabled = !splash) {
        if (permissionStep != 0) return@BackHandler
        if (drawerState.isOpen) { drawerScope.launch { drawerState.close() }; return@BackHandler }
        when (screen) { "Home" -> exitDialog = true; else -> screen = "Home" }
    }

    fun saveHistory(v: String) { history.add(v); historyItems = history.all() }
    fun requestDownloadNow(v: String) {
        val url = v.trim(); if (url.isBlank()) return
        saveHistory(url); screen = "Downloads"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !PdmStorage.hasPublicAccess()) requestStorage(url) else onDownload(url)
    }
    fun submitAddress(v: String) {
        val url = v.trim(); if (url.isBlank()) return
        address = url
        if (url.startsWith("magnet:", true) || url.startsWith("http://", true) || url.startsWith("https://", true)) requestDownloadNow(url)
        else { browserUrl = "https://www.google.com/search?q=${Uri.encode(url)}"; screen = "Browser" }
    }
    fun openRecent(url: String) { address = url; browserUrl = url; screen = "Browser"; drawerScope.launch { drawerState.close() } }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White, surface = Color.White)) {
        if (splash) { SplashScreen(); return@MaterialTheme }
        if (!permissionsReady || permissionStep != 0) {
            PermissionScreen(
                step = permissionStep,
                onNotification = { if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else permissionStep = 2 },
                onStorage = onStorageSettings,
                onSkip = {
                    if (permissionStep == 1) permissionStep = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !PdmStorage.hasPublicAccess()) 2 else { finishPermissionFlow(); 0 }
                    else finishPermissionFlow()
                }
            )
            return@MaterialTheme
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = Color.White) {
                    Column(Modifier.fillMaxHeight().padding(top = 8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { drawerScope.launch { drawerState.close() } }) { Icon(Icons.Default.Close, "Close menu") }
                            Icon(Icons.Default.Download, null, Modifier.size(34.dp), tint = Green)
                            Spacer(Modifier.width(10.dp))
                            Column { Text("Palia Download Manager", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text("Recent tabs", color = Muted, fontSize = 13.sp) }
                        }
                        HorizontalDivider()
                        Text("RECENT", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))
                        if (historyItems.isEmpty()) Text("No recent tabs", color = Muted, modifier = Modifier.padding(20.dp))
                        else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) { items(historyItems.take(30), key = { it.url }) { item -> NavigationDrawerItem(icon = { Icon(Icons.Default.Language, null) }, label = { Text(item.url, maxLines = 2, overflow = TextOverflow.Ellipsis) }, selected = false, onClick = { openRecent(item.url) }, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) } }
                        Spacer(Modifier.weight(1f))
                        NavigationDrawerItem(icon = { Icon(Icons.Default.DeleteSweep, null) }, label = { Text("Clear recent") }, selected = false, onClick = { history.clear(); historyItems = emptyList() }, modifier = Modifier.padding(10.dp))
                    }
                }
            }
        ) {
            Scaffold(containerColor = Color.White, bottomBar = { NavigationBar(containerColor = Color(0xFFF4FFFB), tonalElevation = 0.dp) { NavItem("Home", Icons.Default.Home, screen == "Home") { screen = "Home" }; NavItem("Downloads", Icons.Default.Download, screen == "Downloads") { screen = "Downloads" }; NavItem("Browser", Icons.Default.Web, screen == "Browser") { screen = "Browser" }; NavItem("Settings", Icons.Default.Settings, screen == "Settings") { screen = "Settings" } } }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (screen) {
                        "Home" -> HomeScreen(context, address, { address = it }, { submitAddress(address) }, { screen = "Browser" }, { screen = "Downloads" }, onPickTorrent, { requestDownloadNow(address) }, historyItems, { v -> address = v; submitAddress(v) }, { v -> history.remove(v); historyItems = history.all() }, { history.clear(); historyItems = emptyList() }, { drawerScope.launch { drawerState.open() } })
                        "Downloads" -> DownloadsScreen(context) { drawerScope.launch { drawerState.open() } }
                        "Browser" -> BrowserScreen(context, browserUrl, { saveHistory(it) }, { requestDownloadNow(it) }, { browserUrl = it }, { drawerScope.launch { drawerState.open() } })
                        else -> SettingsScreen(historyItems, { history.clear(); historyItems = emptyList() }, onStorageSettings) { drawerScope.launch { drawerState.open() } }
                    }
                }
            }
        }
        if (exitDialog) AlertDialog(onDismissRequest = { exitDialog = false }, title = { Text("Exit Palia Download Manager?") }, text = { Text("Are you sure you want to exit?") }, confirmButton = { TextButton(onClick = { activity.finish() }) { Text("YES") } }, dismissButton = { TextButton(onClick = { exitDialog = false }) { Text("NO") } })
    }
}

@Composable
private fun PermissionScreen(step: Int, onNotification: () -> Unit, onStorage: () -> Unit, onSkip: () -> Unit) {
    val notification = step == 1
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Mint)) {
            Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(if (notification) Icons.Default.Notifications else Icons.Default.Folder, null, Modifier.size(58.dp), tint = Green)
                Text(if (notification) "Step 1 of 2" else "Step 2 of 2", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Green)
                Text(if (notification) "Allow download notifications" else "Allow storage access", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                Text(if (notification) "Palia Download Manager uses notifications to show download progress and completion." else "PDM needs file access so downloaded files can be saved and managed.", color = Muted)
                Button(onClick = if (notification) onNotification else onStorage, modifier = Modifier.fillMaxWidth()) { Text(if (notification) "Allow notifications" else "Allow storage") }
                TextButton(onClick = onSkip) { Text(if (notification) "Skip this step" else "Continue without storage") }
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) { NavigationBarItem(selected = selected, onClick = onClick, icon = { Icon(icon, null) }, label = { Text(label) }) }

@Composable
private fun DrawerTopBar(title: String, onMenu: () -> Unit) { Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Menu") }; Text(title, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Dark) } }

@Composable
private fun SplashScreen() { Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Default.Download, null, Modifier.size(96.dp), tint = Green); Text("Palia Download Manager", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text("FAST • SMART • SECURE", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold); LinearProgressIndicator(Modifier.width(250.dp).height(6.dp).clip(RoundedCornerShape(50)), color = Green); Text("Developer By Shanpalia", color = Muted, fontSize = 13.sp) } } }

@Composable
private fun HomeScreen(context: Context, url: String, onUrl: (String) -> Unit, onGo: () -> Unit, onBrowser: () -> Unit, onDownloads: () -> Unit, onTorrent: () -> Unit, onAddDownload: () -> Unit, historyItems: List<HistoryItem>, onHistoryClick: (String) -> Unit, onHistoryDelete: (String) -> Unit, onClearHistory: () -> Unit, onMenu: () -> Unit) {
    var field by remember(url) { mutableStateOf(TextFieldValue(url, TextRange(url.length))) }
    LaunchedEffect(url) { if (field.text != url) field = TextFieldValue(url, TextRange(url.length)) }
    Column(Modifier.fillMaxSize()) {
        DrawerTopBar("Palia Download Manager", onMenu)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { OutlinedTextField(value = field, onValueChange = { field = it; onUrl(it.text) }, modifier = Modifier.fillMaxWidth().height(58.dp), singleLine = true, shape = RoundedCornerShape(16.dp), placeholder = { Text("Paste here") }, leadingIcon = { Icon(Icons.Default.Language, null) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { onGo() }), trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) { if (field.text.isNotBlank()) IconButton(onClick = { field = TextFieldValue(""); onUrl("") }) { Icon(Icons.Default.Clear, "Clear") }; TextButton(onClick = { val v = readClipboard(context); field = TextFieldValue(v, TextRange(v.length)); onUrl(v) }) { Text("PASTE", color = Green, fontWeight = FontWeight.Bold) }; FilledIconButton(onClick = onGo, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Blue)) { Icon(Icons.Default.ArrowForward, "Go") } } }) }
            item { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) { Icon(Icons.Default.Download, "PDM", Modifier.size(48.dp), tint = Green); Spacer(Modifier.width(10.dp)); Column { Text("Palia Download Manager", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text("Fast • Smart • Secure", color = Muted) } } }
            item { Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Ready to download?", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text("Paste a direct HTTP/HTTPS file link, .torrent or magnet link.", color = Muted); Button(onClick = onAddDownload, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Start Download") }; OutlinedButton(onClick = onTorrent, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Open .torrent file") }; OutlinedButton(onClick = onDownloads, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.List, null); Spacer(Modifier.width(8.dp)); Text("View Downloads") } } } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onBrowser, Modifier.weight(1f)) { Text("Browser") }; OutlinedButton(onClick = onDownloads, Modifier.weight(1f)) { Text("Downloads") } } }
            item { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) { Text("Recent history", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Dark, modifier = Modifier.weight(1f)); if (historyItems.isNotEmpty()) TextButton(onClick = onClearHistory) { Text("Clear") } } }
            if (historyItems.isEmpty()) item { Text("Your recent links will appear here.", color = Muted) }
            else items(historyItems.take(10), key = { it.url }) { item -> Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF7FAF9)), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) { Text(item.url, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, color = Dark); IconButton(onClick = { onHistoryClick(item.url) }) { Icon(Icons.Default.Download, "Open recent") }; IconButton(onClick = { onHistoryDelete(item.url) }) { Icon(Icons.Default.DeleteOutline, "Delete") } } } }
        }
    }
}

@Composable
private fun DownloadsScreen(context: Context, onMenu: () -> Unit) {
    var files by remember { mutableStateOf(PdmStorage.allFiles(context)) }
    var confirm by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(Unit) { while (true) { files = PdmStorage.allFiles(context); delay(1000) } }
    Column(Modifier.fillMaxSize()) { DrawerTopBar("Downloads", onMenu); Column(Modifier.padding(horizontal = 14.dp)) { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) { Column(Modifier.weight(1f)) { Text("${files.size} file(s) • Download/PDM", color = Muted) }; IconButton(onClick = { files = PdmStorage.allFiles(context) }) { Icon(Icons.Default.Refresh, "Refresh") } }; if (files.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.DownloadDone, null, Modifier.size(60.dp), tint = Green); Text("No downloads yet", fontSize = 20.sp, fontWeight = FontWeight.Bold) } } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 90.dp)) { items(files, key = { it.absolutePath }) { file -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(fileIcon(file), null, tint = Blue, modifier = Modifier.size(34.dp)); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = Dark); Text("${formatSize(file.length())} • ${file.parentFile?.name ?: "PDM"}", fontSize = 12.sp, color = Muted) }; IconButton(onClick = { confirm = file }) { Icon(Icons.Default.Delete, "Delete download", tint = Color(0xFFD32F2F)) } } } } } } }
    confirm?.let { file -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Delete download?") }, text = { Text("Delete ${file.name} permanently?") }, confirmButton = { TextButton(onClick = { try { file.deleteRecursively() } catch (_: Throwable) {}; confirm = null; files = PdmStorage.allFiles(context) }) { Text("DELETE", color = Color(0xFFD32F2F)) } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("CANCEL") } }) }
}

private fun fileIcon(file: File): ImageVector = when (PdmStorage.categoryFor(file.name)) { "Images" -> Icons.Default.Image; "Videos" -> Icons.Default.Movie; "Music" -> Icons.Default.MusicNote; "APK" -> Icons.Default.Android; "ZIP" -> Icons.Default.Archive; "Documents" -> Icons.Default.Description; "Torrents" -> Icons.Default.CloudDownload; else -> Icons.Default.InsertDriveFile }

@Composable
private fun BrowserScreen(context: Context, initialUrl: String, onHistory: (String) -> Unit, onDownload: (String) -> Unit, onUrlChanged: (String) -> Unit, onMenu: () -> Unit) {
    var field by remember(initialUrl) { mutableStateOf(TextFieldValue(initialUrl, TextRange(initialUrl.length))) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    fun submit() { val v = field.text.trim(); if (v.isBlank()) return; onHistory(v); if (v.startsWith("magnet:", true) || isDownloadLink(v)) onDownload(v) else webView?.loadUrl(normalizeUrl(v)) }
    Column(Modifier.fillMaxSize()) { Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Menu, "Menu") }; OutlinedTextField(value = field, onValueChange = { field = it; onUrlChanged(it.text) }, modifier = Modifier.weight(1f).height(54.dp), singleLine = true, shape = RoundedCornerShape(16.dp), placeholder = { Text("Paste here") }, leadingIcon = { Icon(Icons.Default.Language, null) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { submit() }), trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) { if (field.text.isNotBlank()) IconButton(onClick = { field = TextFieldValue(""); onUrlChanged("") }) { Icon(Icons.Default.Clear, "Clear") }; IconButton(onClick = { val v = readClipboard(context); if (v.isNotBlank()) { field = TextFieldValue(v, TextRange(v.length)); onUrlChanged(v) } }) { Icon(Icons.Default.ContentPaste, "Paste") } } }) }; AndroidView(factory = { ctx -> WebView(ctx).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.allowFileAccess = true; settings.allowContentAccess = true; webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean { val v = request.url.toString(); if (v.startsWith("magnet:", true) || isDownloadLink(v)) { onDownload(v); return true }; onHistory(v); field = TextFieldValue(v, TextRange(v.length)); onUrlChanged(v); return false }; override fun onPageFinished(view: WebView, url: String) { if (url.isNotBlank()) { field = TextFieldValue(url, TextRange(url.length)); onUrlChanged(url) } } }; setDownloadListener(DownloadListener { url, _, _, _, _ -> onHistory(url); onDownload(url) }); webView = this; loadUrl(normalizeUrl(initialUrl)) } }, update = { webView = it }, modifier = Modifier.fillMaxSize()) }
}

@Composable
private fun SettingsScreen(history: List<HistoryItem>, clearHistory: () -> Unit, storageSettings: () -> Unit, onMenu: () -> Unit) { Column(Modifier.fillMaxSize()) { DrawerTopBar("Settings", onMenu); Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Storage", fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("Files are saved under Download/PDM.", color = Muted); Button(onClick = storageSettings) { Text("Storage permission") } } }; Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("History", fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("${history.size} saved link(s)", color = Muted); OutlinedButton(onClick = clearHistory) { Text("Clear history") } } } } } }

private fun readClipboard(context: Context): String = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
private fun normalizeUrl(value: String): String { val v = value.trim(); return if (v.startsWith("http://", true) || v.startsWith("https://", true)) v else "https://www.google.com/search?q=${Uri.encode(v)}" }
private fun isTorrentUrl(value: String): Boolean = value.substringBefore('?').substringBefore('#').endsWith(".torrent", true)
private fun isDownloadLink(value: String): Boolean { val clean = value.substringBefore('#'); if (clean.startsWith("magnet:", true)) return true; val path = clean.substringBefore('?').lowercase(); val extensions = listOf(".zip", ".apk", ".torrent", ".rar", ".7z", ".tar", ".gz", ".bz2", ".pdf", ".mp3", ".mp4", ".mkv", ".avi", ".mov", ".webm", ".jpg", ".jpeg", ".png", ".webp", ".gif", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".apks", ".xapk"); return extensions.any { path.endsWith(it) } }
private fun formatSize(bytes: Long): String = when { bytes < 1024 -> "$bytes B"; bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0); bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0); else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0) }
