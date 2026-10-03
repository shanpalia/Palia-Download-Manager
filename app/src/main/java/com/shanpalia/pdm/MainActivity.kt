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
import androidx.compose.ui.text.input.KeyboardActions
import androidx.compose.ui.text.input.KeyboardOptions
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
        val v = url.trim(); if (v.isBlank()) return
        val list = (listOf(HistoryItem(v, System.currentTimeMillis())) + all().filterNot { it.url == v }).take(50)
        prefs.edit().putString("items", list.joinToString("\n") { "${it.url}\t${it.time}" }).apply()
    }
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
        setContent { PdmApp(this, ::startUrlDownload, ::openStorageSettings, ::requestStorageAndDownload, { torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) }) }
    }
    override fun onResume() {
        super.onResume()
        if (waitingForStorage && PdmStorage.hasPublicAccess()) {
            waitingForStorage = false
            pendingDownload?.let { pendingDownload = null; startUrlDownload(it) }
        }
    }
    private fun requestStorageAndDownload(url: String) { pendingDownload = url; waitingForStorage = true; openStorageSettings() }
    private fun startUrlDownload(value: String) {
        val url = value.trim(); if (url.isBlank()) return
        when {
            url.startsWith("magnet:", true) -> startDownloadService(DownloadService.ACTION_TORRENT_MAGNET, url, null)
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

    fun finishPermissions() { permissionStep = 0; permissionsReady = true }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !PdmStorage.hasPublicAccess()) permissionStep = 2 else finishPermissions()
    }
    LaunchedEffect(Unit) {
        delay(900); splash = false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissionStep = 1
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !PdmStorage.hasPublicAccess()) permissionStep = 2
        else finishPermissions()
    }
    LaunchedEffect(permissionStep) {
        if (permissionStep == 2) while (true) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || PdmStorage.hasPublicAccess()) { finishPermissions(); break }
            delay(400)
        }
    }
    BackHandler(enabled = !splash) {
        if (permissionStep != 0) return@BackHandler
        if (dialogUrl != null) { dialogUrl = null; return@BackHandler }
        if (drawerState.isOpen) { scope.launch { drawerState.close() }; return@BackHandler }
        if (screen == "Browser") screen = "Home" else if (screen != "Home") screen = "Home" else exitDialog = true
    }

    fun saveHistory(url: String) { history.add(url); historyItems = history.all() }
    fun showDownload(url: String) {
        val clean = url.trim(); if (clean.isBlank()) return
        dialogUrl = clean
        dialogName = clean.substringBefore('?').substringBefore('#').substringAfterLast('/').ifBlank { "download.bin" }
        dialogSize = null
        saveHistory(clean)
    }
    fun goFromAddress(value: String) {
        val clean = value.trim(); if (clean.isBlank()) return
        address = clean
        if (clean.startsWith("magnet:", true)) showDownload(clean)
        else if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) { browserUrl = clean; screen = "Browser" }
        else { browserUrl = "https://www.google.com/search?q=${Uri.encode(clean)}"; screen = "Browser" }
    }
    fun startConfirmed() {
        val url = dialogUrl ?: return
        dialogUrl = null
        screen = "Downloads"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !PdmStorage.hasPublicAccess()) requestStorage(url) else onDownload(url)
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White, surface = Color.White)) {
        if (splash) { SplashScreen(); return@MaterialTheme }
        if (!permissionsReady || permissionStep != 0) {
            PermissionScreen(permissionStep,
                { if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else permissionStep = 2 },
                onStorageSettings,
                { if (permissionStep == 1) permissionStep = 2 else finishPermissions() })
            return@MaterialTheme
        }
        ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color.White) {
                Column(Modifier.fillMaxHeight().padding(vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawerState.close() } }) { Icon(Icons.Default.Close, "Close") }
                        Icon(Icons.Default.Download, null, Modifier.size(34.dp), tint = Green)
                        Spacer(Modifier.width(10.dp)); Text("Palia Download Manager", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                    }
                    HorizontalDivider()
                    DrawerItem("Home", Icons.Default.Home, screen == "Home") { screen = "Home" }
                    DrawerItem("Downloads", Icons.Default.Download, screen == "Downloads") { screen = "Downloads" }
                    DrawerItem("Browser", Icons.Default.Web, screen == "Browser") { screen = "Browser" }
                    DrawerItem("Settings", Icons.Default.Settings, screen == "Settings") { screen = "Settings" }
                    Spacer(Modifier.height(12.dp)); Text("RECENT TABS", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(historyItems, key = { it.url }) { item ->
                            NavigationDrawerItem(icon = { Icon(Icons.Default.Language, null) }, label = { Text(item.url, maxLines = 2, overflow = TextOverflow.Ellipsis) }, selected = false, onClick = { address = item.url; browserUrl = item.url; screen = "Browser"; scope.launch { drawerState.close() } }, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
                        }
                    }
                    NavigationDrawerItem(icon = { Icon(Icons.Default.DeleteSweep, null) }, label = { Text("Clear recent") }, selected = false, onClick = { history.clear(); historyItems = emptyList() }, modifier = Modifier.padding(10.dp))
                }
            }
        }) {
            Scaffold(containerColor = Color.White, bottomBar = {
                NavigationBar(containerColor = Color(0xFFF4FFFB), tonalElevation = 0.dp) {
                    NavItem("Home", Icons.Default.Home, screen == "Home") { screen = "Home" }
                    NavItem("Downloads", Icons.Default.Download, screen == "Downloads") { screen = "Downloads" }
                    NavItem("Browser", Icons.Default.Web, screen == "Browser") { screen = "Browser" }
                    NavItem("Settings", Icons.Default.Settings, screen == "Settings") { screen = "Settings" }
                }
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (screen) {
                        "Home" -> HomeScreen(address, { address = it }, { goFromAddress(address) }, { showDownload(address) }, { screen = "Browser" }, { screen = "Downloads" }, onPickTorrent) { scope.launch { drawerState.open() } }
                        "Downloads" -> DownloadsScreen(context) { scope.launch { drawerState.open() } }
                        "Browser" -> BrowserScreen(context, browserUrl, { address = it; browserUrl = it; saveHistory(it) }, { showDownload(it) }, { browserUrl = it; address = it }, { scope.launch { drawerState.open() } })
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
                        val c = (URL(u).openConnection() as HttpURLConnection).apply { requestMethod = "HEAD"; instanceFollowRedirects = true; connectTimeout = 7000; readTimeout = 7000 }
                        c.connect(); val n = c.contentLengthLong; c.disconnect(); if (n > 0) n else null
                    } catch (_: Throwable) { null }
                }
            }
            AlertDialog(onDismissRequest = { dialogUrl = null }, title = { Text("Download file", fontWeight = FontWeight.ExtraBold) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(dialogName, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(dialogSize?.let(::formatSize) ?: "Size: checking…", color = Muted)
                    Text(dialogUrl ?: "", color = Muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("Save location: Download/PDM", color = Muted, fontSize = 12.sp)
                }
            }, confirmButton = { Button(onClick = { startConfirmed() }) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("START") } }, dismissButton = { TextButton(onClick = { dialogUrl = null }) { Text("CANCEL") } })
        }
        if (exitDialog) AlertDialog(onDismissRequest = { exitDialog = false }, title = { Text("Exit Palia Download Manager?") }, text = { Text("Are you sure you want to exit?") }, confirmButton = { TextButton(onClick = { activity.finish() }) { Text("YES") } }, dismissButton = { TextButton(onClick = { exitDialog = false }) { Text("NO") } })
    }
}

