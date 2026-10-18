package com.naivety.zhicolor.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseTest {
    private val releases = "https://github.com/XZOWO/ZHIColor/releases"
    private val apk = "$releases/download/v1.0.1/ZHIColor-1.0.1.apk"
    private val digest = "ab".repeat(32)
    private fun manifest() = JSONObject().put("versionCode", 23).put("versionName", "1.0.1").put("sha256", digest)

    @Test fun readsOfficialGitHubReleaseDownloadAndDigest() {
        val parsed = UpdateManifestParser.parse(manifest().put("changelog", JSONArray(listOf("First", "Second")))
            .put("githubUrl", "$releases/tag/v1.0.1").put("downloadUrl", apk)
            .put("sha256", digest.uppercase()).toString(), releases)
        assertEquals(23L, parsed.versionCode)
        assertEquals("1.0.1", parsed.versionName)
        assertEquals("First\nSecond", parsed.changelog)
        assertEquals(digest, parsed.sha256)
        assertEquals(apk, parsed.downloadUrl)
    }

    @Test fun publishedLegacyManifestDerivesOfficialApkUrl() {
        val parsed = UpdateManifestParser.parse(manifest().put("versionCode", 22)
            .put("versionName", "1.0.0").toString(), releases)
        assertEquals(releases, parsed.githubUrl)
        assertEquals("", parsed.changelog)
        assertEquals("$releases/download/v1.0.0/ZHIColor-1.0.0.apk", parsed.downloadUrl)
    }

    @Test fun legacyCloudFieldNeverSelectsAnExternalDownload() {
        val parsed = UpdateManifestParser.parse(manifest()
            .put("cloudUrl", "https://downloads.example.cn/app.apk").toString(), releases)
        assertEquals(apk, parsed.downloadUrl)
    }

    @Test fun checksumIsRequiredAndMustBeAFullDigest() {
        for (value in listOf<Any>(JSONObject.NULL, "", "not-a-digest", "a".repeat(63), true)) {
            assertRejected(manifest().put("sha256", value))
        }
        assertRejected(manifest().apply { remove("sha256") })
        assertRejected(manifest().put("downloadUrl", JSONObject.NULL))
    }

    @Test fun malformedVersionsAreRejected() {
        for (value in listOf<Any>(0, -1, 21.5, "23", true, java.math.BigInteger("9223372036854775808"))) {
            assertRejected(manifest().put("versionCode", value))
        }
        for (name in listOf("", "../1.0.1", "1/0/1", "1.0.1?x=1", "1.0.1#tag", "1.0.1%2f..", "1.0", "v1.0.1")) {
            assertRejected(manifest().put("versionName", name))
        }
        assertTrue(UpdateUrlPolicy.validVersion("1.1.0-rc.1"))
        assertRejected(manifest().put("changelog", JSONArray(listOf(true))))
    }

    @Test fun downloadIsBoundToOfficialRepositoryTagAndAssetName() {
        for (url in listOf(
            "", " ", apk.replace("https://", "http://"), apk.replace("XZOWO", "another"),
            apk.replace("ZHIColor/releases", "another/releases"), apk.replace("v1.0.1", "v1.0.0"),
            apk.replace("ZHIColor-1.0.1.apk", "other.apk"), apk.replace("github.com", "github.com.evil.example"),
            apk.replace("github.com", "user:password@github.com"), apk.replace("github.com", "github.com:8080"),
            apk.replace("/download/", "/%2e%2e/download/"),
            "https://mirror.example/https://github.com/XZOWO/ZHIColor/app.apk",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1424132386/abcd",
            "$apk?url=https://evil.example/app.apk", "$apk#fragment",
        )) assertRejected(manifest().put("downloadUrl", url))
    }

    @Test fun releasePagesCannotEscapeTheOfficialRepository() {
        for (url in listOf("https://github.com/another/project/releases", "$releases/../issues",
            "$releases/%2e%2e/issues", "$releases-not-this/tag/v1.0.1", "$releases/tag/v1.0.1?x=1")) {
            assertRejected(manifest().put("githubUrl", url))
        }
    }

    @Test fun manifestSourcesAndCdnRedirectsAreSeparatePolicies() {
        assertTrue(UpdateUrlPolicy.githubManifest("$releases/latest/download/update.json"))
        assertTrue(UpdateUrlPolicy.githubManifest("$releases/download/v1.0.1/update.json"))
        assertFalse(UpdateUrlPolicy.githubManifest("https://raw.githubusercontent.com/XZOWO/ZHIColor/main/update.json"))
        assertFalse(UpdateUrlPolicy.githubManifest("$releases/download/v1.0.1/other.json"))
        val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/1424132386/abcd-1234?sig=example"
        assertTrue(UpdateUrlPolicy.githubAssetRedirect(cdn))
        assertFalse(UpdateUrlPolicy.githubDownload(cdn))
        assertFalse(UpdateUrlPolicy.githubAssetRedirect(cdn.replace(".com/", ".com.evil.example/")))
        assertFalse(UpdateUrlPolicy.githubAssetRedirect(cdn.replace("release-assets", "raw")))
        assertFalse(UpdateUrlPolicy.githubAssetRedirect(cdn.replace("https://", "http://")))
        assertFalse(UpdateUrlPolicy.githubAssetRedirect(cdn.replace("https://", "https://user@")))
        assertFalse(UpdateUrlPolicy.githubDownloadRedirect(apk.replace("1.0.1", "1.0.2"), apk))
    }

    @Test fun httpsEndpointsCannotDowngradeOrContainCredentials() {
        assertTrue(UpdateUrlPolicy.https(apk))
        assertFalse(UpdateUrlPolicy.https(apk.replace("https://", "http://")))
        assertFalse(UpdateUrlPolicy.https(apk.replace("https://", "https://user@")))
        assertFalse(UpdateUrlPolicy.https("https://github.com:8080/update.json"))
        assertFalse(UpdateUrlPolicy.https("https://github.com/has space.json"))
    }

    @Test fun ignoreOnlyAffectsThatExactVersionAndManualChecksOverrideIt() {
        assertFalse(UpdateVersionPolicy.shouldOffer(20, 20, -1, manual = false))
        assertFalse(UpdateVersionPolicy.shouldOffer(19, 20, -1, manual = true))
        assertFalse(UpdateVersionPolicy.shouldOffer(21, 20, 21, manual = false))
        assertTrue(UpdateVersionPolicy.shouldOffer(21, 20, 21, manual = true))
        assertTrue(UpdateVersionPolicy.shouldOffer(22, 20, 21, manual = false))
        assertTrue(UpdateVersionPolicy.shouldOffer(21, 20, 22, manual = false))
    }

    private fun assertRejected(json: JSONObject) {
        try {
            UpdateManifestParser.parse(json.toString(), releases)
            fail("Invalid update manifest was accepted: $json")
        } catch (_: IllegalArgumentException) {
            // Invalid metadata must never become an install action.
        }
    }
}
