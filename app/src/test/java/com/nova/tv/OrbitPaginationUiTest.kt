package com.nova.tv

import org.junit.Assert.*
import org.junit.Test

class OrbitPaginationUiTest {
    @Test fun prefetchesOnlyNearEndOfNonemptyGrid() {
        assertFalse(shouldLoadOrbitPage(-1, 0, 4))
        assertFalse(shouldLoadOrbitPage(15, 20, 4))
        assertTrue(shouldLoadOrbitPage(16, 20, 4))
        assertTrue(shouldLoadOrbitPage(0, 3, 4))
    }

    @Test fun stopsWhenPageAddsNoItemsOrCursorRepeats() {
        assertFalse(orbitPageProgressed(20, 20, null, null))
        assertFalse(orbitPageProgressed(20, 25, "cursor", "cursor"))
        assertTrue(orbitPageProgressed(20, 25, "old", "new"))
        assertTrue(orbitPageProgressed(20, 25, null, null))
        assertTrue(orbitPageProgressed(20, 25, "old", null))
    }
}
