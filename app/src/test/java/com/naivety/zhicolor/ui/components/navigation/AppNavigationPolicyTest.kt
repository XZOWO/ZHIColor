package com.naivety.zhicolor.ui.components.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationPolicyTest {
    @Test
    fun routeOrderMatchesTheFourMainPagerPages() {
        assertEquals(listOf("home", "tools", "lyrics", "about"), AppNavigationPolicy.routes)
        AppNavigationPolicy.routes.forEachIndexed { page, route ->
            assertEquals(page, AppNavigationPolicy.selectedIndex(route))
        }
    }

    @Test
    fun unknownOrEmptyRouteSelectsTheHomePage() {
        listOf("", "missing", "HOME", "home/detail").forEach { route ->
            val index = AppNavigationPolicy.selectedIndex(route)
            assertEquals("home", AppNavigationPolicy.routes[index])
        }
    }
}
