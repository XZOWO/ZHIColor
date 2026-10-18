package com.naivety.zhicolor.update

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Explicit opt-in only: ordinary unit tests never depend on a live network. */
class GitHubUpdateLiveTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun publishedManifestAndApkUseTheProductionTransferCode() = runBlocking {
        assumeTrue(System.getenv("ZHICOLOR_LIVE_UPDATE_TEST") == "1")
        val releases = "https://github.com/XZOWO/ZHIColor/releases"
        val release = UpdateRepository("$releases/latest/download/update.json", releases).latest()
        val apk = folder.newFile("github-release.apk")
        val result = UpdateDownloadProtocol.download(release.downloadUrl, apk, release.sha256,
            256L * 1024 * 1024, 10_000, 20_000,
            { URL(it).openConnection() as HttpURLConnection }, {})
        val digest = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(release.sha256, digest)
        assertEquals(apk.length(), result.size)
        assertTrue(result.size > 0)
        val report = JSONObject().put("status", "PASS").put("versionName", release.versionName)
            .put("versionCode", release.versionCode).put("bytes", result.size).put("sha256", digest)
            .put("productionManifestAndDownloadProtocol", true)
        System.getenv("ZHICOLOR_UPDATE_NETWORK_REPORT")?.let { File(it).writeText(report.toString(2)) }
        println("LIVE_GITHUB_PASS version=${release.versionName} bytes=${result.size} sha256=$digest")
    }
}
