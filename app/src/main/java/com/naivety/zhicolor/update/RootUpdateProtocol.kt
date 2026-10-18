package com.naivety.zhicolor.update

import com.naivety.zhicolor.core.CommandResult
import com.naivety.zhicolor.core.RootSessionFactory
import java.io.File
import java.util.Locale

/** Pure, bounded parsing used for the persistent result written by the detached installer. */
internal object UpdateInstallProtocol {
    private val receiptIdPattern = Regex("[a-f0-9]{32}")
    internal data class ParsedReceipt(val id: String, val target: Long, val state: String, val exitCode: Int?)

    fun validId(value: String): Boolean = receiptIdPattern.matches(value)

    fun receipt(id: String, target: Long, state: String, exitCode: Int?): String =
        "id=$id\ntarget=$target\nstatus=$state\nexit=${exitCode ?: ""}\n"

    fun parseReceipt(text: String): ParsedReceipt? {
        if (text.length > 4096) return null
        val values = text.lineSequence().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index < 1) null else line.substring(0, index) to line.substring(index + 1)
        }.toMap()
        val id = values["id"]?.takeIf(::validId) ?: return null
        val target = values["target"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val state = values["status"]?.takeIf { it in setOf("pending", "command_ok", "failed") } ?: return null
        val exit = values["exit"]?.takeIf(String::isNotBlank)?.toIntOrNull()
        if (state == "failed" && exit == null) return null
        return ParsedReceipt(id, target, state, exit)
    }

    fun status(actualVersion: Long, targetVersion: Long, receipt: ParsedReceipt?, ageMillis: Long): UpdateReceiptStatus = when {
        targetVersion > 0 && actualVersion >= targetVersion -> UpdateReceiptStatus.INSTALLED
        receipt?.state == "failed" -> UpdateReceiptStatus.FAILED
        receipt?.state in setOf("pending", "command_ok") && ageMillis < INSTALL_RECEIPT_TIMEOUT_MS -> UpdateReceiptStatus.PENDING
        else -> UpdateReceiptStatus.UNKNOWN
    }

    fun installCommand(apkPath: String): String = "pm install -r ${shellQuote(apkPath)}"

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    const val INSTALL_RECEIPT_TIMEOUT_MS = 10 * 60 * 1000L
}

/**
 * Root-side staging and detached installer dispatch. Root output is not treated as install success.
 * The receipt files are created by the app before root execution to retain app-data labels.
 */
