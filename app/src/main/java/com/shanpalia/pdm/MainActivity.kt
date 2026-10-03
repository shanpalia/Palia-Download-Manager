package com.shanpalia.pdm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
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
import androidx.compose.ui.platform.LocalContext
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
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private val Green = Color(0xFF16B978)
private val Blue = Color(0xFF1769D5)
private val Dark = Color(0xFF12231E)
private val Muted = Color(0xFF6F7D77)
private val Mint = Color(0xFFE9FFF6)
private const val UPDATE_MANIFEST_URL = "https://shanpalia.github.io/WebsitePaliaAPK_V.2/pdm-update.json"

private data class HistoryItem(val url: String, val time: Long)
private data class UpdateInfo(val available: Boolean, val versionName: String, val downloadUrl: String?, val notes: String?)

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

private suspend fun checkForPdmUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
    try {
        val connection = (URL(UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            useCaches = false
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        val json = JSONObject(body)
        val remoteCode = json.optLong("versionCode", 0L)
        val remoteName = json.optString("version", json.optString("versionName", ""))
        val url = json.optString("downloadUrl", "").ifBlank { null }
        val notes = json.optString("notes", "").ifBlank { null }
        UpdateInfo(remoteCode > BuildConfig.VERSION_CODE, remoteName, url, notes)
    } catch (_: Throwable) {
        null
    }
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
            PdmApp(this, ::startUrlDownload, ::openStorageSettings, ::requestStorageAndDownload) {
                torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForStorage && PdmStorage.hasPublicAccess()) {
            waitingForStorage = false
            pendingDownload?.let { pendingDownload = null; startUrlDownload(it) }
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
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }
}

@Composable
private fun PdmApp(activity: ComponentActivity, onDownload: (String) -> Unit, onStorageSettings: () -> Unit, requestStorage: (String) -> Unit, onPickTorrent: () -> Unit) {
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
        when {
            Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> permissionStep = 1
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !PdmStorage.hasPublicAccess() -> permissionStep = 2
            else -> finishPermissions()
        }
    }
    LaunchedEffect(permissionStep) {
        if (permissionStep == 2) { while (!PdmStorage.hasPublicAccess()) delay(400); finishPermissions() }
    }

    BackHandler(enabled = !splash) {
        if (permissionStep != 0) return@BackHandler
        if (dialogUrl != null) { dialogUrl = null; return@BackHandler }
        if (drawerState.isOpen) { scope.launch { drawerState.close() }; return@BackHandler }
        if (screen == "Browser") return@BackHandler
        if (screen != "Home") screen = "Home" else exitDialog = true
    }

    fun saveHistory(url: String) { history.add(url); historyItems = history.all() }
    fun showDownload(value: String) {
        val clean = value.trim(); if (clean.isBlank()) return
        dialogUrl = clean
        dialogName = clean.substringBefore('?').substringBefore('#').substringAfterLast('/').ifBlank { "download.bin" }
        dialogSize = null; saveHistory(clean)
    }
    fun goFromAddress(value: String) {
        val clean = value.trim(); if (clean.isBlank()) return
        address = clean
        when {
            clean.startsWith("magnet:", true) -> showDownload(clean)
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> { browserUrl = clean; screen = "Browser" }
            else -> { browserUrl = "https://www.google.com/search?q=${Uri.encode(clean)}"; screen = "Browser" }
        }
    }
    fun startConfirmed() {
        val url = dialogUrl ?: return
        dialogUrl = null; screen = "Downloads"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !PdmStorage.hasPublicAccess()) requestStorage(url) else onDownload(url)
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White, surface = Color.White)) {
        if (splash) { SplashScreen(); return@MaterialTheme }
        if (!permissionsReady || permissionStep != 0) {
            PermissionScreen(permissionStep, { if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else permissionStep = 2 }, onStorageSettings, { if (permissionStep == 1) permissionStep = 2 else finishPermissions() })
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
                            Spacer(Modifier.width(10.dp)); Text("Palia Download Manager", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
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
                                NavigationDrawerItem(icon = { Icon(Icons.Default.Language, null) }, label = { Text(item.url, maxLines = 2, overflow = TextOverflow.Ellipsis) }, selected = false, onClick = { address = item.url; browserUrl = item.url; screen = "Browser"; scope.launch { drawerState.close() } }, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
                            }
                        }
                        NavigationDrawerItem(icon = { Icon(Icons.Default.DeleteSweep, null) }, label = { Text("Clear recent") }, selected = false, onClick = { history.clear(); historyItems = emptyList() }, modifier = Modifier.padding(10.dp))
                    }
                }
            }
        ) {
            Scaffold(containerColor = Color.White, bottomBar = {
                NavigationBar(containerColor = Color(0xFFF4FFFB), tonalElevation = 0.dp) {
                    PdmNavigationBarItem(selected = screen == "Home", onClick = { screen = "Home" }, icon = { Icon(Icons.Default.Home, null) })
                    PdmNavigationBarItem(selected = screen == "Downloads", onClick = { screen = "Downloads" }, icon = { Icon(Icons.Default.Download, null) })
                    PdmNavigationBarItem(selected = screen == "Browser", onClick = { screen = "Browser" }, icon = { Icon(Icons.Default.Web, null) })
                    PdmNavigationBarItem(selected = screen == "Settings", onClick = { screen = "Settings" }, icon = { Icon(Icons.Default.Settings, null) })
                }
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (screen) {
                        "Home" -> HomeScreen(address, { address = it }, { goFromAddress(address) }, { showDownload(address) }, { screen = "Browser" }, { screen = "Downloads" }, onPickTorrent) { scope.launch { drawerState.open() } }
                        "Downloads" -> DownloadsScreen(context) { scope.launch { drawerState.open() } }
                        "Browser" -> BrowserScreen(browserUrl, { address = it; browserUrl = it; saveHistory(it) }, { showDownload(it) }, { browserUrl = it; address = it }, { scope.launch { drawerState.open() } }, { screen = "Home" }) { screen = "Home" }
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
                        c.connect(); val size = c.contentLengthLong; c.disconnect(); if (size > 0) size else null
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

@Composable private fun DrawerItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(icon = { Icon(icon, null) }, label = { Text(label) }, selected = selected, onClick = onClick, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
}

@Composable private fun HomeScreen(url: String, onUrl: (String) -> Unit, onGo: () -> Unit, onStart: () -> Unit, onBrowser: () -> Unit, onDownloads: () -> Unit, onTorrent: () -> Unit, onMenu: () -> Unit) {
    var field by remember(url) { mutableStateOf(TextFieldValue(url, TextRange(url.length))) }
    LaunchedEffect(url) { if (field.text != url) field = TextFieldValue(url, TextRange(url.length)) }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Menu, "Menu", tint = Dark, modifier = Modifier.size(30.dp)) }
            OutlinedTextField(value = field, onValueChange = { field = it; onUrl(it.text) }, modifier = Modifier.weight(1f).height(58.dp), singleLine = true, shape = RoundedCornerShape(18.dp), placeholder = { Text("Paste URL or search") }, leadingIcon = { Icon(Icons.Default.Language, null, tint = Dark) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { onGo() }), trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) { if (field.text.isNotBlank()) IconButton(onClick = { field = TextFieldValue(""); onUrl("") }) { Icon(Icons.Default.Close, "Clear") }; IconButton(onClick = onGo) { Icon(Icons.Default.ArrowForward, "Go", tint = Blue, modifier = Modifier.size(30.dp)) } } })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Download, null, Modifier.size(44.dp), tint = Green); Spacer(Modifier.width(12.dp)); Column { Text("Palia Download Manager", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text("Fast • Smart • Secure", color = Muted, fontSize = 14.sp) }
        }
        Card(Modifier.fillMaxWidth().padding(18.dp), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Mint)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ready to download?", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                Text("Paste a direct HTTP/HTTPS file link, .torrent or magnet link.", color = Muted, fontSize = 16.sp)
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

