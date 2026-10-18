package com.naivety.zhicolor.access

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessPolicyTest {
    @Test fun bothPermissionsAreMandatory() {
        AccessStatus.entries.forEach { root ->
            AccessStatus.entries.forEach { framework ->
                val allowed = AccessPolicy.mayEnter(root, framework)
                if (root == AccessStatus.GRANTED && framework == AccessStatus.GRANTED) assertTrue(allowed)
                else assertFalse(allowed)
            }
        }
    }

    @Test fun pendingRechecksAreNotTreatedAsConfirmedRevocation() {
        assertFalse(AccessState(AccessStatus.CHECKING, AccessStatus.GRANTED).accessRevoked)
        assertFalse(AccessState(AccessStatus.GRANTED, AccessStatus.CHECKING).accessRevoked)
        assertFalse(AccessState(AccessStatus.UNKNOWN, AccessStatus.UNKNOWN).accessRevoked)
        assertTrue(AccessState(AccessStatus.REQUIRED, AccessStatus.GRANTED).accessRevoked)
        assertTrue(AccessState(AccessStatus.GRANTED, AccessStatus.REQUIRED).accessRevoked)
    }

    @Test fun onlyARealRootIdentityGrantsAccess() {
        assertTrue(AccessPolicy.rootGranted(0, "0"))
        assertTrue(AccessPolicy.rootGranted(0, "manager notice\n0\n"))
        assertFalse(AccessPolicy.rootGranted(1, "0"))
        assertFalse(AccessPolicy.rootGranted(0, "2000"))
        assertFalse(AccessPolicy.rootGranted(0, "10000"))
        assertFalse(AccessPolicy.rootGranted(0, ""))
        assertFalse(AccessPolicy.rootGranted(0, "uid=0(root)"))
        assertFalse(AccessPolicy.rootGranted(0, "0\npermission denied"))
    }

    @Test fun frameworkRequiresLiveServiceAndBothDeclaredScopes() {
        val scopes = listOf("system", "com.android.systemui")
        assertTrue(AccessPolicy.frameworkGranted(true, scopes))
        assertTrue(AccessPolicy.frameworkGranted(true, listOf("android", "com.android.systemui")))
        assertFalse(AccessPolicy.frameworkGranted(false, scopes))
        assertFalse(AccessPolicy.frameworkGranted(true, null))
        assertFalse(AccessPolicy.frameworkGranted(true, emptyList()))
        assertFalse(AccessPolicy.frameworkGranted(true, listOf("system")))
        assertFalse(AccessPolicy.frameworkGranted(true, listOf("com.android.systemui")))
        assertFalse(AccessPolicy.frameworkGranted(true, listOf("com.android.settings")))
    }

    @Test fun firstForegroundAutomaticallyRequestsRootWithoutACachedGrant() {
        assertEquals(RootCheck.INITIAL_REQUEST, AccessPolicy.rootCheck(
            previouslyGranted = false, attemptedInProcess = false, busy = false,
        ))
    }

    @Test fun cachedGrantsOnlyNeedAShortRecheck() {
        listOf(false, true).forEach { attempted ->
            assertEquals(RootCheck.RECHECK, AccessPolicy.rootCheck(
                previouslyGranted = true, attemptedInProcess = attempted, busy = false,
            ))
        }
    }

    @Test fun anActiveRootCheckNeverStartsAnotherRequestOrRecheck() {
        listOf(false, true).forEach { previouslyGranted ->
            listOf(false, true).forEach { attempted ->
                assertEquals(RootCheck.NONE, AccessPolicy.rootCheck(
                    previouslyGranted = previouslyGranted, attemptedInProcess = attempted, busy = true,
                ))
            }
        }
    }

    @Test fun deniedOrRevokedAccessDoesNotPromptAgainInTheSameProcess() {
        // The process marker survives onStop/onStart and language-driven Activity recreation.
        repeat(3) {
            assertEquals(RootCheck.NONE, AccessPolicy.rootCheck(
                previouslyGranted = false, attemptedInProcess = true, busy = false,
            ))
        }
    }

    @Test fun aNewProcessMayRequestAgainAfterAnEarlierDenial() {
        assertEquals(RootCheck.NONE, AccessPolicy.rootCheck(
            previouslyGranted = false, attemptedInProcess = true, busy = false,
        ))
        assertEquals(RootCheck.INITIAL_REQUEST, AccessPolicy.rootCheck(
            previouslyGranted = false, attemptedInProcess = false, busy = false,
        ))
    }
}
