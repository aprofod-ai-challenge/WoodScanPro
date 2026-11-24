package com.example.woodscanpro

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScanNamingTest {
    @Test fun validCodes() {
        for (c in listOf("A", "lotto-12_B", "0042")) assertNull(c, ScanCode.error(c, emptyList()))
    }

    @Test fun invalidCodes() {
        for (c in listOf("", "a b", "a.b", "a/b", "..", "è", "a\n", "x".repeat(65)))
            assertNotNull(c, ScanCode.error(c, emptyList()))
    }

    @Test fun duplicatesIgnoreCase() {
        assertNotNull(ScanCode.error("Lotto1", listOf("LOTTO1")))
        assertNull(ScanCode.error("Lotto2", listOf("LOTTO1")))
    }
}