@Composable
private fun DrawerItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(icon = { Icon(icon, null) }, label = { Text(label) }, selected = selected, onClick = onClick, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
}

@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(selected = selected, onClick = onClick, icon = { Icon(icon, null) }, label = { Text(label) })
}

@Composable
private fun HomeScreen(url: String, onUrl: (String) -> Unit, onGo: () -> Unit, onStart: () -> Unit, onBrowser: () -> Unit, onDownloads: () -> Unit, onTorrent: () -> Unit, onMenu: () -> Unit) {
    var field by remember(url) { mutableStateOf(TextFieldValue(url, TextRange(url.length))) }
    LaunchedEffect(url) { if (field.text != url) field = TextFieldValue(url, TextRange(url.length)) }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Menu, "Menu", tint = Dark, modifier = Modifier.size(30.dp)) }
            OutlinedTextField(value = field, onValueChange = { field = it; onUrl(it.text) }, modifier = Modifier.weight(1f).height(58.dp), singleLine = true, shape = RoundedCornerShape(18.dp), placeholder = { Text("Paste URL or search") }, leadingIcon = { Icon(Icons.Default.Language, null, tint = Dark) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { onGo() }), trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) { if (field.text.isNotBlank()) IconButton(onClick = { field = TextFieldValue(""); onUrl("") }) { Icon(Icons.Default.Close, "Clear") }; TextButton(onClick = onGo) { Text("GO", color = Green, fontWeight = FontWeight.ExtraBold) } } })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Download, null, Modifier.size(42.dp), tint = Green); Spacer(Modifier.width(12.dp)); Column { Text("Palia Download Manager", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text("Fast • Smart • Secure", color = Muted, fontSize = 14.sp) }
        }
        Card(Modifier.fillMaxWidth().padding(18.dp), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Mint)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ready to download?", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                Text("Paste a link, open it in Browser, or start a direct download.", color = Muted, fontSize = 16.sp)
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Start Download", fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onTorrent, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(28.dp)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Open .torrent file") }
                OutlinedButton(onClick = onDownloads, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(28.dp)) { Icon(Icons.Default.List, null); Spacer(Modifier.width(8.dp)); Text("View Downloads") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBrowser, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(26.dp)) { Icon(Icons.Default.Web, null); Spacer(Modifier.width(6.dp)); Text("Browser") }
            OutlinedButton(onClick = onDownloads, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(26.dp)) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Downloads") }
        }
    }
}

