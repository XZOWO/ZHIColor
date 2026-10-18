package com.naivety.zhicolor.xposed

import android.app.Application
import android.media.MediaMetadata
import android.media.session.MediaController
import android.os.Handler
import android.util.Log
import com.naivety.zhicolor.bridge.SystemUiMetadataPublisher
import com.naivety.zhicolor.bridge.ZhiBridgeRuntime
import com.naivety.zhicolor.lyrics.LyriconBridgeRuntime
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/** SystemUI ingress and native metadata delivery; the separate core entry owns rendering hooks. */
class ModuleEntry : XposedModule() {
    private var processName = ""
    override fun onModuleLoaded(param: ModuleLoadedParam) { processName = param.processName }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != "com.android.systemui" || processName != "com.android.systemui") return
        runCatching { LockscreenMusicAppHook.install(this, param.classLoader) }
            .onFailure { Log.w(TAG, "lockscreen music app hook failed", it) }
        // Each hook installs independently: an OEM method change must not prevent source startup.
        runCatching {
            hook(MediaController::class.java.getDeclaredMethod("getMetadata"))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    val controller = chain.thisObject as? MediaController
                    if (controller != null) {
                        runCatching {
                            SystemUiMetadataPublisher.observe(controller, false)
                            if (result is MediaMetadata) ZhiBridgeRuntime.decorate(result, controller.packageName)
                        }.onFailure { Log.w(TAG, "metadata read overlay failed", it) }
                    }
                    result
                }
        }.onFailure { Log.w(TAG, "metadata read hook failed", it) }
        runCatching {
            hook(MediaController::class.java.getDeclaredMethod(
                "registerCallback", MediaController.Callback::class.java, Handler::class.java))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? MediaController)?.let { SystemUiMetadataPublisher.observe(it, true) }
                    result
                }
        }.onFailure { Log.w(TAG, "metadata listener hook failed", it) }
        runCatching {
            hook(SystemUiMetadataPublisher.dispatchMethod())
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val controller = chain.thisObject as? MediaController
                    if (controller == null || !SystemUiMetadataPublisher.isMetadataMessage(chain.getArg(0))) {
                        chain.proceed()
                    } else {
                        val metadata = chain.getArg(1) as? MediaMetadata
                        val payload = runCatching {
                            if (metadata == null) null else ZhiBridgeRuntime.decorate(metadata, controller.packageName)
                        }.onFailure { Log.w(TAG, "metadata event overlay failed", it) }.getOrNull()
                        val result = chain.proceed()
                        SystemUiMetadataPublisher.delivered(controller, payload)
                        result
                    }
                }
            Log.i(TAG, "native metadata callback hook ready")
        }.onFailure { Log.w(TAG, "metadata dispatch hook failed", it) }
        runCatching {
            var type: Class<*>? = param.classLoader.loadClass(
                param.applicationInfo.className ?: Application::class.java.name)
            var onCreate: java.lang.reflect.Method? = null
            while (type != null && onCreate == null) {
                onCreate = runCatching { type!!.getDeclaredMethod("onCreate") }.getOrNull()
                type = type.superclass
            }
            hook(checkNotNull(onCreate)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? Application)?.let(::startRuntime)
                    result
                }
            // Covers a framework hot attach after Application.onCreate has already completed.
            val current = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").apply { isAccessible = true }.invoke(null)
            (current as? Application)?.takeIf { it.packageName == "com.android.systemui" }?.let(::startRuntime)
        }.onFailure { Log.w(TAG, "application startup hook failed", it) }
    }

    private fun startRuntime(app: Application) {
        runCatching { LockscreenMusicAppHook.initialize(app) }
            .onFailure { Log.w(TAG, "tool settings initialization failed", it) }
        runCatching { SystemUIMediaBridge.initialize(app) }
            .onFailure { Log.w(TAG, "home media bridge initialization failed", it) }
        runCatching { LyriconBridgeRuntime.start(app) }
            .onFailure { Log.w(TAG, "lyric bridge initialization failed", it) }
    }

    private companion object { const val TAG = "ZHIColorDelivery" }
}
