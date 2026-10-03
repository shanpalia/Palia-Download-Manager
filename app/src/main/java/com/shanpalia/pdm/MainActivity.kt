package com.shanpalia.pdm

import android.Manifest
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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Torrent
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
private const val CURRENT_VERSION = "1.1.0"

class MainActivity : ComponentActivity() {
    private val torrentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val cached = copyTorrentToCache(uri)
            if (cached != null) startTorrentFile(cached)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = extractIncoming(intent)
        setContent {
            PdmApp(
                context = this,
                incomingUrl = incoming,
                onDownload = { startUrlDownload(it) },
                onPickTorrent = { torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) },
                onStorageSettings = { openStorageSettings() }
            )
        }
    }

    private fun extractIncoming(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_SEND) {
            return intent.getStringExtra(Intent.EXTRA_TEXT)
        }
        val data = intent.data
        if (data?.scheme == "magnet") return data.toString()
        if (data?.scheme == "http" || data?.scheme == "https") return data.toString()
        return null
    }

    private fun startUrlDownload(value: String) {
        val clean = value.trim()
        if (clean.startsWith("magnet:?", ignoreCase = true)) {
            startService(DownloadService.ACTION_TORRENT_MAGNET, url = clean)
            return
        }
        val url = if (clean.startsWith("http://") || clean.startsWith("https://")) clean else "https://www.google.com/search?q=${Uri.encode(clean)}"
        if (url.substringBefore('?').lowercase().endsWith(".torrent")) {
            startService(DownloadService.ACTION_TORRENT_URL, url = url)
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            startService(DownloadService.ACTION_HTTP, url = url)
        }
    }

    private fun startTorrentFile(file: File) {
        startService(DownloadService.ACTION_TORRENT_FILE, path = file.absolutePath)
    }

    private fun startService(action: String, url: String? = null, path: String? = null) {
        val intent = Intent(this, DownloadService::class.java).apply {
            this.action = action
            if (url != null) putExtra(DownloadService.EXTRA_URL, url)
            if (path != null) putExtra(DownloadService.EXTRA_PATH, path)
        }
        startForegroundService(intent)
    }

    private fun copyTorrentToCache(uri: Uri): File? = try {
        val file = File(cacheDir, "selected-${System.currentTimeMillis()}.torrent")
        contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        file
    } catch (_: Throwable) {
        null
    }

    private fun openStorageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }
}

