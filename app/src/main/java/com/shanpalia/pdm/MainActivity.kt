package com.shanpalia.pdm

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private val Mint = Color(0xFFE9FFF6)
private val MintStrong = Color(0xFFCFF8E9)
private val Green = Color(0xFF16B978)
private val Blue = Color(0xFF0B62D6)
private val Dark = Color(0xFF12231E)
private val Muted = Color(0xFF6F7D77)
private val Soft = Color(0xFFF5F8F7)
private const val CURRENT_VERSION = "1.2.0"
private const val UPDATE_MANIFEST = "https://shanpalia.github.io/WebsitePaliaAPK_V.2/pdm-update.json"
private const val WEBSITE_URL = "https://shanpalia.github.io/WebsitePaliaAPK_V.2/"

private data class HistoryItem(val url: String, val time: Long)

private class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("pdm_history", Context.MODE_PRIVATE)
    fun all(): List<HistoryItem> {
        val root = try { JSONObject(prefs.getString("data", "{\"items\":[]}") ?: "{\"items\":[]}") } catch (_: Throwable) { JSONObject() }
        val a = root.optJSONArray("items") ?: return emptyList()
        return buildList { for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; o.optString("url").takeIf { it.isNotBlank() }?.let { add(HistoryItem(it, o.optLong("time"))) } } }
    }
    fun add(value: String) {
        val v = value.trim(); if (v.isBlank()) return
        val list = all().filterNot { it.url == v }.toMutableList(); list.add(0, HistoryItem(v, System.currentTimeMillis()))
        val a = org.json.JSONArray(); list.take(100).forEach { a.put(JSONObject().apply { put("url", it.url); put("time", it.time) }) }
        prefs.edit().putString("data", JSONObject().put("items", a).toString()).apply()
    }
    fun remove(value: String) { val a = org.json.JSONArray(); all().filterNot { it.url == value }.forEach { a.put(JSONObject().apply { put("url", it.url); put("time", it.time) }) }; prefs.edit().putString("data", JSONObject().put("items", a).toString()).apply() }
    fun clear() { prefs.edit().remove("data").apply() }
}

class MainActivity : ComponentActivity() {
    private var pendingDownload: String? = null
    private var waitingForStorage = false
    private val torrentPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val target = File(cacheDir, "selected-${System.currentTimeMillis()}.torrent")
            contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            startDownloadService(DownloadService.ACTION_TORRENT_FILE, null, target.absolutePath)
        } catch (_: Throwable) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PdmApp(this, extractIncoming(intent), ::startUrlDownload, { torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) }, ::openStorageSettings, ::requestStorageAndDownload) }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForStorage && PdmStorage.hasPublicAccess()) {
            waitingForStorage = false
            pendingDownload?.also { pendingDownload = null; startUrlDownload(it) }
        }
    }

    private fun requestStorageAndDownload(link: String) {
        pendingDownload = link
        waitingForStorage = true
        openStorageSettings()
    }

    private fun extractIncoming(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND) return intent.getStringExtra(Intent.EXTRA_TEXT)
        return intent?.data?.takeIf { it.scheme in listOf("http", "https", "magnet") }?.toString()
    }

    private fun startUrlDownload(value: String) {
        val clean = value.trim(); if (clean.isBlank()) return
        when {
            clean.startsWith("magnet:", true) -> startDownloadService(DownloadService.ACTION_TORRENT_MAGNET, clean, null)
            clean.substringBefore('?').lowercase().endsWith(".torrent") -> startDownloadService(DownloadService.ACTION_TORRENT_URL, clean, null)
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> startDownloadService(DownloadService.ACTION_HTTP, clean, null)
        }
    }

    private fun startDownloadService(action: String, url: String?, path: String?) {
        val intent = Intent(this, DownloadService::class.java).apply { this.action = action; if (url != null) putExtra(DownloadService.EXTRA_URL, url); if (path != null) putExtra(DownloadService.EXTRA_PATH, path) }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun openStorageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}