internal class RootUpdateDispatcher(
    private val sessions: RootSessionFactory,
    private val frameworkAvailable: () -> Boolean,
) {
    fun dispatch(
        id: String,
        apkPath: String,
        sha256: String,
        receiptPath: String,
        targetVersionCode: Long,
        appUid: Int,
        beforeLaunch: () -> Unit,
    ) {
        require(UpdateInstallProtocol.validId(id))
        require(sha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(targetVersionCode > 0 && appUid >= 0)
        val source = File(apkPath).canonicalFile
        require(source.isFile && source.length() in 1..(150L * 1024 * 1024))
        val receipt = File(receiptPath).canonicalFile
        val temporary = File(receipt.path + ".tmp")
        require(receipt.isFile && temporary.isFile && receipt.parentFile == temporary.parentFile)

        val session = try {
            sessions.open()
        } catch (failure: Exception) {
            throw UpdateOperationException(UpdateOperationFailureCode.ROOT_REQUIRED, failure)
        }
        session.use { root ->
            val authorization = runCatching { root.run("id -u") }.getOrDefault(CommandResult(-1, ""))
            if (!rootAuthorized(authorization)) throw UpdateOperationException(UpdateOperationFailureCode.ROOT_REQUIRED)
            if (!runCatching(frameworkAvailable).getOrDefault(false)) {
                throw UpdateOperationException(UpdateOperationFailureCode.FRAMEWORK_REQUIRED)
            }

            val directory = "/data/local/tmp/zhicolor-update-$id"
            val stagedApk = "$directory/update.apk"
            val scriptPath = "$directory/install.sh"
            val script = installerScript(stagedApk, directory, receipt.path, temporary.path, id, targetVersionCode, appUid)
            val stage = buildString {
                append("DIR=").append(quote(directory)).append("; ")
                // If copy/hash/script creation fails, clean the partial privileged staging tree
                // even when the app-side error path cannot issue a second su command.
                append("trap 'rm -rf \"${'$'}DIR\"' EXIT HUP INT TERM; ")
                append("umask 077; ")
                append("mkdir -m 755 ").append(quote(directory)).append(" || exit 41; ")
                append("cp ").append(quote(source.path)).append(' ').append(quote(stagedApk)).append(" || exit 42; ")
                append("chmod 644 ").append(quote(stagedApk)).append(" || exit 43; ")
                append("test \"$(sha256sum ").append(quote(stagedApk)).append(" | cut -d ' ' -f 1)\" = ")
                    .append(quote(sha256.lowercase(Locale.ROOT))).append(" || exit 44; ")
                append("printf %s ").append(quote(script)).append(" > ").append(quote(scriptPath)).append(" || exit 45; ")
                append("chmod 700 ").append(quote(scriptPath)).append(" || exit 46")
                append("; trap - EXIT HUP INT TERM")
            }
            val staged = runCatching { root.run("($stage)") }.getOrDefault(CommandResult(-1, ""))
            if (staged.exitCode != 0) {
                cleanupStaging(root, directory, retryWithNewSession = Thread.currentThread().isInterrupted)
                throw UpdateOperationException(UpdateOperationFailureCode.STAGING_FAILED)
            }

            try {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Update cancelled before dispatch")
                beforeLaunch()
            } catch (failure: Exception) {
                cleanupStaging(root, directory, retryWithNewSession = shouldRetryCleanup(failure))
                throw failure
            }
            // Recheck both grants at the last app-side boundary before detaching the installer.
            try {
                val launchAuthorization = runCatching { root.run("id -u") }.getOrDefault(CommandResult(-1, ""))
                if (!rootAuthorized(launchAuthorization)) {
                    if (launchAuthorization.exitCode < 0) {
                        cleanupStaging(root, directory, retryWithNewSession = true)
                    }
                    throw UpdateOperationException(UpdateOperationFailureCode.ROOT_REQUIRED)
                }
                if (!runCatching(frameworkAvailable).getOrDefault(false)) {
                    throw UpdateOperationException(UpdateOperationFailureCode.FRAMEWORK_REQUIRED)
                }
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Update cancelled before dispatch")
            } catch (failure: Exception) {
                cleanupStaging(root, directory, retryWithNewSession = shouldRetryCleanup(failure))
                throw failure
            }
            val launch = runCatching {
                root.run("(nohup sh ${quote(scriptPath)} </dev/null >/dev/null 2>&1 &); echo ZHI_DISPATCHED")
            }.getOrDefault(CommandResult(-1, ""))
            if (launch.exitCode != 0 || "ZHI_DISPATCHED" !in launch.output) {
                // Keep the local pending receipt: a detached process might have started even if
                // its acknowledgement was lost. Reconciliation checks the actual installed code.
                throw UpdateOperationException(UpdateOperationFailureCode.DISPATCH_FAILED)
            }
        }
    }

    private fun shouldRetryCleanup(failure: Exception): Boolean =
        Thread.currentThread().isInterrupted ||
            failure is java.util.concurrent.CancellationException ||
            failure !is UpdateOperationException ||
            failure.code != UpdateOperationFailureCode.ROOT_REQUIRED &&
            failure.code != UpdateOperationFailureCode.FRAMEWORK_REQUIRED

    /** Retry cleanup through a fresh root session if cancellation broke the original su session. */
    private fun cleanupStaging(root: com.naivety.zhicolor.core.RootSession, directory: String, retryWithNewSession: Boolean) {
        val command = "rm -rf " + quote(directory)
        val wasInterrupted = Thread.interrupted()
        try {
            val result = runCatching { root.run(command) }.getOrNull()
            if (result?.exitCode == 0 || !retryWithNewSession) return
            runCatching { sessions.open().use { it.run(command) } }
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    private fun installerScript(
        apk: String,
        directory: String,
        receipt: String,
        temporary: String,
        id: String,
        target: Long,
        uid: Int,
    ): String = """#!/system/bin/sh
set -u
APK=${quote(apk)}
DIR=${quote(directory)}
RECEIPT=${quote(receipt)}
TMP=${quote(temporary)}
ID=${quote(id)}
TARGET=${quote(target.toString())}
APP_UID=${quote(uid.toString())}
cleanup() { rm -rf "${'$'}DIR"; }
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
write_result() {
  STATE=${'$'}1
  CODE=${'$'}2
  printf 'id=%s\ntarget=%s\nstatus=%s\nexit=%s\n' "${'$'}ID" "${'$'}TARGET" "${'$'}STATE" "${'$'}CODE" > "${'$'}TMP"
  chown "${'$'}APP_UID:${'$'}APP_UID" "${'$'}TMP" 2>/dev/null || true
  chmod 600 "${'$'}TMP" 2>/dev/null || true
  mv -f "${'$'}TMP" "${'$'}RECEIPT" 2>/dev/null || true
}
${UpdateInstallProtocol.installCommand(apk)} >/dev/null 2>&1
RESULT=${'$'}?
if [ "${'$'}RESULT" -eq 0 ]; then write_result command_ok 0; else write_result failed "${'$'}RESULT"; fi
exit 0
""".trimIndent()

    private fun rootAuthorized(result: CommandResult): Boolean = result.exitCode == 0 &&
        result.output.lineSequence().filter(String::isNotBlank).lastOrNull()?.trim() == "0"

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

