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
private val BrandPurple = Color(0xFFE8D9FF)
private const val UPDATE_URL = "https://shanpalia.github.io/WebsitePaliaAPK_V.2/pdm-update.json"

private data class UpdateInfo(val available: Boolean, val version: String, val url: String?)

private suspend fun checkPdmUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
    try {
        val c = URL(UPDATE_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        c.disconnect()
        UpdateInfo(json.optLong("versionCode", 0L) > BuildConfig.VERSION_CODE.toLong(), json.optString("version", ""), json.optString("downloadUrl", "").ifBlank { null })
    } catch (_: Throwable) { null }
}

class PdmMainActivity : ComponentActivity() {
    private var pending: String? = null
    private var waitingStorage = false
    private val torrentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val file = File(cacheDir, "selected-${System.currentTimeMillis()}.torrent")
            contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            launchDownload(DownloadService.ACTION_TORRENT_FILE, null, file.absolutePath)
        } catch (_: Throwable) { }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            PdmApp(
                activity = this,
                onDownload = ::downloadUrl,
                onStorage = ::openStorage,
                onRequestStorage = ::requestStorage,
                onTorrent = { torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingStorage && PdmStorage.hasPublicAccess()) {
            waitingStorage = false
            pending?.let { pending = null; downloadUrl(it) }
        }
    }

    private fun requestStorage(url: String) { pending = url; waitingStorage = true; openStorage() }
    private fun downloadUrl(value: String) {
        val url = value.trim()
        if (url.isBlank()) return
        when {
            url.startsWith("magnet:", true) -> launchDownload(DownloadService.ACTION_TORRENT_MAGNET, url, null)
            url.startsWith("http://", true) || url.startsWith("https://", true) -> launchDownload(DownloadService.ACTION_HTTP, url, null)
        }
    }
    private fun launchDownload(action: String, url: String?, path: String?) {
        val intent = Intent(this, DownloadService::class.java).apply {
            this.action = action
            if (url != null) putExtra(DownloadService.EXTRA_URL, url)
            if (path != null) putExtra(DownloadService.EXTRA_PATH, path)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }
    private fun openStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }
}

@Composable
private fun PdmApp(activity: ComponentActivity, onDownload: (String) -> Unit, onStorage: () -> Unit, onRequestStorage: (String) -> Unit, onTorrent: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf("Home") }
    var url by rememberSaveable { mutableStateOf("") }
    var browserUrl by rememberSaveable { mutableStateOf("https://www.google.com") }
    var splash by remember { mutableStateOf(true) }
    var permission by rememberSaveable { mutableIntStateOf(0) }
    var ready by rememberSaveable { mutableStateOf(false) }
    var dialogUrl by remember { mutableStateOf<String?>(null) }
    var exit by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = if (Build.VERSION.SDK_INT >= 30 && !PdmStorage.hasPublicAccess()) 2 else 0; ready = permission == 0 }

    LaunchedEffect(Unit) {
        delay(900)
        splash = false
        when {
            Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> permission = 1
            Build.VERSION.SDK_INT >= 30 && !PdmStorage.hasPublicAccess() -> permission = 2
            else -> ready = true
        }
    }
    LaunchedEffect(permission) {
        if (permission == 2) { while (!PdmStorage.hasPublicAccess()) delay(400); permission = 0; ready = true }
    }

    BackHandler(enabled = !splash) {
        if (dialogUrl != null) { dialogUrl = null; return@BackHandler }
        if (screen == "Browser") return@BackHandler
        if (screen != "Home") screen = "Home" else exit = true
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White, surface = Color.White)) {
        if (splash) {
            Splash()
        } else if (!ready) {
            PermissionScreen(
                step = permission,
                notification = { if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else permission = 2 },
                storage = onStorage,
                skip = { permission = 0; ready = true }
            )
        } else {
            Scaffold(
                containerColor = Color.White,
                bottomBar = {
                    NavigationBar(containerColor = Color(0xFFF4FFFB), tonalElevation = 0.dp) {
                        PdmNavigationBarItem(selected = screen == "Home", onClick = { screen = "Home" }, icon = { Icon(Icons.Default.Home, null) })
                        PdmNavigationBarItem(selected = screen == "Downloads", onClick = { screen = "Downloads" }, icon = { Icon(Icons.Default.Download, null) })
                        PdmNavigationBarItem(selected = screen == "Browser", onClick = { screen = "Browser" }, icon = { Icon(Icons.Default.Language, null) })
                        PdmNavigationBarItem(selected = screen == "Settings", onClick = { screen = "Settings" }, icon = { Icon(Icons.Default.Settings, null) })
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    if (screen == "Home") {
                        HomeScreen(url, { url = it }, { browserUrl = if (url.startsWith("http://", true) || url.startsWith("https://", true)) url else "https://www.google.com/search?q=${Uri.encode(url)}"; screen = "Browser" }, { dialogUrl = url }, { screen = "Browser" }, { screen = "Downloads" }, onTorrent)
                    } else if (screen == "Downloads") {
                        DownloadsScreen(activity)
                    } else if (screen == "Browser") {
                        BrowserScreen(browserUrl, { browserUrl = it }, { dialogUrl = it }) { screen = "Home" }
                    } else {
                        SettingsScreen(onStorage)
                    }
                }
            }
        }

        if (dialogUrl != null) {
            AlertDialog(
                onDismissRequest = { dialogUrl = null },
                title = { Text("Download file") },
                text = { Text(dialogUrl ?: "", maxLines = 4, overflow = TextOverflow.Ellipsis) },
                confirmButton = {
                    Button(onClick = {
                        val value = dialogUrl ?: return@Button
                        dialogUrl = null
                        if (Build.VERSION.SDK_INT >= 30 && !PdmStorage.hasPublicAccess()) onRequestStorage(value) else onDownload(value)
                        screen = "Downloads"
                    }) { Text("START") }
                },
                dismissButton = { TextButton(onClick = { dialogUrl = null }) { Text("CANCEL") } }
            )
        }
        if (exit) {
            AlertDialog(
                onDismissRequest = { exit = false },
                title = { Text("Exit PDM?") },
                text = { Text("Are you sure you want to exit?") },
                confirmButton = { TextButton(onClick = { activity.finish() }) { Text("YES") } },
                dismissButton = { TextButton(onClick = { exit = false }) { Text("NO") } }
            )
        }
    }
}