@Composable
private fun PdmApp(activity: ComponentActivity, incomingUrl: String?, onDownload: (String) -> Unit, onPickTorrent: () -> Unit, onStorageSettings: () -> Unit, requestStorage: (String) -> Unit) {
    val context = activity
    val history = remember(context) { HistoryStore(context) }
    var historyItems by remember { mutableStateOf(history.all()) }
    var screen by rememberSaveable { mutableStateOf("Home") }
    var address by rememberSaveable { mutableStateOf(incomingUrl.orEmpty()) }
    var browserUrl by rememberSaveable { mutableStateOf(incomingUrl?.takeIf { it.startsWith("http", true) } ?: "https://www.google.com") }
    var splash by remember { mutableStateOf(true) }
    var storagePrompt by remember { mutableStateOf<String?>(null) }
    var exitDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { delay(1800); splash = false }
    LaunchedEffect(splash) { if (!splash && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 501) }
    BackHandler(enabled = !splash) { if (screen == "Home") exitDialog = true else screen = "Home" }

    fun saveHistory(value: String) { history.add(value); historyItems = history.all() }
    fun requestDownload(value: String) { val v = value.trim(); if (v.isBlank()) return; saveHistory(v); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !PdmStorage.hasPublicAccess()) storagePrompt = v else onDownload(v) }
    fun openAddress(value: String) {
        val v = value.trim(); if (v.isBlank()) return
        if (v.startsWith("magnet:", true) || isDownloadLink(v)) requestDownload(v)
        else if (v.startsWith("http://", true) || v.startsWith("https://", true)) { saveHistory(v); browserUrl = v; screen = "Browser" }
        else { saveHistory(v); browserUrl = "https://www.google.com/search?q=${Uri.encode(v)}"; screen = "Browser" }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White, surface = Color.White)) {
        if (splash) { SplashScreen(); return@MaterialTheme }
        Scaffold(containerColor = Color.White, bottomBar = {
            NavigationBar(containerColor = Color(0xFFF4FFFB), tonalElevation = 0.dp) {
                navItem("Home", Icons.Default.Home, screen == "Home") { screen = "Home" }
                navItem("Downloads", Icons.Default.Download, screen == "Downloads") { screen = "Downloads" }
                navItem("Browser", Icons.Default.Web, screen == "Browser") { screen = "Browser" }
                navItem("Settings", Icons.Default.Settings, screen == "Settings") { screen = "Settings" }
            }
        }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (screen) {
                    "Home" -> HomeScreen(context, address, { address = it }, { openAddress(address) }, { screen = "Browser" }, { screen = "Downloads" }, onPickTorrent, { requestDownload(address) }, historyItems, { address = it; openAddress(it) }, { history.remove(it); historyItems = history.all() }, { history.clear(); historyItems = emptyList() })
                    "Downloads" -> DownloadsScreen(context)
                    "Browser" -> BrowserScreen(context, browserUrl, historyItems, { saveHistory(it) }, { requestDownload(it) }, { browserUrl = it })
                    else -> SettingsScreen(historyItems, { history.clear(); historyItems = emptyList() }, onStorageSettings)
                }
            }
        }
        storagePrompt?.let { link ->
            AlertDialog(onDismissRequest = { storagePrompt = null }, title = { Text("Storage access") }, text = { Text("Allow PDM storage access to save downloads in Download/PDM and its category folders.") }, confirmButton = { Button(onClick = { storagePrompt = null; requestStorage(link) }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Allow") } }, dismissButton = { TextButton(onClick = { storagePrompt = null }) { Text("Cancel") } })
        }
        if (exitDialog) AlertDialog(onDismissRequest = { exitDialog = false }, title = { Text("Exit Palia Download Manager?") }, text = { Text("Are you sure you want to exit?") }, confirmButton = { TextButton(onClick = { exitDialog = false; activity.finish() }) { Text("YES") } }, dismissButton = { TextButton(onClick = { exitDialog = false }) { Text("NO") } })
    }
}

@Composable private fun RowScope.navItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) { NavigationBarItem(selected = selected, onClick = onClick, icon = { Icon(icon, null) }, label = { Text(label) }) }

@Composable private fun SplashScreen() { Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Image(androidx.compose.ui.res.painterResource(R.drawable.ic_pdm_logo), "PDM", Modifier.size(210.dp)); Text("Palia Download Manager", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text("FAST  •  SMART  •  SECURE", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold); LinearProgressIndicator(Modifier.width(250.dp).height(6.dp).clip(RoundedCornerShape(50)), color = Green, trackColor = Color(0xFFE8EEF0)); Text("Developer By Shanpalia", color = Muted, fontSize = 13.sp) } } }

