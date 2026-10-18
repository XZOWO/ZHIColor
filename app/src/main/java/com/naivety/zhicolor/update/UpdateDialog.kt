package com.naivety.zhicolor.update

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naivety.zhicolor.R
import com.naivety.zhicolor.ui.components.AppDialog
import com.naivety.zhicolor.ui.components.DialogAction
import com.naivety.zhicolor.ui.components.DialogActions
import com.naivety.zhicolor.ui.components.DialogBody
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun UpdateDialog(viewModel: UpdateViewModel) {
    val state = viewModel.state
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(state.feedbackId, state.feedback) {
        val feedback = state.feedback ?: return@LaunchedEffect
        val resource = when (feedback) {
            UpdateFeedback.CURRENT -> R.string.update_current
            UpdateFeedback.FAILED -> R.string.update_check_failed
            UpdateFeedback.OPEN_FAILED -> R.string.update_open_failed
            UpdateFeedback.OPERATION_FAILED -> state.feedbackMessageRes ?: R.string.update_operation_failed
            UpdateFeedback.INSTALLED -> R.string.update_installed
        }
        Toast.makeText(context, resource, Toast.LENGTH_SHORT).show()
        viewModel.consumeFeedback(state.feedbackId)
    }

    var retainedRelease by remember { mutableStateOf<UpdateRelease?>(null) }
    var retainedTask by remember { mutableStateOf<UpdateTaskUiState?>(null) }
    val release = state.release ?: retainedRelease
    val task = state.task ?: retainedTask.takeIf { !state.showDialog }
    SideEffect {
        if (state.release != null) retainedRelease = state.release
        if (state.task != null) retainedTask = state.task
    }

    val updateStartAction: (@Composable RowScope.() -> Unit)? = when (state.task?.phase) {
        UpdateTaskPhase.DOWNLOADING -> ({
            DialogAction(text = stringResource(R.string.update_cancel), onClick = viewModel::cancelDownload)
        })
        UpdateTaskPhase.INSTALLING, UpdateTaskPhase.SCHEDULED -> null
        else -> ({
            DialogAction(text = stringResource(R.string.update_ignore), onClick = viewModel::ignore)
        })
    }
    AppDialog(
        show = state.showDialog,
        title = stringResource(if (release != null) R.string.update_available else R.string.update_status),
        summary = release?.versionName,
        onDismissRequest = viewModel::dismiss,
        onDismissFinished = {
            if (!viewModel.state.showDialog) {
                retainedRelease = null
                retainedTask = null
            }
        },
    ) {
        if (release != null && release.changelog.isNotBlank()) {
            DialogBody(maxHeight = 320.dp, verticalSpacing = 0.dp) {
                Text(release.changelog, modifier = Modifier.fillMaxWidth(), fontSize = 15.sp, lineHeight = 23.sp)
            }
        }
        task?.let { currentTask ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(currentTask.messageRes), modifier = Modifier.fillMaxWidth(), fontSize = 14.sp)
                if (currentTask.phase == UpdateTaskPhase.DOWNLOADING) {
                    val percent = currentTask.progressPercent
                    if (percent == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { (percent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.update_progress_percent, percent),
                            modifier = Modifier.fillMaxWidth(), fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        if (release != null) {
            DialogActions(startActions = updateStartAction) {
                DialogAction(text = stringResource(R.string.update_github), onClick = {
                    runCatching { uriHandler.openUri(release.githubUrl) }
                        .onFailure { viewModel.feedback(UpdateFeedback.OPEN_FAILED) }
                })
                DialogAction(
                    text = stringResource(R.string.update_download),
                    enabled = state.task?.phase !in setOf(
                        UpdateTaskPhase.DOWNLOADING,
                        UpdateTaskPhase.INSTALLING,
                        UpdateTaskPhase.SCHEDULED,
                    ),
                    onClick = viewModel::downloadAndInstall,
                )
            }
        }
    }
}
