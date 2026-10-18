package com.naivety.zhicolor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun GlassMenuItem(
    title: String,
    selected: Boolean? = null,
    onClick: () -> Unit,
) {
    val primary = MiuixTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected == true) primary.copy(alpha = 0.12f) else Color.Transparent)
            .then(
                if (selected != null) {
                    Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                } else {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
            color = if (selected == true) primary else MiuixTheme.colorScheme.onSurface,
        )
        if (selected == true) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = primary, modifier = Modifier.size(22.dp))
        }
    }
}
