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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
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
private const val UPDATE_URL = "https://shanpalia.github.io/WebsitePaliaAPK_V.2/pdm-update.json"

private data class UpdateInfo(val available: Boolean, val version: String, val url: String?)

private suspend fun websiteUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
    try {
        val c = URL(UPDATE_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        c.disconnect()
        val code = json.optLong("versionCode", 0L)
        UpdateInfo(code > BuildConfig.VERSION_CODE.toLong(), json.optString("version", ""), json.optString("downloadUrl", "").ifBlank { null })
    } catch (_: Throwable) { null }
}

class PdmMainActivity : ComponentActivity() {
    private var pending: String? = null
    private var waitingStorage = false
    private val torrentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val target = File(cacheDir, "selected-${System.currentTimeMillis()}.torrent")
            contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            startService(DownloadService.ACTION_TORRENT_FILE, null, target.absolutePath)
        } catch (_: Throwable) { }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { PdmRoot(this, ::startUrl, ::openStorage, ::requestStorage) { torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) } }
    }

    override fun onResume() {
        super.onResume()
        if (waitingStorage && PdmStorage.hasPublicAccess()) { waitingStorage = false; pending?.let { pending = null; startUrl(it) } }
    }

    private fun requestStorage(url: String) { pending = url; waitingStorage = true; openStorage() }
    private fun startUrl(value: String) {
        val u = value.trim(); if (u.isBlank()) return
        when { u.startsWith("magnet:", true) -> startService(DownloadService.ACTION_TORRENT_MAGNET, u, null); u.startsWith("http://", true) || u.startsWith("https://", true) -> startService(DownloadService.ACTION_HTTP, u, null) }
    }
    private fun startService(action: String, url: String?, path: String?) {
        val i = Intent(this, DownloadService::class.java).apply { this.action = action; if (url != null) putExtra(DownloadService.EXTRA_URL, url); if (path != null) putExtra(DownloadService.EXTRA_PATH, path) }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }
    private fun openStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}

@Composable private fun PdmRoot(activity: ComponentActivity, onDownload: (String) -> Unit, onStorage: () -> Unit, requestStorage: (String) -> Unit, onTorrent: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf("Home") }
    var browserUrl by rememberSaveable { mutableStateOf("https://www.google.com") }
    var homeUrl by rememberSaveable { mutableStateOf("") }
    var splash by remember { mutableStateOf(true) }
    var permission by rememberSaveable { mutableIntStateOf(0) }
    var ready by rememberSaveable { mutableStateOf(false) }
    var downloadUrl by remember { mutableStateOf<String?>(null) }
    var exit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (Build.VERSION.SDK_INT >= 30 && !PdmStorage.hasPublicAccess()) permission = 2 else { permission = 0; ready = true } }
    LaunchedEffect(Unit) { delay(900); splash = false; when { Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> permission = 1; Build.VERSION.SDK_INT >= 30 && !PdmStorage.hasPublicAccess() -> permission = 2; else -> { permission = 0; ready = true } } }
    LaunchedEffect(permission) { if (permission == 2) { while (!PdmStorage.hasPublicAccess()) delay(400); permission = 0; ready = true } }

    BackHandler(enabled = !splash) {
        if (downloadUrl != null) { downloadUrl = null; return@BackHandler }
        if (screen == "Browser") return@BackHandler
        if (screen != "Home") screen = "Home" else exit = true
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White, surface = Color.White)) {
        when {
            splash -> Splash()
            !ready -> Permission(permission, { if (Build.VERSION.SDK_INT >= 33) notification.launch(Manifest.permission.POST_NOTIFICATIONS) else permission = 2 }, onStorage, { permission = 0; ready = true })
            else -> Scaffold(containerColor = Color.White, bottomBar = {
                NavigationBar(containerColor = Color(0xFFF4FFFB), tonalElevation = 0.dp) {
                    PdmNavigationBarItem(screen == "Home", { screen = "Home" }, { Icon(Icons.Default.Home, null) })
                    PdmNavigationBarItem(screen == "Downloads", { screen = "Downloads" }, { Icon(Icons.Default.Download, null) })
                    PdmNavigationBarItem(screen == "Browser", { screen = "Browser" }, { Icon(Icons.Default.Web, null) })
                    PdmNavigationBarItem(screen == "Settings", { screen = "Settings" }, { Icon(Icons.Default.Settings, null) })
                }
            }) { pad ->
                Box(Modifier.fillMaxSize().padding(pad)) {
                    when (screen) {
                        "Home" -> Home(homeUrl, { homeUrl = it }, { browserUrl = if (homeUrl.startsWith("http", true)) homeUrl else "https://www.google.com/search?q=${Uri.encode(homeUrl)}"; screen = "Browser" }, { downloadUrl = homeUrl }, { screen = "Browser" }, { screen = "Downloads" }, onTorrent)
                        "Downloads" -> Downloads(activity)
                        "Browser" -> Browser(browserUrl, { browserUrl = it }, { downloadUrl = it }) { screen = "Home" }
                        else -> Settings(onStorage, scope)
                    }
                }
            }
        }
        if (downloadUrl != null) AlertDialog(onDismissRequest = { downloadUrl = null }, title = { Text("Download file") }, text = { Text(downloadUrl ?: "", maxLines = 4, overflow = TextOverflow.Ellipsis) }, confirmButton = { Button(onClick = { val u = downloadUrl ?: return@Button; downloadUrl = null; if (Build.VERSION.SDK_INT >= 30 && !PdmStorage.hasPublicAccess()) requestStorage(u) else onDownload(u); screen = "Downloads" }) { Text("START") } }, dismissButton = { TextButton(onClick = { downloadUrl = null }) { Text("CANCEL") } })
        if (exit) AlertDialog(onDismissRequest = { exit = false }, title = { Text("Exit PDM?") }, text = { Text("Are you sure you want to exit?") }, confirmButton = { TextButton(onClick = { activity.finish() }) { Text("YES") } }, dismissButton = { TextButton(onClick = { exit = false }) { Text("NO") } })
    }
}

