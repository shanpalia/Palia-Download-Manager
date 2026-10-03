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
import androidx.compose.runtime.saveable.rememberSaveable
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
private const val CURRENT_VERSION = "1.2.0"
private const val UPDATE_MANIFEST = "https://shanpalia.github.io/WebsitePaliaAPK_V.2/pdm-update.json"
private const val WEBSITE_URL = "https://shanpalia.github.io/WebsitePaliaAPK_V.2/"

private data class HistoryItem(val url: String, val time: Long)

private class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("pdm_history", Context.MODE_PRIVATE)
    fun all(): List<HistoryItem> {
        val root = try { JSONObject(prefs.getString("data", "{\"items\":[]}") ?: "{\"items\":[]}") } catch (_: Throwable) { JSONObject() }
        val array = root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (url.isNotBlank()) add(HistoryItem(url, item.optLong("time")))
            }
        }
    }
    fun add(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        val list = all().filterNot { it.url == clean }.toMutableList()
        list.add(0, HistoryItem(clean, System.currentTimeMillis()))
        val array = JSONArray()
        list.take(100).forEach { item -> array.put(JSONObject().apply { put("url", item.url); put("time", item.time) }) }
        prefs.edit().putString("data", JSONObject().put("items", array).toString()).apply()
    }
    fun remove(value: String) {
        val array = JSONArray()
        all().filterNot { it.url == value }.forEach { item -> array.put(JSONObject().apply { put("url", item.url); put("time", item.time) }) }
        prefs.edit().putString("data", JSONObject().put("items", array).toString()).apply()
    }
    fun clear() { prefs.edit().remove("data").apply() }
}

class MainActivity : ComponentActivity() {
    private var pendingDownload: String? = null
    private var waitingForStorage = false

