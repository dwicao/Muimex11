package com.muimex11.official

import org.junit.Assert.*
import org.junit.Test

/** Coverage for the automation (Tasker/MacroDroid) entry surface. */
class AutomationTest {

    @Test
    fun newTokenFormat() {
        val t = AutomationAuth.newToken()
        assertTrue(AutomationAuth.isWellFormed(t))
        assertEquals(32, t.length)
        assertNotEquals(AutomationAuth.newToken(), AutomationAuth.newToken())
    }

    @Test
    fun isWellFormedRejectsGarbage() {
        assertFalse(AutomationAuth.isWellFormed(null))
        assertFalse(AutomationAuth.isWellFormed(""))
        assertFalse(AutomationAuth.isWellFormed("short"))
        assertFalse(AutomationAuth.isWellFormed("has spaces in it!!"))
        assertFalse(AutomationAuth.isWellFormed("semi;colon-not-ok-123456"))
    }

    @Test
    fun matchesToken() {
        val stored = AutomationAuth.newToken()
        assertTrue(AutomationAuth.matches(stored, stored))
        assertFalse(AutomationAuth.matches(stored, AutomationAuth.newToken()))
        assertFalse(AutomationAuth.matches(stored, null))
        assertFalse(AutomationAuth.matches(null, stored))
        assertFalse(AutomationAuth.matches(stored, stored.dropLast(1)))
        assertFalse(AutomationAuth.matches("", ""))
    }

    @Test
    fun parseAction() {
        assertEquals(TaskerCmd.ACTION_RENDERER, TaskerCmd.parseAction("renderer"))
        assertEquals(TaskerCmd.ACTION_RENDERER, TaskerCmd.parseAction("  Renderer "))
        assertEquals(TaskerCmd.ACTION_BOOST, TaskerCmd.parseAction("game_boost"))
        assertEquals(TaskerCmd.ACTION_BOOST, TaskerCmd.parseAction("GAME_BOOST"))
        assertNull(TaskerCmd.parseAction(""))
        assertNull(TaskerCmd.parseAction(null))
        assertNull(TaskerCmd.parseAction("shell"))
        assertNull(TaskerCmd.parseAction("renderer; rm -rf /"))
    }

    @Test
    fun parseRenderer() {
        assertEquals("Vulkan", TaskerCmd.parseRenderer("vulkan"))
        assertEquals("Vulkan", TaskerCmd.parseRenderer("SKIAVK"))
        assertEquals("OpenGL", TaskerCmd.parseRenderer("opengl"))
        assertEquals("OpenGL", TaskerCmd.parseRenderer("skiagl"))
        assertNull(TaskerCmd.parseRenderer("direct3d"))
        assertNull(TaskerCmd.parseRenderer(null))
        assertNull(TaskerCmd.parseRenderer(""))
    }
}
