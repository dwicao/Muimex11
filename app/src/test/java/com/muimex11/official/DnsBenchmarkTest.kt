package com.muimex11.official

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsBenchmarkTest {

    @Test
    fun catalogHasPopularAndUnpopularResolversWithValidFields() {
        val all = DnsBenchmarkLogic.catalog
        val popular = all.filter { it.tier == DnsBenchmarkLogic.DnsTier.POPULAR }
        val unpopular = all.filter { it.tier == DnsBenchmarkLogic.DnsTier.UNPOPULAR }

        assertTrue("Expected at least 15 popular DNS", popular.size >= 15)
        assertTrue("Expected at least 25 unpopular DNS", unpopular.size >= 25)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.primaryIp }.toSet().size)

        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        all.forEach { p ->
            assertTrue("Bad IP for ${p.id}: ${p.primaryIp}", ipv4.matches(p.primaryIp))
            if (p.secondaryIp != null) {
                assertTrue("Bad secondary IP for ${p.id}: ${p.secondaryIp}", ipv4.matches(p.secondaryIp))
            }
            assertTrue(p.name.isNotBlank())
            assertTrue(p.region.isNotBlank())
            assertTrue(p.note.isNotBlank())
        }
    }

    @Test
    fun buildAndParseDnsPacketRoundTrip() {
        val txId = 0x4A2B
        val query = DnsBenchmarkLogic.buildDnsQuery("example.com", txId)
        assertEquals(0x4A, query[0].toInt() and 0xFF)
        assertEquals(0x2B, query[1].toInt() and 0xFF)
        assertEquals(0x01, query[2].toInt() and 0xFF)
        assertEquals(0x01, query[5].toInt() and 0xFF)

        val fakeResponse = query.copyOf()
        fakeResponse[2] = (fakeResponse[2].toInt() or 0x80).toByte() // QR = 1
        fakeResponse[3] = 0x80.toByte() // RCODE = 0 (NOERROR)
        assertEquals(0, DnsBenchmarkLogic.parseDnsResponse(fakeResponse, fakeResponse.size, txId))

        fakeResponse[3] = 0x83.toByte() // RCODE = 3 (NXDOMAIN)
        assertEquals(3, DnsBenchmarkLogic.parseDnsResponse(fakeResponse, fakeResponse.size, txId))

        // Reject wrong txId, query-bit=0, or truncated replies
        assertNull(DnsBenchmarkLogic.parseDnsResponse(fakeResponse, fakeResponse.size, 0x1111))
        assertNull(DnsBenchmarkLogic.parseDnsResponse(query, query.size, txId))
        assertNull(DnsBenchmarkLogic.parseDnsResponse(fakeResponse, 8, txId))
    }

    @Test
    fun frameDotPrependsLength() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val framed = DnsBenchmarkLogic.frameDotQuery(payload)
        assertEquals(7, framed.size)
        assertEquals(0, framed[0].toInt() and 0xFF)
        assertEquals(5, framed[1].toInt() and 0xFF)
        assertEquals(5, framed[6].toInt())
    }

    @Test
    fun summarizeSortAndFilterResults() {
        val stats = DnsBenchmarkLogic.summarizeSamples(listOf(12, 18, 15))
        assertEquals(15, stats.avgMs)
        assertEquals(12, stats.minMs)
        assertEquals(18, stats.maxMs)

        val emptyStats = DnsBenchmarkLogic.summarizeSamples(emptyList())
        assertNull(emptyStats.avgMs)

        val cf = DnsBenchmarkLogic.catalog.first { it.id == "cf" }
        val libredns = DnsBenchmarkLogic.catalog.first { it.id == "libredns" }
        val fdn = DnsBenchmarkLogic.catalog.first { it.id == "fdn" }

        val raw = listOf(
            DnsBenchmarkLogic.DnsBenchResult(fdn, DnsBenchmarkLogic.DnsProtocol.UDP_53, null, null, null, 0, 3),
            DnsBenchmarkLogic.DnsBenchResult(libredns, DnsBenchmarkLogic.DnsProtocol.UDP_53, 28, 25, 31, 3, 3),
            DnsBenchmarkLogic.DnsBenchResult(cf, DnsBenchmarkLogic.DnsProtocol.UDP_53, 14, 11, 17, 3, 3)
        )

        val sortedAll = DnsBenchmarkLogic.sortResults(raw)
        assertEquals(listOf("cf", "libredns", "fdn"), sortedAll.map { it.provider.id })

        val onlyUnpop = DnsBenchmarkLogic.sortResults(raw, DnsBenchmarkLogic.DnsFilter.UNPOPULAR)
        assertEquals(listOf("libredns", "fdn"), onlyUnpop.map { it.provider.id })

        val searched = DnsBenchmarkLogic.sortResults(raw, DnsBenchmarkLogic.DnsFilter.ALL, "Libre")
        assertEquals(listOf("libredns"), searched.map { it.provider.id })
    }

    @Test
    fun serializeAndDeserializeRoundTrip() {
        val cf = DnsBenchmarkLogic.catalog.first { it.id == "cf" }
        val dnswatch = DnsBenchmarkLogic.catalog.first { it.id == "dnswatch" }
        val input = listOf(
            DnsBenchmarkLogic.DnsBenchResult(cf, DnsBenchmarkLogic.DnsProtocol.DOT_853, 19, 17, 22, 3, 3),
            DnsBenchmarkLogic.DnsBenchResult(dnswatch, DnsBenchmarkLogic.DnsProtocol.DOT_853, null, null, null, 0, 3, "UDP only")
        )
        val encoded = DnsBenchmarkLogic.serializeResults(input)
        val decoded = DnsBenchmarkLogic.deserializeResults(encoded)
        assertEquals(2, decoded.size)
        assertEquals("cf", decoded[0].provider.id)
        assertEquals(19, decoded[0].avgMs)
        assertEquals(DnsBenchmarkLogic.DnsProtocol.DOT_853, decoded[0].protocol)
        assertEquals("UDP only", decoded[1].skippedReason)
        assertNotNull(decoded[1].provider)
    }
}
