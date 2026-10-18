package com.naivety.zhicolor.core

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RootSessionProtocolTest {
    @Test fun oneShellPreservesExitCodesAndContinuesAfterFailure() {
        val shell = if (System.getProperty("os.name")?.startsWith("Windows") == true) "C:/Program Files/Git/bin/bash.exe" else "/bin/sh"
        assumeTrue(File(shell).isFile)
        SuSession(ProcessBuilder(shell).start(), 5000).use { session ->
            assertEquals(CommandResult(0, "hello"), session.run("printf hello"))
            assertEquals(1, session.run("false").exitCode)
            assertEquals(CommandResult(0, "again"), session.run("printf again"))
        }
    }
}
