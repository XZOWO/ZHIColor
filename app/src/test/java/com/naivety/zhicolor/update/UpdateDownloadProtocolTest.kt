package com.naivety.zhicolor.update

import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadProtocolTest {
    @get:Rule val folder = TemporaryFolder()
    private val apk = "https://github.com/XZOWO/ZHIColor/releases/download/v1.0.1/ZHIColor-1.0.1.apk"
    private val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/1424132386/abcd-1234?sig=example"

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private open class FakeConnection(
        url: URL,
        private val status: Int,
        private val body: ByteArray = byteArrayOf(),
        private val headers: Map<String, String> = emptyMap(),
    ) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun getInputStream() = ByteArrayInputStream(body)
    }

    private fun run(
        body: ByteArray,
        hash: String = sha(body),
        maxBytes: Long = 256_000,
        contentLength: String? = body.size.toString(),
    ): Pair<File, List<UpdateDownloadProgress>> {
        val output = folder.newFile("download.apk")
        val updates = mutableListOf<UpdateDownloadProgress>()
        val headers = contentLength?.let { mapOf("Content-Length" to it) }.orEmpty()
        UpdateDownloadProtocol.download(
            startUrl = apk,
            output = output,
            expectedSha256 = hash,
            maxBytes = maxBytes,
            connectTimeoutMs = 1000,
            readTimeoutMs = 1000,
            openConnection = { FakeConnection(URL(it), 200, body, headers) },
            onProgress = updates::add,
        )
        return output to updates
    }

    @Test fun successfulDownloadHasCorrectDigestAndProgress() {
        val bytes = ByteArray(120_000) { (it % 127).toByte() }
        val (file, progress) = run(bytes)
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(progress.any { it.stage == UpdateDownloadStage.CONNECTING })
        assertTrue(progress.any { it.stage == UpdateDownloadStage.DOWNLOADING })
        assertTrue(progress.any { it.stage == UpdateDownloadStage.VERIFYING })
    }

    @Test fun incorrectHashDeletesPartialFile() {
        val bytes = byteArrayOf(1, 2, 3)
        val file = folder.newFile("wrong-hash.apk")
        val failure = runCatching {
            UpdateDownloadProtocol.download(
                apk, file, "0".repeat(64), 50, 1000, 1000,
                { FakeConnection(URL(it), 200, bytes, mapOf("Content-Length" to "3")) }, {},
            )
        }.exceptionOrNull() as UpdateOperationException
        assertEquals(UpdateOperationFailureCode.CHECKSUM_MISMATCH, failure.code)
        assertFalse(file.exists())
    }

    @Test fun contentLengthAndStreamingLimitsAreEnforced() {
        val bytes = ByteArray(12) { 1 }
        val lengthFailure = runCatching { run(bytes, maxBytes = 10) }.exceptionOrNull() as UpdateOperationException
        assertEquals(UpdateOperationFailureCode.DOWNLOAD_TOO_LARGE, lengthFailure.code)

        val streamingFailure = runCatching {
            val output = folder.newFile("stream-limit.apk")
            UpdateDownloadProtocol.download(
                apk, output, sha(bytes), 10, 1000, 1000,
                { FakeConnection(URL(it), 200, bytes) }, {},
            )
        }.exceptionOrNull() as UpdateOperationException
        assertEquals(UpdateOperationFailureCode.DOWNLOAD_TOO_LARGE, streamingFailure.code)
    }

    @Test fun redirectsAreRevalidatedAndBounded() {
        val file = folder.newFile("redirect.apk")
        val failure = runCatching {
            UpdateDownloadProtocol.download(
                apk, file, sha(byteArrayOf()), 100, 1000, 1000,
                { FakeConnection(URL(it), 302, headers = mapOf("Location" to "http://cloud.example/b.apk")) }, {},
            )
        }.exceptionOrNull() as UpdateOperationException
        assertEquals(UpdateOperationFailureCode.INVALID_DOWNLOAD_URL, failure.code)
        assertFalse(file.exists())
    }

    @Test fun interruptCancelsAndRemovesPartialFile() {
        val file = folder.newFile("cancel.apk")
        val interrupted = ByteArray(64 * 1024) { 7 }
        val failure = runCatching {
            UpdateDownloadProtocol.download(
                apk, file, sha(interrupted), 100_000, 1000, 1000,
                { url -> object : FakeConnection(URL(url), 200, interrupted) {
                    override fun getInputStream() = object : ByteArrayInputStream(interrupted) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            val read = super.read(buffer, offset, length)
                            if (read > 0) Thread.currentThread().interrupt()
                            return read
                        }
                    }
                } }, {},
            )
        }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertFalse(file.exists())
        Thread.interrupted()
    }

    @Test fun githubReleaseRedirectDownloadsFromTheOfficialCdn() {
        val file = folder.newFile("github.apk")
        val bytes = byteArrayOf(1, 2, 3)
        val visited = mutableListOf<String>()
        UpdateDownloadProtocol.download(apk, file, sha(bytes), 100, 1000, 1000, { url ->
            visited += url
            if (url == apk) FakeConnection(URL(url), 302, headers = mapOf("Location" to cdn))
            else FakeConnection(URL(url), 200, bytes, mapOf("Content-Length" to "3"))
        }, {})
        assertEquals(listOf(apk, cdn), visited)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun nonGithubStartUrlsAreRejectedBeforeAnyConnection() {
        for ((index, url) in listOf(cdn, "https://cloud.example/a.apk", apk.replace("XZOWO", "other")).withIndex()) {
            val file = folder.newFile("start-$index.apk")
            val failure = runCatching {
                UpdateDownloadProtocol.download(url, file, sha(byteArrayOf()), 100, 1000, 1000,
                    { error("Unexpected connection to $it") }, {})
            }.exceptionOrNull() as UpdateOperationException
            assertEquals(UpdateOperationFailureCode.INVALID_DOWNLOAD_URL, failure.code)
            assertFalse(file.exists())
        }
    }

    @Test fun externalAndDifferentVersionRedirectsAreRejectedBeforeConnecting() {
        for ((index, target) in listOf("https://evil.example/app.apk", cdn.replace(".com/", ".com.evil.example/"),
            apk.replace("1.0.1", "1.0.2"), cdn.replace("https://", "http://")).withIndex()) {
            val file = folder.newFile("target-$index.apk")
            var opened = 0
            val failure = runCatching {
                UpdateDownloadProtocol.download(apk, file, sha(byteArrayOf()), 100, 1000, 1000, { url ->
                    opened++
                    FakeConnection(URL(url), 302, headers = mapOf("Location" to target))
                }, {})
            }.exceptionOrNull() as UpdateOperationException
            assertEquals(UpdateOperationFailureCode.INVALID_DOWNLOAD_URL, failure.code)
            assertEquals(1, opened)
            assertFalse(file.exists())
        }
    }

    @Test fun redirectLoopStopsAfterThreeRedirects() {
        val file = folder.newFile("loop.apk")
        var opened = 0
        val failure = runCatching {
            UpdateDownloadProtocol.download(apk, file, sha(byteArrayOf()), 100, 1000, 1000, { url ->
                opened++
                FakeConnection(URL(url), 302, headers = mapOf("Location" to apk))
            }, {})
        }.exceptionOrNull() as UpdateOperationException
        assertEquals(UpdateOperationFailureCode.HTTP, failure.code)
        assertEquals(4, opened)
        assertFalse(file.exists())
    }

    @Test fun truncatedResponseIsDeleted() {
        val bytes = byteArrayOf(1, 2, 3)
        val failure = runCatching { run(bytes, contentLength = "4") }.exceptionOrNull() as UpdateOperationException
        assertEquals(UpdateOperationFailureCode.HTTP, failure.code)
        assertFalse(File(folder.root, "download.apk").exists())
    }
}
