package com.naivety.zhicolor.ui.components.navigation

object AppNavigationPolicy {
    val routes: List<String> = listOf("home", "tools", "lyrics", "about")

    fun selectedIndex(route: String): Int = routes.indexOf(route).coerceAtLeast(0)
}
