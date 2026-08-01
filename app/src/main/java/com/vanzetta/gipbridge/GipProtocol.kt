package com.vanzetta.gipbridge

/**
 * Microsoft's Game Input Protocol (GIP) — wire format ported from the open-source
 * `xone` Linux kernel driver (medusalix/xone, bus/protocol.c, GPL-2.0-or-later).
 * https://github.com/medusalix/xone
 *
 * This is the documented, reverse-engineered DATA protocol Xbox One/Series
 * controllers speak over USB — not the console-side accessory authentication.
 * PC/XInput never needs the latter to read button/stick state; this bridge
 * is betting the 8BitDo unit behaves the same way over its wired connection.
 */
object GipCommand {
    const val ACKNOWLEDGE = 0x01
    const val ANNOUNCE = 0x02
    const val STATUS = 0x03
    const val IDENTIFY = 0x04
    const val POWER = 0x05
    const val AUTHENTICATE = 0x06
    const val VIRTUAL_KEY = 0x07
    const val AUDIO_CONTROL = 0x08
    const val LED = 0x0a
    const val HID_REPORT = 0x0b
    const val FIRMWARE = 0x0c
    const val SERIAL_NUMBER = 0x1e
    const val AUDIO_SAMPLES = 0x60

    // "client" (non-internal) commands
    const val RUMBLE = 0x09
    const val INPUT = 0x20
}

object GipOption {
    const val ACKNOWLEDGE = 0x10 // BIT(4)
    const val INTERNAL = 0x20    // BIT(5)
    const val CHUNK_START = 0x40 // BIT(6)
    const val CHUNK = 0x80       // BIT(7)
    const val CLIENT_ID_MASK = 0x0F
}

data class GipHeader(
    val command: Int,
    val options: Int,
    val sequence: Int,
    val packetLength: Int,
    val chunkOffset: Int = 0,
) {
    val isInternal get() = (options and GipOption.INTERNAL) != 0
    val needsAck get() = (options and GipOption.ACKNOWLEDGE) != 0
    val isChunked get() = (options and GipOption.CHUNK) != 0
}

/** 7-bit varint, continuation bit (0x80) = "more bytes follow". Matches gip_(en|de)code_varint. */
private fun encodeVarint(value: Int): ByteArray {
    val out = ArrayList<Byte>()
    var v = value
    do {
        var b = v and 0x7F
        v = v ushr 7
        if (v != 0) b = b or 0x80
        out.add(b.toByte())
    } while (v != 0)
    return out.toByteArray()
}

private fun decodeVarint(data: ByteArray, offset: Int): Pair<Int, Int> {
    var value = 0
    var i = 0
    while (offset + i < data.size && i < 4) {
        val b = data[offset + i].toInt() and 0xFF
        value = value or ((b and 0x7F) shl (i * 7))
        i++
        if ((b and 0x80) == 0) break
    }
    return value to i
}

/** Mirrors gip_encode_header: cmd, options, sequence, then varint(length) [+ varint(chunkOffset)],
 *  padded to an even total header length. */
fun encodeGipHeader(hdr: GipHeader): ByteArray {
    val out = ArrayList<Byte>()
    out.add(hdr.command.toByte())
    out.add(hdr.options.toByte())
    out.add(hdr.sequence.toByte())

    val lenBytes = encodeVarint(hdr.packetLength).toMutableList()
    var actualLen = 3 + lenBytes.size
    if (hdr.isChunked) actualLen += encodeVarint(hdr.chunkOffset).size

    if (actualLen % 2 != 0) {
        // set continuation bit on the last length byte, then pad with 0x00
        val last = lenBytes.removeAt(lenBytes.size - 1)
        lenBytes.add((last.toInt() or 0x80).toByte())
        lenBytes.add(0)
    }
    out.addAll(lenBytes)

    if (hdr.isChunked) out.addAll(encodeVarint(hdr.chunkOffset).toList())

    return out.toByteArray()
}

/** Mirrors gip_decode_header. Returns the header plus how many bytes it consumed. */
fun decodeGipHeader(data: ByteArray): Pair<GipHeader, Int> {
    var pos = 3
    val command = data[0].toInt() and 0xFF
    val options = data[1].toInt() and 0xFF
    val sequence = data[2].toInt() and 0xFF

    val (packetLength, lenConsumed) = decodeVarint(data, pos)
    pos += lenConsumed

    var chunkOffset = 0
    if ((options and GipOption.CHUNK) != 0) {
        val (chunk, chunkConsumed) = decodeVarint(data, pos)
        chunkOffset = chunk
        pos += chunkConsumed
    }

    return GipHeader(command, options, sequence, packetLength, chunkOffset) to pos
}

/** GIP_CMD_ACKNOWLEDGE payload: struct gip_pkt_acknowledge (8 bytes). */
fun buildAcknowledgePayload(ackedCommand: Int, clientId: Int, totalLen: Int, remaining: Int = 0): ByteArray {
    val buf = ByteArray(8)
    buf[0] = 0 // unknown
    buf[1] = ackedCommand.toByte()
    buf[2] = (clientId or GipOption.INTERNAL).toByte()
    buf[3] = (totalLen and 0xFF).toByte()
    buf[4] = ((totalLen shr 8) and 0xFF).toByte()
    // buf[5..6] padding
    buf[7] = (remaining and 0xFF).toByte()
    // (remaining high byte omitted for v1 — chunked packets not handled yet)
    return buf
}