    private val torrentPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
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
                extractIncoming(intent),
                ::startUrlDownload,
                { torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) },
                ::openStorageSettings,
                ::requestStorageAndDownload
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForStorage && PdmStorage.hasPublicAccess()) {
            waitingForStorage = false
            pendingDownload?.let { link ->
                pendingDownload = null
                startUrlDownload(link)
            }
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
        val clean = value.trim()
        if (clean.isBlank()) return
        when {
            clean.startsWith("magnet:", true) -> startDownloadService(DownloadService.ACTION_TORRENT_MAGNET, clean, null)
            clean.substringBefore('?').substringBefore('#').endsWith(".torrent", true) -> startDownloadService(DownloadService.ACTION_TORRENT_URL, clean, null)
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> startDownloadService(DownloadService.ACTION_HTTP, clean, null)
        }
    }

    private fun startDownloadService(action: String, url: String?, path: String?) {
        val serviceIntent = Intent(this, DownloadService::class.java).apply {
            this.action = action
            if (url != null) putExtra(DownloadService.EXTRA_URL, url)
            if (path != null) putExtra(DownloadService.EXTRA_PATH, path)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
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
    var browserUrl by rememberSaveable { mutableStateOf(incomingUrl?.takeIf { it.startsWith("http", true) } ?: "https://www.google.com") }
    var splash by remember { mutableStateOf(true) }
    var storagePrompt by remember { mutableStateOf<String?>(null) }
    var exitDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { delay(1800); splash = false }
    LaunchedEffect(splash) {
        if (!splash && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 501)
        }
    }

    BackHandler(enabled = !splash) {
        if (screen == "Home") exitDialog = true else screen = "Home"
    }

    fun saveHistory(value: String) {
        history.add(value)
        historyItems = history.all()
    }

    fun requestDownload(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        saveHistory(clean)
        // IMPORTANT: navigate first. The download can then continue in the foreground service.
        screen = "Downloads"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !PdmStorage.hasPublicAccess()) {
            storagePrompt = clean
        } else {
            onDownload(clean)
        }
    }

    fun openAddress(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        when {
            clean.startsWith("magnet:", true) || isDownloadLink(clean) -> requestDownload(clean)
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> {
                saveHistory(clean)
                browserUrl = clean
                screen = "Browser"
            }
            else -> {
                saveHistory(clean)
                browserUrl = "https://www.google.com/search?q=${Uri.encode(clean)}"
                screen = "Browser"
            }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White, surface = Color.White)) {
        if (splash) {
            SplashScreen()
            return@MaterialTheme
        }

        Scaffold(
            containerColor = Color.White,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFFF4FFFB), tonalElevation = 0.dp) {
                    NavItem("Home", Icons.Default.Home, screen == "Home") { screen = "Home" }
                    NavItem("Downloads", Icons.Default.Download, screen == "Downloads") { screen = "Downloads" }
                    NavItem("Browser", Icons.Default.Web, screen == "Browser") { screen = "Browser" }
                    NavItem("Settings", Icons.Default.Settings, screen == "Settings") { screen = "Settings" }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    "Home" -> HomeScreen(
                        context, address,
                        { address = it },
                        { openAddress(address) },
                        { screen = "Browser" },
                        { screen = "Downloads" },
                        onPickTorrent,
                        { requestDownload(address) },
                        historyItems,
                        { value -> address = value; openAddress(value) },
                        { value -> history.remove(value); historyItems = history.all() },
                        { history.clear(); historyItems = emptyList() }
                    )
                    "Downloads" -> DownloadsScreen(context)
                    "Browser" -> BrowserScreen(context, browserUrl, { saveHistory(it) }, { requestDownload(it) }, { browserUrl = it })
                    else -> SettingsScreen(historyItems, { history.clear(); historyItems = emptyList() }, onStorageSettings)
                }
            }
        }

        storagePrompt?.let { link ->
            AlertDialog(
                onDismissRequest = { storagePrompt = null },
                title = { Text("Storage access") },
                text = { Text("Allow storage access so PDM can save files in Download/PDM and its category folders.") },
                confirmButton = {
                    Button(onClick = { storagePrompt = null; requestStorage(link) }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                        Text("Allow")
                    }
                },
                dismissButton = { TextButton(onClick = { storagePrompt = null }) { Text("Cancel") } }
            )
        }

        if (exitDialog) {
            AlertDialog(
                onDismissRequest = { exitDialog = false },
                title = { Text("Exit Palia Download Manager?") },
                text = { Text("Are you sure you want to exit?") },
                confirmButton = { TextButton(onClick = { exitDialog = false; activity.finish() }) { Text("YES") } },
                dismissButton = { TextButton(onClick = { exitDialog = false }) { Text("NO") } }
            )
        }
    }
}

@Composable
private fun RowScope.NavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(selected = selected, onClick = onClick, icon = { Icon(icon, null) }, label = { Text(label) })
}

@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(androidx.compose.ui.res.painterResource(R.drawable.ic_pdm_logo), "Palia Download Manager", Modifier.size(210.dp))
            Text("Palia Download Manager", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
            Text("FAST • SMART • SECURE", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(Modifier.width(250.dp).height(6.dp).clip(RoundedCornerShape(50)), color = Green, trackColor = Color(0xFFE8EEF0))
            Text("Developer By Shanpalia", color = Muted, fontSize = 13.sp)
        }
    }
}

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

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it; onUrl(it.text) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                singleLine = true,
                shape = RoundedCornerShape(15.dp),
                placeholder = { Text("Paste here") },
                leadingIcon = { Icon(Icons.Default.Language, null, tint = Dark) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (field.text.isNotBlank()) IconButton(onClick = { field = TextFieldValue(""); onUrl("") }) { Icon(Icons.Default.Clear, "Clear") }
                        TextButton(onClick = {
                            val value = readClipboard(context)
                            field = TextFieldValue(value, TextRange(value.length))
                            onUrl(value)
                        }) { Text("PASTE", color = Green, fontWeight = FontWeight.Bold) }
                        FilledIconButton(onClick = onGo, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Blue)) {
                            Icon(Icons.Default.ArrowForward, "Go")
                        }
                    }
                }
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(androidx.compose.ui.res.painterResource(R.drawable.ic_pdm_logo), "PDM", Modifier.size(64.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Palia Download Manager", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                    Text("Fast • Smart • Secure", color = Muted)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Ready to download?", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                    Text("HTTP/HTTPS files, .torrent and magnet links", color = Muted)
                    Button(onClick = onAddDownload, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                        Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Start Download")
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SmallAction("Browser", Icons.Default.Web, Modifier.weight(1f), onBrowser)
                SmallAction("Downloads", Icons.Default.Download, Modifier.weight(1f), onDownloads)
                SmallAction("Torrent", Icons.Default.CloudDownload, Modifier.weight(1f), onTorrent)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Recent History", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Dark, modifier = Modifier.weight(1f))
                if (historyItems.isNotEmpty()) TextButton(onClick = onClearHistory) { Text("Clear") }
            }
        }
        if (historyItems.isEmpty()) item { Text("Your recent links will appear here.", color = Muted) }
        else items(historyItems.take(10), key = { it.url }) { item ->
            Card(colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.url, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, color = Dark)
                    IconButton(onClick = { onHistoryClick(item.url) }) { Icon(Icons.Default.OpenInBrowser, "Open") }
                    IconButton(onClick = { onHistoryDelete(item.url) }) { Icon(Icons.Default.DeleteOutline, "Delete") }
                }
            }
        }
    }
}