@Composable private fun BrowserScreen(initialUrl: String, onUrlSaved: (String) -> Unit, onDownload: (String) -> Unit, onUrlChange: (String) -> Unit, onMenu: () -> Unit, onExit: () -> Unit) {
    var field by remember(initialUrl) { mutableStateOf(TextFieldValue(initialUrl, TextRange(initialUrl.length))) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    BackHandler(enabled = true) { val view = webView; if (view != null && view.canGoBack()) view.goBack() else onExit() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Menu, "Menu", tint = Dark, modifier = Modifier.size(30.dp)) }
            OutlinedTextField(value = field, onValueChange = { field = it; onUrlChange(it.text) }, modifier = Modifier.weight(1f).height(58.dp), singleLine = true, shape = RoundedCornerShape(18.dp), placeholder = { Text("Search or enter address") }, leadingIcon = { Icon(Icons.Default.Web, "Browser", tint = Dark) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { loadBrowserText(field.text, webView, onUrlSaved) }), trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) { if (field.text.isNotBlank()) IconButton(onClick = { field = TextFieldValue(""); onUrlChange("") }) { Icon(Icons.Default.Close, "Clear") }; IconButton(onClick = { loadBrowserText(field.text, webView, onUrlSaved) }) { Icon(Icons.Default.ArrowForward, "Go", tint = Blue) } } })
        }
        AndroidView(factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.databaseEnabled = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isScrollbarFadingEnabled = true
                setNestedScrollingEnabled(true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean { return false }
                    override fun onPageFinished(view: WebView, url: String) { field = TextFieldValue(url, TextRange(url.length)); onUrlSaved(url) }
                }
                setDownloadListener { downloadUrl, _, _, _, _ -> if (!downloadUrl.isNullOrBlank()) onDownload(downloadUrl) }
                loadUrl(initialUrl.ifBlank { "https://www.google.com" })
                webView = this
            }
        }, modifier = Modifier.fillMaxSize())
    }
}