// struct gip_gamepad_pkt_input from xone's driver/gamepad.c: buttons, trigL, trigR,
// stickLX, stickLY, stickRX, stickRY — all little-endian u16/s16, 14 bytes total.
object GipButton {
    const val MENU = 1 shl 2
    const val VIEW = 1 shl 3
    const val A = 1 shl 4
    const val B = 1 shl 5
    const val X = 1 shl 6
    const val Y = 1 shl 7
    const val DPAD_UP = 1 shl 8
    const val DPAD_DOWN = 1 shl 9
    const val DPAD_LEFT = 1 shl 10
    const val DPAD_RIGHT = 1 shl 11
    const val BUMPER_L = 1 shl 12
    const val BUMPER_R = 1 shl 13
    const val STICK_L = 1 shl 14
    const val STICK_R = 1 shl 15
}

data class GamepadState(
    val buttons: Int,
    val triggerLeft: Int,
    val triggerRight: Int,
    val stickLeftX: Int, val stickLeftY: Int,
    val stickRightX: Int, val stickRightY: Int,
) {
    fun describe(): String {
        val names = buildList {
            if (buttons and GipButton.MENU != 0) add("MENU")
            if (buttons and GipButton.VIEW != 0) add("VIEW")
            if (buttons and GipButton.A != 0) add("A")
            if (buttons and GipButton.B != 0) add("B")
            if (buttons and GipButton.X != 0) add("X")
            if (buttons and GipButton.Y != 0) add("Y")
            if (buttons and GipButton.DPAD_UP != 0) add("UP")
            if (buttons and GipButton.DPAD_DOWN != 0) add("DOWN")
            if (buttons and GipButton.DPAD_LEFT != 0) add("LEFT")
            if (buttons and GipButton.DPAD_RIGHT != 0) add("RIGHT")
            if (buttons and GipButton.BUMPER_L != 0) add("LB")
            if (buttons and GipButton.BUMPER_R != 0) add("RB")
            if (buttons and GipButton.STICK_L != 0) add("L3")
            if (buttons and GipButton.STICK_R != 0) add("R3")
        }
        val parts = mutableListOf<String>()
        if (names.isNotEmpty()) parts.add(names.joinToString("+"))
        if (triggerLeft != 0) parts.add("LT=$triggerLeft")
        if (triggerRight != 0) parts.add("RT=$triggerRight")
        if (kotlin.math.abs(stickLeftX) > 3000 || kotlin.math.abs(stickLeftY) > 3000) parts.add("LS=($stickLeftX,$stickLeftY)")
        if (kotlin.math.abs(stickRightX) > 3000 || kotlin.math.abs(stickRightY) > 3000) parts.add("RS=($stickRightX,$stickRightY)")
        val knownMask = GipButton.MENU or GipButton.VIEW or GipButton.A or GipButton.B or GipButton.X or GipButton.Y or
            GipButton.DPAD_UP or GipButton.DPAD_DOWN or GipButton.DPAD_LEFT or GipButton.DPAD_RIGHT or
            GipButton.BUMPER_L or GipButton.BUMPER_R or GipButton.STICK_L or GipButton.STICK_R
        val unknownBits = buttons and knownMask.inv()
        if (unknownBits != 0) parts.add("UNKNOWN_BITS=0x${unknownBits.toString(16)}")
        return if (parts.isEmpty()) "idle" else parts.joinToString(" ")
    }
}

fun parseGamepadInput(data: ByteArray): GamepadState? {
    if (data.size < 14) return null
    fun le16u(off: Int) = (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
    fun le16s(off: Int) = le16u(off).toShort().toInt()
    return GamepadState(
        buttons = le16u(0),
        triggerLeft = le16u(2),
        triggerRight = le16u(4),
        stickLeftX = le16s(6), stickLeftY = le16s(8),
        stickRightX = le16s(10), stickRightY = le16s(12),
    )
}

data class GipAnnounce(
    val address: ByteArray,
    val vendorId: Int,
    val productId: Int,
    val fwMajor: Int, val fwMinor: Int, val fwBuild: Int, val fwRevision: Int,
    val hwMajor: Int, val hwMinor: Int, val hwBuild: Int, val hwRevision: Int,
)

/** struct gip_pkt_announce: addr[6], unknown(le16), vendor(le16), product(le16), fw{4×le16}, hw{4×le16} = 26 bytes. */
fun parseAnnounce(data: ByteArray): GipAnnounce? {
    if (data.size < 26) return null
    fun le16(off: Int) = (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
    return GipAnnounce(
        address = data.copyOfRange(0, 6),
        vendorId = le16(8),
        productId = le16(10),
        fwMajor = le16(12), fwMinor = le16(14), fwBuild = le16(16), fwRevision = le16(18),
        hwMajor = le16(20), hwMinor = le16(22), hwBuild = le16(24), hwRevision = if (data.size >= 28) le16(26) else 0,
    )
}
