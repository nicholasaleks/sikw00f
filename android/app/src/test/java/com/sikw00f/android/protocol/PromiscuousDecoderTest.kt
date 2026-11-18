package com.sikw00f.android.protocol

import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.GpsFixType
import io.dronefleet.mavlink.common.GpsRawInt
import io.dronefleet.mavlink.minimal.Heartbeat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromiscuousDecoderTest {

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun frame(v2: Boolean, payload: Any, sysId: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        val conn = MavlinkConnection.create(ByteArrayInputStream(ByteArray(0)), out)
        if (v2) conn.send2(sysId, 1, payload) else conn.send1(sysId, 1, payload)
        return out.toByteArray()
    }

    private val position = GlobalPositionInt.builder()
        .lat(436282700).lon(-795025500).relativeAlt(42_000).alt(150_000).build()

    /** Feeds [stream] in chunks of [chunk] bytes, the way USB reads arrive. */
    private fun decodeChunked(stream: ByteArray, chunk: Int): List<PromiscuousDecoder.Event> {
        val decoder = PromiscuousDecoder()
        return stream.toList().chunked(chunk).flatMap { decoder.feed(it.toByteArray()) }
    }

    @Test
    fun headerSplitAcrossReadsIsNotMisparsed() {
        // The bug behind phantom NetID 1: a read ending between "1" and "8".
        val decoder = PromiscuousDecoder()
        val events = decoder.feed(ascii("NetID: 1")) + decoder.feed(ascii("8\r\nChannel: 12\r\n"))
        assertEquals(
            listOf(PromiscuousDecoder.Event.Packet(18), PromiscuousDecoder.Event.Channel(18, 12)),
            events
        )
    }

    @Test
    fun everyChunkSizeGivesTheSameHeaders() {
        val stream = ascii("NetID: 18\r\nChannel: 3\r\nNetID: 18\r\nChannel: 41\r\nNetID: 7\r\nChannel: 9\r\n")
        val expected = decodeChunked(stream, stream.size)
        for (chunk in 1..stream.size) assertEquals("chunk=$chunk", expected, decodeChunked(stream, chunk))
        assertEquals(6, expected.size)
        assertTrue(expected.none { it is PromiscuousDecoder.Event.Packet && it.netId == 1 })
    }

    @Test
    fun channelIsCreditedToPrecedingNetIdNotLastDiscovered() {
        val events = PromiscuousDecoder().feed(
            ascii("NetID: 18\r\nChannel: 1\r\nNetID: 1\r\nChannel: 2\r\nNetID: 18\r\nChannel: 3\r\n")
        )
        val channels = events.filterIsInstance<PromiscuousDecoder.Event.Channel>()
        assertEquals(listOf(18, 1, 18), channels.map { it.netId })
    }

    @Test
    fun decodesMavlinkPayloadAfterHeaderInAnyChunking() {
        for (v2 in listOf(true, false)) {
            val stream = ascii("NetID: 18\r\nChannel: 5\r\n") + frame(v2, position) +
                ascii("NetID: 25\r\nChannel: 6\r\n") + frame(v2, Heartbeat.builder().build(), sysId = 9)
            for (chunk in listOf(1, 2, 3, 7, 16, 1000)) {
                val mav = decodeChunked(stream, chunk).filterIsInstance<PromiscuousDecoder.Event.Mavlink>()
                assertEquals("v2=$v2 chunk=$chunk", 2, mav.size)
                assertEquals(18, mav[0].netId)
                val gp = mav[0].payload as GlobalPositionInt
                assertEquals(436282700, gp.lat())
                assertEquals(-795025500, gp.lon())
                assertEquals(25, mav[1].netId)
                assertEquals(9, mav[1].sysId)
                assertTrue(mav[1].payload is Heartbeat)
            }
        }
    }

    @Test
    fun corruptedFrameIsRejectedAndStreamRecovers() {
        val bad = frame(true, position).also { it[14] = (it[14] + 1).toByte() } // flip a payload byte
        val stream = ascii("NetID: 18\r\nChannel: 5\r\n") + bad +
            ascii("NetID: 18\r\nChannel: 6\r\n") + frame(true, position)
        val mav = PromiscuousDecoder().feed(stream).filterIsInstance<PromiscuousDecoder.Event.Mavlink>()
        assertEquals(1, mav.size)
    }

    @Test
    fun payloadWithoutHeadersIsAttributedToNullNetId() {
        val mav = PromiscuousDecoder().feed(frame(true, position)).filterIsInstance<PromiscuousDecoder.Event.Mavlink>()
        assertEquals(1, mav.size)
        assertEquals(null, mav[0].netId)
    }

    @Test
    fun positionPrefersFixAndScales() {
        val fix = mavlinkPosition(18, position, now = 1L)
        assertNotNull(fix)
        assertEquals(43.62827, fix!!.latitude, 1e-6)
        assertEquals(-79.50255, fix.longitude, 1e-6)
        assertEquals(42.0, fix.altitudeM!!, 1e-6)

        val noFix = GpsRawInt.builder().lat(436282700).lon(-795025500).fixType(GpsFixType.GPS_FIX_TYPE_NO_FIX).build()
        assertEquals(null, mavlinkPosition(18, noFix))
        val fix3d = GpsRawInt.builder().lat(436282700).lon(-795025500).fixType(GpsFixType.GPS_FIX_TYPE_3D_FIX).build()
        assertEquals("GPS_RAW_INT", mavlinkPosition(18, fix3d)?.source)
    }

    @Test
    fun rssiLineIsParsedAndPayloadStillDecodes() {
        // RSSI firmware build: NetID, Channel and RSSI header lines, then the payload.
        val stream = ascii("NetID: 18\r\nChannel: 7\r\nRSSI: 140\r\n") + frame(true, position)
        for (chunk in listOf(1, 3, 64)) {
            val events = decodeChunked(stream, chunk)
            assertEquals(PromiscuousDecoder.Event.Rssi(18, 140), events.filterIsInstance<PromiscuousDecoder.Event.Rssi>().single())
            val mav = events.filterIsInstance<PromiscuousDecoder.Event.Mavlink>().single()
            assertEquals(18, mav.netId)
            assertTrue(mav.payload is GlobalPositionInt)
        }
    }

    @Test
    fun rssiConvertsToDbm() {
        assertEquals(-127, rssiToDbm(0))
        assertEquals(-53, rssiToDbm(140))
    }

    @Test
    fun signalTrendFollowsFastVersusSlowAverage() {
        val closing = DiscoveredDrone(netId = 1, rssiFast = -60.0, rssiSlow = -75.0)
        val leaving = DiscoveredDrone(netId = 1, rssiFast = -90.0, rssiSlow = -75.0)
        val steady = DiscoveredDrone(netId = 1, rssiFast = -74.0, rssiSlow = -75.0)
        assertEquals(1, closing.rssiTrend)
        assertEquals(-1, leaving.rssiTrend)
        assertEquals(0, steady.rssiTrend)
    }

    @Test
    fun idleLinkIsNotReportedAsUndecodable() {
        // Empty TDM packets only: radios linked, nothing sending telemetry.
        val idle = DiscoveredDrone(netId = 1, packetsCount = 30, mavlinkCount = 0, payloadBytes = 0)
        assertTrue(idle.linkIdle)
        assertTrue(!idle.noMavlink)
        // Real data that never decodes: encrypted or another protocol.
        val opaque = DiscoveredDrone(netId = 1, packetsCount = 30, mavlinkCount = 0, payloadBytes = 3000)
        assertTrue(!opaque.linkIdle)
        assertTrue(opaque.noMavlink)
        // Too few packets to judge, or decoding fine.
        assertTrue(!DiscoveredDrone(netId = 1, packetsCount = 5).noMavlink)
        assertTrue(!DiscoveredDrone(netId = 1, packetsCount = 30, mavlinkCount = 2, payloadBytes = 3000).noMavlink)
    }

    @Test
    fun emptyPacketsCarryNoPayload() {
        val events = PromiscuousDecoder().feed(
            ascii("NetID: 13\r\nChannel: 4\r\nRSSI: 120\r\nNetID: 13\r\nChannel: 9\r\nRSSI: 118\r\n")
        )
        assertTrue(events.none { it is PromiscuousDecoder.Event.Payload })
        assertEquals(2, events.count { it is PromiscuousDecoder.Event.Packet })
    }

    @Test
    fun payloadIsCountedPerNetId() {
        val frame = frame(true, position)
        val events = PromiscuousDecoder().feed(ascii("NetID: 2\r\nChannel: 1\r\n") + frame)
        val payload = events.filterIsInstance<PromiscuousDecoder.Event.Payload>()
        assertEquals(frame.size, payload.filter { it.netId == 2 }.sumOf { it.bytes })
    }
}
