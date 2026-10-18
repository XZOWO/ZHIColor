package com.naivety.zhicolor.update

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val changelog: String,
    val githubUrl: String,
    val cloudUrl: String?,
    val sha256: String?,
)

object UpdateUrlPolicy {
    fun https(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) && !value.any(Char::isWhitespace)
    }.getOrDefault(false)

    fun githubRelease(value: String): Boolean = https(value) && runCatching {
        val uri = URI(value)
        val path = uri.path.lowercase(Locale.ROOT)
        uri.host.equals("github.com", ignoreCase = true) &&
            (path == "/xzowo/zhicolor/releases" || path.startsWith("/xzowo/zhicolor/releases/")) &&
            uri.path.split('/').none { it == "." || it == ".." }
    }.getOrDefault(false)

    fun cloudDownload(value: String): Boolean = https(value) && runCatching {
        val uri = URI(value)
        val host = uri.host.lowercase(Locale.ROOT).trimEnd('.')
        val githubHosts = listOf("github.com", "githubusercontent.com", "githubassets.com", "github.io")
        githubHosts.none { host == it || host.endsWith(".$it") } &&
            !host.contains("ghproxy") && !host.contains("gh-proxy") &&
            !uri.path.orEmpty().contains("github.com", ignoreCase = true) &&
            !uri.query.orEmpty().contains("github.com", ignoreCase = true)
    }.getOrDefault(false)
}

object UpdateManifestParser {
    fun parse(text: String, defaultGithubUrl: String): UpdateRelease {
        val json = JSONObject(text)
        val codeValue = json.get("versionCode")
        val code = (codeValue as? Number)?.toString()?.toLongOrNull()
        require(code != null && code > 0) { "Invalid versionCode" }
        require(json.get("versionName") is String) { "Invalid versionName" }
        val name = json.getString("versionName").trim()
        require(name.isNotEmpty() && name.length <= 80) { "Invalid versionName" }
        val changelog = when (val value = json.opt("changelog")) {
            null, JSONObject.NULL -> ""
            is String -> value.trim()
            is JSONArray -> (0 until value.length()).joinToString("\n") { index ->
                require(value.get(index) is String) { "Invalid changelog entry" }
                value.getString(index).trim()
            }.trim()
            else -> throw IllegalArgumentException("Invalid changelog")
        }
        require(changelog.length <= 30_000) { "Changelog too long" }
        val github = optionalString(json, "githubUrl") ?: defaultGithubUrl
        require(UpdateUrlPolicy.githubRelease(github)) { "Invalid GitHub release URL" }
        val cloud = optionalString(json, "cloudUrl")
        require(cloud == null || UpdateUrlPolicy.cloudDownload(cloud)) { "Invalid cloud download URL" }
        val sha256 = optionalString(json, "sha256")
        require(sha256 == null || sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid SHA-256" }
        return UpdateRelease(code, name, changelog, github, cloud, sha256?.lowercase(Locale.ROOT))
    }

    private fun optionalString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        require(json.get(key) is String) { "Invalid $key" }
        return json.getString(key).trim().ifEmpty { null }
    }
}

object UpdateVersionPolicy {
    fun shouldOffer(remoteCode: Long, installedCode: Long, ignoredCode: Long, manual: Boolean): Boolean =
        remoteCode > installedCode && (manual || remoteCode != ignoredCode)
}
