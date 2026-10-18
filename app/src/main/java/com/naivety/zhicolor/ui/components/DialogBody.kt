package com.naivety.zhicolor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val LocalDialogBodyLimit = staticCompositionLocalOf { 320.dp }
internal const val DialogBodyLayoutId = "dialog-body"

@Composable
fun DialogBody(
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
    verticalSpacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val limit = minOf(maxHeight ?: LocalDialogBodyLimit.current, LocalDialogBodyLimit.current)
    Column(
        modifier.layoutId(DialogBodyLayoutId).fillMaxWidth()
            .heightIn(max = limit).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content,
    )
}
