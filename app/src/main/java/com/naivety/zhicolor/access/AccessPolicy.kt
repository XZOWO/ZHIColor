package com.naivety.zhicolor.access

internal enum class AccessStatus { UNKNOWN, CHECKING, GRANTED, REQUIRED }

internal enum class RootCheck { NONE, INITIAL_REQUEST, RECHECK }

internal data class AccessState(
    val root: AccessStatus = AccessStatus.UNKNOWN,
    val framework: AccessStatus = AccessStatus.UNKNOWN,
) {
    val allowed: Boolean get() = AccessPolicy.mayEnter(root, framework)
    /** A pending foreground verification hides the app, but does not prove revocation. */
    val accessRevoked: Boolean get() = root == AccessStatus.REQUIRED || framework == AccessStatus.REQUIRED
}

internal object AccessPolicy {
    fun mayEnter(root: AccessStatus, framework: AccessStatus): Boolean =
        root == AccessStatus.GRANTED && framework == AccessStatus.GRANTED

    fun rootGranted(exitCode: Int, output: String): Boolean =
        exitCode == 0 && output.lineSequence().filter(String::isNotBlank).lastOrNull()?.trim() == "0"

    fun frameworkGranted(connected: Boolean, scopes: List<String>?): Boolean =
        connected && scopes != null && "com.android.systemui" in scopes &&
            scopes.any { it == "system" || it == "android" || it == "system_server" }

    fun rootCheck(previouslyGranted: Boolean, attemptedInProcess: Boolean, busy: Boolean): RootCheck = when {
        busy -> RootCheck.NONE
        previouslyGranted -> RootCheck.RECHECK
        !attemptedInProcess -> RootCheck.INITIAL_REQUEST
        else -> RootCheck.NONE
    }
}