private fun loadBrowserText(value: String, webView: WebView?, onSaved: (String) -> Unit) {
    val text = value.trim(); if (text.isBlank()) return
    val target = if (text.startsWith("http://", true) || text.startsWith("https://", true)) text else "https://www.google.com/search?q=${Uri.encode(text)}"
    onSaved(text); webView?.loadUrl(target)
}

@Composable private fun DownloadsScreen(context: Context, onMenu: () -> Unit) {
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var previous by remember { mutableStateOf<Map<String, Pair<Long, Long>>>(emptyMap()) }
    var speeds by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(Unit) { while (true) { val now = System.currentTimeMillis(); val current = PdmStorage.allFiles(context); val next = mutableMapOf<String, Pair<Long, Long>>(); val nextSpeeds = mutableMapOf<String, Long>(); current.forEach { file -> previous[file.absolutePath]?.let { old -> val dt = (now - old.second).coerceAtLeast(1); nextSpeeds[file.absolutePath] = ((file.length() - old.first) * 1000L / dt).coerceAtLeast(0L) }; next[file.absolutePath] = file.length() to now }; previous = next; speeds = nextSpeeds; files = current; delay(700) } }
    val active = files.filter { it.name.startsWith(".") && it.name.endsWith(".part") }; val done = files.filterNot { it.name.startsWith(".") && it.name.endsWith(".part") }
    Column(Modifier.fillMaxSize()) { DrawerTopBar("Downloads", onMenu); Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text("${files.size} file(s) • Download/PDM", color = Muted, fontSize = 15.sp); Spacer(Modifier.weight(1f)); IconButton(onClick = {}) { Icon(Icons.Default.Refresh, "Refresh") } }; LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(active, key = { it.absolutePath }) { file -> val display = file.name.removePrefix(".").removeSuffix(".part"); val speed = speeds[file.absolutePath] ?: 0L; Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF4FFFB)), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.InsertDriveFile, null, Modifier.size(42.dp), tint = Blue); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(display, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${formatSize(file.length())} • ${formatSpeed(speed)} • Downloading", color = Muted, fontSize = 13.sp) }; Icon(Icons.Default.Download, "Downloading", tint = Green) }; LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp), color = Green) } } }
        items(done, key = { it.absolutePath }) { file -> Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF6F7F8)), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.InsertDriveFile, null, Modifier.size(42.dp), tint = Blue); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(file.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${formatSize(file.length())} • Completed", color = Muted, fontSize = 13.sp) }; IconButton(onClick = { file.delete() }) { Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFD83232)) } } } }
        if (files.isEmpty()) item { Text("No downloads yet", color = Muted, modifier = Modifier.padding(24.dp)) }
    } }
}

