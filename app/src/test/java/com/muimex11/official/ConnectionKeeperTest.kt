package com.muimex11.official

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionKeeperTest {

    @Test
    fun constantsMatchPancingScript() {
        assertEquals(
            listOf(
                "https://c.speedtest.net/speedtest-config.php",
                "https://c.speedtest.net/speedtest-servers-static.php"
            ),
            ConnectionKeeperLogic.URLS
        )
        assertEquals("Speedtest/4.8.0", ConnectionKeeperLogic.USER_AGENT)
        assertEquals("bytes=0-0", ConnectionKeeperLogic.RANGE_HEADER)
    }

    @Test
    fun latencyClassificationAndHttpCodes() {
        assertTrue(ConnectionKeeperLogic.isValidHttpCode(200))
        assertTrue(ConnectionKeeperLogic.isValidHttpCode(206))
        assertFalse(ConnectionKeeperLogic.isValidHttpCode(404))

        assertEquals(ConnectionKeeperLogic.LatencyClass.FAST, ConnectionKeeperLogic.classifyLatency(120L))
        assertEquals(ConnectionKeeperLogic.LatencyClass.NORMAL, ConnectionKeeperLogic.classifyLatency(500L))
        assertEquals(ConnectionKeeperLogic.LatencyClass.SLOW, ConnectionKeeperLogic.classifyLatency(2_400L))
    }

    @Test
    fun adaptiveIntervalAdjustmentFollowsLatencyAndRecovery() {
        val preset = ConnectionKeeperLogic.IntervalPreset.ADAPTIVE_DEFAULT
        // Normal response with stable backoffCount=3: stays within [25, 30]
        assertEquals(
            27,
            ConnectionKeeperLogic.computeNextIntervalSec(preset, 500L, failCount = 0, backoffCount = 3, rawRandom = 27, jitterOffset = 0)
        )
        // Fast (<300ms): subtracts 3 down to min-2 (23)
        assertEquals(
            23,
            ConnectionKeeperLogic.computeNextIntervalSec(preset, 100L, failCount = 0, backoffCount = 3, rawRandom = 25, jitterOffset = 0)
        )
        // Slow (>2000ms): adds 5 up to max+5 (35)
        assertEquals(
            35,
            ConnectionKeeperLogic.computeNextIntervalSec(preset, 2_500L, failCount = 0, backoffCount = 3, rawRandom = 30, jitterOffset = 0)
        )
        // Recovering (backoffCount < 3): adds +2
        assertEquals(
            29,
            ConnectionKeeperLogic.computeNextIntervalSec(preset, 500L, failCount = 0, backoffCount = 1, rawRandom = 27, jitterOffset = 0)
        )
    }

    @Test
    fun exponentialBackoffAndDurationFormatting() {
        assertEquals(5, ConnectionKeeperLogic.computeFailureBackoffSec(1))
        assertEquals(10, ConnectionKeeperLogic.computeFailureBackoffSec(2))
        assertEquals(10, ConnectionKeeperLogic.computeFailureBackoffSec(5))
        assertFalse(ConnectionKeeperLogic.shouldResetFailureCycle(19))
        assertTrue(ConnectionKeeperLogic.shouldResetFailureCycle(20))

        assertEquals("00:02:15", ConnectionKeeperLogic.formatDurationHms(135L))
        assertEquals("01:01:05", ConnectionKeeperLogic.formatDurationHms(3665L))
        assertEquals("1B", ConnectionKeeperLogic.formatBytesLabel(1L))
        assertEquals("2.0KB", ConnectionKeeperLogic.formatBytesLabel(2048L))
    }
}