@Composable private fun HomeScreen(context: Context, url: String, onUrl: (String) -> Unit, onGo: () -> Unit, onBrowser: () -> Unit, onDownloads: () -> Unit, onTorrent: () -> Unit, onAddDownload: () -> Unit, historyItems: List<HistoryItem>, onHistoryClick: (String) -> Unit, onHistoryDelete: (String) -> Unit, onClearHistory: () -> Unit) {
    var field by remember(url) { mutableStateOf(TextFieldValue(url, TextRange(url.length))) }
    LaunchedEffect(url) { if (field.text != url) field = TextFieldValue(url, TextRange(url.length)) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedTextField(value = field, onValueChange = { field = it; onUrl(it.text) }, modifier = Modifier.fillMaxWidth(), singleLine = true, maxLines = 1, shape = RoundedCornerShape(18.dp), placeholder = { Text("Paste here", maxLines = 1) }, leadingIcon = { Icon(Icons.Default.Language, null, tint = Dark) }, trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) { if (field.text.isNotBlank()) IconButton(onClick = { field = TextFieldValue(""); onUrl("") }) { Icon(Icons.Default.Clear, "Clear") }; TextButton(onClick = { val p = readClipboard(context); field = TextFieldValue(p, TextRange(p.length)); onUrl(p) }) { Text("PASTE", color = Green, fontWeight = FontWeight.Bold) }; FilledIconButton(onClick = onGo, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Blue)) { Icon(Icons.Default.ArrowForward, "Go") } } }) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Image(androidx.compose.ui.res.painterResource(R.drawable.ic_pdm_logo), "PDM", Modifier.size(68.dp)); Spacer(Modifier.width(10.dp)); Column { Text("Palia Download Manager", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text("Fast • Smart • Secure", color = Muted) } } }
        item { Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Ready to download?", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text("HTTP/HTTPS files, .torrent and magnet links", color = Muted); Button(onClick = onAddDownload, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Start Download") } } } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { SmallAction("Browser", Icons.Default.Web, Modifier.weight(1f), onBrowser); SmallAction("Downloads", Icons.Default.Download, Modifier.weight(1f), onDownloads); SmallAction("Torrent", Icons.Default.Download, Modifier.weight(1f), onTorrent) } }
        item { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) { Text("Recent History", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Dark, modifier = Modifier.weight(1f)); if (historyItems.isNotEmpty()) TextButton(onClick = onClearHistory) { Text("Clear") } } }
        if (historyItems.isEmpty()) item { Text("Your recent links will appear here.", color = Muted) } else items(historyItems.take(10), key = { it.url }) { item -> Card(colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Text(item.url, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, color = Dark); IconButton(onClick = { onHistoryClick(item.url) }) { Icon(Icons.Default.OpenInBrowser, "Open") }; IconButton(onClick = { onHistoryDelete(item.url) }) { Icon(Icons.Default.DeleteOutline, "Delete") } } } }
    }
}

@Composable private fun SmallAction(title: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) { Card(onClick = onClick, modifier = modifier.height(82.dp), colors = CardDefaults.cardColors(containerColor = MintStrong), shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(icon, title, tint = Green); Spacer(Modifier.height(4.dp)); Text(title, fontWeight = FontWeight.Bold, color = Dark, fontSize = 12.sp) } } }

@Composable private fun DownloadsScreen(context: Context) { var files by remember { mutableStateOf(PdmStorage.allFiles(context)) }; LaunchedEffect(Unit) { while (true) { files = PdmStorage.allFiles(context); delay(1500) } }; LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Text("Downloads", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text("Download/PDM", color = Muted) }; if (files.isEmpty()) item { Text("No downloads yet.", color = Muted) }; items(files, key = { it.absolutePath }) { file -> Card(colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(file.name, fontWeight = FontWeight.Bold, color = Dark); Text(file.parent ?: "", color = Muted, fontSize = 12.sp); Text(formatSize(file.length()), color = Muted, fontSize = 12.sp) } } } } }

