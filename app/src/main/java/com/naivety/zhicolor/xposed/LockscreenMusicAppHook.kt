package com.naivety.zhicolor.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.content.ContextCompat
import com.naivety.zhicolor.bridge.BridgeSettings
import com.naivety.zhicolor.bridge.ToolsSettings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Independent card icon tools; native media content and animation stay intact. */
object LockscreenMusicAppHook {
    private const val TAG = "ZHIColorMusicApp"
    private const val PLUGIN_PACKAGE = "com.oplus.systemui.plugins"
    private const val INDICATOR_ID = "img_top_right_icon"
    private const val INDICATOR_CLASS =
        "com.oplus.systemui.plugins.shared.template.component.media.view.OplusLottieAnimationView"
    private const val SECTION_CLASS =
        "com.oplus.systemui.plugins.shared.template.section.media.s1"
    private const val PLUGIN_LOADER_CLASS = "com.android.systemui.shared.plugins.OPlusPluginClassLoader"
    private const val CONTROL_CENTER_HOLDER_CLASS =
        "com.oplus.systemui.qs.media.OplusMediaViewPagerAdapter\$OplusMediaViewHolder"
    private const val CONTROL_CENTER_ICON_ID = "oplus_media_panel_container_icon"

    private val installed = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    private val tracking = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor {
        Thread(it, "ZHIColor-tools-read").apply { isDaemon = true }
    }
    private val records = WeakHashMap<View, Indicator>()
    private val sectionTypes = WeakHashMap<Class<*>, Boolean>()
    private val controlCenterTypes = WeakHashMap<Class<*>, Boolean>()
    private val internalVisibility = ThreadLocal.withInitial { false }
    private val settingsLock = Any()
    private var revision = -1L
    @Volatile private var enabled = false
    @Volatile private var controlCenterEnabled = false

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        try {
            module.hook(View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val requested = chain.getArg(0) as Int
                    val view = chain.thisObject as? View
                    val record = if (tracking.get() && internalVisibility.get() != true && view != null) {
                        synchronized(records) { records[view] }
                    } else null
                    val effective = if (record != null) {
                        record.nativeVisibility = requested
                        val hide = runCatching { shouldHide(record) }.getOrDefault(false)
                        record.overridden = hide
                        LockscreenMusicAppPolicy.visibility(requested, hide)
                    } else requested
                    // Keep the original invocation outside recovery: a failing OEM method runs once.
                    if (effective == requested) chain.proceed()
                    else chain.proceed(arrayOf<Any>(effective))
                }
        } catch (t: Throwable) {
            installed.set(false)
            Log.w(TAG, "visibility hook failed", t)
            return
        }
        runCatching { installSection(module, classLoader) }
            .onFailure { Log.w(TAG, "initial indicator hook failed", it) }
        runCatching { installControlCenter(module, classLoader) }
            .onFailure { Log.w(TAG, "control center indicator hook failed", it) }
        runCatching {
            val loaderType = classLoader.loadClass(PLUGIN_LOADER_CLASS)
            module.hook(loaderType.getDeclaredMethod("loadClass", String::class.java,
                Boolean::class.javaPrimitiveType))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (chain.getArg(0) == SECTION_CLASS && result is Class<*>) {
                        runCatching { installSectionType(module, result) }
                            .onFailure { Log.w(TAG, "loaded indicator hook failed", it) }
                    }
                    result
                }
            loaderType.declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                module.hook(constructor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        (chain.thisObject as? ClassLoader)?.let { loader ->
                            runCatching { installSection(module, loader) }
                                .onFailure { Log.w(TAG, "plugin indicator hook failed", it) }
                        }
                        result
                    }
            }
        }.onFailure { Log.w(TAG, "plugin loader hook failed", it) }
    }

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        try {
            ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == ToolsSettings.CHANGED) {
                        runCatching { updateSettings(intent.extras) }
                            .onFailure { Log.w(TAG, "tool settings update failed", it) }
                    }
                }
            }, IntentFilter(ToolsSettings.CHANGED), BridgeSettings.PERMISSION, main,
                ContextCompat.RECEIVER_EXPORTED)
        } catch (t: Throwable) {
            initialized.set(false)
            Log.w(TAG, "tool settings receiver failed", t)
            return
        }
        loadSettings(app, 0)
    }

    private fun loadSettings(context: Context, attempt: Int) {
        worker.execute {
            val result = runCatching {
                checkNotNull(context.contentResolver.call(
                    Uri.parse("content://${BridgeSettings.CONFIG_AUTHORITY}"),
                    ToolsSettings.SNAPSHOT_METHOD, null, null
                )).also(::updateSettings)
            }
            if (result.isFailure && attempt < 2) {
                main.postDelayed({ loadSettings(context, attempt + 1) }, (attempt + 1) * 1500L)
            } else if (result.isFailure) {
                Log.w(TAG, "tool settings snapshot failed", result.exceptionOrNull())
            }
        }
    }

    private fun installSection(module: XposedModule, loader: ClassLoader) {
        val type = runCatching { loader.loadClass(SECTION_CLASS) }.getOrNull() ?: return
        installSectionType(module, type)
    }

    private fun installSectionType(module: XposedModule, type: Class<*>) {
        if (type.name != SECTION_CLASS) return
        synchronized(sectionTypes) {
            if (sectionTypes.containsKey(type)) return
            val indicatorField = type.getDeclaredField("u").apply { isAccessible = true }
            val notificationMethod = type.getDeclaredMethod("o").apply { isAccessible = true }
            if (notificationMethod.returnType != Boolean::class.javaPrimitiveType) return
            sectionTypes[type] = true
            var hooked = 0
            type.declaredConstructors.forEach { constructor ->
                runCatching {
                    constructor.isAccessible = true
                    module.hook(constructor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val result = chain.proceed()
                            runCatching {
                                val section = chain.thisObject ?: return@runCatching
                                val view = indicatorField.get(section) as? View ?: return@runCatching
                                if (isIndicator(view)) track(view, section, notificationMethod)
                            }.onFailure { Log.w(TAG, "indicator registration failed", it) }
                            result
                        }
                    hooked++
                }.onFailure { Log.w(TAG, "indicator constructor hook failed", it) }
            }
            if (hooked == 0) sectionTypes.remove(type)
        }
    }

    private fun isIndicator(view: View): Boolean =
        view.javaClass.name == INDICATOR_CLASS && view.id != View.NO_ID &&
            view.resources.getResourcePackageName(view.id) == PLUGIN_PACKAGE &&
            view.resources.getResourceEntryName(view.id) == INDICATOR_ID

    private fun installControlCenter(module: XposedModule, loader: ClassLoader) {
        val type = loader.loadClass(CONTROL_CENTER_HOLDER_CLASS)
        synchronized(controlCenterTypes) {
            if (controlCenterTypes.containsKey(type)) return
            val icon = type.getDeclaredField("mAppIcon").apply { isAccessible = true }
            val bind = type.declaredMethods.single {
                it.name == "bindMediaIcon" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name == "com.android.systemui.media.controls.shared.model.MediaData"
            }.apply { isAccessible = true }
            fun register(holder: Any?) {
                if (holder == null) return
                val view = icon.get(holder) as? View ?: return
                if (view.id == View.NO_ID ||
                    view.resources.getResourcePackageName(view.id) != "com.android.systemui" ||
                    view.resources.getResourceEntryName(view.id) != CONTROL_CENTER_ICON_ID) return
                track(view, null, controlCenter = true)
            }
            // Bind also covers recycled holders. The native method always runs exactly once.
            module.hook(bind)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching { register(chain.thisObject) }
                        .onFailure { Log.w(TAG, "control center indicator binding failed", it) }
                    result
                }
            controlCenterTypes[type] = true
            type.declaredConstructors.forEach { constructor ->
                runCatching {
                    constructor.isAccessible = true
                    module.hook(constructor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val result = chain.proceed()
                            runCatching { register(chain.thisObject) }
                                .onFailure { Log.w(TAG, "control center indicator registration failed", it) }
                            result
                        }
                }.onFailure { Log.w(TAG, "control center holder hook failed", it) }
            }
        }
    }

    private fun track(view: View, section: Any, notificationMethod: Method) {
        track(view, StateAccess.create(section, notificationMethod), controlCenter = false)
    }

    private fun track(view: View, state: StateAccess?, controlCenter: Boolean) {
        val record = synchronized(records) {
            records[view]?.also { it.state = state } ?: Indicator(view.visibility, state, controlCenter).also {
                records[view] = it
            }
        }
        tracking.set(true)
        onMain {
            if (!record.listening) {
                record.listening = true
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        runCatching {
                            watch(v, record)
                            applyVisibility(v, record)
                        }.onFailure { Log.w(TAG, "indicator attachment failed", it) }
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        runCatching { unwatch(record) }
                            .onFailure { Log.w(TAG, "indicator detachment failed", it) }
                    }
                })
            }
            if (view.isAttachedToWindow) watch(view, record)
            applyVisibility(view, record)
        }
    }

    private fun watch(view: View, record: Indicator) {
        val observer = view.viewTreeObserver
        if (record.observer?.get() === observer && record.preDraw != null) return
        unwatch(record)
        val target = WeakReference(view)
        val listener = ViewTreeObserver.OnPreDrawListener {
            target.get()?.let { applyVisibility(it, record) }
            true
        }
        record.preDraw = listener
        record.observer = WeakReference(observer)
        observer.addOnPreDrawListener(listener)
    }

    private fun unwatch(record: Indicator) {
        val observer = record.observer?.get()
        val listener = record.preDraw
        if (observer?.isAlive == true && listener != null) observer.removeOnPreDrawListener(listener)
        record.observer = null
        record.preDraw = null
    }

    private fun shouldHide(record: Indicator): Boolean =
        if (record.controlCenter) controlCenterEnabled
        else enabled && record.state?.isLockscreenCard() == true

    private fun applyVisibility(view: View, record: Indicator) {
        runCatching {
            val hide = runCatching { shouldHide(record) }.getOrDefault(false)
            if (!hide && !record.overridden) return
            val desired = LockscreenMusicAppPolicy.visibility(record.nativeVisibility, hide)
            record.overridden = hide
            if (view.visibility != desired) {
                internalVisibility.set(true)
                try { view.visibility = desired } finally { internalVisibility.set(false) }
            }
        }.onFailure { Log.w(TAG, "indicator visibility update failed", it) }
    }

    private fun updateSettings(snapshot: Bundle?) {
        if (snapshot == null || !snapshot.containsKey(ToolsSettings.HIDE_LOCKSCREEN_MUSIC_APP)) return
        val nextRevision = snapshot.getLong(ToolsSettings.REVISION, -1L)
        if (nextRevision < 0L) return
        synchronized(settingsLock) {
            // The provider read may finish after a newer live broadcast.
            if (!LockscreenMusicAppPolicy.acceptRevision(revision, nextRevision)) return
            revision = nextRevision
            enabled = snapshot.getBoolean(ToolsSettings.HIDE_LOCKSCREEN_MUSIC_APP, false)
            controlCenterEnabled = snapshot.getBoolean(ToolsSettings.HIDE_CONTROL_CENTER_MUSIC_APP, false)
        }
        onMain {
            val current = synchronized(records) { records.entries.map { it.key to it.value } }
            current.forEach { (view, record) -> applyVisibility(view, record) }
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private class Indicator(nativeVisibility: Int, state: StateAccess?, val controlCenter: Boolean) {
        @Volatile var nativeVisibility = nativeVisibility
        @Volatile var state = state
        @Volatile var overridden = false
        var listening = false
        var observer: WeakReference<ViewTreeObserver>? = null
        var preDraw: ViewTreeObserver.OnPreDrawListener? = null
    }

    /** Field names are bound to the verified ColorOS 17 section, never guessed on unrelated views. */
    private class StateAccess(
        private val section: WeakReference<Any>,
        private val notification: Method,
        private val configFlow: WeakReference<Any>,
        private val value: Method,
        private val keyguard: Field,
        private val aod: Field,
        private val screenOn: Field
    ) {
        fun isLockscreenCard(): Boolean {
            val owner = section.get() ?: return false
            if (notification.invoke(owner) != true) return false
            val flow = configFlow.get() ?: return false
            val config = value.invoke(flow) ?: return false
            return LockscreenMusicAppPolicy.hidden(true, true, keyguard.getBoolean(config),
                aod.getBoolean(config), screenOn.getBoolean(config))
        }

        companion object {
            fun create(section: Any, notification: Method): StateAccess {
                val vm = section.javaClass.getDeclaredField("k").apply { isAccessible = true }.get(section)
                val flow = vm.javaClass.getDeclaredField("b").apply { isAccessible = true }.get(vm)
                val value = flow.javaClass.getMethod("getValue").apply { isAccessible = true }
                val config = checkNotNull(value.invoke(flow))
                fun booleanField(name: String): Field = config.javaClass.getDeclaredField(name).apply {
                    check(type == Boolean::class.javaPrimitiveType)
                    isAccessible = true
                }
                return StateAccess(WeakReference(section), notification, WeakReference(flow), value,
                    booleanField("v"), booleanField("i"), booleanField("j"))
            }
        }
    }
}

/** Pure decisions shared by the live hook and its local JVM checks. */
internal object LockscreenMusicAppPolicy {
    fun hidden(enabled: Boolean, notificationCard: Boolean, keyguardShowing: Boolean,
        alwaysOnDisplay: Boolean, screenOn: Boolean): Boolean =
        // OEM state flags publish separately; an unlit card stays hidden between lockscreen and AOD.
        enabled && notificationCard && (keyguardShowing || alwaysOnDisplay || !screenOn)

    fun visibility(nativeVisibility: Int, hidden: Boolean): Int =
        if (hidden && nativeVisibility == 0) 4 /* View.INVISIBLE */ else nativeVisibility

    fun acceptRevision(current: Long, incoming: Long): Boolean = incoming >= 0L && incoming > current
}