@Composable private fun Home(url: String, setUrl: (String) -> Unit, go: () -> Unit, start: () -> Unit, browser: () -> Unit, downloads: () -> Unit, torrent: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Menu, null, Modifier.size(30.dp), tint = Dark)
            OutlinedTextField(url, { setUrl(it) }, Modifier.weight(1f).height(58.dp).padding(start = 8.dp), singleLine = true, shape = RoundedCornerShape(18.dp), placeholder = { Text("Paste URL or search") }, leadingIcon = { Icon(Icons.Default.Language, null) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go() }), trailingIcon = { IconButton(onClick = go) { Icon(Icons.Default.ArrowForward, null, tint = Blue) } })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Download, null, Modifier.size(44.dp), tint = Green); Spacer(Modifier.width(12.dp)); Column { Text("Palia Download Manager", fontSize = 20.sp, color = Dark); Text("Fast • Smart • Secure", color = Muted) } }
        Card(Modifier.fillMaxWidth().padding(18.dp), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Mint)) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Ready to download?", fontSize = 26.sp, color = Dark); Text("Paste a direct HTTP/HTTPS file link, .torrent or magnet link.", color = Muted, fontSize = 16.sp); Button(onClick = start, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Start Download") }; OutlinedButton(onClick = torrent, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(28.dp)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Open .torrent file") }; OutlinedButton(onClick = downloads, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(28.dp)) { Icon(Icons.Default.List, null); Spacer(Modifier.width(8.dp)); Text("View Downloads") } } }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedButton(onClick = browser, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(26.dp)) { Icon(Icons.Default.Web, null); Spacer(Modifier.width(6.dp)); Text("Browser") }; OutlinedButton(onClick = downloads, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(26.dp)) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Downloads") } }
    }
}

