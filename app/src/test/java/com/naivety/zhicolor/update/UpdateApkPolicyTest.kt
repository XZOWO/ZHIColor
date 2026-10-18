package com.naivety.zhicolor.update

import org.junit.Assert.*
import org.junit.Test

class UpdateApkPolicyTest {
    private val first = byteArrayOf(1, 2, 3)
    private val rotated = byteArrayOf(4, 5, 6)
    private val stranger = byteArrayOf(7, 8, 9)

    @Test fun sameSignerCanUpdate() {
        assertTrue(UpdateApkPolicy.signersCompatible(listOf(first), listOf(first.copyOf()), emptyList(), false, false))
    }

    @Test fun singleSignerRotationRequiresInstalledKeyInHistory() {
        assertTrue(UpdateApkPolicy.signersCompatible(listOf(first), listOf(rotated), listOf(first, rotated), false, false))
        assertFalse(UpdateApkPolicy.signersCompatible(listOf(first), listOf(stranger), listOf(rotated, stranger), false, false))
    }

    @Test fun multiSignerRequiresExactSignerSet() {
        assertTrue(UpdateApkPolicy.signersCompatible(listOf(first, rotated), listOf(rotated, first), emptyList(), true, true))
        assertFalse(UpdateApkPolicy.signersCompatible(listOf(first, rotated), listOf(first, stranger), emptyList(), true, true))
        assertFalse(UpdateApkPolicy.signersCompatible(listOf(first), listOf(first, rotated), emptyList(), false, true))
        assertFalse(UpdateApkPolicy.signersCompatible(listOf(first, first), listOf(first, stranger), emptyList(), true, true))
    }

    @Test fun absentSigningInformationNeverMatches() {
        assertFalse(UpdateApkPolicy.signersCompatible(emptyList(), listOf(first), emptyList(), false, false))
        assertFalse(UpdateApkPolicy.signersCompatible(listOf(first), emptyList(), emptyList(), false, false))
    }
}
