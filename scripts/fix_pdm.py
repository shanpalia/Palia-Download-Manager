from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/java/com/shanpalia/pdm/PdmMainActivity.kt"

NEW_BROWSER = r'''
@Composable
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
            IconButton(onClick = { if (webViewRef?.canGoBack() == true) webViewRef?.goBack() else onBack() }) { Icon(Icons.Default.ArrowBack, "Back", tint = Dark) }
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
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 16; CPH2707) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    }
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
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

NEW_HISTORY = r'''
@Composable
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
                                    if (visit.time > 0) Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(visit.time)), color = Muted, fontSize = 11.sp)
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


def find_function(text, name):
    marker = f"private fun {name}("
    start = text.find(marker)
    if start < 0:
        raise SystemExit(f"{name} not found")
    ann = text.rfind("@Composable", 0, start)
    if ann < 0:
        ann = start
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"{name} body not found")
    depth = 0
    i = brace
    in_line = False
    in_block = False
    in_string = False
    in_triple = False
    escape = False
    while i < len(text):
        c = text[i]
        n = text[i + 1] if i + 1 < len(text) else ""
        if in_line:
            if c == "\n": in_line = False
        elif in_block:
            if c == "*" and n == "/": in_block = False; i += 1
        elif in_triple:
            if text.startswith('"""', i): in_triple = False; i += 2
        elif in_string:
            if escape: escape = False
            elif c == "\\": escape = True
            elif c == '"': in_string = False
        else:
            if c == "/" and n == "/": in_line = True; i += 1
            elif c == "/" and n == "*": in_block = True; i += 1
            elif text.startswith('"""', i): in_triple = True; i += 2
            elif c == '"': in_string = True
            elif c == "{": depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0: return ann, i + 1
        i += 1
    raise SystemExit(f"Unbalanced braces in {name}")

text = KOTLIN.read_text(encoding="utf-8")
text = text.replace('"History" -> HistoryScreen(activity)', '"History" -> HistoryScreen(activity) { target -> browserUrl = target; screen = "Browser" }')
start, end = find_function(text, "BrowserScreen")
text = text[:start] + NEW_BROWSER.strip() + text[end:]
start, end = find_function(text, "HistoryScreen")
text = text[:start] + NEW_HISTORY.strip() + text[end:]
KOTLIN.write_text(text, encoding="utf-8")
print("PDM source patched: Chrome-compatible WebView, Facebook support, clickable Recent history")
