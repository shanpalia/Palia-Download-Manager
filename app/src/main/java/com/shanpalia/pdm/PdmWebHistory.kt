package com.shanpalia.pdm

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

        val host = try {
            Uri.parse(clean).host.orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val name = title?.trim().takeUnless { it.isNullOrBlank() } ?: host.ifBlank { clean }
        val old = read(context).filterNot { it.url == clean }.toMutableList()
        old.add(0, PdmVisit(name, clean, System.currentTimeMillis()))

        val json = JSONArray()
        old.take(MAX).forEach { item ->
            json.put(
                JSONObject()
                    .put("title", item.title)
                    .put("url", item.url)
                    .put("time", item.time)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, json.toString())
            .apply()
    }

    fun read(context: Context): List<PdmVisit> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        PdmVisit(
                            item.optString("title"),
                            item.optString("url"),
                            item.optLong("time")
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }
}

@Composable
fun RecentHistoryScreen(context: Context) {
    var visits by remember { mutableStateOf(PdmWebHistory.read(context)) }

    LaunchedEffect(Unit) {
        visits = PdmWebHistory.read(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = RecentGreen,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Recent",
                    fontSize = 25.sp,
                    color = RecentDark,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Websites visited in PDM Browser",
                    fontSize = 12.sp,
                    color = RecentMuted
                )
            }
            if (visits.isNotEmpty()) {
                IconButton(onClick = {
                    PdmWebHistory.clear(context)
                    visits = emptyList()
                }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Clear recent",
                        tint = RecentMuted
                    )
                }
            }
        }

        if (visits.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No recent sites",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = RecentDark
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Websites you visit in PDM Browser will appear here.",
                        color = RecentMuted
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visits, key = { it.url }) { visit ->
                    val host = try {
                        Uri.parse(visit.url).host ?: visit.url
                    } catch (_: Throwable) {
                        visit.url
                    }
                    val time = SimpleDateFormat(
                        "dd MMM, hh:mm a",
                        Locale.getDefault()
                    ).format(Date(visit.time))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = RecentMint)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(44.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Language,
                                    contentDescription = null,
                                    tint = RecentGreen,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 12.dp, end = 12.dp)
                            ) {
                                Text(
                                    visit.title.ifBlank { host },
                                    fontWeight = FontWeight.SemiBold,
                                    color = RecentDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    host,
                                    color = RecentMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    time,
                                    color = RecentMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
