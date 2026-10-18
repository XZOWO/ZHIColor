package com.naivety.zhicolor.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One trailing affordance for tappable settings rows, including navigation and dialogs. */
@Composable
fun ActionPreference(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val arrowColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    BasicComponent(
        title = title,
        summary = summary,
        modifier = modifier,
        enabled = enabled,
        onClick = onClick,
        endActions = {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = if (enabled) arrowColor else arrowColor.copy(alpha = arrowColor.alpha * 0.38f),
                modifier = Modifier.padding(start = 8.dp).size(20.dp),
            )
        },
    )
}
