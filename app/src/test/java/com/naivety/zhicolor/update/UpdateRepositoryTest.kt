package com.naivety.zhicolor.update

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdateRepositoryTest {
    private val releases = "https://github.com/XZOWO/ZHIColor/releases"
    private val latest = "$releases/latest/download/update.json"
    private val tagged = "$releases/download/v1.0.0/update.json"
    private val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/1424132386/abcd-1234?sig=example"
    private val manifest = """{"versionCode":22,"versionName":"1.0.0","sha256":"${"ab".repeat(32)}"}"""

    private class Response(url: String, private val status: Int = 200,
        private val body: String = "", private val location: String? = null,
        private val declaredLength: Long = body.toByteArray().size.toLong(),
    ) : HttpURLConnection(URL(url)) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String) = if (name.equals("Location", true)) location else null
        override fun getContentLengthLong() = declaredLength
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    }

    @Test fun latestManifestFollowsBothRealGitHubRedirectStages() = runBlocking {
        val visited = mutableListOf<String>()
        val release = UpdateRepository(latest, releases) { url ->
            visited += url
            when (url) {
                latest -> Response(url, 302, location = tagged)
                tagged -> Response(url, 302, location = cdn)
                else -> Response(url, body = manifest)
            }
        }.latest()
        assertEquals(listOf(latest, tagged, cdn), visited)
        assertEquals("$releases/download/v1.0.0/ZHIColor-1.0.0.apk", release.downloadUrl)
    }

    @Test fun rejectsExternalInitialManifestBeforeOpeningAConnection() = runBlocking {
        for (url in listOf(cdn, "https://updates.example/update.json", latest.replace("XZOWO", "other"))) {
            val failure = runCatching { UpdateRepository(url, releases) { error("Unexpected connection") }.latest() }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
    }

    @Test fun rejectsExternalOrInsecureRedirectBeforeConnecting() = runBlocking {
        for (target in listOf("https://evil.example/update.json", cdn.replace(".com/", ".com.evil.example/"),
            cdn.replace("https://", "http://"), tagged.replace("XZOWO", "other"))) {
            var opened = 0
            val failure = runCatching { UpdateRepository(latest, releases) { url ->
                opened++
                Response(url, 302, location = target)
            }.latest() }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(1, opened)
        }
    }

    @Test fun redirectLoopIsBounded() = runBlocking {
        var opened = 0
        val failure = runCatching { UpdateRepository(latest, releases) { url ->
            opened++
            Response(url, 302, location = latest)
        }.latest() }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(4, opened)
    }

    @Test fun manifestSizeIsBoundedWithAndWithoutContentLength() = runBlocking {
        for (declared in listOf(-1L, 300_000L)) {
            val failure = runCatching { UpdateRepository(latest, releases) { url ->
                Response(url, body = "a".repeat(300_000), declaredLength = declared)
            }.latest() }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
    }

    @Test fun missingRedirectLocationAndHttpErrorsFail() = runBlocking {
        for (status in listOf(302, 404, 500)) {
            assertNotNull(runCatching { UpdateRepository(latest, releases) { Response(it, status) }.latest() }.exceptionOrNull())
        }
    }
}