@Composable
private fun BrowserScreen(context: Context, initialUrl: String, onUrlSaved: (String) -> Unit, onDownload: (String) -> Unit, onUrlChange: (String) -> Unit, onMenu: () -> Unit) {
    var field by remember(initialUrl) { mutableStateOf(TextFieldValue(initialUrl, TextRange(initialUrl.length))) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Menu, "Menu", tint = Dark, modifier = Modifier.size(30.dp)) }
            OutlinedTextField(value = field, onValueChange = { field = it; onUrlChange(it.text) }, modifier = Modifier.weight(1f).height(58.dp), singleLine = true, shape = RoundedCornerShape(18.dp), placeholder = { Text("Search or enter address") }, leadingIcon = { Icon(Icons.Default.Language, null) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { val u = field.text.trim(); if (u.isNotBlank()) { onUrlSaved(u); webView?.loadUrl(if (u.startsWith("http", true)) u else "https://www.google.com/search?q=${Uri.encode(u)}") } }), trailingIcon = { if (field.text.isNotBlank()) IconButton(onClick = { field = TextFieldValue("") }) { Icon(Icons.Default.Close, "Clear") } })
        }
        AndroidView(factory = { ctx -> WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean { onUrlSaved(request.url.toString()); return false }
                override fun onPageFinished(view: WebView, url: String) { field = TextFieldValue(url, TextRange(url.length)); onUrlSaved(url) }
            }
            setDownloadListener { url, _, _, _, _ -> if (!url.isNullOrBlank()) onDownload(url) }
            loadUrl(initialUrl.ifBlank { "https://www.google.com" })
            webView = this
        }, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun DownloadsScreen(context: Context, onMenu: () -> Unit) {
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var previous by remember { mutableStateOf<Map<String, Pair<Long, Long>>>(emptyMap()) }
    var speeds by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            val current = PdmStorage.allFiles(context)
            val next = mutableMapOf<String, Pair<Long, Long>>()
            val sp = mutableMapOf<String, Long>()
            current.forEach { f ->
                val old = previous[f.absolutePath]
                if (old != null) { val dt = (now - old.second).coerceAtLeast(1); sp[f.absolutePath] = ((f.length() - old.first) * 1000L / dt).coerceAtLeast(0L) }
                next[f.absolutePath] = f.length() to now
            }
            previous = next; speeds = sp; files = current
            delay(700)
        }
    }
    val active = files.filter { it.name.startsWith(".") && it.name.endsWith(".part") }
    val done = files.filterNot { it.name.startsWith(".") && it.name.endsWith(".part") }
    Column(Modifier.fillMaxSize()) {
        DrawerTopBar("Downloads", onMenu)
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text("${active.size + done.size} file(s) • Download/PDM", color = Muted, fontSize = 15.sp); Spacer(Modifier.weight(1f)); IconButton(onClick = {}) { Icon(Icons.Default.Refresh, "Refresh") } }
        LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(active, key = { it.absolutePath }) { f ->
                val display = f.name.removePrefix(".").removeSuffix(".part")
                val speed = speeds[f.absolutePath] ?: 0L
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF4FFFB)), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.InsertDriveFile, null, Modifier.size(42.dp), tint = Blue); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(display, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${formatSize(f.length())} • ${formatSpeed(speed)} • Downloading", color = Muted, fontSize = 13.sp) }; IconButton(onClick = {}) { Icon(Icons.Default.Pause, "Pause") } }
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp), color = Green)
                    }
                }
            }
            items(done, key = { it.absolutePath }) { f ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF6F7F8)), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.InsertDriveFile, null, Modifier.size(42.dp), tint = Blue); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(f.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${formatSize(f.length())} • Completed", color = Muted, fontSize = 13.sp) }; IconButton(onClick = { f.delete() }) { Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFD83232)) } }
                }
            }
            if (files.isEmpty()) item { Text("No downloads yet", color = Muted, modifier = Modifier.padding(24.dp)) }
        }
    }
}