@Composable private fun HomeScreen(url: String, setUrl: (String) -> Unit, go: () -> Unit, start: () -> Unit, browser: () -> Unit, downloads: () -> Unit, torrent: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Menu, null, Modifier.size(30.dp), tint = Dark)
            OutlinedTextField(value = url, onValueChange = setUrl, modifier = Modifier.weight(1f).height(58.dp).padding(start = 8.dp), singleLine = true, shape = RoundedCornerShape(18.dp), placeholder = { Text("Paste URL or search") }, leadingIcon = { Icon(Icons.Default.Language, null) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go() }), trailingIcon = { IconButton(onClick = go) { Icon(Icons.Default.ArrowForward, null, tint = Blue) } })
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Download, null, Modifier.size(44.dp), tint = Green); Spacer(Modifier.width(12.dp)); Column { Text("Palia Download Manager", fontSize = 20.sp, color = Dark); Text("Fast • Smart • Secure", color = Muted) } }
        Card(modifier = Modifier.fillMaxWidth().padding(18.dp), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Mint)) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Ready to download?", fontSize = 26.sp, color = Dark); Text("Paste a direct HTTP/HTTPS file link, .torrent or magnet link.", color = Muted, fontSize = 16.sp); Button(onClick = start, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Start Download") }; OutlinedButton(onClick = torrent, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(28.dp)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Open .torrent file") }; OutlinedButton(onClick = downloads, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(28.dp)) { Icon(Icons.Default.List, null); Spacer(Modifier.width(8.dp)); Text("View Downloads") } } }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedButton(onClick = browser, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(26.dp)) { Icon(Icons.Default.Web, null); Spacer(Modifier.width(6.dp)); Text("Browser") }; OutlinedButton(onClick = downloads, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(26.dp)) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Downloads") } }
    }
}

@Composable private fun BrowserScreen(initial: String, onUrl: (String) -> Unit, onDownload: (String) -> Unit, onExit: () -> Unit) {
    var field by remember(initial) { mutableStateOf(initial) }
    var web by remember { mutableStateOf<WebView?>(null) }
    BackHandler { if (web?.canGoBack() == true) web?.goBack() else onExit() }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Menu, null, Modifier.size(30.dp), tint = Dark)
            OutlinedTextField(value = field, onValueChange = { field = it; onUrl(it) }, modifier = Modifier.weight(1f).height(58.dp).padding(start = 8.dp), singleLine = true, shape = RoundedCornerShape(18.dp), placeholder = { Text("Search or enter address") }, leadingIcon = { Icon(Icons.Default.Web, "Browser") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { loadBrowser(field, web) }), trailingIcon = { IconButton(onClick = { loadBrowser(field, web) }) { Icon(Icons.Default.ArrowForward, null, tint = Blue) } })
        }
        AndroidView(factory = { ctx -> WebView(ctx).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.loadsImagesAutomatically = true; isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false; override fun onPageFinished(view: WebView, pageUrl: String) { field = pageUrl; onUrl(pageUrl) } }; setDownloadListener { u, _, _, _, _ -> if (!u.isNullOrBlank()) onDownload(u) }; loadUrl(initial); web = this } }, modifier = Modifier.fillMaxSize())
    }
}

