package com.shanpalia.pdm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val Mint = Color(0xFFE8FFF5)
private val Green = Color(0xFF16B978)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PdmApp() }
    }
}

@Composable
fun PdmApp() {
    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White)) {
        var url by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf("Home") }
        Scaffold(bottomBar = { NavigationBar {
            listOf("Home", "Downloads", "Browser", "Settings").forEach { item ->
                NavigationBarItem(selected = selected == item, onClick = { selected = item }, icon = { Text(item.take(1)) }, label = { Text(item) })
            }
        } }) { pad ->
            when (selected) {
                "Home" -> HomeScreen(url, { url = it }, { /* downloader engine added next */ })
                "Downloads" -> SimplePage("Downloads", "Your downloads will appear here")
                "Browser" -> SimplePage("Browser", "Built-in browser is coming next")
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
fun HomeScreen(url: String, onUrl: (String) -> Unit, onDownload: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("PDM", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Green)
            Text("Palia Download Manager", color = Color.Gray)
        }
        item {
            OutlinedTextField(value = url, onValueChange = onUrl, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Paste URL or search the web") }, trailingIcon = { TextButton(onClick = onDownload) { Text("GO") } })
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ready to download?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Paste a direct file URL to start a download.")
                    Button(onClick = onDownload) { Text("Add Download") }
                }
            }
        }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Downloading", "0", Modifier.weight(1f)); Stat("Completed", "0", Modifier.weight(1f)); Stat("Failed", "0", Modifier.weight(1f))
        }}
        item { Text("Recent Downloads", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item { Text("No downloads yet", color = Color.Gray, modifier = Modifier.padding(vertical = 28.dp)) }
    }
}

@Composable fun Stat(title: String, value: String, modifier: Modifier) { Card(modifier = modifier) { Column(Modifier.padding(14.dp)) { Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(title, color = Color.Gray) } } }

@Composable fun SimplePage(title: String, text: String) { Column(Modifier.fillMaxSize().padding(20.dp)) { Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp)); Text(text, color = Color.Gray) } }

@Composable fun SettingsScreen() { Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); SettingRow("Downloads", "Download location, connections and retry"); SettingRow("Browser", "History, cache and browser options"); SettingRow("App Update", "Check for the latest PDM version"); SettingRow("About PDM", "PDM 1.0.0 • Developer By Shanpalia") } }
@Composable fun SettingRow(title: String, subtitle: String) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = Color.Gray) } } }
