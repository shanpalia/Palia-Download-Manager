package com.shanpalia.pdm

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
private const val CURRENT_VERSION = "1.1.0"

private data class HistoryItem(val url: String, val time: Long)

private class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("pdm_history", Context.MODE_PRIVATE)
    private val key = "items"

    fun all(): List<HistoryItem> {
        val array = try { JSONArray(prefs.getString(key, "[]") ?: "[]") } catch (_: Throwable) { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val u = o.optString("url")
                if (u.isNotBlank()) add(HistoryItem(u, o.optLong("time")))
            }
        }
    }

    fun add(url: String) {
        val clean = url.trim()
        if (clean.isBlank()) return
        val items = all().filterNot { it.url == clean }.toMutableList()
        items.add(0, HistoryItem(clean, System.currentTimeMillis()))
        val out = JSONArray()
        items.take(100).forEach { item ->
            out.put(JSONObject().apply { put("url", item.url); put("time", item.time) })
        }
        prefs.edit().putString(key, out.toString()).apply()
    }

    fun clear() = prefs.edit().remove(key).apply()

    fun remove(url: String) {
        val out = JSONArray()
        all().filterNot { it.url == url }.forEach {
            out.put(JSONObject().apply { put("url", it.url); put("time", it.time) })
        }
        prefs.edit().putString(key, out.toString()).apply()
    }
}

class MainActivity : ComponentActivity() {
    private var pendingStorageLink: String? = null
    private var waitingForStorage = false

    private val torrentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { copyTorrentToCache(it)?.let(::startTorrentFile) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PdmApp(
                context = this,
                incomingUrl = extractIncoming(intent),
                onDownload = ::startUrlDownload,
                onPickTorrent = { torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) },
                onStorageSettings = ::openStorageSettings,
                requestStorage = { link ->
                    pendingStorageLink = link
                    waitingForStorage = true
                    openStorageSettings()
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForStorage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            val link = pendingStorageLink
            pendingStorageLink = null
            waitingForStorage = false
            if (!link.isNullOrBlank()) startUrlDownload(link)
        }
    }