@Composable
private fun SmallAction(title: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.height(78.dp), colors = CardDefaults.cardColors(containerColor = MintStrong), shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, title, tint = Green); Spacer(Modifier.height(4.dp)); Text(title, fontWeight = FontWeight.Bold, color = Dark, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DownloadsScreen(context: Context) {
    var files by remember { mutableStateOf(PdmStorage.allFiles(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            files = PdmStorage.allFiles(context)
            delay(1200)
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Downloads", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Dark, modifier = Modifier.weight(1f))
            IconButton(onClick = { files = PdmStorage.allFiles(context) }) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        Text("Download/PDM", color = Muted)
        Spacer(Modifier.height(12.dp))
        if (files.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Download, null, tint = Green, modifier = Modifier.size(54.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No completed downloads yet", color = Muted)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                items(files, key = { it.absolutePath }) { file ->
                    Card(colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(fileIcon(file), null, tint = Green)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.name, fontWeight = FontWeight.Bold, color = Dark, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${file.parentFile?.name ?: "PDM"} • ${formatSize(file.length())}", color = Muted, fontSize = 12.sp)
                            }
                            IconButton(onClick = { openFile(context, file) }) { Icon(Icons.Default.OpenInNew, "Open") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserScreen(context: Context, url: String, onHistory: (String) -> Unit, onDownload: (String) -> Unit, onUrlChange: (String) -> Unit) {
    var field by remember(url) { mutableStateOf(TextFieldValue(url, TextRange(url.length))) }
    var webView: WebView? by remember { mutableStateOf(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { webView?.goBack() }) { Icon(Icons.Default.ArrowBack, "Back") }
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                modifier = Modifier.weight(1f).height(52.dp),
                singleLine = true,
                placeholder = { Text("Paste here") },
                shape = RoundedCornerShape(15.dp),
                trailingIcon = {
                    IconButton(onClick = {
                        val value = normalizeUrl(field.text)
                        field = TextFieldValue(value, TextRange(value.length))
                        onUrlChange(value)
                        if (isDownloadLink(value) || value.startsWith("magnet:", true)) onDownload(value) else webView?.loadUrl(value)
                        onHistory(value)
                    }) { Icon(Icons.Default.ArrowForward, "Go") }
                }
            )
            IconButton(onClick = {
                val value = readClipboard(context)
                field = TextFieldValue(value, TextRange(value.length))
            }) { Icon(Icons.Default.ContentPaste, "Paste") }
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val value = request.url.toString()
                            if (isDownloadLink(value) || value.startsWith("magnet:", true)) {
                                onDownload(value)
                                return true
                            }
                            onHistory(value)
                            field = TextFieldValue(value, TextRange(value.length))
                            onUrlChange(value)
                            return false
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SettingsScreen(historyItems: List<HistoryItem>, onClearHistory: () -> Unit, onStorage: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Checking for updates…") }
    var updateUrl by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun checkUpdate() {
        checking = true
        status = "Checking for updates…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchUpdate() }
            checking = false
            if (result == null) status = "Could not check right now"
            else if (compareVersions(result.first, CURRENT_VERSION) > 0) {
                status = "Update available: ${result.first}"
                updateUrl = result.second ?: WEBSITE_URL
            } else {
                status = "Up-to-date • v$CURRENT_VERSION"
                updateUrl = null
            }
        }
    }

    LaunchedEffect(Unit) { checkUpdate() }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Dark) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Palia Download Manager", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Dark)
                    Text("Current version: $CURRENT_VERSION", color = Muted)
                    Text(status, color = if (status.startsWith("Up-to-date")) Green else Blue, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { checkUpdate() }, enabled = !checking, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Check Update") }
                        if (updateUrl != null) Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))) }, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Open Update") }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Storage", fontWeight = FontWeight.Bold, color = Dark)
                    Text("Downloads are organized under Download/PDM: Images, Videos, Music, APK, ZIP, Documents, Torrents and Other.", color = Muted)
                    Button(onClick = onStorage, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Storage Access") }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("History", fontWeight = FontWeight.Bold, color = Dark)
                    Text("${historyItems.size} saved links", color = Muted)
                    OutlinedButton(onClick = onClearHistory) { Text("Clear History") }
                }
            }
        }
        item { Text("Developer By Shanpalia", color = Muted, modifier = Modifier.padding(top = 12.dp)) }
    }
}

