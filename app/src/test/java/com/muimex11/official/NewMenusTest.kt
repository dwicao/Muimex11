package com.muimex11.official

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewMenusTest {

    @Test
    fun inputTesterComputesHzAndDeadzone() {
        // 240 Hz = ~4,166,667 ns per sample
        val timestamps = (0..10).map { it * 4_166_667L }
        val hz = InputTesterLogic.hzFromTimestampsNs(timestamps)
        assertTrue("Expected ~240 Hz, got $hz", hz in 238..242)

        assertEquals(0, InputTesterLogic.hzFromTimestampsNs(listOf(1000L)))
        assertEquals(0f, InputTesterLogic.stickMagnitude(0.02f, 0.03f), 0.001f)
        assertTrue(InputTesterLogic.stickMagnitude(0.6f, 0.8f) > 0.9f)
    }

    @Test
    fun netPingLogicSummarizesAndParsesCustomTarget() {
        val (avg, jitter, minMs) = NetPingLogic.summarize(listOf(20, 30, 22, 28), 4)
        assertEquals(25, avg)
        assertEquals(8, jitter)
        assertEquals(20, minMs)

        val empty = NetPingLogic.summarize(emptyList(), 4)
        assertNull(empty.first)

        val valid = NetPingLogic.parseCustomTarget("sg.game.example.com:8443")
        assertNotNull(valid)
        assertEquals("sg.game.example.com", valid?.host)
        assertEquals(8443, valid?.port)

        assertNull(NetPingLogic.parseCustomTarget("bad;host"))
        assertNull(NetPingLogic.parseCustomTarget("host:99999"))
        assertEquals("5 GHz · Ch 36", NetPingLogic.freqToBandChannel(5180))
        assertEquals("2.4 GHz · Ch 6", NetPingLogic.freqToBandChannel(2437))
    }

    @Test
    fun artCompilerCommandValidation() {
        assertEquals(
            "cmd package compile -m speed -f com.example.game",
            ArtCompilerLogic.buildCompileCommand("com.example.game", ArtCompilerLogic.CompileProfile.SPEED)
        )
        assertNull(ArtCompilerLogic.buildCompileCommand("bad;pkg", ArtCompilerLogic.CompileProfile.SPEED))
        assertEquals(
            "cmd package compile --reset com.a && cmd package compile --reset com.b",
            ArtCompilerLogic.buildResetAllCommand(linkedSetOf("com.a", "com.b"))
        )
    }

    @Test
    fun wakelockInspectorParsesPowerAlarmsAndBuckets() {
        val powerRaw = """
            PARTIAL_WAKE_LOCK '*alarm*' ACQ=-1s (uid=10123 pid=100 pkg=com.drain.app)
            PARTIAL_WAKE_LOCK 'sync' ACQ=-5s (uid=10123 pid=100 pkg=com.drain.app)
            PARTIAL_WAKE_LOCK 'AudioMix' ACQ=-2s (uid=1041 pkg=com.audio.app)
        """.trimIndent()
        val locks = WakelockLogic.parsePowerWakelocks(powerRaw)
        assertEquals(2, locks["com.drain.app"])
        assertEquals(1, locks["com.audio.app"])

        val alarmRaw = "  u0a123:com.drain.app +12ms running, 18 wakeups:"
        val alarms = WakelockLogic.parseAlarmWakeups(alarmRaw)
        assertEquals(18, alarms["com.drain.app"])

        val bucketsRaw = "com.drain.app 10\ncom.audio.app 40"
        val buckets = WakelockLogic.parseStandbyBuckets(bucketsRaw)
        assertEquals("active", buckets["com.drain.app"])
        assertEquals("rare", buckets["com.audio.app"])

        val merged = WakelockLogic.mergeTopEntries(locks, alarms, buckets)
        assertEquals("com.drain.app", merged.first().pkg)
        assertEquals(38, merged.first().score)
        assertEquals("am set-standby-bucket com.drain.app restricted", WakelockLogic.buildBucketCommand("com.drain.app", "restricted"))
    }

    @Test
    fun dexDesktopLogicParsesAndCountsAppliedSettings() {
        val tagged = ShellBatcher.run { "" }
        assertNotNull(tagged)
        val raw = """
            __M_START_force_desktop
            1
            __M_END_force_desktop
            __M_START_freeform
            1
            __M_END_freeform
            __M_START_resizable
            0
            __M_END_resizable
            __M_START_non_res_mw
            1
            __M_END_non_res_mw
            __M_START_stay_on
            7
            __M_END_stay_on
            __M_START_font_scale
            0.85
            __M_END_font_scale
            __M_START_ptr_speed
            3
            __M_END_ptr_speed
        """.trimIndent()
        val state = DexDesktopLogic.parseState(raw)
        assertTrue(state.forceDesktop)
        assertTrue(state.freeform)
        assertFalse(state.forceResizable)
        assertTrue(state.nonResizableMw)
        assertTrue(state.stayAwake)
        assertEquals(6, state.appliedCount())
        assertTrue(DexDesktopLogic.buildResetCommand().contains("settings delete system pointer_speed"))
    }

    @Test
    fun privacyAuditParsesAllowedOpsAndBuildsRevokeCommands() {
        val dump = """
            Uid u0a210:
              Package com.social.app:
                READ_CLIPBOARD (allow):
                CAMERA (foreground):
                RECORD_AUDIO (ignore):
              Package com.muimex11.official:
                READ_CLIPBOARD (allow):
        """.trimIndent()
        val findings = PrivacyAuditLogic.parseDumpsysAppOps(dump)
        assertEquals(2, findings.size)
        assertEquals("READ_CLIPBOARD", findings[0].op)
        assertEquals("CAMERA", findings[1].op)
        assertEquals(
            "cmd appops set com.social.app READ_CLIPBOARD ignore",
            PrivacyAuditLogic.buildRevokeCommand("com.social.app", "READ_CLIPBOARD")
        )
        assertNull(PrivacyAuditLogic.buildRevokeCommand("com.social.app", "UNKNOWN_OP"))
    }

    @Test
    fun perfProfilerParsesGfxMetricsAndSerializesSessions() {
        val gfx = """
            Total frames rendered: 1850
            Janky frames: 37 (2.00%)
            99th percentile: 21ms
        """.trimIndent()
        val parsed = PerfProfilerLogic.parseGfxMetrics(gfx)
        assertNotNull(parsed)
        assertEquals(1850L, parsed?.totalFrames)
        assertEquals(37L, parsed?.jankyFrames)
        assertEquals(21, parsed?.p99Ms)

        val (fps, jank) = PerfProfilerLogic.computeFpsAndJank(1000L, 1900L, 10L, 28L, 15_000L)
        assertEquals(60, fps)
        assertEquals(2.0f, jank, 0.01f)

        val session = PerfProfilerLogic.PerfSession("com.game.test", "Test Game", 30, 60, 2.0f, 18, 34.5f, 36.2f, 2400, 123456L)
        val encoded = PerfProfilerLogic.serializeSessions(listOf(session))
        val decoded = PerfProfilerLogic.deserializeSessions(encoded)
        assertEquals(1, decoded.size)
        assertEquals("Test Game", decoded[0].label)
        assertEquals(1.7f, decoded[0].tempDeltaC, 0.05f)
    }
}