    private fun extractIncoming(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND) return intent.getStringExtra(Intent.EXTRA_TEXT)
        val data = intent?.data ?: return null
        return if (data.scheme == "magnet" || data.scheme == "http" || data.scheme == "https") data.toString() else null
    }

    private fun startUrlDownload(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        if (clean.startsWith("magnet:?", true)) {
            startService(DownloadService.ACTION_TORRENT_MAGNET, url = clean)
            return
        }
        val url = if (clean.startsWith("http://") || clean.startsWith("https://")) clean
        else "https://www.google.com/search?q=${Uri.encode(clean)}"
        if (url.substringBefore('?').lowercase().endsWith(".torrent")) {
            startService(DownloadService.ACTION_TORRENT_URL, url = url)
        } else {
            startService(DownloadService.ACTION_HTTP, url = url)
        }
    }

    private fun startTorrentFile(file: File) = startService(DownloadService.ACTION_TORRENT_FILE, path = file.absolutePath)

    private fun startService(action: String, url: String? = null, path: String? = null) {
        val intent = Intent(this, DownloadService::class.java).apply {
            this.action = action
            url?.let { putExtra(DownloadService.EXTRA_URL, it) }
            path?.let { putExtra(DownloadService.EXTRA_PATH, it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun copyTorrentToCache(uri: Uri): File? = try {
        File(cacheDir, "selected-${System.currentTimeMillis()}.torrent").also { file ->
            contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        }
    } catch (_: Throwable) { null }

    private fun openStorageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:$packageName") })
        }
    }
}

@Composable
private fun PdmApp(
    context: Context,
    incomingUrl: String?,
    onDownload: (String) -> Unit,
    onPickTorrent: () -> Unit,
    onStorageSettings: () -> Unit,
    requestStorage: (String) -> Unit
) {
    val history = remember(context) { HistoryStore(context) }
    var historyItems by remember { mutableStateOf(history.all()) }
    var splash by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf("Home") }
    var url by remember { mutableStateOf(incomingUrl.orEmpty()) }
    var browserUrl by remember { mutableStateOf(incomingUrl?.takeIf { it.startsWith("http") } ?: "https://www.google.com") }
    var downloadDialog by remember { mutableStateOf<String?>(null) }
    var storageDialog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { kotlinx.coroutines.delay(2200); splash = false }
    LaunchedEffect(splash) {
        if (!splash && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            (context as? ComponentActivity)?.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 501)
        }
    }

    fun record(value: String) {
        history.add(value)
        historyItems = history.all()
    }

    fun begin(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        record(clean)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) storageDialog = clean
        else downloadDialog = clean
    }

    fun openLink(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        when {
            clean.startsWith("magnet:", true) || isFileUrl(clean) -> begin(clean)
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> { record(clean); browserUrl = clean; selected = "Browser" }
            else -> { val search = "https://www.google.com/search?q=${Uri.encode(clean)}"; record(clean); browserUrl = search; selected = "Browser" }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White, surface = Color.White)) {
        if (splash) { SplashScreen(); return@MaterialTheme }
        Scaffold(
            containerColor = Color.White,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFFF4FFFB), tonalElevation = 0.dp) {
                    listOf(
                        Triple("Home", Icons.Default.Home, "Home"),
                        Triple("Downloads", Icons.Default.Download, "Downloads"),
                        Triple("Browser", Icons.Default.Web, "Browser"),
                        Triple("Settings", Icons.Default.Settings, "Settings")
                    ).forEach { (name, icon, label) ->
                        NavigationBarItem(
                            selected = selected == name,
                            onClick = { selected = name },
                            icon = { Icon(icon, null) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Dark, selectedTextColor = Dark, indicatorColor = Color(0xFFE5DDF7), unselectedIconColor = Muted, unselectedTextColor = Muted)
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (selected) {
                    "Home" -> HomeScreen(
                        context = context,
                        url = url,
                        onUrl = { url = it },
                        onGo = { openLink(url) },
                        onBrowser = { selected = "Browser" },
                        onDownloads = { selected = "Downloads" },
                        onTorrent = onPickTorrent,
                        onAddDownload = { begin(url) },
                        historyItems = historyItems,
                        onHistoryClick = { url = it; openLink(it) },
                        onHistoryDelete = { history.remove(it); historyItems = history.all() },
                        onClearHistory = { history.clear(); historyItems = emptyList() }
                    )
                    "Downloads" -> DownloadsScreen(context)
                    "Browser" -> BrowserScreen(
                        context = context,
                        initialUrl = browserUrl,
                        historyItems = historyItems,
                        onVisit = { record(it) },
                        onDownload = ::begin,
                        onOpen = { browserUrl = it }
                    )
                    else -> SettingsScreen(context, onStorageSettings, historyItems, { history.clear(); historyItems = emptyList() })
                }
            }
        }

        downloadDialog?.let { link ->
            AlertDialog(
                onDismissRequest = { downloadDialog = null },
                title = { Text(if (link.startsWith("magnet:", true)) "Add Torrent" else "Download File") },
                text = { Text(link, color = Muted) },
                confirmButton = {
                    Button({ onDownload(link); downloadDialog = null }, colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                        Icon(Icons.Default.Download, null); Spacer(Modifier.width(7.dp)); Text(if (link.startsWith("magnet:", true)) "Start Torrent" else "Download")
                    }
                },
                dismissButton = { TextButton({ downloadDialog = null }) { Text("Cancel") } }
            )
        }
        storageDialog?.let { link ->
            AlertDialog(
                onDismissRequest = { storageDialog = null },
                title = { Text("Storage access") },
                text = { Text("PDM will save files in shared storage under Download/PDM with separate Images, Videos, Music, APK, ZIP, Documents and Torrents folders. Android will open its system access screen.") },
                confirmButton = { Button({ storageDialog = null; requestStorage(link) }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Allow storage access") } },
                dismissButton = { TextButton({ storageDialog = null; onDownload(link) }) { Text("Use app storage") } }
            )
        }
    }
}

@Composable private fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Color.White), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Image(painterResource(R.drawable.ic_pdm_logo), "PDM", Modifier.size(170.dp), ContentScale.Fit)
            Text("PDM", fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, color = Green)
            Text("Palia Download Manager", color = Muted, fontSize = 18.sp)
            Text("FAST  •  SMART  •  SECURE", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(Modifier.width(250.dp).height(6.dp).clip(RoundedCornerShape(50)), color = Green, trackColor = Color(0xFFE8EEF0))
            Text("Loading…", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp)); Text("Developer By", color = Muted, fontSize = 12.sp); Text("Shanpalia", color = Blue, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable private fun PdmLogo(size: Int) = Image(painterResource(R.drawable.ic_pdm_logo), "PDM logo", Modifier.size(size.dp), ContentScale.Fit)

@Composable
private fun HomeScreen(
    context: Context,
    url: String,
    onUrl: (String) -> Unit,
    onGo: () -> Unit,
    onBrowser: () -> Unit,
    onDownloads: () -> Unit,
    onTorrent: () -> Unit,
    onAddDownload: () -> Unit,
    historyItems: List<HistoryItem>,
    onHistoryClick: (String) -> Unit,
    onHistoryDelete: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    var field by remember(url) { mutableStateOf(TextFieldValue(url, TextRange(url.length))) }
    LaunchedEffect(url) { if (field.text != url) field = TextFieldValue(url, TextRange(url.length)) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 105.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { PdmLogo(62); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text("Palia Download Manager", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text("Fast • Smart • Secure", color = Muted, fontSize = 14.sp) } }
            }
            item {
                Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(28.dp), elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = field,
                        onValueChange = { field = it; onUrl(it.text) },
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(22.dp),
                        placeholder = { Text("Paste URL, magnet or search") },
                        leadingIcon = { Icon(Icons.Default.Language, null, tint = Dark) },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (field.text.isNotBlank()) IconButton({ field = TextFieldValue(""); onUrl("") }) { Icon(Icons.Default.Clear, "Clear") }
                                TextButton({ val clip = readClipboard(context); if (clip.isNotBlank()) { field = TextFieldValue(clip, TextRange(clip.length)); onUrl(clip) } }) { Text("PASTE") }
                                FilledIconButton(onClick = onGo, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Blue)) { Icon(Icons.Default.ArrowForward, "Go", tint = Color.White) }
                            }
                        }
                    )
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(Mint), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { PdmLogo(72); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text("Ready to download?", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text("HTTP/HTTPS, .torrent and magnet links.", color = Muted, fontSize = 14.sp) } }
                        Button(onClick = onAddDownload, Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add Download", fontWeight = FontWeight.Bold) }
                    }
                }
            }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { QuickAction("Browser", Icons.Default.Web, onBrowser, Modifier.weight(1f)); QuickAction("Downloads", Icons.Default.Download, onDownloads, Modifier.weight(1f)); QuickAction("Torrent", Icons.Default.Download, onTorrent, Modifier.weight(1f)) } }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Recent History", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Dark); if (historyItems.isNotEmpty()) TextButton(onClearHistory) { Text("Clear all") } }
                if (historyItems.isEmpty()) Text("Your visited links and searches will appear here.", color = Muted, fontSize = 14.sp)
                else historyItems.take(6).forEach { item -> HistoryRow(item.url, onClick = { onHistoryClick(item.url) }, onDelete = { onHistoryDelete(item.url) }) }
            }
            item { Text("Download Center", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) { Stat("Downloading", Icons.Default.Download, Modifier.weight(1f)); Stat("Completed", Icons.Default.CheckCircle, Modifier.weight(1f)); Stat("Failed", Icons.Default.ErrorOutline, Modifier.weight(1f)) } }
        }
        FloatingActionButton(onClick = onAddDownload, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 18.dp), containerColor = Blue, contentColor = Color.White) { Icon(Icons.Default.Download, "Add download") }
    }
}

