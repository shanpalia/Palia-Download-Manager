package com.shanpalia.pdm

import android.content.Context
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private val RecentGreen = Color(0xFF16B978)
private val RecentDark = Color(0xFF12231E)
private val RecentMuted = Color(0xFF6F7D77)
private val RecentMint = Color(0xFFE9FFF6)

data class PdmVisit(val title: String, val url: String, val time: Long)

object PdmWebHistory {
    private const val PREFS = "pdm_web_history"
    private const val KEY = "recent_sites"
    private const val MAX = 50

    fun record(context: Context, title: String?, url: String?) {
        val clean = url?.trim().orEmpty()
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) return
        val host = try { Uri.parse(clean).host.orEmpty() } catch (_: Throwable) { "" }
        val name = title?.trim().takeUnless { it.isNullOrBlank() } ?: host.ifBlank { clean }
        val old = read(context).filterNot { it.url == clean }.toMutableList()
        old.add(0, PdmVisit(name, clean, System.currentTimeMillis()))
        val json = JSONArray()
        old.take(MAX).forEach { item ->
            json.put(JSONObject().put("title", item.title).put("url", item.url).put("time", item.time))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }

    fun read(context: Context): List<PdmVisit> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(PdmVisit(item.optString("title"), item.optString("url"), item.optLong("time")))
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

@Composable
fun RecentHistoryScreen(activity: android.content.ComponentActivity) {
    var visits by remember { mutableStateOf(PdmWebHistory.read(activity)) }

    LaunchedEffect(Unit) {
        visits = PdmWebHistory.read(activity)
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Language, null, tint = RecentGreen, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Recent", fontSize = 25.sp, color = RecentDark, fontWeight = FontWeight.Bold)
                Text("Websites visited in PDM Browser", fontSize = 12.sp, color = RecentMuted)
            }
            if (visits.isNotEmpty()) {
                IconButton(onClick = {
                    PdmWebHistory.clear(activity)
                    visits = emptyList()
                }) {
                    Icon(Icons.Default.Delete, "Clear recent", tint = RecentMuted)
                }
            }
        }

        if (visits.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No recent sites", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = RecentDark)
                    Spacer(Modifier.height(8.dp))
                    Text("Websites you visit in PDM Browser will appear here.", color = RecentMuted)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visits, key = { it.url }) { visit ->
                    val host = try { Uri.parse(visit.url).host ?: visit.url } catch (_: Throwable) { visit.url }
                    val time = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(visit.time))
                    Row(
                        Modifier.fillMaxWidth()
                            .background(RecentMint, RoundedCornerShape(16.dp)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Language, null, tint = RecentGreen, modifier = Modifier.padding(14.dp).size(28.dp))
                        Column(Modifier.weight(1f).padding(vertical = 12.dp, end = 12.dp)) {
                            Text(visit.title.ifBlank { host }, fontWeight = FontWeight.SemiBold, color = RecentDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(host, color = RecentMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(time, color = RecentMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserScreen(
    initialUrl: String,
    onUrlChange: (String) -> Unit,
    onDownload: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var address by rememberSaveable(initialUrl) { mutableStateOf(initialUrl.ifBlank { "https://www.google.com" }) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val google = "https://www.google.com"

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = RecentDark) }
            Text("PDM Browser", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = RecentDark, modifier = Modifier.weight(1f))
            IconButton(onClick = { webViewRef?.reload() }) { Icon(Icons.Default.Refresh, "Refresh", tint = RecentGreen, modifier = Modifier.size(30.dp)) }
        }

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(58.dp),
            singleLine = true,
            shape = RoundedCornerShape(30.dp),
            leadingIcon = { Icon(Icons.Default.Search, null, tint = RecentGreen) },
            trailingIcon = {
                IconButton(onClick = {
                    val raw = address.trim()
                    val target = if (raw.isBlank()) google else if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://www.google.com/search?q=${android.net.Uri.encode(raw)}"
                    address = target
                    webViewRef?.loadUrl(target)
                }) { Icon(Icons.Default.ArrowForward, "Go", tint = RecentGreen, modifier = Modifier.size(30.dp)) }
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = {
                val raw = address.trim()
                val target = if (raw.isBlank()) google else if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://www.google.com/search?q=${android.net.Uri.encode(raw)}"
                address = target
                webViewRef?.loadUrl(target)
            })
        )

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean = false

                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                                address = url
                                onUrlChange(url)
                                PdmWebHistory.record(context, view.title, url)
                            }
                        }
                    }
                    webViewRef = this
                    val first = if (initialUrl.startsWith("http://", true) || initialUrl.startsWith("https://", true)) initialUrl else google
                    loadUrl(first)
                }
            },
            update = { webViewRef = it }
        )
    }
}
