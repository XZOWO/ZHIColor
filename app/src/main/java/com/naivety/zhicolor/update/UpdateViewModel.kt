package com.naivety.zhicolor.update

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.naivety.zhicolor.BuildConfig
import com.naivety.zhicolor.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UpdateFeedback { CURRENT, FAILED, OPEN_FAILED, OPERATION_FAILED, INSTALLED }

enum class UpdateTaskPhase { DOWNLOADING, INSTALLING, SCHEDULED, FAILED }

data class UpdateTaskUiState(
    val phase: UpdateTaskPhase,
    val messageRes: Int,
    val progressPercent: Int? = null,
)

data class UpdateUiState(
    val checking: Boolean = false,
    val release: UpdateRelease? = null,
    val feedback: UpdateFeedback? = null,
    val feedbackId: Long = 0,
    val feedbackMessageRes: Int? = null,
    val task: UpdateTaskUiState? = null,
    val taskDialogDismissed: Boolean = false,
) {
    val showDialog: Boolean get() = release != null || (task != null && !taskDialogDismissed)
}

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("zhicolor_updates", Context.MODE_PRIVATE)
    private val session = UpdateCheckSession()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = UpdateRepository(
        manifestUrls = listOf(BuildConfig.UPDATE_MANIFEST_URL, BuildConfig.GITHUB_UPDATE_MANIFEST_URL),
        githubReleasesUrl = BuildConfig.GITHUB_RELEASES_URL,
    )
    private val apkUpdater = RootApkUpdater(application)
    private var accessAvailable = false
    private var foreground = false
    private var checkJob: Job? = null
    private var updateJob: Job? = null
    private var receiptJob: Job? = null
    private var taskRelease: UpdateRelease? = null
    private var operationGeneration = 0L

    var state by mutableStateOf(UpdateUiState())
        private set

    fun onForeground() {
        accessAvailable = true
        foreground = true
        reconcileInstallation()
        if (session.enterForeground()) check()
    }

    fun onBackground(changingConfigurations: Boolean) {
        foreground = false
        session.leaveForeground(changingConfigurations)
    }

    /** Called by the access gate whenever Root or LSPosed access is revoked. */
    fun onAccessRevoked() {
        accessAvailable = false
        foreground = false
        operationGeneration++
        session.leaveForeground(changingConfigurations = false)
        checkJob?.cancel()
        updateJob?.cancel()
        receiptJob?.cancel()
        checkJob = null
        updateJob = null
        taskRelease = null
        state = state.copy(checking = false, release = null, task = null)
    }

    fun checkManually() {
        if (!accessAvailable) return
        val phase = state.task?.phase
        if (phase == UpdateTaskPhase.DOWNLOADING || phase == UpdateTaskPhase.INSTALLING || phase == UpdateTaskPhase.SCHEDULED) {
            val release = taskRelease
            state = state.copy(release = release, taskDialogDismissed = false)
            return
        }
        if (session.requestManual()) check()
    }

    private fun check() {
        if (!accessAvailable) return
        if (updateJob?.isActive == true) {
            session.complete()
            return
        }
        if (checkJob?.isActive == true) return
        state = state.copy(checking = true)
        checkJob = scope.launch {
            val result = try {
                Result.success(repository.latest())
            } catch (timeout: TimeoutCancellationException) {
                Result.failure(timeout)
            } catch (cancelled: CancellationException) {
                session.complete()
                if (accessAvailable) state = state.copy(checking = false)
                throw cancelled
            } catch (exception: Exception) {
                Result.failure(exception)
            }
            val manual = session.complete()
            if (!accessAvailable) return@launch
            state = state.copy(checking = false)
            val release = result.getOrNull()
            if (release == null) {
                if (manual) feedback(UpdateFeedback.FAILED)
            } else if (UpdateVersionPolicy.shouldOffer(
                    remoteCode = release.versionCode,
                    installedCode = BuildConfig.VERSION_CODE.toLong(),
                    ignoredCode = preferences.getLong(IGNORED_VERSION, -1),
                    manual = manual,
                )) {
                taskRelease = release
                state = state.copy(
                    release = release,
                    task = state.task.takeIf { it?.phase == UpdateTaskPhase.SCHEDULED },
                    taskDialogDismissed = false,
                )
            } else if (manual) {
                feedback(UpdateFeedback.CURRENT)
            }
        }
    }

    fun downloadAndInstall() {
        val release = state.release ?: return
        if (!accessAvailable || updateJob?.isActive == true || state.task?.phase == UpdateTaskPhase.SCHEDULED) return
        if (release.cloudUrl == null || release.sha256 == null) {
            state = state.copy(task = UpdateTaskUiState(UpdateTaskPhase.FAILED, R.string.update_download_unavailable))
            return
        }
        taskRelease = release
        val generation = ++operationGeneration
        state = state.copy(task = UpdateTaskUiState(UpdateTaskPhase.DOWNLOADING, R.string.update_connecting), taskDialogDismissed = false)
        updateJob = scope.launch {
            var apk: java.io.File? = null
            try {
                val downloaded = apkUpdater.download(release) { progress ->
                    scope.launch(Dispatchers.Main.immediate) {
                        if (accessAvailable && generation == operationGeneration && updateJob?.isActive == true && state.task?.phase == UpdateTaskPhase.DOWNLOADING) state = state.copy(task = UpdateTaskUiState(
                            phase = UpdateTaskPhase.DOWNLOADING,
                            messageRes = when (progress.stage) {
                                UpdateDownloadStage.CONNECTING -> R.string.update_connecting
                                UpdateDownloadStage.DOWNLOADING -> R.string.update_downloading
                                UpdateDownloadStage.VERIFYING -> R.string.update_verifying
                                UpdateDownloadStage.VALIDATING -> R.string.update_validating
                            },
                            progressPercent = progress.percent,
                        ))
                    }
                }
                apk = downloaded
                if (!accessAvailable) return@launch
                apkUpdater.install(release, downloaded) { installProgress ->
                    scope.launch(Dispatchers.Main.immediate) progressUpdate@{
                        if (!accessAvailable || generation != operationGeneration || updateJob?.isActive != true) return@progressUpdate
                        state = state.copy(task = when (installProgress.stage) {
                            UpdateInstallStage.VALIDATING, UpdateInstallStage.STAGING -> UpdateTaskUiState(
                                UpdateTaskPhase.INSTALLING,
                                if (installProgress.stage == UpdateInstallStage.STAGING) R.string.update_staging
                                else R.string.update_validating,
                            )
                            UpdateInstallStage.SCHEDULED -> UpdateTaskUiState(UpdateTaskPhase.SCHEDULED, R.string.update_install_scheduled)
                        })
                    }
                }
                if (accessAvailable) {
                    state = state.copy(task = UpdateTaskUiState(UpdateTaskPhase.SCHEDULED, R.string.update_install_scheduled))
                    reconcileInstallation()
                }
            } catch (timeout: TimeoutCancellationException) {
                if (accessAvailable) {
                    state = state.copy(task = UpdateTaskUiState(UpdateTaskPhase.FAILED, R.string.update_operation_failed))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: UpdateOperationException) {
                if (accessAvailable) {
                    val resource = failureMessage(failure.code)
                    state = state.copy(task = UpdateTaskUiState(UpdateTaskPhase.FAILED, resource))
                }
            } catch (_: Exception) {
                if (accessAvailable) {
                    state = state.copy(task = UpdateTaskUiState(UpdateTaskPhase.FAILED, R.string.update_operation_failed))
                }
            } finally {
                apk?.delete()
            }
        }
    }

    fun cancelDownload() {
        if (state.task?.phase != UpdateTaskPhase.DOWNLOADING) return
        operationGeneration++
        updateJob?.cancel()
        updateJob = null
        state = state.copy(task = null, taskDialogDismissed = false)
    }

    private fun reconcileInstallation() {
        if (!accessAvailable || !foreground || receiptJob?.isActive == true) return
        receiptJob = scope.launch {
            while (isActive && accessAvailable && foreground) {
                val receipt = try {
                    apkUpdater.reconcile()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    delay(5_000)
                    continue
                } ?: return@launch
                if (receipt.status == UpdateReceiptStatus.PENDING) {
                    if (updateJob?.isActive != true) {
                        val firstPending = state.task?.phase != UpdateTaskPhase.SCHEDULED
                        state = state.copy(
                            task = UpdateTaskUiState(UpdateTaskPhase.SCHEDULED, R.string.update_install_scheduled),
                            taskDialogDismissed = if (firstPending) false else state.taskDialogDismissed,
                        )
                    }
                    delay(2_000)
                    continue
                }
                if (receipt.status == UpdateReceiptStatus.INSTALLED) {
                    state = state.copy(release = null, task = null, taskDialogDismissed = false)
                    taskRelease = null
                    feedback(UpdateFeedback.INSTALLED)
                } else {
                    state = state.copy(
                        task = UpdateTaskUiState(UpdateTaskPhase.FAILED, R.string.update_install_result_failed),
                        taskDialogDismissed = false,
                    )
                }
                withContext(NonCancellable + Dispatchers.IO) { apkUpdater.clearReceipt() }
                return@launch
            }
        }
    }

    private fun failureMessage(code: UpdateOperationFailureCode): Int = when (code) {
        UpdateOperationFailureCode.MISSING_DOWNLOAD_URL,
        UpdateOperationFailureCode.MISSING_SHA256,
        UpdateOperationFailureCode.INVALID_DOWNLOAD_URL -> R.string.update_download_unavailable
        UpdateOperationFailureCode.NETWORK,
        UpdateOperationFailureCode.HTTP -> R.string.update_network_failed
        UpdateOperationFailureCode.DOWNLOAD_TOO_LARGE,
        UpdateOperationFailureCode.CHECKSUM_MISMATCH -> R.string.update_verification_failed
        UpdateOperationFailureCode.INVALID_APK,
        UpdateOperationFailureCode.PACKAGE_MISMATCH,
        UpdateOperationFailureCode.VERSION_MISMATCH,
        UpdateOperationFailureCode.DOWNGRADE -> R.string.update_apk_invalid
        UpdateOperationFailureCode.SIGNATURE_MISMATCH -> R.string.update_signature_mismatch
        UpdateOperationFailureCode.ROOT_REQUIRED -> R.string.update_root_required
        UpdateOperationFailureCode.FRAMEWORK_REQUIRED -> R.string.update_framework_required
        UpdateOperationFailureCode.STAGING_FAILED,
        UpdateOperationFailureCode.DISPATCH_FAILED -> R.string.update_install_failed
    }

    fun dismiss() {
        state = state.copy(release = null, taskDialogDismissed = true)
    }

    fun ignore() {
        val release = state.release ?: return
        preferences.edit().putLong(IGNORED_VERSION, release.versionCode).apply()
        dismiss()
    }

    fun feedback(value: UpdateFeedback, messageRes: Int? = null) {
        state = state.copy(feedback = value, feedbackId = state.feedbackId + 1, feedbackMessageRes = messageRes)
    }

    fun consumeFeedback(id: Long) {
        if (state.feedbackId == id) state = state.copy(feedback = null, feedbackMessageRes = null)
    }

    override fun onCleared() {
        scope.cancel()
    }

    private companion object {
        const val IGNORED_VERSION = "ignored_version_code"
    }
}