@Composable private fun HistoryRow(url: String, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(17.dp), elevation = CardDefaults.cardElevation(1.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Language, null, tint = Green, modifier = Modifier.size(25.dp)); Spacer(Modifier.width(10.dp)); Text(url, Modifier.weight(1f), maxLines = 2, fontSize = 13.sp, color = Dark); IconButton(onDelete) { Icon(Icons.Default.Clear, "Delete", tint = Muted) }
        }
    }
}

@Composable private fun QuickAction(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) { Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(MintStrong), shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(13.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, tint = Green, Modifier.size(29.dp)); Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp) } } }
@Composable private fun Stat(title: String, icon: ImageVector, modifier: Modifier) { Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(Soft)) { Column(Modifier.padding(13.dp)) { Icon(icon, null, tint = Green, Modifier.size(22.dp)); Text("0", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text(title, color = Muted, fontSize = 11.sp) } } }

@Composable private fun DownloadsScreen(context: Context) {
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(Unit) { files = withContext(Dispatchers.IO) { PdmStorage.ensureFolders(context).walkTopDown().filter(File::isFile).take(200).toList() } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Downloads", fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, color = Dark); Text("PDM: ${PdmStorage.root(context).absolutePath}", color = Muted, fontSize = 12.sp) }
        if (files.isEmpty()) item { Card(colors = CardDefaults.cardColors(Soft), shape = RoundedCornerShape(24.dp), Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Folder, null, tint = Green, Modifier.size(56.dp)); Text("No downloads yet", fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("Downloaded files will appear here.", color = Muted) } } }
        else items(files, key = { it.absolutePath }) { file -> Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(1.dp), Modifier.fillMaxWidth()) { Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Storage, null, tint = Green, Modifier.size(30.dp)); Spacer(Modifier.width(12.dp)); Column { Text(file.name, fontWeight = FontWeight.Bold); Text(file.parentFile?.name ?: "PDM", color = Muted, fontSize = 12.sp) } } } }
    }
}

