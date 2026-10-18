package com.naivety.zhicolor.access

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.naivety.zhicolor.R
import com.naivety.zhicolor.core.RootSession
import com.naivety.zhicolor.core.SuSession
import com.naivety.zhicolor.home.FrameworkBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class AccessViewModel(application: Application) : AndroidViewModel(application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = application.getSharedPreferences("zhicolor_access", Context.MODE_PRIVATE)
    private var rootJob: Job? = null
    private var frameworkJob: Job? = null
    private var foreground = false

    var state by mutableStateOf(AccessState())
        private set

    init {
        scope.launch {
            FrameworkBridge.active.collect { connected ->
                if (!connected) state = state.copy(framework = AccessStatus.REQUIRED)
                else if (foreground) checkFramework()
            }
        }
    }

    fun onForeground() {
        foreground = true
        checkFramework()
        when (AccessPolicy.rootCheck(
            previouslyGranted = preferences.getBoolean(ROOT_GRANTED, false),
            attemptedInProcess = rootCheckAttempted.get(),
            busy = rootJob?.isActive == true,
        )) {
            RootCheck.INITIAL_REQUEST -> checkRoot(initialRequest = true)
            RootCheck.RECHECK -> checkRoot(initialRequest = false)
            RootCheck.NONE -> if (state.root == AccessStatus.UNKNOWN) {
                state = state.copy(root = AccessStatus.REQUIRED)
            }
        }
    }

    fun onBackground() { foreground = false }

    private fun checkFramework() {
        frameworkJob?.cancel()
        state = state.copy(framework = AccessStatus.CHECKING)
        frameworkJob = scope.launch {
            val granted = withContext(Dispatchers.IO) {
                AccessPolicy.frameworkGranted(FrameworkBridge.active.value, FrameworkBridge.selectedScope())
            }
            state = state.copy(framework = if (granted) AccessStatus.GRANTED else AccessStatus.REQUIRED)
            // A delayed binder delivery after first launch must not leave a stale status card.
            if (!granted && foreground) {
                delay(1000)
                val refreshed = withContext(Dispatchers.IO) {
                    AccessPolicy.frameworkGranted(FrameworkBridge.active.value, FrameworkBridge.selectedScope())
                }
                state = state.copy(framework = if (refreshed) AccessStatus.GRANTED else AccessStatus.REQUIRED)
            }
        }
    }

    private fun checkRoot(initialRequest: Boolean) {
        if (rootJob?.isActive == true) return
        // Keep the first attempt process-wide across Activity recreation and permission UI lifecycles.
        if (initialRequest && !rootCheckAttempted.compareAndSet(false, true)) return
        rootCheckAttempted.set(true)
        state = state.copy(root = AccessStatus.CHECKING)
        rootJob = scope.launch {
            val session = AtomicReference<RootSession?>()
            val timeout = if (initialRequest) 45_000L else 6_000L
            val granted = try {
                withTimeout(timeout + 1000) {
                    runInterruptible(Dispatchers.IO) {
                        SuSession(timeoutMillis = timeout).use { root ->
                            session.set(root)
                            val identity = root.run("id -u")
                            AccessPolicy.rootGranted(identity.exitCode, identity.output)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // A timeout is an unsuccessful check, while ViewModel cancellation propagates.
                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                false
            } catch (_: Exception) { false } finally {
                withContext(Dispatchers.IO) { session.getAndSet(null)?.close() }
            }
            preferences.edit().putBoolean(ROOT_GRANTED, granted).apply()
            state = state.copy(root = if (granted) AccessStatus.GRANTED else AccessStatus.REQUIRED)
            if (initialRequest && !granted) {
                Toast.makeText(getApplication(), R.string.access_root_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCleared() { scope.cancel() }

    private companion object {
        const val ROOT_GRANTED = "root_previously_granted"
        val rootCheckAttempted = AtomicBoolean(false)
    }
}
