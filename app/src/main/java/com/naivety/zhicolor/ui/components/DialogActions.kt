package com.naivety.zhicolor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val LocalDialogActionMaxWidth = staticCompositionLocalOf { Dp.Infinity }

@Composable
fun DialogActions(
    modifier: Modifier = Modifier,
    startActions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        val stacked = startActions != null &&
            (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.15f)
        val actionMaxWidth = ((maxWidth - 8.dp) / 2).coerceAtLeast(1.dp)
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                    content = startActions!!,
                )
                CompositionLocalProvider(
                    LocalDialogActionMaxWidth provides actionMaxWidth,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        content = content,
                    )
                }
            }
        } else {
            val actionsPerRow = if (startActions != null) 3 else 2
            val horizontalGaps = if (startActions != null) 16.dp else 8.dp
            CompositionLocalProvider(
                LocalDialogActionMaxWidth provides
                    ((maxWidth - horizontalGaps) / actionsPerRow).coerceAtLeast(1.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (startActions != null) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically,
                            content = startActions,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = content,
                    )
                }
            }
        }
    }
}

@Composable
fun DialogAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val color = if (destructive) Color(0xFFE5484D) else MiuixTheme.colorScheme.primary
    TextButton(
        text = text,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .widthIn(max = LocalDialogActionMaxWidth.current)
            .heightIn(min = 48.dp),
        minWidth = 48.dp,
        minHeight = 48.dp,
        cornerRadius = 12.dp,
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
        colors = ButtonDefaults.textButtonColors(
            color = Color.Transparent,
            disabledColor = Color.Transparent,
            textColor = color,
            disabledTextColor = color.copy(alpha = 0.38f),
        ),
    )
}