@Composable private fun BrowserScreen(context: Context, initialUrl: String, historyItems: List<HistoryItem>, onVisit: (String) -> Unit, onDownload: (String) -> Unit, onOpen: (String) -> Unit) {
    var address by remember(initialUrl) { mutableStateOf(TextFieldValue(initialUrl, TextRange(initialUrl.length))) }
    var showHistory by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({}) { Icon(Icons.Default.ArrowBack, "Back") }
            OutlinedTextField(value = address, onValueChange = { address = it }, modifier = Modifier.weight(1f).focusRequester(focusRequester).onFocusChanged { if (it.isFocused) address = address.copy(selection = TextRange(0, address.text.length)) }, singleLine = true, shape = RoundedCornerShape(24.dp), placeholder = { Text("Search or enter address") }, leadingIcon = { Icon(Icons.Default.Language, null) }, trailingIcon = { TextButton({ val clip = readClipboard(context); if (clip.isNotBlank()) address = TextFieldValue(clip, TextRange(clip.length)); showHistory = false }) { Text("PASTE") } })
            FilledIconButton({ val v = address.text.trim(); if (v.isNotBlank()) { onVisit(v); onOpen(v) }; showHistory = false }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Blue)) { Icon(Icons.Default.ArrowForward, "Go", tint = Color.White) }
        }
        if (showHistory && historyItems.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), colors = CardDefaults.cardColors(Color.White), elevation = CardDefaults.cardElevation(4.dp), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text("Recent history", Modifier.padding(10.dp), fontWeight = FontWeight.Bold, color = Dark)
                    historyItems.take(8).forEach { item -> TextButton({ address = TextFieldValue(item.url, TextRange(item.url.length)); onOpen(item.url); showHistory = false }, Modifier.fillMaxWidth()) { Text(item.url, maxLines = 1, color = Dark) }
                }
            }
        }
        key(initialUrl) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val link = request.url.toString()
                                onVisit(link)
                                if (isFileUrl(link) || link.startsWith("magnet:", true)) { onDownload(link); return true }
                                return false
                            }
                            override fun onPageFinished(view: WebView, url: String) { onVisit(url) }
                        }
                        webChromeClient = WebChromeClient()
                        setDownloadListener { downloadUrl, _, _, _, _ -> onVisit(downloadUrl); onDownload(downloadUrl) }
                        loadUrl(initialUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun readClipboard(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}

@Composable private fun SettingsScreen(context: Context, onStorageSettings: () -> Unit, historyItems: List<HistoryItem>, onClearHistory: () -> Unit) {
    val scope = rememberCoroutineScope(); var status by remember { mutableStateOf("Current version $CURRENT_VERSION") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Settings", fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, color = Dark) }
        item { Card(colors = CardDefaults.cardColors(Mint), shape = RoundedCornerShape(22.dp), Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text("Downloads", fontWeight = FontWeight.Bold, fontSize = 19.sp); Text("HTTP resume, background service, torrent and magnet support.", color = Muted) } } }
        item { Card(colors = CardDefaults.cardColors(Soft), shape = RoundedCornerShape(22.dp), Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("PDM Storage", fontWeight = FontWeight.Bold, fontSize = 19.sp); Text(PdmStorage.root(context).absolutePath, color = Muted, fontSize = 12.sp); Button(onClick = onStorageSettings, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Icon(Icons.Default.Storage, null); Spacer(Modifier.width(7.dp)); Text("Storage access") } } } }
        item { Card(colors = CardDefaults.cardColors(Soft), shape = RoundedCornerShape(22.dp), Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Browser History", fontWeight = FontWeight.Bold, fontSize = 19.sp); Text("${historyItems.size} saved entries", color = Muted); Button(onClick = onClearHistory, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Clear history") } } } }
        item { Card(colors = CardDefaults.cardColors(Soft), shape = RoundedCornerShape(22.dp), Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("App Update", fontWeight = FontWeight.Bold, fontSize = 19.sp); Text(status, color = Muted); Button(onClick = { status = "Checking GitHub Releases…"; scope.launch(Dispatchers.IO) { val result = checkLatestRelease(); withContext(Dispatchers.Main) { status = result } } }, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Check for Updates") } } } }
        item { Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(22.dp), Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text("About PDM", fontWeight = FontWeight.Bold, fontSize = 19.sp); Text("Palia Download Manager"); Text("Version $CURRENT_VERSION • Developer By Shanpalia", color = Muted) } } }
    }
}

private suspend fun checkLatestRelease(): String = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val request = Request.Builder().url("https://api.github.com/repos/shanpalia/Palia-Download-Manager/releases/latest").header("Accept", "application/vnd.github+json").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext "No published GitHub release yet."
            if (!response.isSuccessful) return@withContext "Update check failed: HTTP ${response.code}"
            val tag = JSONObject(response.body?.string().orEmpty()).optString("tag_name")
            if (tag.isBlank()) "No release version found." else if (tag.removePrefix("v") == CURRENT_VERSION) "You are up to date ($CURRENT_VERSION)." else "Latest release: $tag"
        }
    } catch (e: Throwable) { "Update check failed: ${e.message ?: "network error"}" }
}

private fun isFileUrl(value: String): Boolean {
    val path = value.substringBefore('?').substringBefore('#').lowercase()
    return listOf(".apk", ".zip", ".rar", ".7z", ".pdf", ".mp3", ".m4a", ".wav", ".mp4", ".mkv", ".avi", ".mov", ".jpg", ".jpeg", ".png", ".webp", ".iso", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".torrent").any(path::endsWith)
}