@Composable private fun BrowserScreen(context: Context, initialUrl: String, historyItems: List<HistoryItem>, onVisit: (String) -> Unit, onDownload: (String) -> Unit, onOpen: (String) -> Unit) {
    var address by remember(initialUrl) { mutableStateOf(TextFieldValue(initialUrl, TextRange(initialUrl.length))) }
    var current by remember(initialUrl) { mutableStateOf(initialUrl) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Surface(shadowElevation = 2.dp, color = Color.White) { Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { webView?.goBack() }) { Icon(Icons.Default.ArrowBack, "Back") }; OutlinedTextField(value = address, onValueChange = { address = it }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(18.dp), placeholder = { Text("Search or enter address") }); IconButton(onClick = { showHistory = !showHistory }) { Icon(Icons.Default.History, "History") }; IconButton(onClick = { val u = normalizeUrl(address.text); current = u; onOpen(u); onVisit(u) }) { Icon(Icons.Default.ArrowForward, "Go") } } }
        if (showHistory) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp).background(Color.White).padding(8.dp)) { items(historyItems.take(8)) { TextButton(onClick = { address = TextFieldValue(it.url, TextRange(it.url.length)); current = normalizeUrl(it.url); showHistory = false }) { Text(it.url, maxLines = 1, overflow = TextOverflow.Ellipsis) } } }
        AndroidView(factory = { ctx -> WebView(ctx).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean { current = request.url.toString(); address = TextFieldValue(current, TextRange(current.length)); onVisit(current); return false } }; setDownloadListener { url, _, _, _, _ -> onDownload(url) }; loadUrl(current); webView = this }, update = { if (it.url != current && current.isNotBlank()) it.loadUrl(current) }, modifier = Modifier.fillMaxSize())
    }
}

@Composable private fun SettingsScreen(historyItems: List<HistoryItem>, onClearHistory: () -> Unit, onStorageSettings: () -> Unit) {
    var status by remember { mutableStateOf("Not checked") }; var checking by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text("Palia Download Manager $CURRENT_VERSION", color = Muted) }
        item { Card(colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("App update", fontWeight = FontWeight.Bold); Text(status, color = Muted); Button(enabled = !checking, onClick = { checking = true; status = "Checking…"; scope.launch { status = checkUpdate(); checking = false } }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text(if (checking) "Checking…" else "Check for updates") } } } }
        item { Card(onClick = onStorageSettings, colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Storage access", fontWeight = FontWeight.Bold); Text("Download/PDM category folders", color = Muted) } } }
        item { Card(onClick = onClearHistory, colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Clear history", fontWeight = FontWeight.Bold); Text("${historyItems.size} saved items", color = Muted) } } }
        item { Text("Update source: $WEBSITE_URL", color = Muted, fontSize = 12.sp) }
    }
}

private suspend fun checkUpdate(): String = withContext(Dispatchers.IO) { try { val response = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build().newCall(Request.Builder().url(UPDATE_MANIFEST).build()).execute(); if (!response.isSuccessful) return@withContext "Could not check update (${response.code})"; val json = JSONObject(response.body?.string().orEmpty()); val latest = json.optString("version", json.optString("latestVersion", "")); if (latest.isBlank()) "Update information unavailable" else if (compareVersions(latest, CURRENT_VERSION) > 0) "Update available: $latest" else "PDM is up to date" } catch (_: Throwable) { "Update check failed" } }
private fun compareVersions(a: String, b: String): Int { val x = a.trimStart('v','V').split('.').map { it.toIntOrNull() ?: 0 }; val y = b.trimStart('v','V').split('.').map { it.toIntOrNull() ?: 0 }; for (i in 0 until maxOf(x.size, y.size)) { val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 }); if (c != 0) return c }; return 0 }
private fun readClipboard(context: Context): String = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
private fun isDownloadLink(value: String): Boolean { val c = value.substringBefore('?').substringBefore('#').lowercase(); return listOf(".zip",".rar",".7z",".tar",".gz",".apk",".apks",".xapk",".mp4",".mkv",".avi",".mov",".webm",".mp3",".m4a",".wav",".flac",".jpg",".jpeg",".png",".webp",".gif",".pdf",".doc",".docx",".xls",".xlsx",".ppt",".pptx",".txt",".torrent").any(c::endsWith) }
private fun normalizeUrl(value: String): String { val v = value.trim(); return if (v.startsWith("http://", true) || v.startsWith("https://", true)) v else "https://www.google.com/search?q=${Uri.encode(v)}" }
private fun formatSize(bytes: Long): String = when { bytes >= 1073741824L -> "%.1f GB".format(bytes / 1073741824.0); bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576.0); bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0); else -> "$bytes B" }
