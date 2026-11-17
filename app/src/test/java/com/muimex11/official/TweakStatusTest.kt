package com.muimex11.official

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the typed tweak-state model. These exist because "already
 * applied?" used to be answered by substring-matching localized UI text, which
 * unit tests could not reach — and which broke whenever copy changed.
 */
class TweakStatusTest {

    @Test
    fun unknownUnlessSet() {
        StatusRegistry.clear()
        assertEquals(StatusKind.UNKNOWN, StatusRegistry.get(42))
        assertFalse(StatusRegistry.isApplied(42))
    }

    @Test
    fun appliedAndPartialCountAsApplied() {
        StatusRegistry.clear()
        StatusRegistry.set(1, StatusKind.APPLIED)
        StatusRegistry.set(2, StatusKind.PARTIAL)
        StatusRegistry.set(3, StatusKind.READY)
        assertTrue(StatusRegistry.isApplied(1))
        assertTrue(StatusRegistry.isApplied(2))
        assertFalse(StatusRegistry.isApplied(3))
    }

    @Test
    fun readyIsNotApplied() {
        StatusRegistry.clear()
        StatusRegistry.set(7, StatusKind.READY)
        assertFalse(StatusRegistry.isApplied(7))
        assertEquals(StatusKind.READY, StatusRegistry.get(7))
    }

    @Test
    fun clearResetsEverything() {
        StatusRegistry.set(9, StatusKind.APPLIED)
        StatusRegistry.clear()
        assertEquals(0, StatusRegistry.snapshot().size)
        assertEquals(StatusKind.UNKNOWN, StatusRegistry.get(9))
    }

    @Test
    fun classifyMatchesAppliedValues() {
        assertEquals(StatusKind.APPLIED, StatusRegistry.classify("0", "0"))
        assertEquals(StatusKind.READY, StatusRegistry.classify("1", "0"))
        assertEquals(StatusKind.APPLIED, StatusRegistry.classify(" 0.5\n", "0.5"))
        assertEquals(StatusKind.APPLIED, StatusRegistry.classify("hostname", "hostname", "automatic"))
    }

    @Test
    fun classifyTreatsEmptyAndNullAsUnknown() {
        assertEquals(StatusKind.UNKNOWN, StatusRegistry.classify(null, "0"))
        assertEquals(StatusKind.UNKNOWN, StatusRegistry.classify("", "0"))
        assertEquals(StatusKind.UNKNOWN, StatusRegistry.classify("null", "0"))
    }
}