@Composable
private fun DrawerTopBar(title: String, onMenu: () -> Unit) { Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Menu", tint = Dark) }; Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Dark) } }

@Composable
private fun PermissionScreen(step: Int, onNotification: () -> Unit, onStorage: () -> Unit, onSkip: () -> Unit) {
    val notification = step == 1
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Mint)) {
            Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(if (notification) Icons.Default.Notifications else Icons.Default.Folder, null, Modifier.size(58.dp), tint = Green)
                Text(if (notification) "Step 1 of 2" else "Step 2 of 2", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Green)
                Text(if (notification) "Allow download notifications" else "Allow storage access", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                Text(if (notification) "Notifications show download progress and completion." else "Storage access lets PDM save and manage downloaded files.", color = Muted)
                Button(onClick = if (notification) onNotification else onStorage, modifier = Modifier.fillMaxWidth()) { Text(if (notification) "Allow notifications" else "Allow storage") }
                TextButton(onClick = onSkip) { Text(if (notification) "Skip this step" else "Continue") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(historyItems: List<HistoryItem>, onClear: () -> Unit, onStorage: () -> Unit, onMenu: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        DrawerTopBar("Settings", onMenu)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Palia Download Manager", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
            Text("Downloads are saved in Download/PDM with automatic categories.", color = Muted)
            Button(onClick = onStorage, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text("Storage access") }
            OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("Clear recent tabs") }
            Text("Recent tabs: ${historyItems.size}", color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SplashScreen() { Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Default.Download, null, Modifier.size(96.dp), tint = Green); Text("Palia Download Manager", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text("FAST • SMART • SECURE", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold); LinearProgressIndicator(Modifier.width(150.dp), color = Green) } } }

private fun formatSpeed(v: Long): String = when { v <= 0L -> "0 B/s"; v < 1024L -> "$v B/s"; v < 1024L * 1024L -> String.format("%.1f KB/s", v / 1024.0); v < 1024L * 1024L * 1024L -> String.format("%.1f MB/s", v / 1024.0 / 1024.0); else -> String.format("%.2f GB/s", v / 1024.0 / 1024.0 / 1024.0) }
private fun formatSize(v: Long): String = when { v < 1024L -> "$v B"; v < 1024L * 1024L -> String.format("%.1f KB", v / 1024.0); v < 1024L * 1024L * 1024L -> String.format("%.1f MB", v / 1024.0 / 1024.0); else -> String.format("%.2f GB", v / 1024.0 / 1024.0 / 1024.0) }
