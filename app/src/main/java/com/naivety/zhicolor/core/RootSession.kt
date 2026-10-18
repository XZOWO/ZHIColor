package com.naivety.zhicolor.core

import java.io.BufferedReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class CommandResult(val exitCode: Int, val output: String)
internal interface RootSession : AutoCloseable {
    fun run(command: String): CommandResult
}
internal fun interface RootSessionFactory { fun open(): RootSession }

/** Opened only for a user action. Standard su prompts Magisk; no manager-specific precheck/cache. */
internal class SuSession(
    private val process: Process = ProcessBuilder("su").start(),
    private val timeoutMillis: Long = 45_000,
) : RootSession {
    private val input: OutputStream = process.outputStream
    private val output: BufferedReader = process.inputStream.bufferedReader()
    private val reader = Executors.newSingleThreadExecutor { Thread(it, "ZHIColor-su-output").apply { isDaemon = true } }
    init {
        Thread({ runCatching { process.errorStream.bufferedReader().use { stream -> while (stream.readLine() != null) Unit } } }, "ZHIColor-su-error")
            .apply { isDaemon = true; start() }
    }
    @Synchronized override fun run(command: String): CommandResult {
        val marker = "__ZHICOLOR_${UUID.randomUUID().toString().replace("-", "")}__"
        return try {
            input.write((command + "\nprintf '\n" + marker + "%s\n' \"$?\"\n").toByteArray())
            input.flush()
            val task = reader.submit<CommandResult> {
                val text = StringBuilder()
                while (true) {
                    val line = output.readLine() ?: return@submit CommandResult(-1, text.toString().trim())
                    if (line.startsWith(marker)) return@submit CommandResult(line.removePrefix(marker).trim().toIntOrNull() ?: -1, text.toString().trim())
                    if (text.length < 65536) text.appendLine(line.take(65536 - text.length))
                }
                @Suppress("UNREACHABLE_CODE") CommandResult(-1, text.toString())
            }
            task.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            close()
            CommandResult(-1, "")
        }
    }
    override fun close() {
        runCatching { input.write("exit\n".toByteArray()); input.flush() }
        runCatching { input.close() }
        process.destroy()
        reader.shutdownNow()
        Thread({ runCatching { output.close() } }, "ZHIColor-su-close").apply { isDaemon = true; start() }
    }
}
