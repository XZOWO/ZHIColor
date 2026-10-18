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
    val downloadUrl: String,
    val sha256: String,
)

object UpdateUrlPolicy {
    private val versionPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?")

    fun validVersion(value: String): Boolean = value.length <= 80 && versionPattern.matches(value)

    fun https(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) && uri.rawFragment == null &&
            !value.any(Char::isWhitespace)
    }.getOrDefault(false)

    fun githubRelease(value: String): Boolean = https(value) && runCatching {
        val uri = URI(value)
        val path = repositoryPath(uri)
        path != null && (path == "" || path == "/latest" ||
            (path.startsWith("/tag/v") && validVersion(path.removePrefix("/tag/v"))))
    }.getOrDefault(false)

    fun githubApkUrl(versionName: String): String {
        require(validVersion(versionName)) { "Invalid versionName" }
        return "https://github.com/XZOWO/ZHIColor/releases/download/v$versionName/ZHIColor-$versionName.apk"
    }

    fun githubDownload(value: String, versionName: String? = null): Boolean = https(value) && runCatching {
        val uri = URI(value)
        val path = repositoryPath(uri) ?: return@runCatching false
        val version = path.removePrefix("/download/v").substringBefore('/')
        validVersion(version) && (versionName == null || version == versionName) &&
            path == "/download/v$version/ZHIColor-$version.apk"
    }.getOrDefault(false)

    fun githubManifest(value: String): Boolean = https(value) && runCatching {
        val path = repositoryPath(URI(value)) ?: return@runCatching false
        val version = path.removePrefix("/download/v").substringBefore('/')
        path == "/latest/download/update.json" ||
            (validVersion(version) && path == "/download/v$version/update.json")
    }.getOrDefault(false)

    /** Only redirects from a validated repository URL may reach GitHub's signed asset CDN. */
    fun githubAssetRedirect(value: String): Boolean = https(value) && runCatching {
        val uri = URI(value)
        uri.host.equals("release-assets.githubusercontent.com", ignoreCase = true) &&
            uri.rawPath.matches(Regex("/github-production-release-asset/[0-9]+/[0-9a-fA-F-]+"))
    }.getOrDefault(false)

    fun githubDownloadRedirect(value: String, startUrl: String): Boolean = githubAssetRedirect(value) ||
        (githubDownload(value) && URI(value).rawPath == URI(startUrl).rawPath)

    private fun repositoryPath(uri: URI): String? {
        if (!uri.host.equals("github.com", ignoreCase = true) || uri.rawQuery != null) return null
        val prefix = "/XZOWO/ZHIColor/releases"
        val path = uri.rawPath.orEmpty()
        if (!path.startsWith(prefix, ignoreCase = true)) return null
        return path.substring(prefix.length)
    }
}

object UpdateManifestParser {
    fun parse(text: String, defaultGithubUrl: String): UpdateRelease {
        val json = JSONObject(text)
        val codeValue = json.get("versionCode")
        val code = (codeValue as? Number)?.toString()?.toLongOrNull()
        require(code != null && code > 0) { "Invalid versionCode" }
        require(json.get("versionName") is String) { "Invalid versionName" }
        val name = json.getString("versionName").trim()
        require(UpdateUrlPolicy.validVersion(name)) { "Invalid versionName" }
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
        val download = if (json.has("downloadUrl")) {
            require(json.opt("downloadUrl") is String) { "Invalid downloadUrl" }
            json.getString("downloadUrl").trim()
        } else UpdateUrlPolicy.githubApkUrl(name)
        require(UpdateUrlPolicy.githubDownload(download, name)) { "Invalid GitHub download URL" }
        val sha256 = optionalString(json, "sha256")
        require(sha256 != null && sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid SHA-256" }
        return UpdateRelease(code, name, changelog, github, download, sha256.lowercase(Locale.ROOT))
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
