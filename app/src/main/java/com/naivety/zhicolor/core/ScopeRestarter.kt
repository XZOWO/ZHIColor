package com.naivety.zhicolor.core

internal data class ScopeCommand(val packageName: String, val command: String)
internal data class ScopePlan(val commands: List<ScopeCommand>, val requiresDeviceRestart: Boolean)

internal object ScopeRestartPolicy {
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    fun plan(scopes: List<String>, ownPackage: String): ScopePlan {
        val commands = scopes.distinct().filter { it != ownPackage && packageName.matches(it) }.map { pkg ->
            ScopeCommand(pkg, if (pkg == "com.android.systemui") {
                "(pids=\"$(pidof com.android.systemui)\"; [ -n \"\$pids\" ] || exit 3; for p in \$pids; do kill -TERM \"\$p\" || exit 1; done)"
            } else {
                "am force-stop --user current '$pkg'"
            })
        }
        return ScopePlan(commands, scopes.any { it in setOf("android", "system", "system_server") })
    }
}

internal enum class RestartKind { ROOT_REQUIRED, FRAMEWORK_REQUIRED, EMPTY, FINISHED }
internal data class RestartOutcome(
    val kind: RestartKind,
    val restarted: Int = 0,
    val failed: Int = 0,
    val requiresDeviceRestart: Boolean = false,
) {
    fun message(): String = when (kind) {
        RestartKind.ROOT_REQUIRED -> "未获得 Root 权限，请在 Root 管理器中授予 ZHIColor 权限后重试"
        RestartKind.FRAMEWORK_REQUIRED -> "请先在 LSPosed 中启用 ZHIColor，再重新打开应用"
        RestartKind.EMPTY -> "未选择可重启的应用作用域"
        RestartKind.FINISHED -> buildString {
            if (restarted > 0) append("已重启 $restarted 个作用域")
            if (failed > 0) { if (isNotEmpty()) append("，"); append("$failed 个重启失败") }
            if (requiresDeviceRestart) { if (isNotEmpty()) append("；"); append("系统框架作用域请重启设备生效") }
        }.ifBlank { "未选择可重启的应用作用域" }
    }
}

internal class ScopeRestarter(
    private val sessions: RootSessionFactory,
    private val scope: () -> List<String>?,
    private val ownPackage: String = "com.naivety.zhicolor",
) {
    /** Every click opens a new su session and retries authorization. Never called at startup. */
    fun restart(): RestartOutcome {
        val session = try { sessions.open() } catch (_: Exception) { return RestartOutcome(RestartKind.ROOT_REQUIRED) }
        session.use {
            val authorization = try { it.run("id -u") } catch (_: Exception) { CommandResult(-1, "") }
            if (authorization.exitCode != 0 || authorization.output.lineSequence().filter(String::isNotBlank).lastOrNull()?.trim() != "0") {
                return RestartOutcome(RestartKind.ROOT_REQUIRED)
            }
            val selected = runCatching { scope() }.getOrNull() ?: return RestartOutcome(RestartKind.FRAMEWORK_REQUIRED)
            val plan = ScopeRestartPolicy.plan(selected, ownPackage)
            if (plan.commands.isEmpty() && !plan.requiresDeviceRestart) return RestartOutcome(RestartKind.EMPTY)
            var ok = 0
            var failed = 0
            plan.commands.forEach { target ->
                val result = runCatching { it.run(target.command) }.getOrDefault(CommandResult(-1, ""))
                if (result.exitCode == 0) ok++ else failed++
            }
            return RestartOutcome(RestartKind.FINISHED, ok, failed, plan.requiresDeviceRestart)
        }
    }
}
