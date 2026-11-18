package com.sikw00f.android.protocol

import io.dronefleet.mavlink.annotations.MavlinkMessageInfo
import io.dronefleet.mavlink.ardupilotmega.ArdupilotmegaDialect
import io.dronefleet.mavlink.common.CommonDialect
import io.dronefleet.mavlink.serialization.payload.reflection.ReflectionPayloadDeserializer

/**
 * Demultiplexes the serial stream of a SiKW00F radio in promiscuous mode.
 *
 * For every packet heard, the firmware prints `NetID: N\r\nChannel: C\r\n`
 * (and, since the RSSI build, `RSSI: R\r\n`) ahead of it (radio_443x.c
 * `print_scan_header`), then tdm.c writes the
 * packet's user data to serial whatever network it came from. So the bytes
 * after a header, up to the next header, are that NetID's payload; for
 * MAVLink links they carry whole MAVLink frames (incl. GPS position).
 *
 * Header lines are only parsed once complete, so a USB read that ends
 * mid-line ("NetID: 1" | "8\r\n") can't produce a bogus NetID. Payload bytes
 * seen before any header (radio not in promiscuous mode) are attributed to a
 * null NetID.
 */
class PromiscuousDecoder {

    sealed interface Event {
        /** A packet from [netId] was received. */
        data class Packet(val netId: Int) : Event
        /** Physical channel the last packet arrived on. */
        data class Channel(val netId: Int?, val channel: Int) : Event
        /** Raw Si443x RSSI latched while the last packet arrived (see [rssiToDbm]). */
        data class Rssi(val netId: Int?, val raw: Int) : Event
        /** [bytes] of user data that followed [netId]'s header (0 for empty TDM sync packets, so not emitted). */
        data class Payload(val netId: Int?, val bytes: Int) : Event
        /** A CRC-valid MAVLink message decoded from [netId]'s payload. */
        data class Mavlink(val netId: Int?, val sysId: Int, val compId: Int, val payload: Any) : Event
    }

    private var pending = ByteArray(0)
    private var currentNetId: Int? = null
    private val parsers = HashMap<Int?, MavlinkFrameParser>()

    fun feed(bytes: ByteArray): List<Event> {
        val data = if (pending.isEmpty()) bytes else pending + bytes
        val events = mutableListOf<Event>()
        var segmentStart = 0
        var i = 0
        scan@ while (i < data.size) {
            when (val match = matchHeader(data, i)) {
                HeaderMatch.None -> i++
                HeaderMatch.Partial -> break@scan
                is HeaderMatch.Found -> {
                    flushPayload(data, segmentStart, i, events)
                    when (match.kind) {
                        HeaderKind.NETID -> {
                            currentNetId = match.value
                            events += Event.Packet(match.value)
                        }
                        HeaderKind.CHANNEL -> events += Event.Channel(currentNetId, match.value)
                        HeaderKind.RSSI -> events += Event.Rssi(currentNetId, match.value)
                    }
                    i = match.end
                    segmentStart = i
                }
            }
        }
        flushPayload(data, segmentStart, i, events)
        pending = data.copyOfRange(i, data.size)
        return events
    }

    private fun flushPayload(data: ByteArray, from: Int, to: Int, out: MutableList<Event>) {
        if (to <= from) return
        val netId = currentNetId
        out += Event.Payload(netId, to - from)
        parsers.getOrPut(netId) { MavlinkFrameParser() }.feed(data, from, to) { sysId, compId, payload ->
            out += Event.Mavlink(netId, sysId, compId, payload)
        }
    }

    private sealed interface HeaderMatch {
        data object None : HeaderMatch
        data object Partial : HeaderMatch
        data class Found(val kind: HeaderKind, val value: Int, val end: Int) : HeaderMatch
    }

    private enum class HeaderKind { NETID, CHANNEL, RSSI }

    private fun matchHeader(data: ByteArray, start: Int): HeaderMatch {
        val (kind, prefix) = when (data[start]) {
            'N'.code.toByte() -> HeaderKind.NETID to NETID_PREFIX
            'C'.code.toByte() -> HeaderKind.CHANNEL to CHANNEL_PREFIX
            'R'.code.toByte() -> HeaderKind.RSSI to RSSI_PREFIX
            else -> return HeaderMatch.None
        }
        var i = start
        for (expected in prefix) {
            if (i >= data.size) return HeaderMatch.Partial
            if (data[i] != expected) return HeaderMatch.None
            i++
        }
        var value = 0
        var digits = 0
        while (true) {
            if (i >= data.size) return if (digits <= MAX_DIGITS) HeaderMatch.Partial else HeaderMatch.None
            val c = data[i].toInt().toChar()
            when {
                c.isDigit() && digits < MAX_DIGITS -> {
                    value = value * 10 + (c - '0'); digits++; i++
                }
                (c == '\r' || c == '\n') && digits > 0 -> {
                    // A read can end between \r and \n; wait for the \n rather
                    // than leave it behind as a stray byte of "payload".
                    if (c == '\r' && i + 1 >= data.size) return HeaderMatch.Partial
                    if (c == '\r' && data[i + 1] == '\n'.code.toByte()) i++
                    return HeaderMatch.Found(kind, value, i + 1)
                }
                else -> return HeaderMatch.None
            }
        }
    }

