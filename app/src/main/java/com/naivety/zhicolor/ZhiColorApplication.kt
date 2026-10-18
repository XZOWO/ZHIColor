package com.naivety.zhicolor

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.naivety.zhicolor.home.FrameworkBridge
import com.naivety.zhicolor.localization.AppLanguage

class ZhiColorApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppLanguage.initialize(this)
        com.naivety.zhicolor.bridge.BridgeSettings.sync(this)
        FrameworkBridge.initialize()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.applyLegacyConfiguration(this)
    }
}
