package com.naivety.zhicolor.update

import com.naivety.zhicolor.core.CommandResult
import com.naivety.zhicolor.core.RootSession
import com.naivety.zhicolor.core.RootSessionFactory
import java.security.MessageDigest
import org.junit.Rule
import org.junit.Assert.*
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateInstallProtocolTest {
    private val id = "0123456789abcdef0123456789abcdef"

    @get:Rule val folder = TemporaryFolder()

    @Test fun receiptRoundTripAndBoundedParsing() {
        val parsed = UpdateInstallProtocol.parseReceipt(UpdateInstallProtocol.receipt(id, 22, "command_ok", 0))
        assertNotNull(parsed)
        assertEquals(id, parsed!!.id)
        assertEquals(22L, parsed.target)
        assertEquals("command_ok", parsed.state)
        assertEquals(0, parsed.exitCode)
        assertNull(UpdateInstallProtocol.parseReceipt("id=$id\ntarget=22\nstatus=failed\nexit=\n"))
        assertNull(UpdateInstallProtocol.parseReceipt("x".repeat(4097)))
    }

    @Test fun statusNeverUsesDispatchAsInstalled() {
        val pending = UpdateInstallProtocol.parseReceipt(UpdateInstallProtocol.receipt(id, 22, "command_ok", 0))
        assertEquals(UpdateReceiptStatus.PENDING, UpdateInstallProtocol.status(21, 22, pending, 1000))
        assertEquals(UpdateReceiptStatus.INSTALLED, UpdateInstallProtocol.status(22, 22, pending, 1000))
        assertEquals(UpdateReceiptStatus.UNKNOWN, UpdateInstallProtocol.status(21, 22, pending, UpdateInstallProtocol.INSTALL_RECEIPT_TIMEOUT_MS + 1))
    }

    @Test fun failedInstallIsExplicit() {
        val failed = UpdateInstallProtocol.parseReceipt(UpdateInstallProtocol.receipt(id, 22, "failed", 9))
        assertEquals(UpdateReceiptStatus.FAILED, UpdateInstallProtocol.status(21, 22, failed, 1))
    }

    @Test fun shellCommandQuotesInput() {
        assertEquals("pm install -r '/data/local/tmp/a'\\''b.apk'", UpdateInstallProtocol.installCommand("/data/local/tmp/a'b.apk"))
    }

    private class FakeSession(
        private val uid: String = "0",
        private val response: ((String, Int) -> CommandResult)? = null,
    ) : RootSession {
        val calls = mutableListOf<String>()
        var closed = false
        override fun run(command: String): CommandResult {
            calls += command
            response?.let { return it(command, calls.count { call -> call == "id -u" }) }
            return when (command) {
                "id -u" -> CommandResult(0, uid)
                else -> if (command.contains("ZHI_DISPATCHED")) CommandResult(0, "ZHI_DISPATCHED") else CommandResult(0, "")
            }
        }
        override fun close() { closed = true }
    }

    private fun inputs(): Triple<java.io.File, java.io.File, String> {
        val apk = folder.newFile("update.apk").apply { writeText("verified placeholder apk") }
        val receipt = folder.newFile("receipt.txt").apply { writeText("pending") }
        java.io.File(receipt.path + ".tmp").writeText("")
        val digest = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
        return Triple(apk, receipt, digest)
    }

    @Test fun dispatcherChecksRootThenFrameworkAndClosesSession() {
        val (apk, receipt, sha) = inputs()
        val denied = FakeSession(uid = "2000")
        val rootError = runCatching {
            RootUpdateDispatcher(RootSessionFactory { denied }) { fail("framework check must follow root"); true }
                .dispatch(id, apk.path, sha, receipt.path, 22, 10123) {}
        }.exceptionOrNull() as UpdateOperationException
        assertEquals(UpdateOperationFailureCode.ROOT_REQUIRED, rootError.code)
        assertEquals(listOf("id -u"), denied.calls)
        assertTrue(denied.closed)

        val noFramework = FakeSession()
        val frameworkError = runCatching {
            RootUpdateDispatcher(RootSessionFactory { noFramework }) { false }
                .dispatch(id, apk.path, sha, receipt.path, 22, 10123) {}
        }.exceptionOrNull() as UpdateOperationException
        assertEquals(UpdateOperationFailureCode.FRAMEWORK_REQUIRED, frameworkError.code)
        assertEquals(listOf("id -u"), noFramework.calls)
    }

    @Test fun dispatcherStagesHashAndOnlyReportsDetachedDispatch() {
        val (apk, receipt, sha) = inputs()
        val session = FakeSession()
        var persisted = false
        RootUpdateDispatcher(RootSessionFactory { session }) { true }
            .dispatch(id, apk.path, sha, receipt.path, 22, 10123) { persisted = true }
        assertTrue(persisted)
        assertTrue(session.closed)
        assertEquals(listOf("id -u", "id -u"), session.calls.filter { it == "id -u" })
        val stage = session.calls.first { it.contains("sha256sum") }
        assertTrue(stage.contains("cp '${apk.canonicalPath}'"))
        assertTrue(stage.contains(sha))
        assertTrue(stage.contains("trap 'rm -rf"))
        assertTrue(stage.contains("chown"))
        assertTrue(stage.contains("mv -f"))
        assertTrue(stage.contains("trap cleanup EXIT"))
        assertTrue(stage.contains("pm install -r"))
        assertTrue(session.calls.last().contains("nohup sh"))
        assertFalse(session.calls.last().contains("pm install"))
    }

    @Test fun stagingFailureAttemptsCleanupAndNeverDispatches() {
        val (apk, receipt, sha) = inputs()
        val session = FakeSession(response = { command, _ ->
            when {
                command == "id -u" -> CommandResult(0, "0")
                command.contains("sha256sum") -> CommandResult(44, "")
                else -> CommandResult(0, "")
            }
        })
        val failure = runCatching {
            RootUpdateDispatcher(RootSessionFactory { session }) { true }
                .dispatch(id, apk.path, sha, receipt.path, 22, 10123) {}
        }.exceptionOrNull() as UpdateOperationException
        assertEquals(UpdateOperationFailureCode.STAGING_FAILED, failure.code)
        assertTrue(session.calls.last().contains("rm -rf"))
        assertFalse(session.calls.any { it.contains("nohup sh") })
        assertTrue(session.closed)
    }

    @Test fun rootRevocationBeforeDispatchAttemptsCleanup() {
        val (apk, receipt, sha) = inputs()
        val session = FakeSession(response = { command, rootCount ->
            CommandResult(0, if (command == "id -u") { if (rootCount == 1) "0" else "2000" } else "")
        })
        val failure = runCatching {
            RootUpdateDispatcher(RootSessionFactory { session }) { true }
                .dispatch(id, apk.path, sha, receipt.path, 22, 10123) {}
        }.exceptionOrNull() as UpdateOperationException
        assertEquals(UpdateOperationFailureCode.ROOT_REQUIRED, failure.code)
        assertEquals(2, session.calls.count { it == "id -u" })
        assertTrue(session.calls.last().contains("rm -rf"))
        assertFalse(session.calls.any { it.contains("nohup sh") })
        assertTrue(session.closed)
    }

    @Test fun frameworkRevocationBeforeDispatchAttemptsCleanup() {
        val (apk, receipt, sha) = inputs()
        val session = FakeSession()
        var frameworkChecks = 0
        val failure = runCatching {
            RootUpdateDispatcher(RootSessionFactory { session }) { ++frameworkChecks == 1 }
                .dispatch(id, apk.path, sha, receipt.path, 22, 10123) {}
        }.exceptionOrNull() as UpdateOperationException
        assertEquals(UpdateOperationFailureCode.FRAMEWORK_REQUIRED, failure.code)
        assertEquals(2, frameworkChecks)
        assertTrue(session.calls.last().contains("rm -rf"))
        assertFalse(session.calls.any { it.contains("nohup sh") })
    }

    @Test fun failedReceiptPersistenceDoesNotDispatchInstaller() {
        val (apk, receipt, sha) = inputs()
        val session = FakeSession()
        val failure = runCatching {
            RootUpdateDispatcher(RootSessionFactory { session }) { true }
                .dispatch(id, apk.path, sha, receipt.path, 22, 10123) {
                    throw UpdateOperationException(UpdateOperationFailureCode.STAGING_FAILED)
                }
        }.exceptionOrNull() as UpdateOperationException
        assertEquals(UpdateOperationFailureCode.STAGING_FAILED, failure.code)
        assertTrue(session.calls.last().contains("rm -rf"))
        assertFalse(session.calls.any { it.contains("nohup sh") })
    }

    @Test fun lostDispatchAcknowledgementIsNotInstallSuccess() {
        val (apk, receipt, sha) = inputs()
        val session = FakeSession(response = { command, _ ->
            when {
                command == "id -u" -> CommandResult(0, "0")
                command.contains("nohup sh") -> CommandResult(0, "")
                else -> CommandResult(0, "")
            }
        })
        val failure = runCatching {
            RootUpdateDispatcher(RootSessionFactory { session }) { true }
                .dispatch(id, apk.path, sha, receipt.path, 22, 10123) {}
        }.exceptionOrNull() as UpdateOperationException
        assertEquals(UpdateOperationFailureCode.DISPATCH_FAILED, failure.code)
        assertTrue(session.calls.last().contains("nohup sh"))
        assertTrue(session.closed)
    }
}
