package com.naivety.zhicolor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.highlight.Highlight
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text

/** Records only the page background, so cards never recursively sample themselves. */
val LocalCardBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(20.dp)
    val backdrop = LocalCardBackdrop.current
    val tint = MiuixTheme.colorScheme.surface.copy(alpha = if (dark) 0.34f else 0.48f)
    val surface = if (backdrop != null) Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = { blur(26.dp.toPx()) },
        highlight = { Highlight.Default.copy(alpha = if (dark) 0.16f else 0.28f) },
        onDrawSurface = { drawRect(tint) },
    ) else Modifier.background(tint)
    Column(modifier.fillMaxWidth().clip(shape).then(surface), content = content)
}

@Composable
fun GlassSectionTitle(text: String) {
    Text(text, modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 8.dp),
        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.76f),
        fontSize = 14.sp, fontWeight = FontWeight.Medium)
}
