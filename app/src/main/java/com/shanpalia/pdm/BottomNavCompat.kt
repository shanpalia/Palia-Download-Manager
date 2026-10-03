package com.shanpalia.pdm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Compatibility bottom-navigation item for Palia Download Manager.
 * This is a RowScope item so each destination receives an equal share
 * of the NavigationBar width. The previous fixed 25% width plus padding
 * made labels wrap vertically on narrow phones.
 */
@Composable
fun RowScope.NavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier
                .background(
                    if (selected) Color(0xFFE9DDFB) else Color.Transparent
                )
                .padding(horizontal = 14.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            icon()
            label()
        }
    }
}
