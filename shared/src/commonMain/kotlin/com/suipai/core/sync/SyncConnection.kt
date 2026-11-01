package com.suipai.core.sync

/**
 * 平台无关的阻塞式字节连接。
 *
 * 由各平台 actual（Android: java.net.Socket，iOS: posix socket）实现。
 * 约定：
 * - [readFully] 阻塞直到读满 len 字节；一个字节都没读到就对端关闭时抛 [SyncEofException]。
 * - [writeFully] 阻塞直到写完 len 字节。
 * - [close] 可从其他线程调用以唤醒阻塞中的读写。
 */
interface SyncConnection {
    fun readFully(buf: ByteArray, offset: Int, len: Int)

    fun writeFully(buf: ByteArray, offset: Int, len: Int)

    fun close()

    val isOpen: Boolean
}

/** 服务端每收到一条连接的回调。在平台的后台线程上执行，允许阻塞。 */
fun interface SyncServerHandler {
    fun onConnection(conn: SyncConnection, remoteHost: String)
}

/**
 * 单调时钟毫秒值，仅用于 TTL 与传输 id，不保证是墙上时间。
 *
 * 从类首次加载时刻起算，进程内单调递增，适合做 8 秒 TTL 这类差值判断。
 */
private val syncClockOrigin = kotlin.time.TimeSource.Monotonic.markNow()

internal fun syncNowMs(): Long = syncClockOrigin.elapsedNow().inWholeMilliseconds
/**
 * 墙上时钟毫秒值，用于对外展示（如设备「最近可见」时间）。
 * 与 [syncNowMs] 分离：TTL 用单调钟，展示用墙钟，避免两套时钟混用。
 */
internal expect fun wallNowMs(): Long
