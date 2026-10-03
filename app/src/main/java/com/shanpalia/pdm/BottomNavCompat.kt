package com.shanpalia.pdm

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.NavigationBarItem

/**
 * Compatibility wrapper used by MainActivity.
 * The label argument is intentionally accepted for source compatibility,
 * but the bottom navigation is icon-only to prevent text wrapping/cropping.
 */
@Composable
fun PdmNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: (@Composable () -> Unit)? = null
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        modifier = Modifier,
        label = null,
        alwaysShowLabel = false
    )
}
