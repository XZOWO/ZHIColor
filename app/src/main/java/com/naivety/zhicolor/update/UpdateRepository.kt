package com.naivety.zhicolor.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class UpdateRepository(
    private val manifestUrl: String,
    private val githubReleasesUrl: String,
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) {
    suspend fun latest(): UpdateRelease = withTimeout(22_000) {
        require(UpdateUrlPolicy.githubManifest(manifestUrl)) { "Invalid GitHub manifest URL" }
        val text = runInterruptible(Dispatchers.IO) { readManifest(manifestUrl) }
        UpdateManifestParser.parse(text, githubReleasesUrl)
    }

    private fun readManifest(endpoint: String): String {
        var url = endpoint
        repeat(4) { redirect ->
            require(UpdateUrlPolicy.githubManifest(url) || UpdateUrlPolicy.githubAssetRedirect(url)) { "Invalid manifest redirect" }
            val connection = openConnection(url)
            try {
                connection.connectTimeout = 6_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "ZHIColor-Update-Checker")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(redirect < 3) { "Too many manifest redirects" }
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Missing redirect location")
                    url = URI(url).resolve(location).toString()
                } else {
                    require(status == HttpURLConnection.HTTP_OK) { "Manifest HTTP $status" }
                    require(connection.contentLengthLong <= MAX_BYTES) { "Manifest too large" }
                    return connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8_192)
                        while (true) {
                            if (Thread.interrupted()) throw InterruptedException("Manifest read interrupted")
                            val read = input.read(buffer)
                            if (read < 0) break
                            require(output.size() + read <= MAX_BYTES) { "Manifest too large" }
                            output.write(buffer, 0, read)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Too many manifest redirects")
    }

    private companion object {
        const val MAX_BYTES = 256 * 1024
    }
}