    private companion object {
        val NETID_PREFIX = "NetID: ".toByteArray(Charsets.US_ASCII)
        val CHANNEL_PREFIX = "Channel: ".toByteArray(Charsets.US_ASCII)
        val RSSI_PREFIX = "RSSI: ".toByteArray(Charsets.US_ASCII)
        const val MAX_DIGITS = 5
    }
}

/** Si443x RSSI register to approximate dBm (the conversion SiK ground stations use). */
fun rssiToDbm(raw: Int): Int = (raw / 1.9 - 127).toInt()

/**
 * Minimal MAVLink v1/v2 frame extractor for a lossy byte stream: resyncs on
 * start bytes, validates the X.25 CRC (with CRC_EXTRA from the dronefleet
 * dialect) and decodes payloads into dronefleet message objects.
 */
internal class MavlinkFrameParser {
    private var buf = ByteArray(0)

    fun feed(src: ByteArray, from: Int, to: Int, onMessage: (sysId: Int, compId: Int, payload: Any) -> Unit) {
        buf += src.copyOfRange(from, to)
        var i = 0
        while (i < buf.size) {
            val b = buf[i].toInt() and 0xFF
            if (b != V2_STX && b != V1_STX) { i++; continue }
            when (val r = tryFrame(i)) {
                FrameResult.Incomplete -> break
                FrameResult.Invalid -> i++
                is FrameResult.Ok -> {
                    onMessage(r.sysId, r.compId, r.payload)
                    i += r.length
                }
            }
        }
        buf = buf.copyOfRange(i, buf.size)
        // Bound memory if a bogus length byte keeps us waiting on noise.
        if (buf.size > MAX_BUFFER) buf = buf.copyOfRange(buf.size - MAX_FRAME, buf.size)
    }

    private sealed interface FrameResult {
        data object Incomplete : FrameResult
        data object Invalid : FrameResult
        class Ok(val length: Int, val sysId: Int, val compId: Int, val payload: Any) : FrameResult
    }

    private fun u8(i: Int) = buf[i].toInt() and 0xFF

    private fun tryFrame(i: Int): FrameResult {
        val v2 = u8(i) == V2_STX
        val headerLen = if (v2) 10 else 6
        if (buf.size - i < headerLen) return FrameResult.Incomplete
        val len = u8(i + 1)
        val signed = v2 && (u8(i + 2) and 0x01) != 0
        val total = headerLen + len + 2 + if (signed) 13 else 0
        if (buf.size - i < total) return FrameResult.Incomplete

        val sysId: Int
        val compId: Int
        val msgId: Int
        if (v2) {
            sysId = u8(i + 5); compId = u8(i + 6)
            msgId = u8(i + 7) or (u8(i + 8) shl 8) or (u8(i + 9) shl 16)
        } else {
            sysId = u8(i + 3); compId = u8(i + 4); msgId = u8(i + 5)
        }
        val type = MessageTypes.resolve(msgId) ?: return FrameResult.Invalid

        var crc = 0xFFFF
        for (k in i + 1 until i + headerLen + len) crc = crcAccumulate(u8(k), crc)
        crc = crcAccumulate(type.crcExtra, crc)
        val wire = u8(i + headerLen + len) or (u8(i + headerLen + len + 1) shl 8)
        if (crc != wire) return FrameResult.Invalid

        // MAVLink 2 trims trailing zero bytes; pad back out before decoding.
        val payloadBytes = ByteArray(MAX_PAYLOAD)
        System.arraycopy(buf, i + headerLen, payloadBytes, 0, len)
        val payload = runCatching { deserializer.deserialize(payloadBytes, type.clazz) }.getOrNull()
            ?: return FrameResult.Invalid
        return FrameResult.Ok(total, sysId, compId, payload)
    }

    private fun crcAccumulate(byte: Int, crc: Int): Int {
        var tmp = byte xor (crc and 0xFF)
        tmp = (tmp xor (tmp shl 4)) and 0xFF
        return ((crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)) and 0xFFFF
    }

    private companion object {
        const val V1_STX = 0xFE
        const val V2_STX = 0xFD
        const val MAX_PAYLOAD = 255
        const val MAX_FRAME = 280
        const val MAX_BUFFER = 4096
        val deserializer = ReflectionPayloadDeserializer()
    }
}

/** Message id -> (class, CRC_EXTRA), resolved once from the ArduPilot + common dialects. */
private object MessageTypes {
    class Type(val clazz: Class<*>, val crcExtra: Int)

    private val dialects = listOf(ArdupilotmegaDialect(), CommonDialect())
    private val cache = HashMap<Int, Type?>()

    @Synchronized
    fun resolve(msgId: Int): Type? = cache.getOrPut(msgId) {
        val clazz = dialects.firstOrNull { it.supports(msgId) }?.resolve(msgId) ?: return@getOrPut null
        val info = clazz.getAnnotation(MavlinkMessageInfo::class.java) ?: return@getOrPut null
        Type(clazz, info.crc)
    }
}
