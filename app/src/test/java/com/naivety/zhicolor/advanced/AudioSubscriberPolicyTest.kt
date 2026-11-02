package com.naivety.zhicolor.advanced

import org.junit.Assert.*
import org.junit.Test

class AudioSubscriberPolicyTest {
    @Test fun acceptsTheActualDedicatedSystemUiUid() {
        assertTrue(AudioSubscriberPolicy.accepts(10218, 10225, 10218))
    }
    @Test fun retainsSystemUidAndModuleClients() {
        assertTrue(AudioSubscriberPolicy.accepts(1000, 10225, 10218))
        assertTrue(AudioSubscriberPolicy.accepts(10225, 10225, 10218))
    }
    @Test fun rejectsUnrelatedUidEvenWhenSystemUiLookupFails() {
        assertFalse(AudioSubscriberPolicy.accepts(10219, 10225, 10218))
        assertFalse(AudioSubscriberPolicy.accepts(10218, 10225, -1))
        assertFalse(AudioSubscriberPolicy.accepts(-1, 10225, -1))
        assertFalse(AudioSubscriberPolicy.accepts(0, 10225, -1))
    }
}