@Composable private fun DrawerTopBar(title: String, onMenu: () -> Unit) { Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Menu", tint = Dark) }; Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Dark) } }

@Composable private fun PermissionScreen(step: Int, onNotification: () -> Unit, onStorage: () -> Unit, onSkip: () -> Unit) {
    val notification = step == 1
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Mint)) { Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) { Icon(if (notification) Icons.Default.Notifications else Icons.Default.Folder, null, Modifier.size(58.dp), tint = Green); Text(if (notification) "Step 1 of 2" else "Step 2 of 2", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Green); Text(if (notification) "Allow download notifications" else "Allow storage access", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text(if (notification) "Notifications show download progress and completion." else "Storage access lets PDM save and manage downloaded files.", color = Muted); Button(onClick = if (notification) onNotification else onStorage, modifier = Modifier.fillMaxWidth()) { Text(if (notification) "Allow notifications" else "Allow storage") }; TextButton(onClick = onSkip) { Text(if (notification) "Skip this step" else "Continue") } } } }
}

@Composable private fun SettingsScreen(historyItems: List<HistoryItem>, onClear: () -> Unit, onStorage: () -> Unit, onMenu: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("Not checked") }
    var remoteVersion by rememberSaveable { mutableStateOf("") }
    var updateUrl by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        DrawerTopBar("Settings", onMenu)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Palia Download Manager", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
            Text("Downloads are saved in Download/PDM with automatic categories.", color = Muted)
            Button(onClick = onStorage, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text("Storage access") }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.SystemUpdate, null, tint = Green, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text("App updates", fontWeight = FontWeight.Bold, color = Dark); Text(if (status == "Not checked") "Check the Palia website for a newer PDM version." else status, color = Muted, fontSize = 13.sp) } }
                    if (remoteVersion.isNotBlank()) Text("Website version: $remoteVersion", color = Dark, fontSize = 13.sp)
                    Button(enabled = !checking, onClick = { scope.launch { checking = true; status = "Checking website…"; val info = checkForPdmUpdate(); if (info == null) { status = "Could not check for updates"; remoteVersion = ""; updateUrl = "" } else if (info.available) { status = "Update available • v${info.versionName}"; remoteVersion = info.versionName; updateUrl = info.downloadUrl ?: "" } else { status = "Up to date • v${BuildConfig.VERSION_NAME}"; remoteVersion = info.versionName; updateUrl = "" }; checking = false } }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.SystemUpdate, null); Spacer(Modifier.width(8.dp)); Text(if (checking) "Checking…" else "Check for updates") }
                    if (updateUrl.isNotBlank()) Button(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))) } catch (_: Throwable) {} }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Open update") }
                }
            }
            OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("Clear recent tabs") }
            Text("PDM • v${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 13.sp)
            Text("Developer by shanpalia", color = Muted, fontSize = 13.sp)
            Text("Recent tabs: ${historyItems.size}", color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable private fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AndroidView(factory = { ctx -> ImageView(ctx).apply { setImageResource(R.mipmap.ic_pdm_logo) } }, modifier = Modifier.size(104.dp))
            Text("PDM", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
            Text("By PaliaAPK HUB", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Green)
            Text("Developer by shanpalia", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp)); LinearProgressIndicator(Modifier.width(150.dp), color = Green)
        }
    }
}

private fun formatSpeed(value: Long): String = when { value <= 0L -> "0 B/s"; value < 1024L -> "$value B/s"; value < 1024L * 1024L -> String.format("%.1f KB/s", value / 1024.0); value < 1024L * 1024L * 1024L -> String.format("%.1f MB/s", value / 1024.0 / 1024.0); else -> String.format("%.2f GB/s", value / 1024.0 / 1024.0 / 1024.0) }
private fun formatSize(value: Long): String = when { value < 1024L -> "$value B"; value < 1024L * 1024L -> String.format("%.1f KB", value / 1024.0); value < 1024L * 1024L * 1024L -> String.format("%.1f MB", value / 1024.0 / 1024.0); else -> String.format("%.2f GB", value / 1024.0 / 1024.0 / 1024.0) }
