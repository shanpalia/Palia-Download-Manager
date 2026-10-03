package com.shanpalia.pdm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val Green = Color(0xFF149B72)
private val Blue = Color(0xFF1677FF)
private val Dark = Color(0xFF18212B)
private val Muted = Color(0xFF6D7782)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PdmApp(this) }
    }
}

@Composable
private fun PdmApp(context: Context) {
    val activity = context as? Activity
    var selected by rememberSaveable { mutableStateOf("Home") }
    var showExit by remember { mutableStateOf(false) }
    var showSplash by remember { mutableStateOf(true) }
    var url by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { delay(1800); showSplash = false }

    BackHandler {
        if (showSplash) return@BackHandler
        when (selected) {
            "Home" -> showExit = true
            else -> selected = "Home"
        }
    }

    if (showSplash) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("PDM", style = MaterialTheme.typography.displayLarge, color = Green)
        }
        return
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White)) {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color(0xFFF4FFFB)) {
                    listOf(
                        Triple("Home", Icons.Default.Home, "Home"),
                        Triple("Downloads", Icons.Default.Download, "Downloads"),
                        Triple("Browser", Icons.Default.Language, "Browser"),
                        Triple("Settings", Icons.Default.Settings, "Settings")
                    ).forEach { (name, icon, label) ->
                        NavigationBarItem(
                            selected = selected == name,
                            onClick = { selected = name },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                // Compact Chrome/IDM-style address bar is always above the PDM header.
                if (selected == "Home") {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        singleLine = true,
                        maxLines = 1,
                        placeholder = { Text("Paste here", maxLines = 1, overflow = TextOverflow.Clip) },
                        leadingIcon = { Icon(Icons.Default.Language, null) },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { url = readClipboard(context) }) { Text("PASTE") }
                                IconButton(onClick = { if (url.isNotBlank()) selected = "Browser" }) {
                                    Icon(Icons.Default.ArrowForward, "Go")
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("PDM", style = MaterialTheme.typography.titleLarge, color = Dark)
                        Spacer(Modifier.weight(1f))
                        Text("Palia Download Manager", color = Muted)
                    }
                }

                when (selected) {
                    "Home" -> HomePlaceholder(url)
                    "Downloads" -> CenterText("Downloads")
                    "Browser" -> CenterText("Browser")
                    "Settings" -> CenterText("Settings")
                }
            }
        }

        if (showExit) {
            AlertDialog(
                onDismissRequest = { showExit = false },
                title = { Text("Exit Palia Download Manager?") },
                text = { Text("Are you sure you want to exit?") },
                confirmButton = {
                    TextButton(onClick = { showExit = false; activity?.finish() }) { Text("YES") }
                },
                dismissButton = {
                    TextButton(onClick = { showExit = false }) { Text("NO") }
                }
            )
        }
    }
}

@Composable
private fun HomePlaceholder(url: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Ready to download", style = MaterialTheme.typography.headlineSmall, color = Dark)
        Spacer(Modifier.height(8.dp))
        Text(
            if (url.isBlank()) "Paste a download link above" else "Link ready: $url",
            color = Muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.headlineSmall)
    }
}

private fun readClipboard(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    return clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}
