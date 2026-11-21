package com.nova.tv

import org.junit.Assert.*
import org.junit.Test

class ReleaseUpdateTest {
    @Test fun comparesNumericVersionComponents() {
        assertTrue(isNewerRelease("v0.1.10", "0.1.2"))
        assertTrue(isNewerRelease("v1.0.0", "0.9.99"))
        assertFalse(isNewerRelease("v0.1.2", "0.1.2"))
        assertFalse(isNewerRelease("v0.1.1", "0.1.2"))
        assertFalse(isNewerRelease("v0.2.0-rc1", "0.1.2"))
        assertFalse(isNewerRelease("invalid", "0.1.2"))
    }
}