@Composable
private fun PdmApp(
    context: Context,
    incomingUrl: String?,
    onDownload: (String) -> Unit,
    onPickTorrent: () -> Unit,
    onStorageSettings: () -> Unit
) {
    var showSplash by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf("Home") }
    var url by remember { mutableStateOf(incomingUrl.orEmpty()) }
    var browserUrl by remember { mutableStateOf("https://www.google.com") }
    var downloadDialog by remember { mutableStateOf<String?>(null) }
    var storageDialog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        delay(2200)
        showSplash = false
    }

    LaunchedEffect(showSplash) {
        if (!showSplash && Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            (context as? ComponentActivity)?.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 501)
        }
    }

    fun begin(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        val needsPublic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()
        if (needsPublic) storageDialog = clean else downloadDialog = clean
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White, surface = Color.White)) {
        if (showSplash) {
            SplashScreen()
            return@MaterialTheme
        }

        Scaffold(
            containerColor = Color.White,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFFF4FFFB), tonalElevation = 0.dp) {
                    listOf(
                        Triple("Home", Icons.Default.Home, "Home"),
                        Triple("Downloads", Icons.Default.Download, "Downloads"),
                        Triple("Browser", Icons.Default.Web, "Browser"),
                        Triple("Settings", Icons.Default.Settings, "Settings")
                    ).forEach { (item, icon, label) ->
                        NavigationBarItem(
                            selected = selected == item,
                            onClick = { selected = item },
                            icon = { Icon(icon, null) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Dark,
                                selectedTextColor = Dark,
                                indicatorColor = Color(0xFFE5DDF7),
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (selected) {
                    "Home" -> HomeScreen(
                        url = url,
                        onUrl = { url = it },
                        onGo = {
                            val value = url.trim()
                            if (value.startsWith("magnet:?", true) || value.startsWith("http://") || value.startsWith("https://")) {
                                if (value.startsWith("magnet:?", true) || isFileUrl(value)) begin(value)
                                else { browserUrl = value; selected = "Browser" }
                            } else if (value.isNotBlank()) {
                                browserUrl = "https://www.google.com/search?q=${Uri.encode(value)}"
                                selected = "Browser"
                            }
                        },
                        onBrowser = { selected = "Browser" },
                        onDownloads = { selected = "Downloads" },
                        onTorrent = onPickTorrent,
                        onAddDownload = { begin(url) }
                    )
                    "Downloads" -> DownloadsScreen(context)
                    "Browser" -> BrowserScreen(browserUrl) { link -> begin(link) }
                    else -> SettingsScreen(context, onStorageSettings)
                }
            }
        }

        downloadDialog?.let { link ->
            AlertDialog(
                onDismissRequest = { downloadDialog = null },
                title = { Text(if (link.startsWith("magnet:", true)) "Add Torrent" else "Download File") },
                text = { Text(link, color = Muted) },
                confirmButton = {
                    Button(onClick = { onDownload(link); downloadDialog = null }, colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (link.startsWith("magnet:", true)) "Start Torrent" else "Download")
                    }
                },
                dismissButton = { TextButton({ downloadDialog = null }) { Text("Cancel") } }
            )
        }

        storageDialog?.let { link ->
            AlertDialog(
                onDismissRequest = { storageDialog = null },
                title = { Text("Storage access") },
                text = {
                    Text("To save downloads directly in Download/PDM and its Images, Videos, Music, APK, ZIP and Documents folders, allow PDM to manage shared storage.")
                },
                confirmButton = {
                    Button(onClick = { storageDialog = null; onStorageSettings() }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                        Text("Allow storage access")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onDownload(link); storageDialog = null }) { Text("Use app storage") }
                }
            )
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Image(painterResource(R.drawable.ic_pdm_logo), "PDM", Modifier.size(170.dp), ContentScale.Fit)
            Text("PDM", fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, color = Green)
            Text("Palia Download Manager", color = Muted, fontSize = 18.sp)
            Text("FAST  •  SMART  •  SECURE", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.width(250.dp).height(6.dp).clip(RoundedCornerShape(50)), color = Green, trackColor = Color(0xFFE8EEF0))
            Text("Loading…", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            Text("Developer By", color = Muted, fontSize = 12.sp)
            Text("Shanpalia", color = Blue, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PdmLogo(size: Int) {
    Image(painterResource(R.drawable.ic_pdm_logo), "PDM logo", Modifier.size(size.dp), ContentScale.Fit)
}

@Composable
private fun HomeScreen(
    url: String,
    onUrl: (String) -> Unit,
    onGo: () -> Unit,
    onBrowser: () -> Unit,
    onDownloads: () -> Unit,
    onTorrent: () -> Unit,
    onAddDownload: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 18.dp, bottom = 105.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PdmLogo(62)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Palia Download Manager", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                        Text("Fast • Smart • Secure", color = Muted, fontSize = 14.sp)
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(28.dp), elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = onUrl,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(22.dp),
                        placeholder = { Text("Paste URL, magnet or search") },
                        leadingIcon = { Icon(Icons.Default.Language, null, tint = Dark) },
                        trailingIcon = {
                            FilledIconButton(onClick = onGo, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Blue)) {
                                Icon(Icons.Default.ArrowForward, "Go", tint = Color.White)
                            }
                        }
                    )
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(Mint), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PdmLogo(72)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Ready to download?", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                                Text("HTTP/HTTPS, .torrent and magnet links.", color = Muted, fontSize = 14.sp)
                            }
                        }
                        Button(onClick = onAddDownload, Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add Download", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickAction("Browser", Icons.Default.Web, onBrowser, Modifier.weight(1f))
                    QuickAction("Downloads", Icons.Default.Download, onDownloads, Modifier.weight(1f))
                    QuickAction("Torrent", Icons.Default.Torrent, onTorrent, Modifier.weight(1f))
                }
            }
            item {
                Text("Download Center", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Stat("Downloading", "•", Icons.Default.Download, Modifier.weight(1f))
                    Stat("Completed", "•", Icons.Default.CheckCircle, Modifier.weight(1f))
                    Stat("Failed", "•", Icons.Default.ErrorOutline, Modifier.weight(1f))
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(Soft), shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Recent Downloads", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                        Spacer(Modifier.height(16.dp))
                        Icon(Icons.Default.Download, null, tint = Green, modifier = Modifier.size(54.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Open Downloads to see your files", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("Files are organized automatically inside PDM folders.", color = Muted, fontSize = 13.sp)
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(Color(0xFFF7FBFA)), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Speed, null, tint = Green, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Smart Download Engine", fontWeight = FontWeight.Bold)
                            Text("Background downloads, resume support and torrent engine.", color = Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        FloatingActionButton(onClick = onAddDownload, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 18.dp), containerColor = Blue, contentColor = Color.White) {
            Icon(Icons.Default.Download, "Add download")
        }
    }
}

@Composable
private fun QuickAction(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(MintStrong), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Green, modifier = Modifier.size(29.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun Stat(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(Soft)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = Green, modifier = Modifier.size(22.dp))
            Text(value, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
            Text(title, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DownloadsScreen(context: Context) {
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(Unit) {
        files = withContext(Dispatchers.IO) {
            val root = PdmStorage.ensureFolders(context)
            root.walkTopDown().filter { it.isFile }.take(200).toList()
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Downloads", fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, color = Dark) }
        item { Text("PDM folder: ${PdmStorage.root(context).absolutePath}", color = Muted, fontSize = 12.sp) }
        if (files.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(Soft), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Folder, null, tint = Green, modifier = Modifier.size(56.dp))
                        Text("No downloads yet", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Downloaded files will appear here.", color = Muted)
                    }
                }
            }
        } else {
            items(files, key = { it.absolutePath }) { file ->
                Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(1.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Storage, null, tint = Green, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.name, fontWeight = FontWeight.Bold)
                            Text(file.parentFile?.name ?: "PDM", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserScreen(initialUrl: String, onDownload: (String) -> Unit) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val link = request.url.toString()
                        if (isFileUrl(link) || link.startsWith("magnet:")) {
                            onDownload(link)
                            return true
                        }
                        return false
                    }
                }
                webChromeClient = WebChromeClient()
                setDownloadListener { downloadUrl, _, contentDisposition, mimeType, _ ->
                    onDownload(downloadUrl)
                }
                loadUrl(initialUrl)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun SettingsScreen(context: Context, onStorageSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var updateStatus by remember { mutableStateOf("Current version $CURRENT_VERSION") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Settings", fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, color = Dark) }
        item {
            Card(colors = CardDefaults.cardColors(Mint), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Downloads", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text("HTTP resume, background service, torrent and magnet support.", color = Muted)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(Soft), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("PDM Storage", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text(PdmStorage.root(context).absolutePath, color = Muted, fontSize = 12.sp)
                    Button(onClick = onStorageSettings, colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                        Icon(Icons.Default.Storage, null)
                        Spacer(Modifier.width(7.dp))
                        Text("Storage access")
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(Soft), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("App Update", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text(updateStatus, color = Muted)
                    Button(onClick = {
                        updateStatus = "Checking GitHub Releases…"
                        scope.launch(Dispatchers.IO) {
                            val result = checkLatestRelease()
                            withContext(Dispatchers.Main) { updateStatus = result }
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                        Text("Check for Updates")
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("About PDM", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text("Palia Download Manager", fontWeight = FontWeight.SemiBold)
                    Text("Version $CURRENT_VERSION • Developer By Shanpalia", color = Muted)
                }
            }
        }
    }
}

private suspend fun checkLatestRelease(): String = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val request = Request.Builder().url("https://api.github.com/repos/shanpalia/Palia-Download-Manager/releases/latest").header("Accept", "application/vnd.github+json").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext "No published GitHub release yet."
            if (!response.isSuccessful) return@withContext "Update check failed: HTTP ${response.code}"
            val body = response.body?.string().orEmpty()
            val tag = JSONObject(body).optString("tag_name", "")
            if (tag.isBlank()) "No release version found." else if (tag.removePrefix("v") == CURRENT_VERSION) "You are up to date ($CURRENT_VERSION)." else "Latest release: $tag"
        }
    } catch (e: Throwable) {
        "Update check failed: ${e.message ?: "network error"}"
    }
}

private fun isFileUrl(value: String): Boolean {
    val path = value.substringBefore('?').substringBefore('#').lowercase()
    return listOf(".apk", ".zip", ".rar", ".7z", ".pdf", ".mp3", ".m4a", ".wav", ".mp4", ".mkv", ".avi", ".mov", ".jpg", ".jpeg", ".png", ".webp", ".iso", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".torrent").any(path::endsWith)
}
