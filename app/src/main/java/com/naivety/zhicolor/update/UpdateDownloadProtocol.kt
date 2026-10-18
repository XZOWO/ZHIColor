package com.naivety.zhicolor.update

import kotlinx.coroutines.CancellationException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Bounded HTTPS transfer, split out so redirects, hash handling and cancellation are JVM-testable. */
internal object UpdateDownloadProtocol {
    data class Result(val size: Long)

    fun download(
        startUrl: String,
        output: File,
        expectedSha256: String,
        maxBytes: Long,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        openConnection: (String) -> HttpURLConnection,
        onProgress: (UpdateDownloadProgress) -> Unit,
    ): Result {
        require(maxBytes > 0)
        require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}")))
        var currentUrl = startUrl
        var received = 0L
        var connection: HttpURLConnection? = null
        var complete = false
        try {
            repeat(MAX_REDIRECTS + 1) { redirectCount ->
                if (!UpdateUrlPolicy.cloudDownload(currentUrl)) {
                    throw UpdateOperationException(UpdateOperationFailureCode.INVALID_DOWNLOAD_URL)
                }
                onProgress(UpdateDownloadProgress(UpdateDownloadStage.CONNECTING, received))
                connection = openConnection(currentUrl).apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
                    setRequestProperty("User-Agent", "ZHIColor-Updater")
                }
                val response = connection ?: throw UpdateOperationException(UpdateOperationFailureCode.NETWORK)
                val status = response.responseCode
                if (status in REDIRECTS) {
                    if (redirectCount >= MAX_REDIRECTS) throw UpdateOperationException(UpdateOperationFailureCode.HTTP)
                    val location = response.getHeaderField("Location")
                        ?: throw UpdateOperationException(UpdateOperationFailureCode.HTTP)
                    currentUrl = URI(currentUrl).resolve(location).toString()
                    response.disconnect()
                    connection = null
                    return@repeat
                }
                if (status != HttpURLConnection.HTTP_OK) throw UpdateOperationException(UpdateOperationFailureCode.HTTP)
                val contentLength = response.contentLengthLong.takeIf { it >= 0L }
                if (contentLength != null && contentLength > maxBytes) {
                    throw UpdateOperationException(UpdateOperationFailureCode.DOWNLOAD_TOO_LARGE)
                }
                val digest = MessageDigest.getInstance("SHA-256")
                BufferedInputStream(response.inputStream).use { input ->
                    BufferedOutputStream(FileOutputStream(output, false)).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (Thread.interrupted()) throw InterruptedException("Download interrupted")
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > maxBytes) throw UpdateOperationException(UpdateOperationFailureCode.DOWNLOAD_TOO_LARGE)
                            digest.update(buffer, 0, count)
                            out.write(buffer, 0, count)
                            onProgress(UpdateDownloadProgress(UpdateDownloadStage.DOWNLOADING, received, contentLength))
                        }
                    }
                }
                if (contentLength != null && received != contentLength) {
                    throw UpdateOperationException(UpdateOperationFailureCode.HTTP)
                }
                onProgress(UpdateDownloadProgress(UpdateDownloadStage.VERIFYING, received, contentLength))
                val actualSha = digest.digest().toHex()
                val expected = expectedSha256.lowercase(Locale.ROOT)
                if (!MessageDigest.isEqual(actualSha.toByteArray(), expected.toByteArray())) {
                    throw UpdateOperationException(UpdateOperationFailureCode.CHECKSUM_MISMATCH)
                }
                complete = true
                return Result(received)
            }
            throw UpdateOperationException(UpdateOperationFailureCode.HTTP)
        } catch (failure: UpdateOperationException) {
            throw failure
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Download cancelled").also { it.initCause(failure) }
        } catch (failure: Exception) {
            throw UpdateOperationException(UpdateOperationFailureCode.NETWORK, failure)
        } finally {
            connection?.disconnect()
            if (!complete) output.delete()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val MAX_REDIRECTS = 3
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)
}

internal class DownloadProgressReporter(private val callback: (UpdateDownloadProgress) -> Unit) {
    private var lastStage: UpdateDownloadStage? = null
    private var lastPercent: Int? = null
    private var lastAtMillis = 0L

    @Synchronized fun report(progress: UpdateDownloadProgress, force: Boolean = false) {
        val now = System.nanoTime() / 1_000_000
        val percentChanged = progress.percent != null && progress.percent != lastPercent
        if (!force && progress.stage == lastStage && !percentChanged && now - lastAtMillis < 100) return
        callback(progress)
        lastStage = progress.stage
        lastPercent = progress.percent
        lastAtMillis = now
    }
}