@Composable private fun Browser(initial: String, onUrl: (String) -> Unit, onDownload: (String) -> Unit, onExit: () -> Unit) {
    var field by remember(initial) { mutableStateOf(initial) }
    var web by remember { mutableStateOf<WebView?>(null) }
    BackHandler { if (web?.canGoBack() == true) web?.goBack() else onExit() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Menu, null, Modifier.size(30.dp), tint = Dark)
            OutlinedTextField(field, { field = it; onUrl(it) }, Modifier.weight(1f).height(58.dp).padding(start = 8.dp), singleLine = true, shape = RoundedCornerShape(18.dp), placeholder = { Text("Search or enter address") }, leadingIcon = { Icon(Icons.Default.Web, "Browser") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { loadWeb(field, web) }), trailingIcon = { IconButton(onClick = { loadWeb(field, web) }) { Icon(Icons.Default.ArrowForward, null, tint = Blue) } })
        }
        AndroidView(factory = { ctx -> WebView(ctx).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.loadsImagesAutomatically = true; isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isScrollbarFadingEnabled = true; webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false; override fun onPageFinished(view: WebView, url: String) { field = url; onUrl(url) } }; setDownloadListener { u, _, _, _, _ -> if (!u.isNullOrBlank()) onDownload(u) }; loadUrl(initial); web = this } }, Modifier.fillMaxSize())
    }
}
private fun loadWeb(value: String, web: WebView?) { val v = value.trim(); if (v.isBlank()) return; web?.loadUrl(if (v.startsWith("http://", true) || v.startsWith("https://", true)) v else "https://www.google.com/search?q=${Uri.encode(v)}") }

@Composable private fun Downloads(context: Context) {
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(Unit) { while (true) { files = PdmStorage.allFiles(context); delay(700) } }
    Column(Modifier.fillMaxSize()) { Text("Downloads", fontSize = 24.sp, color = Dark, modifier = Modifier.padding(20.dp)); LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(files, key = { it.absolutePath }) { f -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF4FFFB))) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.InsertDriveFile, null, Modifier.size(42.dp), tint = Blue); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${f.length()} bytes", color = Muted) }; IconButton(onClick = { f.delete() }) { Icon(Icons.Default.Delete, null) } } } }; if (files.isEmpty()) item { Text("No downloads yet", color = Muted, modifier = Modifier.padding(20.dp)) } } }
}

@Composable private fun Settings(onStorage: () -> Unit, scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current
    var checking by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("Not checked") }
    var version by rememberSaveable { mutableStateOf("") }
    var updateUrl by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) { Text("Settings", fontSize = 24.sp, color = Dark, modifier = Modifier.padding(20.dp)); Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Button(onClick = onStorage, Modifier.fillMaxWidth()) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text("Storage access") }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("App updates", fontSize = 18.sp, color = Dark); Text(if (status == "Not checked") "Check your Palia website for updates." else status, color = Muted); if (version.isNotBlank()) Text("Website version: $version", color = Dark); Button(enabled = !checking, onClick = { scope.launch { checking = true; status = "Checking website…"; val r = websiteUpdate(); if (r == null) { status = "Could not check for updates"; version = ""; updateUrl = "" } else if (r.available) { status = "Update available • v${r.version}"; version = r.version; updateUrl = r.url ?: "" } else { status = "Up to date • v${BuildConfig.VERSION_NAME}"; version = r.version; updateUrl = "" }; checking = false } }, Modifier.fillMaxWidth()) { Text(if (checking) "Checking…" else "Check for updates") }; if (updateUrl.isNotBlank()) Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))) }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Open update") } } }
        Text("PDM • v${BuildConfig.VERSION_NAME}", color = Muted); Text("Developer by shanpalia", color = Muted)
    } }
}

@Composable private fun Splash() { Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { AndroidView(factory = { ImageView(it).apply { setImageResource(R.mipmap.ic_pdm_logo) } }, Modifier.size(104.dp)); Text("PDM", fontSize = 28.sp, color = Dark); Text("By PaliaAPK HUB", color = Green); Text("Developer by shanpalia", color = Muted, fontSize = 13.sp); LinearProgressIndicator(Modifier.width(150.dp), color = Green) } } }

@Composable private fun Permission(step: Int, notification: () -> Unit, storage: () -> Unit, skip: () -> Unit) { val n = step == 1; Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Card(Modifier.padding(24.dp), colors = CardDefaults.cardColors(containerColor = Mint)) { Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(if (n) Icons.Default.Notifications else Icons.Default.Folder, null, Modifier.size(56.dp), tint = Green); Text(if (n) "Allow notifications" else "Allow storage access"); Button(onClick = if (n) notification else storage) { Text("Continue") }; TextButton(onClick = skip) { Text("Skip") } } } } }
