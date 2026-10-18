package com.naivety.zhicolor.core

import android.content.Context
import android.widget.Toast
import android.util.Log
import com.naivety.zhicolor.R
import com.naivety.zhicolor.home.FrameworkBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object ScopeRestartController {
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val busyState = MutableStateFlow(false)
    val busy = busyState.asStateFlow()

    fun request(context: Context) {
        if (busyState.value) return
        busyState.value = true
        val app = context.applicationContext
        actionScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    ScopeRestarter(RootSessionFactory { SuSession() }, FrameworkBridge::selectedScope).restart()
                }
                Log.i("ZHIColorScope", "result=${outcome.kind} restarted=${outcome.restarted} failed=${outcome.failed}")
                Toast.makeText(app, outcome.localizedMessage(app), Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(app, app.getString(R.string.scr_restart_failed), Toast.LENGTH_LONG).show()
            } finally { busyState.value = false }
        }
    }
}

private fun RestartOutcome.localizedMessage(context: Context): String = when(kind) {
    RestartKind.ROOT_REQUIRED -> context.getString(R.string.scr_root_required)
    RestartKind.FRAMEWORK_REQUIRED -> context.getString(R.string.scr_framework_required)
    RestartKind.EMPTY -> context.getString(R.string.scr_scope_empty)
    RestartKind.FINISHED -> buildList {
        if(restarted>0) add(context.getString(R.string.scr_scope_restarted,restarted))
        if(failed>0) add(context.getString(R.string.scr_scope_failed,failed))
        if(requiresDeviceRestart) add(context.getString(R.string.scr_scope_system))
    }.joinToString(context.getString(R.string.scr_separator)).ifBlank {context.getString(R.string.scr_scope_empty)}
}
