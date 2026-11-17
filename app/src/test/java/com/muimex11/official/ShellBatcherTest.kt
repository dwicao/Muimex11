package com.muimex11.official

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for [ShellBatcher]. The batching path replaced one shell
 * process per `settings get` with a single tagged script, so the marker
 * parsing has to be exactly right — including noisy, multi-line and missing
 * command output.
 */
class ShellBatcherTest {

    @Test
    fun buildEmitsMarkerBeforeEachCommand() {
        val script = ShellBatcher.build(
            listOf(
                "a" to "settings get global foo",
                "b" to "settings get system bar"
            )
        )
        val lines = script.lines()
        assertEquals("echo '@@muimex:a@@'", lines[0])
        assertEquals("{ settings get global foo ; } 2>&1", lines[1])
        assertEquals("echo '@@muimex:b@@'", lines[2])
        assertEquals("{ settings get system bar ; } 2>&1", lines[3])
        assertEquals("echo '@@muimex:__end__@@'", lines[4])
    }

    @Test
    fun buildRejectsUnsafeKeys() {
        val bad = listOf("a b" to "true", "a;rm" to "true", "" to "true", "a/b" to "true")
        bad.forEach { req ->
            var threw = false
            try {
                ShellBatcher.build(listOf(req))
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("key should be rejected: '${req.first}'", threw)
        }
    }

    @Test
    fun buildRejectsEmptyBatch() {
        var threw = false
        try {
            ShellBatcher.build(emptyList())
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun parseRoundTrip() {
        val out = """
            @@muimex:min_refresh@@
            60
            @@muimex:peak_refresh@@
            120
            @@muimex:wm_size@@
            Physical size: 1080x2400
            Override size: 864x1920
            @@muimex:__end__@@
        """.trimIndent()
        val parsed = ShellBatcher.parse(out)
        assertEquals("60", parsed["min_refresh"])
        assertEquals("120", parsed["peak_refresh"])
        assertEquals("Physical size: 1080x2400\nOverride size: 864x1920", parsed["wm_size"])
        assertFalse(parsed.containsKey("__end__"))
    }

    @Test
    fun parseMissingKeyReturnsEmpty() {
        val parsed = ShellBatcher.parse("@@muimex:a@@\n1\n@@muimex:__end__@@\n")
        assertEquals("1", ShellBatcher.value(parsed, "a"))
        assertEquals("", ShellBatcher.value(parsed, "never-emitted"))
    }

    @Test
    fun parseIgnoresNoiseBeforeFirstMarker() {
        val parsed = ShellBatcher.parse(
            "WARNING: linker: something noisy\n@@muimex:a@@\nvalue\n@@muimex:__end__@@"
        )
        assertEquals("value", parsed["a"])
        assertEquals(1, parsed.size)
    }

    @Test
    fun parseHandlesEmptyBody() {
        val parsed = ShellBatcher.parse(
            "@@muimex:a@@\n@@muimex:b@@\n7\n@@muimex:__end__@@\n"
        )
        assertEquals("", parsed["a"])
        assertEquals("7", parsed["b"])
    }

    @Test
    fun runTaggedSkipsEmptyRequestList() {
        // No shell at all for an empty batch — keeps call sites cheap to guard.
        assertTrue(ShizukuRunner.runTagged(emptyList()).isEmpty())
    }
}
