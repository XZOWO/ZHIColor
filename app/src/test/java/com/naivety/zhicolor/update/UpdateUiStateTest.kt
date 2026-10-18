package com.naivety.zhicolor.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUiStateTest {
    @Test fun emptyStateHasNoDialog() {
        assertFalse(UpdateUiState().showDialog)
    }

    @Test fun restoredPendingReceiptShowsWithoutRelease() {
        val state = UpdateUiState(task = UpdateTaskUiState(UpdateTaskPhase.SCHEDULED, 0))
        assertTrue(state.showDialog)
    }

    @Test fun dismissedPendingReceiptStaysClosedWhilePolling() {
        val state = UpdateUiState(
            task = UpdateTaskUiState(UpdateTaskPhase.SCHEDULED, 0),
            taskDialogDismissed = true,
        )
        assertFalse(state.showDialog)
        assertFalse(state.copy(task = UpdateTaskUiState(UpdateTaskPhase.SCHEDULED, 0)).showDialog)
        assertTrue(state.copy(taskDialogDismissed = false).showDialog)
    }

    @Test fun restoredFailureIsVisibleAndDismissible() {
        val state = UpdateUiState(task = UpdateTaskUiState(UpdateTaskPhase.FAILED, 0))
        assertTrue(state.showDialog)
        assertFalse(state.copy(taskDialogDismissed = true).showDialog)
    }

    @Test fun completedReceiptClosesDialog() {
        val pending = UpdateUiState(task = UpdateTaskUiState(UpdateTaskPhase.SCHEDULED, 0))
        assertFalse(pending.copy(task = null).showDialog)
    }
}
