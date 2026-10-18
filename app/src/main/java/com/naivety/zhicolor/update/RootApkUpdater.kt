package com.naivety.zhicolor.update

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import com.naivety.zhicolor.access.AccessPolicy
import com.naivety.zhicolor.core.RootSessionFactory
import com.naivety.zhicolor.core.SuSession
import com.naivety.zhicolor.home.FrameworkBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal enum class UpdateOperationFailureCode {
    MISSING_DOWNLOAD_URL,
    MISSING_SHA256,
    INVALID_DOWNLOAD_URL,
    NETWORK,
    HTTP,
    DOWNLOAD_TOO_LARGE,
    CHECKSUM_MISMATCH,
    INVALID_APK,
    PACKAGE_MISMATCH,
    VERSION_MISMATCH,
    DOWNGRADE,
    SIGNATURE_MISMATCH,
    ROOT_REQUIRED,
    FRAMEWORK_REQUIRED,
    STAGING_FAILED,
    DISPATCH_FAILED,
}

internal class UpdateOperationException(
    val code: UpdateOperationFailureCode,
    cause: Throwable? = null,
) : Exception(code.name, cause)

internal enum class UpdateDownloadStage { CONNECTING, DOWNLOADING, VERIFYING, VALIDATING }

/** Progress callbacks run on an IO dispatcher. A null percent means the server omitted Content-Length. */
internal data class UpdateDownloadProgress(
    val stage: UpdateDownloadStage,
    val receivedBytes: Long = 0,
    val totalBytes: Long? = null,
) {
    val percent: Int?
        get() = totalBytes?.takeIf { it > 0 }?.let { ((receivedBytes * 100L) / it).coerceIn(0L, 100L).toInt() }
}

internal enum class UpdateInstallStage { VALIDATING, STAGING, SCHEDULED }
internal data class UpdateInstallProgress(val stage: UpdateInstallStage)
internal enum class UpdateReceiptStatus { PENDING, INSTALLED, FAILED, UNKNOWN }
internal data class UpdateInstallResult(val status: UpdateReceiptStatus, val targetVersionCode: Long, val receiptId: String)
internal data class UpdateInstallReceipt(
    val status: UpdateReceiptStatus,
    val targetVersionCode: Long,
    val receiptId: String,
    val actualVersionCode: Long,
    val installerExitCode: Int?,
)

/**
 * Download and install backend. The returned install result means only that a detached
 * package-manager job was dispatched; installation is reported only by [reconcile].
 */