private fun readClipboard(context: Context): String {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    return manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}

private fun normalizeUrl(value: String): String {
    val clean = value.trim()
    if (clean.isBlank()) return ""
    if (clean.startsWith("http://", true) || clean.startsWith("https://", true) || clean.startsWith("magnet:", true)) return clean
    return "https://$clean"
}

private fun isDownloadLink(value: String): Boolean {
    val clean = value.trim().substringBefore('#').lowercase()
    if (clean.startsWith("magnet:")) return true
    val path = clean.substringBefore('?')
    return listOf(".zip", ".rar", ".7z", ".apk", ".apks", ".xapk", ".pdf", ".jpg", ".jpeg", ".png", ".webp", ".gif", ".mp3", ".m4a", ".wav", ".mp4", ".mkv", ".avi", ".mov", ".webm", ".torrent", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".bin", ".exe").any { path.endsWith(it) }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

private fun fileIcon(file: File): ImageVector {
    val name = file.name.lowercase()
    return when {
        name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z") -> Icons.Default.Folder
        name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".mov") -> Icons.Default.PlayArrow
        name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".wav") -> Icons.Default.MusicNote
        name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp") -> Icons.Default.Image
        name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".xapk") -> Icons.Default.Android
        name.endsWith(".torrent") -> Icons.Default.CloudDownload
        else -> Icons.Default.InsertDriveFile
    }
}

private fun openFile(context: Context, file: File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    } catch (_: Throwable) { }
}

private suspend fun fetchUpdate(): Pair<String, String?>? {
    return try {
        val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
        val response = client.newCall(Request.Builder().url(UPDATE_MANIFEST).get().build()).execute()
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: return null
        val json = JSONObject(body)
        val version = json.optString("version").trim()
        if (version.isBlank()) return null
        val url = json.optString("apk_url").takeIf { it.isNotBlank() }
            ?: json.optString("download_url").takeIf { it.isNotBlank() }
            ?: json.optString("url").takeIf { it.isNotBlank() }
        Pair(version, url)
    } catch (_: Throwable) { null }
}

private fun compareVersions(remote: String, current: String): Int {
    val a = remote.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
    val b = current.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
    val size = maxOf(a.size, b.size)
    for (i in 0 until size) {
        val av = a.getOrElse(i) { 0 }
        val bv = b.getOrElse(i) { 0 }
        if (av != bv) return av.compareTo(bv)
    }
    return 0
}
