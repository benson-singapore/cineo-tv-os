package com.nova.tv

import org.junit.Assert.*
import org.junit.Test

class OrbitWatchCompletionTest {
    @Test fun completionRequiresKnownDurationAndNearEnd() {
        assertFalse(orbitWatchComplete(0, 0))
        assertFalse(orbitWatchComplete(0, 5_000))
        assertFalse(orbitWatchComplete(4_000, 5_000))
        assertTrue(orbitWatchComplete(4_500, 5_000))
        assertFalse(orbitWatchComplete(80_000, 100_000))
        assertTrue(orbitWatchComplete(90_000, 100_000))
    }
}
