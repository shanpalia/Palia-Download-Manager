package com.shanpalia.pdm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Icon-only bottom navigation item.
 * Keeps the existing navigation flow while hiding all bottom-nav labels.
 * Important: do not use fillMaxHeight() here; NavigationBar measures its
 * children to determine its own height. fillMaxHeight() makes the bar
 * expand to the whole screen.
 */
@Composable
fun RowScope.PdmNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(64.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .background(
                color = if (selected) Color(0xFFE8D9FF) else Color.Transparent,
                shape = RoundedCornerShape(28.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}
