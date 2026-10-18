package com.naivety.zhicolor.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naivety.zhicolor.ui.theme.headerBlur
import com.naivety.zhicolor.ui.theme.headerBlurSource
import com.naivety.zhicolor.ui.theme.rememberHeaderBlurState
import com.naivety.zhicolor.ui.theme.rememberHeaderBlurStyle
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar

@Composable
fun AppListPage(
    title: String,
    bottomPadding: Dp,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val header = MiuixScrollBehavior()
    val blur = rememberHeaderBlurState()
    val style = rememberHeaderBlurStyle()
    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(title = title, actions = actions, scrollBehavior = header,
                color = Color.Transparent, modifier = Modifier.headerBlur(blur, style))
        },
    ) { safe ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().headerBlurSource(blur).nestedScroll(header.nestedScrollConnection),
            contentPadding = PaddingValues(top = safe.calculateTopPadding() + 8.dp,
                bottom = safe.calculateBottomPadding() + bottomPadding + 20.dp),
            content = content,
        )
    }
}
