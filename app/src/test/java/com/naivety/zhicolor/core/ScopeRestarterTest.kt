package com.naivety.zhicolor.core

import org.junit.Assert.*
import org.junit.Test

class ScopeRestarterTest {
    private class Session(private val uid: CommandResult = CommandResult(0, "0"), private val failRestart: Boolean = false) : RootSession {
        val calls = mutableListOf<String>()
        var closed = false
        override fun run(command: String): CommandResult {
            calls += command
            return if (command == "id -u") uid else CommandResult(if (failRestart) 1 else 0, "")
        }
        override fun close() { closed = true }
    }
    @Test fun authorizationIsAttemptedBeforeScopeLookup() {
        val session = Session()
        val result = ScopeRestarter(RootSessionFactory { session }, {
            assertEquals(listOf("id -u"), session.calls)
            listOf("com.example.music")
        }).restart()
        assertEquals(RestartKind.FINISHED, result.kind)
        assertEquals(1, result.restarted)
        assertTrue(session.closed)
        assertEquals("am force-stop --user current 'com.example.music'", session.calls[1])
    }
    @Test fun deniedAuthorizationShowsGrantMessageAndDoesNotReadScopes() {
        val session = Session(CommandResult(1, "Permission denied"))
        val result = ScopeRestarter(RootSessionFactory { session }, { fail("scope must not be read before root grant"); null }).restart()
        assertEquals(RestartKind.ROOT_REQUIRED, result.kind)
        assertTrue(result.message().contains("授予 ZHIColor"))
        assertEquals(listOf("id -u"), session.calls)
        assertTrue(session.closed)
    }
    @Test fun noRootUidIsNotTreatedAsAuthorization() {
        val result = ScopeRestarter(RootSessionFactory { Session(CommandResult(0, "2000")) }, { emptyList() }).restart()
        assertEquals(RestartKind.ROOT_REQUIRED, result.kind)
    }
    @Test fun missingSuReturnsGrantHintWithoutManagerAssumptions() {
        val result = ScopeRestarter(RootSessionFactory { throw java.io.IOException("missing su") }, { emptyList() }).restart()
        assertEquals(RestartKind.ROOT_REQUIRED, result.kind)
    }
    @Test fun denialIsRetriedOnNextClick() {
        var opens = 0
        val restarter = ScopeRestarter(RootSessionFactory {
            opens++
            Session(CommandResult(if (opens == 1) 1 else 0, if (opens == 1) "denied" else "0"))
        }, { listOf("com.example.music") })
        assertEquals(RestartKind.ROOT_REQUIRED, restarter.restart().kind)
        assertEquals(1, restarter.restart().restarted)
        assertEquals(2, opens)
    }
    @Test fun inactiveFrameworkDoesNotUseRecommendedScopeAsActualScope() {
        val session = Session()
        val result = ScopeRestarter(RootSessionFactory { session }, { null }).restart()
        assertEquals(RestartKind.FRAMEWORK_REQUIRED, result.kind)
        assertEquals(listOf("id -u"), session.calls)
    }
    @Test fun emptyScopeDoesNotRestartAnything() {
        val session = Session()
        val result = ScopeRestarter(RootSessionFactory { session }, { emptyList() }).restart()
        assertEquals(RestartKind.EMPTY, result.kind)
        assertEquals(1, session.calls.size)
    }
    @Test fun commandFailureIsNotReportedAsRestartSuccess() {
        val result = ScopeRestarter(RootSessionFactory { Session(failRestart = true) }, { listOf("com.example.music") }).restart()
        assertEquals(0, result.restarted)
        assertEquals(1, result.failed)
        assertTrue(result.message().contains("失败"))
    }
    @Test fun scopeInputsAreValidatedAndDeduplicated() {
        val plan = ScopeRestartPolicy.plan(listOf("com.example.music", "com.example.music", "com.foo;reboot", "$(reboot)", "com.naivety.zhicolor", "android", "system"), "com.naivety.zhicolor")
        assertEquals(listOf("com.example.music"), plan.commands.map { it.packageName })
        assertTrue(plan.requiresDeviceRestart)
    }
    @Test fun systemUiUsesExactPidAndNoSystemServerKill() {
        val plan = ScopeRestartPolicy.plan(listOf("com.android.systemui", "system_server"), "com.naivety.zhicolor")
        assertEquals(1, plan.commands.size)
        assertTrue(plan.commands.single().command.contains("pidof com.android.systemui"))
        assertFalse(plan.commands.single().command.contains("system_server"))
        assertTrue(plan.requiresDeviceRestart)
    }
    @Test fun frameworkOnlyRequiresExplicitDeviceRestart() {
        val result = ScopeRestarter(RootSessionFactory { Session() }, { listOf("system") }).restart()
        assertEquals(0, result.restarted)
        assertTrue(result.requiresDeviceRestart)
        assertTrue(result.message().contains("重启设备"))
    }
}
