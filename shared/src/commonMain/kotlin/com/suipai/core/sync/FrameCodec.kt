package com.suipai.core.sync

import kotlinx.serialization.Serializable

/**
 * 一帧：header 为 JSON 字节（可能为空），body 为可选二进制载荷。
 *
 * 与 [FrameHeader] 的 JSON 形态区分：这里是原始字节，便于 body 直通传输。
 */
class SyncFrame(
    val header: ByteArray,
    val body: ByteArray?,
)

/**
 * 帧头。一个结构覆盖所有消息类型，未用到的字段保持默认值即可。
 *
 * 消息类型与字段对照（冻结契约）：
 * - `hello`            -> 回 `hello`（deviceId/name/platform）
 * - `list-photos`      {offset, limit} -> `photo-list` {items}
 * - `get-thumb`        {photoId, maxEdge} -> `photo-bytes` {photoId}，body=JPEG
 * - `get-photo`        {photoId} -> `photo-bytes`，body=原图 JPEG（含 EXIF）
 * - `send-photo`       {meta}，body=JPEG -> `send-ok` 或 `send-error` {error}
 * - 未知 type          -> `{type 同名, ok=false, error="unknown type"}`
 */
@Serializable
data class FrameHeader(
    val type: String,
    val ok: Boolean = true,
    val error: String? = null,
    // hello
    val deviceId: String? = null,
    val name: String? = null,
    val platform: String? = null,
    // list-photos
    val offset: Int = 0,
    val limit: Int = 0,
    // photo-list / photo-bytes / send-photo
    val items: List<SyncPhotoMeta> = emptyList(),
    val photoId: String? = null,
    val maxEdge: Int = 0,
    val meta: SyncPhotoMeta? = null,
)

/**
 * 帧的纯编解码（不涉及 IO）。
 *
 * 线格式（大端）：
 * `[4B headerLen][header JSON UTF-8][4B bodyLen][body bytes]`
 * headerLen / bodyLen 为 0 表示该段不存在。长度字段一律大端有符号 Int。
 */
object FrameCodec {

    /** 把 header + body 编成完整一帧字节。body 为 null 或空表示无 body。 */
    fun encode(header: FrameHeader, body: ByteArray? = null): ByteArray {
        val headerBytes = SyncJson.encodeToString(FrameHeader.serializer(), header).encodeToByteArray()
        val bodyBytes = body ?: EMPTY_BYTES
        requireLength(headerBytes.size.toLong())
        requireLength(bodyBytes.size.toLong())

        val total = 4 + headerBytes.size + 4 + bodyBytes.size
        val out = ByteArray(total)
        writeIntBE(out, 0, headerBytes.size)
        headerBytes.copyInto(out, 4)
        val bodyLenPos = 4 + headerBytes.size
        writeIntBE(out, bodyLenPos, bodyBytes.size)
        bodyBytes.copyInto(out, bodyLenPos + 4)
        return out
    }

    /**
     * 解析一整帧字节。校验长度字段合法且不超过 [SyncProtocol.MAX_BODY]。
     *
     * @throws SyncException 长度非法或超限。
     */
    fun decode(bytes: ByteArray): SyncFrame {
        if (bytes.size < 4) throw SyncException("frame too short: ${bytes.size}")
        val headerLen = readIntBE(bytes, 0)
        requireLength(headerLen.toLong())
        if (bytes.size < 4 + headerLen + 4) throw SyncException("frame truncated at header")
        val header = bytes.copyOfRange(4, 4 + headerLen)
        val bodyLenPos = 4 + headerLen
        val bodyLen = readIntBE(bytes, bodyLenPos)
        requireLength(bodyLen.toLong())
        val bodyStart = bodyLenPos + 4
        if (bytes.size < bodyStart + bodyLen) throw SyncException("frame truncated at body")
        val body = if (bodyLen == 0) null else bytes.copyOfRange(bodyStart, bodyStart + bodyLen)
        return SyncFrame(header, body)
    }

    /** 解析帧头 JSON；header 为空或 JSON 非法时返回 null。 */
    fun decodeHeader(frame: SyncFrame): FrameHeader? {
        if (frame.header.isEmpty()) return null
        return try {
            SyncJson.decodeFromString(FrameHeader.serializer(), frame.header.decodeToString())
        } catch (_: Throwable) {
            null
        }
    }

    /** 仅编出 header JSON 字节，供测试与调试。 */
    fun encodeHeaderBytes(header: FrameHeader): ByteArray =
        SyncJson.encodeToString(FrameHeader.serializer(), header).encodeToByteArray()

    private fun requireLength(len: Long) {
        if (len < 0 || len > SyncProtocol.MAX_BODY) throw SyncException("body too large")
    }

    private val EMPTY_BYTES = ByteArray(0)

    private fun writeIntBE(out: ByteArray, pos: Int, value: Int) {
        out[pos] = (value ushr 24).toByte()
        out[pos + 1] = (value ushr 16).toByte()
        out[pos + 2] = (value ushr 8).toByte()
        out[pos + 3] = value.toByte()
    }

    private fun readIntBE(buf: ByteArray, pos: Int): Int =
        ((buf[pos].toInt() and 0xFF) shl 24) or
            ((buf[pos + 1].toInt() and 0xFF) shl 16) or
            ((buf[pos + 2].toInt() and 0xFF) shl 8) or
            (buf[pos + 3].toInt() and 0xFF)
}

/**
 * 帧的 IO 读写，跑在阻塞 [SyncConnection] 上。
 *
 * 注意：原生端用 Dispatchers.Default 承载阻塞 socket 读写，v1 可接受。
 * readFrame 返回 null 表示对端在帧边界正常关闭；抛异常表示 IO 错误。
 */
object FrameIO {

    /** 读一帧。返回 null = 对端正常关闭；抛 [SyncException] = IO 错误。 */
    fun readFrame(conn: SyncConnection): SyncFrame? {
        val lenBuf = ByteArray(4)
        try {
            conn.readFully(lenBuf, 0, 4)
        } catch (_: SyncEofException) {
            return null
        }
        val headerLen = readIntBE(lenBuf, 0)
        requireLength(headerLen)

        val header = ByteArray(headerLen)
        if (headerLen > 0) conn.readFully(header, 0, headerLen)

        conn.readFully(lenBuf, 0, 4)
        val bodyLen = readIntBE(lenBuf, 0)
        requireLength(bodyLen)

        val body = if (bodyLen == 0) null else ByteArray(bodyLen).also { conn.readFully(it, 0, bodyLen) }
        return SyncFrame(header, body)
    }

    /** 写一帧并 flush。 */
    fun writeFrame(conn: SyncConnection, header: FrameHeader, body: ByteArray? = null) {
        val bytes = FrameCodec.encode(header, body)
        conn.writeFully(bytes, 0, bytes.size)
    }

    private fun requireLength(len: Int) {
        if (len < 0 || len.toLong() > SyncProtocol.MAX_BODY) throw SyncException("body too large")
    }

    private fun readIntBE(buf: ByteArray, pos: Int): Int =
        ((buf[pos].toInt() and 0xFF) shl 24) or
            ((buf[pos + 1].toInt() and 0xFF) shl 16) or
            ((buf[pos + 2].toInt() and 0xFF) shl 8) or
            (buf[pos + 3].toInt() and 0xFF)
}