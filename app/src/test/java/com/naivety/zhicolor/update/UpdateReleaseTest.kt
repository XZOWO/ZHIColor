package com.naivety.zhicolor.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateReleaseTest {
    private val releases = "https://github.com/XZOWO/ZHIColor/releases"

    @Test
    fun readsReleaseAndIndependentCloudDownload() {
        val parsed = UpdateManifestParser.parse(
            """{
                "versionCode": 21,
                "versionName": "0.2.17",
                "changelog": ["First change", "Second change"],
                "githubUrl": "https://github.com/XZOWO/ZHIColor/releases/tag/v0.2.17",
                "cloudUrl": "https://downloads.example.cn/zhicolor/21/ZHIColor.apk",
                "sha256": "${"AB".repeat(32)}"
            }""".trimIndent(), releases,
        )
        assertEquals(21L, parsed.versionCode)
        assertEquals("First change\nSecond change", parsed.changelog)
        assertEquals("ab".repeat(32), parsed.sha256)
        assertEquals("https://downloads.example.cn/zhicolor/21/ZHIColor.apk", parsed.cloudUrl)
    }

    @Test
    fun optionalFieldsHaveDefinedDefaults() {
        val parsed = UpdateManifestParser.parse("""{"versionCode":21,"versionName":"0.2.17"}""", releases)
        assertEquals(releases, parsed.githubUrl)
        assertEquals("", parsed.changelog)
        assertNull(parsed.cloudUrl)
        assertNull(parsed.sha256)
    }

    @Test
    fun rejectsMalformedVersionsAndUrls() {
        val invalidVersions = listOf("0", "-1", "21.5", "\"21\"", "true", "9223372036854775808")
        invalidVersions.forEach { code ->
            assertRejected("""{"versionCode":$code,"versionName":"0.2.17"}""")
        }
        assertRejected("""{"versionCode":21,"versionName":" "}""")
        assertRejected(manifestWith("githubUrl", "https://github.com/another/project/releases"))
        assertRejected(manifestWith("githubUrl", "https://github.com/XZOWO/ZHIColor/releases/../issues"))
        assertRejected(manifestWith("githubUrl", "https://github.com/XZOWO/ZHIColor/releases/%2e%2e/issues"))
        assertRejected(manifestWith("cloudUrl", "http://downloads.example.cn/app.apk"))
        assertRejected(manifestWith("cloudUrl", "https://github.com/XZOWO/ZHIColor/releases/download/v1/app.apk"))
        assertRejected(manifestWith("cloudUrl", "https://mirror.example.cn/https://github.com/XZOWO/ZHIColor/app.apk"))
        assertRejected(manifestWith("cloudUrl", "https://user:password@downloads.example.cn/app.apk"))
        assertRejected(manifestWith("sha256", "not-a-digest"))
        assertRejected("""{"versionCode":21,"versionName":"0.2.17","changelog":[true]}""")
    }

    @Test
    fun httpsEndpointsCannotDowngradeOrContainCredentials() {
        assertTrue(UpdateUrlPolicy.https("https://downloads.example.cn/latest.json"))
        assertFalse(UpdateUrlPolicy.https("http://downloads.example.cn/latest.json"))
        assertFalse(UpdateUrlPolicy.https("https://user@example.cn/latest.json"))
        assertFalse(UpdateUrlPolicy.https("https://example.cn:8080/latest.json"))
        assertFalse(UpdateUrlPolicy.https("https://example.cn/has space.json"))
        assertFalse(UpdateUrlPolicy.cloudDownload("https://raw.githubusercontent.com/XZOWO/ZHIColor/main/app.apk"))
        assertFalse(UpdateUrlPolicy.cloudDownload("https://xzowo.github.io/app.apk"))
        assertFalse(UpdateUrlPolicy.cloudDownload("https://ghproxy.example.cn/app.apk"))
    }

    @Test
    fun ignoreOnlyAffectsThatExactVersionAndManualChecksOverrideIt() {
        assertFalse(UpdateVersionPolicy.shouldOffer(20, 20, -1, manual = false))
        assertFalse(UpdateVersionPolicy.shouldOffer(19, 20, -1, manual = true))
        assertFalse(UpdateVersionPolicy.shouldOffer(21, 20, 21, manual = false))
        assertTrue(UpdateVersionPolicy.shouldOffer(21, 20, 21, manual = true))
        assertTrue(UpdateVersionPolicy.shouldOffer(22, 20, 21, manual = false))
        assertTrue(UpdateVersionPolicy.shouldOffer(21, 20, 22, manual = false))
    }

    private fun manifestWith(key: String, value: String): String = JSONObject()
        .put("versionCode", 21).put("versionName", "0.2.17").put(key, value).toString()

    private fun assertRejected(json: String) {
        try {
            UpdateManifestParser.parse(json, releases)
            fail("Invalid update manifest was accepted: $json")
        } catch (_: IllegalArgumentException) {
            // Expected: reject before exposing a release action to the UI.
        }
    }
}
