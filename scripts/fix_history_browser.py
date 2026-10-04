from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/java/com/shanpalia/pdm/PdmMainActivity.kt"

text = KOTLIN.read_text(encoding="utf-8")

# History entries must reopen in the existing PDM Browser WebView.
text = text.replace(
    '"History" -> HistoryScreen(activity)',
    '"History" -> HistoryScreen(activity) { target -> browserUrl = target; screen = "Browser" }'
)

# Replace the browser implementation produced by the older patcher. Facebook must
# remain in the embedded WebView; do not launch Custom Tabs or the Facebook app.
start = text.find('@Composable\nprivate fun BrowserScreen(')
end = text.find('\nprivate fun loadBrowser(', start)
if start == -1 or end == -1:
    raise SystemExit("BrowserScreen boundaries not found")

new_browser = r'''@Composable
private fun BrowserScreen(initialUrl: String, onUrlChange: (String) -> Unit, onDownload: (String) -> Unit, onBack: () -> Unit) {
    var address by rememberSaveable(initialUrl) { mutableStateOf(initialUrl.ifBlank { "https://www.google.com" }) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun targetFor(value: String): String {
        val raw = value.trim()
        return when {
            raw.isBlank() -> "https://www.google.com"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            else -> "https://www.google.com/search?q=${Uri.encode(raw)}"
        }
    }

    fun openAddress() {
        val target = targetFor(address)
        address = target
        onUrlChange(target)
        webViewRef?.loadUrl(target)
    }

    BackHandler {
        if (webViewRef?.canGoBack() == true) webViewRef?.goBack() else onBack()
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (webViewRef?.canGoBack() == true) webViewRef?.goBack() else onBack() }) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Dark)
            }
            Text("PDM Browser", modifier = Modifier.weight(1f), fontSize = 20.sp, color = Dark, fontWeight = FontWeight.Bold)
            IconButton(onClick = { webViewRef?.reload() }) { Icon(Icons.Default.Refresh, "Refresh", tint = Green) }
        }

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(56.dp),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = { Text("Search or type URL") },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Green) },
            trailingIcon = { IconButton(onClick = ::openAddress) { Icon(Icons.Default.ArrowForward, "Go", tint = Green) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { openAddress() })
        )

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    }
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url.toString()
                            if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
                                // Every normal HTTPS page, including Facebook, stays in PDM.
                                return false
                            }

                            // Never hand Facebook's app schemes to Android. Keep the user in PDM.
                            if (target.startsWith("sfilvavs://", true) ||
                                target.startsWith("fb://", true) ||
                                target.startsWith("fb-messenger://", true)) {
                                view.loadUrl("https://www.facebook.com/")
                                return true
                            }

                            // Ignore other app-only schemes rather than launching another app.
                            return true
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            super.onPageFinished(view, pageUrl)
                            if (pageUrl.startsWith("http://", true) || pageUrl.startsWith("https://", true)) {
                                address = pageUrl
                                onUrlChange(pageUrl)
                                saveBrowserVisit(ctx, pageUrl, view.title)
                            }
                        }
                    }
                    setDownloadListener { u, _, _, _, _ -> if (!u.isNullOrBlank()) onDownload(u) }
                    webViewRef = this
                    loadUrl(targetFor(initialUrl))
                }
            },
            update = { webViewRef = it }
        )
    }
}
'''
text = text[:start] + new_browser + text[end:]

# Replace HistoryScreen with a clickable Recent list. The callback changes the
# parent screen to Browser and supplies the selected URL to the same WebView.
start = text.find('@Composable\nprivate fun HistoryScreen(')
end = text.find('\n@Composable private fun FileIcon(', start)
if start == -1 or end == -1:
    raise SystemExit("HistoryScreen boundaries not found")

new_history = r'''@Composable
private fun HistoryScreen(activity: Context, onOpenUrl: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var visits by remember { mutableStateOf(readBrowserHistory(activity)) }

    LaunchedEffect(Unit) { visits = readBrowserHistory(activity) }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        PdmHeader("Recent", "Websites visited in PDM Browser")
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0; visits = readBrowserHistory(activity) }, label = { Text("Recent") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Downloads") })
            if (tab == 0 && visits.isNotEmpty()) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { clearBrowserHistory(activity); visits = emptyList() }) { Text("Clear") }
            }
        }

        if (tab == 0) {
            if (visits.isEmpty()) {
                EmptyState("No recent sites", "Websites you visit in the PDM Browser will appear here.")
            } else {
                LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(visits, key = { it.url }) { visit ->
                        Card(
                            Modifier.fillMaxWidth().clickable { onOpenUrl(visit.url) },
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = Soft),
                            elevation = CardDefaults.cardElevation(1.dp)
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Language, null, Modifier.size(36.dp), tint = Green)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(visit.title, color = Dark, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(visit.url, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (visit.time > 0) Text(
                                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(visit.time)),
                                        color = Muted,
                                        fontSize = 11.sp
                                    )
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = Muted)
                            }
                        }
                    }
                }
            }
        } else {
            DownloadsScreen(activity)
        }
    }
}
'''
text = text[:start] + new_history + text[end:]

KOTLIN.write_text(text, encoding="utf-8")
print("PDM browser/history final patch applied")