private fun loadBrowser(value: String, web: WebView?) { val v = value.trim(); if (v.isBlank()) return; web?.loadUrl(if (v.startsWith("http://", true) || v.startsWith("https://", true)) v else "https://www.google.com/search?q=${Uri.encode(v)}") }

@Composable private fun DownloadsScreen(context: Context) {
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(Unit) { while (true) { files = PdmStorage.allFiles(context); delay(700) } }
    Column(modifier = Modifier.fillMaxSize()) { Text("Downloads", fontSize = 24.sp, color = Dark, modifier = Modifier.padding(20.dp)); LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(files, key = { it.absolutePath }) { file -> Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF4FFFB))) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.InsertDriveFile, null, Modifier.size(42.dp), tint = Blue); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${file.length()} bytes", color = Muted) }; IconButton(onClick = { file.delete() }) { Icon(Icons.Default.Delete, null) } } } }; if (files.isEmpty()) item { Text("No downloads yet", color = Muted, modifier = Modifier.padding(20.dp)) } } } }

@Composable private fun SettingsScreen(onStorage: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("Not checked") }
    var version by rememberSaveable { mutableStateOf("") }
    var updateUrl by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.White).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(14.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Mint)
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                AndroidView(factory = { ImageView(it).apply { setImageResource(R.mipmap.ic_pdm_logo) } }, modifier = Modifier.size(62.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("PDM", fontSize = 27.sp, color = Dark)
                    Text("PaliaAPK HUB", fontSize = 17.sp, color = Green)
                    Text("Download Manager", fontSize = 13.sp, color = Muted)
                }
            }
        }

        Text("Settings", fontSize = 30.sp, color = Dark)

        Button(
            onClick = onStorage,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(27.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green)
        ) {
            Icon(Icons.Default.Folder, null)
            Spacer(Modifier.width(8.dp))
            Text("Storage access", fontSize = 16.sp)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Mint),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SystemUpdate, null, tint = Green, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("App updates", fontSize = 20.sp, color = Dark)
                        Text("Official Palia website", fontSize = 13.sp, color = Muted)
                    }
                }
                Text(
                    when {
                        status == "Not checked" -> "Check your Palia website for the latest PDM release."
                        status.startsWith("Update available") -> status
                        else -> status
                    },
                    color = if (status.startsWith("Update available")) Blue else Muted,
                    fontSize = 15.sp
                )
                if (version.isNotBlank()) {
                    Text("Website version: $version", color = Dark, fontSize = 14.sp)
                }
                Button(
                    enabled = !checking,
                    onClick = {
                        scope.launch {
                            checking = true
                            status = "Checking website…"
                            val r = checkPdmUpdate()
                            if (r == null) {
                                status = "Could not check for updates"
                                version = ""
                                updateUrl = ""
                            } else if (r.available) {
                                status = "Update available • v${r.version}"
                                version = r.version
                                updateUrl = r.url ?: ""
                            } else {
                                status = "Up to date • v${BuildConfig.VERSION_NAME}"
                                version = r.version
                                updateUrl = ""
                            }
                            checking = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (checking) "Checking…" else "Check for updates")
                }
                if (updateUrl.isNotBlank()) {
                    Button(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))) },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                    ) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open update")
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FBFA))
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("PDM", fontSize = 18.sp, color = Dark)
                Text("By PaliaAPK HUB", color = Green, fontSize = 16.sp)
                Text("Developer by shanpalia", color = Muted, fontSize = 14.sp)
                Text("Version ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable private fun Splash() { Box(modifier = Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { AndroidView(factory = { ImageView(it).apply { setImageResource(R.mipmap.ic_pdm_logo) } }, modifier = Modifier.size(104.dp)); Text("PDM", fontSize = 28.sp, color = Dark); Text("By PaliaAPK HUB", color = Green); Text("Developer by shanpalia", color = Muted, fontSize = 13.sp); LinearProgressIndicator(modifier = Modifier.width(150.dp), color = Green) } } }

@Composable private fun PermissionScreen(step: Int, notification: () -> Unit, storage: () -> Unit, skip: () -> Unit) { val n = step == 1; Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Card(modifier = Modifier.padding(24.dp), colors = CardDefaults.cardColors(containerColor = Mint)) { Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(if (n) Icons.Default.Notifications else Icons.Default.Folder, null, Modifier.size(56.dp), tint = Green); Text(if (n) "Allow notifications" else "Allow storage access"); Button(onClick = if (n) notification else storage) { Text("Continue") }; TextButton(onClick = skip) { Text("Skip") } } } } }