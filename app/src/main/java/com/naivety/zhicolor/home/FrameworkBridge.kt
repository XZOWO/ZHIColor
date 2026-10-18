package com.naivety.zhicolor.home

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object FrameworkBridge {
    private val activeState = MutableStateFlow(false)
    val active = activeState.asStateFlow()
    @Volatile private var service: XposedService? = null

    fun initialize() {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                FrameworkBridge.service = service
                activeState.value = true
            }
            override fun onServiceDied(service: XposedService) {
                if (FrameworkBridge.service === service) {
                    FrameworkBridge.service = null
                    activeState.value = false
                }
            }
        })
    }

    fun selectedScope(): List<String>? = service?.let { current ->
        runCatching { current.scope.toList() }.getOrNull()
    }
}
