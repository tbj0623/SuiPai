package com.suipai.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 随拍双端互通的协议常量。
 *
 * 冻结契约：Android UI 层与 iOS Swift 层都按这些值工作，不要随意改动。
 */
object SyncProtocol {
    /** 发现用 UDP 端口。 */
    const val UDP_PORT = 47470

    /** 照片传输用 TCP 端口。 */
    const val TCP_PORT = 47471

    /** UDP 广播载荷前缀，其后为 JSON。 */
    const val PROTO_MAGIC = "SPDISCOVER1|"

    /** 单帧 body 上限：64MB。超过直接抛 [SyncException]。 */
    const val MAX_BODY = 64L * 1024 * 1024

    /** 协议版本。 */
    const val VERSION = 1
}

/** 照片元数据，两端共享的序列化形态。 */
@Serializable
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("SyncPhotoMeta", exact = true)
data class SyncPhotoMeta(
    val id: String,
    val name: String,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)

/** 发现到的对端设备。 */
@Serializable
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("PeerInfo", exact = true)
data class PeerInfo(
    val id: String,
    val name: String,
    val platform: String,
    val host: String,
    val tcpPort: Int,
    val lastSeenMs: Long,
)

/** 协议层统一异常。 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("SyncException", exact = true)
open class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 对端在帧边界前正常关闭连接。
 *
 * [SyncConnection.readFully] 在「一个字节都没读到就已关闭」时抛此异常，
 * [FrameIO.readFrame] 借此区分「正常关闭」（返回 null）与「读到一半断了」（IO 错误）。
 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("SyncEofException", exact = true)
class SyncEofException(message: String = "connection closed") : SyncException(message)

/**
 * 协议内共用的 JSON 配置。
 *
 * encodeDefaults=true 保证 `ok` 等默认值也出现在 header 里；
 * explicitNulls=false 让可选字段（deviceId 等）在为空时直接省略，减小帧头体积。
 */
internal val SyncJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}