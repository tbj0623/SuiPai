package com.suipai.core.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 内存 [SyncConnection]：input 为可读字节，output 收集写入字节。
 * 用于在不碰真实 socket 的前提下测帧编解码。
 */
private class FakeConnection(private val input: ByteArray = ByteArray(0)) : SyncConnection {
    private var readPos = 0
    private val output = ArrayList<Byte>(input.size + 64)

    override fun readFully(buf: ByteArray, offset: Int, len: Int) {
        if (readPos + len > input.size) throw SyncEofException()
        input.copyInto(buf, offset, readPos, readPos + len)
        readPos += len
    }

    override fun writeFully(buf: ByteArray, offset: Int, len: Int) {
        for (i in 0 until len) output.add(buf[offset + i])
    }

    fun written(): ByteArray = output.toByteArray()

    override fun close() {}

    override val isOpen: Boolean get() = true
}

private fun beInt(bytes: ByteArray, pos: Int): Int =
    ((bytes[pos].toInt() and 0xFF) shl 24) or
        ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
        ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
        (bytes[pos + 3].toInt() and 0xFF)

/** 手工拼一帧，长度字段可任意指定，用于测超限与截断。 */
private fun rawFrame(header: ByteArray, bodyLenField: Int, body: ByteArray = ByteArray(0)): ByteArray {
    val out = ByteArray(4 + header.size + 4 + body.size)
    out[0] = (header.size ushr 24).toByte()
    out[1] = (header.size ushr 16).toByte()
    out[2] = (header.size ushr 8).toByte()
    out[3] = header.size.toByte()
    header.copyInto(out, 4)
    val p = 4 + header.size
    out[p] = (bodyLenField ushr 24).toByte()
    out[p + 1] = (bodyLenField ushr 16).toByte()
    out[p + 2] = (bodyLenField ushr 8).toByte()
    out[p + 3] = bodyLenField.toByte()
    body.copyInto(out, p + 4)
    return out
}

class FrameCodecTest {

    private val sampleMeta = SyncPhotoMeta(
        id = "photo-1",
        name = "IMG_0001.jpg",
        dateTaken = 1_700_000_000_000L,
        width = 4032,
        height = 3024,
        sizeBytes = 5_000_000L,
    )

    private val sampleHeader = FrameHeader(
        type = "photo-list",
        ok = true,
        items = listOf(sampleMeta, sampleMeta.copy(id = "photo-2", name = "IMG_0002.jpg")),
    )

    @Test
    fun encodeLayoutIsBigEndian() {
        val body = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = FrameCodec.encode(sampleHeader, body)

        val expectedHeader = FrameCodec.encodeHeaderBytes(sampleHeader)
        assertEquals(expectedHeader.size, beInt(encoded, 0), "headerLen 应为大端 header JSON 长度")
        assertContentEquals(expectedHeader, encoded.copyOfRange(4, 4 + expectedHeader.size))

        val bodyLenPos = 4 + expectedHeader.size
        assertEquals(body.size, beInt(encoded, bodyLenPos), "bodyLen 应为大端 body 长度")
        assertContentEquals(body, encoded.copyOfRange(bodyLenPos + 4, encoded.size))
        assertEquals(4 + expectedHeader.size + 4 + body.size, encoded.size)
    }

    @Test
    fun encodeDecodeRoundTripWithItemsAndBody() {
        val body = ByteArray(300) { (it and 0xFF).toByte() }
        val encoded = FrameCodec.encode(sampleHeader, body)

        val frame = FrameCodec.decode(encoded)
        val header = FrameCodec.decodeHeader(frame)
        assertNotNull(header)
        assertEquals("photo-list", header.type)
        assertEquals(2, header.items.size)
        assertEquals(sampleMeta, header.items[0])
        assertEquals("photo-2", header.items[1].id)
        assertContentEquals(body, frame.body)
    }

    @Test
    fun ioRoundTripThroughFakeConnection() {
        val body = byteArrayOf(9, 8, 7, 6)
        val writer = FakeConnection()
        FrameIO.writeFrame(writer, sampleHeader, body)

        val reader = FakeConnection(writer.written())
        val frame = FrameIO.readFrame(reader)
        assertNotNull(frame)
        val header = FrameCodec.decodeHeader(frame)
        assertNotNull(header)
        assertEquals(sampleHeader, header)
        assertContentEquals(body, frame.body)
    }

    @Test
    fun nullBodyEncodesZeroLengthAndReadsBackAsNull() {
        val encoded = FrameCodec.encode(FrameHeader(type = "hello", deviceId = "d1"))
        val headerLen = beInt(encoded, 0)
        assertEquals(0, beInt(encoded, 4 + headerLen), "无 body 时 bodyLen 应为 0")

        val frame = FrameIO.readFrame(FakeConnection(encoded))
        assertNotNull(frame)
        assertNull(frame.body)
        val header = FrameCodec.decodeHeader(frame)
        assertNotNull(header)
        assertEquals("hello", header.type)
        assertEquals("d1", header.deviceId)
    }

    @Test
    fun readFrameReturnsNullOnCleanEof() {
        assertNull(FrameIO.readFrame(FakeConnection(ByteArray(0))))
    }

    @Test
    fun readFrameThrowsOnTruncatedBody() {
        // 声明 5 字节 body，实际只给 2 字节。
        val encoded = rawFrame(FrameCodec.encodeHeaderBytes(FrameHeader(type = "x")), 5, byteArrayOf(1, 2))
        assertFailsWith<SyncException> {
            FrameIO.readFrame(FakeConnection(encoded))
        }
    }

    @Test
    fun readFrameThrowsWhenBodyLengthExceedsMax() {
        val encoded = rawFrame(
            FrameCodec.encodeHeaderBytes(FrameHeader(type = "send-photo")),
            (SyncProtocol.MAX_BODY + 1).toInt(),
        )
        val failure = assertFailsWith<SyncException> {
            FrameIO.readFrame(FakeConnection(encoded))
        }
        assertEquals("body too large", failure.message)
    }

    @Test
    fun decodeThrowsWhenHeaderLengthExceedsMax() {
        val bytes = rawFrame(ByteArray(0), 0)
        // 把 headerLen 字段改成超限值。
        bytes[0] = (0x7F).toByte()
        bytes[1] = (0xFF).toByte()
        bytes[2] = (0xFF).toByte()
        bytes[3] = (0xFF).toByte()
        assertFailsWith<SyncException> { FrameCodec.decode(bytes) }
    }

    @Test
    fun decodeHeaderReturnsNullForEmptyOrInvalid() {
        assertNull(FrameCodec.decodeHeader(SyncFrame(ByteArray(0), null)))
        assertNull(FrameCodec.decodeHeader(SyncFrame("not json".encodeToByteArray(), null)))
    }

    @Test
    fun unknownFieldsInHeaderAreIgnored() {
        val json = """{"type":"hello","ok":true,"extraField":"ignored"}""".encodeToByteArray()
        val header = FrameCodec.decodeHeader(SyncFrame(json, null))
        assertNotNull(header)
        assertEquals("hello", header.type)
        assertTrue(header.ok)
    }
}