internal class RootApkUpdater(
    private val context: Context,
    private val sessions: RootSessionFactory = RootSessionFactory { SuSession() },
    private val selectedScope: () -> List<String>? = FrameworkBridge::selectedScope,
    private val frameworkActive: () -> Boolean = { FrameworkBridge.active.value },
) {
    private val preferences = context.getSharedPreferences("zhicolor_update_installer", Context.MODE_PRIVATE)

    suspend fun download(
        release: UpdateRelease,
        onProgress: (UpdateDownloadProgress) -> Unit = {},
    ): File = withTimeout(DOWNLOAD_TIMEOUT_MS) {
        val url = release.downloadUrl
        val expectedSha = release.sha256
        if (!UpdateUrlPolicy.githubDownload(url, release.versionName)) {
            throw UpdateOperationException(UpdateOperationFailureCode.INVALID_DOWNLOAD_URL)
        }
        val output = File.createTempFile("zhicolor-update-", ".apk", context.cacheDir)
        try {
            val info = runInterruptible(Dispatchers.IO) {
                downloadBlocking(url, output, expectedSha.lowercase(Locale.ROOT), onProgress)
            }
            onProgress(UpdateDownloadProgress(UpdateDownloadStage.VALIDATING, info.size, info.size))
            runInterruptible(Dispatchers.IO) { validateArchive(output, release) }
            output
        } catch (cancelled: CancellationException) {
            output.delete()
            throw cancelled
        } catch (failure: UpdateOperationException) {
            output.delete()
            throw failure
        } catch (failure: Exception) {
            output.delete()
            throw UpdateOperationException(UpdateOperationFailureCode.NETWORK, failure)
        }
    }

    suspend fun install(
        release: UpdateRelease,
        apk: File,
        onProgress: (UpdateInstallProgress) -> Unit = {},
    ): UpdateInstallResult = withTimeout(INSTALL_TIMEOUT_MS) {
        val id = runInterruptible(Dispatchers.IO) {
            onProgress(UpdateInstallProgress(UpdateInstallStage.VALIDATING))
            validateDownloadedFile(apk, release)
            validateArchive(apk, release)
            onProgress(UpdateInstallProgress(UpdateInstallStage.STAGING))
            stageAndDispatch(apk, release)
        }
        onProgress(UpdateInstallProgress(UpdateInstallStage.SCHEDULED))
        UpdateInstallResult(UpdateReceiptStatus.PENDING, release.versionCode, id)
    }

    suspend fun reconcile(): UpdateInstallReceipt? = withContext(Dispatchers.IO) {
        val id = preferences.getString("receipt_id", null)?.takeIf(UpdateInstallProtocol::validId) ?: return@withContext null
        val target = preferences.getLong("target_version", 0L).takeIf { it > 0 } ?: return@withContext null
        val actual = installedVersionCode()
        val parsed = UpdateInstallProtocol.parseReceipt(runCatching { receiptFile(id).readText().take(4096) }.getOrDefault(""))
        val age = (System.currentTimeMillis() - preferences.getLong("started_millis", 0L)).coerceAtLeast(0L)
        val status = UpdateInstallProtocol.status(actual, target, parsed?.takeIf { it.id == id && it.target == target }, age)
        UpdateInstallReceipt(status, target, id, actual, parsed?.exitCode)
    }

    fun clearReceipt() {
        val id = preferences.getString("receipt_id", null)
        if (id != null && UpdateInstallProtocol.validId(id)) {
            receiptFile(id).delete()
            File(receiptFile(id).path + ".tmp").delete()
        }
        preferences.edit().clear().commit()
    }

    private fun downloadBlocking(
        startUrl: String,
        output: File,
        expectedSha: String,
        onProgress: (UpdateDownloadProgress) -> Unit,
    ): DownloadInfo {
        val progress = DownloadProgressReporter(onProgress)
        val result = UpdateDownloadProtocol.download(
            startUrl = startUrl,
            output = output,
            expectedSha256 = expectedSha,
            maxBytes = MAX_APK_BYTES,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            openConnection = { URL(it).openConnection() as HttpURLConnection },
            onProgress = { progress.report(it) },
        )
        progress.report(UpdateDownloadProgress(UpdateDownloadStage.VERIFYING, result.size, result.size), force = true)
        return DownloadInfo(result.size)
    }

    private fun validateArchive(apk: File, release: UpdateRelease) {
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateOperationException(UpdateOperationFailureCode.INVALID_APK)
        if (archive.packageName != context.packageName) throw UpdateOperationException(UpdateOperationFailureCode.PACKAGE_MISMATCH)
        val archiveCode = archive.longVersionCode
        if (archiveCode != release.versionCode) throw UpdateOperationException(UpdateOperationFailureCode.VERSION_MISMATCH)
        val installed = try {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (failure: PackageManager.NameNotFoundException) {
            throw UpdateOperationException(UpdateOperationFailureCode.PACKAGE_MISMATCH, failure)
        }
        if (archiveCode < installed.longVersionCode) throw UpdateOperationException(UpdateOperationFailureCode.DOWNGRADE)
        val oldSigning = installed.signingInfo
        val newSigning = archive.signingInfo
        if (oldSigning == null || newSigning == null || !UpdateApkPolicy.signersCompatible(
                installedSigners = oldSigning.apkContentsSigners.orEmpty().map(Signature::toByteArray),
                updateSigners = newSigning.apkContentsSigners.orEmpty().map(Signature::toByteArray),
                updateSignerHistory = newSigning.signingCertificateHistory.orEmpty().map(Signature::toByteArray),
                installedMultipleSigners = oldSigning.hasMultipleSigners(),
                updateMultipleSigners = newSigning.hasMultipleSigners(),
            )) {
            throw UpdateOperationException(UpdateOperationFailureCode.SIGNATURE_MISMATCH)
        }
    }

    private fun validateDownloadedFile(apk: File, release: UpdateRelease) {
        val expectedSha = release.sha256
        val downloadUrl = release.downloadUrl
        if (!UpdateUrlPolicy.githubDownload(downloadUrl, release.versionName)) throw UpdateOperationException(UpdateOperationFailureCode.INVALID_DOWNLOAD_URL)
        val canonical = runCatching { apk.canonicalFile }.getOrElse {
            throw UpdateOperationException(UpdateOperationFailureCode.INVALID_APK, it)
        }
        val cache = runCatching { context.cacheDir.canonicalFile }.getOrElse {
            throw UpdateOperationException(UpdateOperationFailureCode.INVALID_APK, it)
        }
        if (!canonical.path.startsWith(cache.path + File.separator) || !canonical.isFile || canonical.length() !in 1..MAX_APK_BYTES) {
            throw UpdateOperationException(UpdateOperationFailureCode.INVALID_APK)
        }
        val actual = sha256(canonical)
        if (!MessageDigest.isEqual(actual.toByteArray(), expectedSha.lowercase(Locale.ROOT).toByteArray())) {
            throw UpdateOperationException(UpdateOperationFailureCode.CHECKSUM_MISMATCH)
        }
    }

    private fun stageAndDispatch(apk: File, release: UpdateRelease): String {
        val id = UUID.randomUUID().toString().replace("-", "")
        val receipt = receiptFile(id)
        val temporary = File(receipt.path + ".tmp")
        try {
            receipt.writeText(UpdateInstallProtocol.receipt(id, release.versionCode, "pending", null))
            temporary.writeText("")
        } catch (failure: Exception) {
            receipt.delete(); temporary.delete()
            throw UpdateOperationException(UpdateOperationFailureCode.STAGING_FAILED, failure)
        }
        val dispatcher = RootUpdateDispatcher(sessions) {
            AccessPolicy.frameworkGranted(frameworkActive(), selectedScope())
        }
        try {
            dispatcher.dispatch(
                id = id,
                apkPath = apk.canonicalPath,
                sha256 = release.sha256!!,
                receiptPath = receipt.canonicalPath,
                targetVersionCode = release.versionCode,
                appUid = context.applicationInfo.uid,
                beforeLaunch = {
                    if (!preferences.edit().putString("receipt_id", id)
                            .putLong("target_version", release.versionCode)
                            .putLong("started_millis", System.currentTimeMillis()).commit()) {
                        throw UpdateOperationException(UpdateOperationFailureCode.STAGING_FAILED)
                    }
                },
            )
        } catch (failure: Exception) {
            if (failure !is UpdateOperationException || failure.code != UpdateOperationFailureCode.DISPATCH_FAILED) {
                receipt.delete(); temporary.delete()
                if (preferences.getString("receipt_id", null) == id) {
                    preferences.edit().clear().commit()
                }
            }
            throw failure
        }
        return id
    }

    private fun receiptFile(id: String) = File(context.filesDir, "update-install-$id.txt")

    private fun installedVersionCode(): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class DownloadInfo(val size: Long)

    private companion object {
        const val MAX_APK_BYTES = 150L * 1024L * 1024L
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 15_000
        const val DOWNLOAD_TIMEOUT_MS = 180_000L
        const val INSTALL_TIMEOUT_MS = 60_000L
    }
}

/** Pure policy helpers kept free of Android APIs so safety cases can be unit tested on the JVM. */
internal object UpdateApkPolicy {
    fun signersCompatible(
        installedSigners: List<ByteArray>,
        updateSigners: List<ByteArray>,
        updateSignerHistory: List<ByteArray>,
        installedMultipleSigners: Boolean,
        updateMultipleSigners: Boolean,
    ): Boolean {
        if (installedSigners.isEmpty() || updateSigners.isEmpty()) return false
        if (installedMultipleSigners || updateMultipleSigners) {
            if (!installedMultipleSigners || !updateMultipleSigners) return false
            return sameSignerSet(installedSigners, updateSigners)
        }
        val installed = installedSigners.singleOrNull() ?: return false
        val update = updateSigners.singleOrNull() ?: return false
        return installed.contentEquals(update) || updateSignerHistory.any(installed::contentEquals)
    }

    private fun sameSignerSet(left: List<ByteArray>, right: List<ByteArray>): Boolean =
        left.size == right.size && left.all { signer -> right.any(signer::contentEquals) } &&
            right.all { signer -> left.any(signer::contentEquals) }
}
