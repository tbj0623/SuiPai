// iOS actual：基于 platform.posix 的 BSD socket 实现。
//
// 说明：这是给 iOS 真机/模拟器用的代码，在 Windows 开发机上无法编译验证
// （本机未安装 Kotlin/Native 工具链，iosArm64/iosSimulatorArm64 目标被 Gradle 禁用）。
// 写法保守，沿用最标准的 BSD socket 模式：SO_REUSEADDR + bind/listen/accept，
// 读写用循环处理部分读写，accept/recv 用 SO_RCVTIMEO 1 秒 + running 标志退出。
// 后台循环用 NSThread.detachNewThread，避免占用 kotlinx.coroutines 的 Default 线程池。
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.suipai.core.sync

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSThread
import platform.posix.AF_INET
import platform.posix.ByteVar
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.INADDR_ANY
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_BROADCAST
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.SO_SNDTIMEO
import platform.posix.accept
import platform.posix.bind
import platform.posix.connect
import platform.posix.errno
import platform.posix.htons
import platform.posix.inet_addr
import platform.posix.inet_ntop
import platform.posix.listen
import platform.posix.recv
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socklen_tVar
import platform.posix.socket
import platform.posix.timeval
import platform.posix.close as posixClose

/** posix fd 之上的 [SyncConnection]。 */
private class PosixSyncConnection(private val fd: Int) : SyncConnection {

    @Volatile
    private var open = true

    override fun readFully(buf: ByteArray, offset: Int, len: Int) {
        var read = 0
        while (read < len) {
            val n = buf.usePinned { pinned ->
                recv(fd, pinned.addressOf(offset + read), (len - read).convert(), 0)
            }
            if (n == 0L) throw SyncEofException()
            if (n < 0L) {
                val err = errno
                if (err == EINTR || err == EAGAIN || err == EWOULDBLOCK) continue
                throw SyncException("recv failed: errno=$err")
            }
            read += n.toInt()
        }
    }

    override fun writeFully(buf: ByteArray, offset: Int, len: Int) {
        var written = 0
        while (written < len) {
            val n = buf.usePinned { pinned ->
                send(fd, pinned.addressOf(offset + written), (len - written).convert(), 0)
            }
            if (n < 0L) {
                val err = errno
                if (err == EINTR) continue
                throw SyncException("send failed: errno=$err")
            }
            if (n == 0L) throw SyncException("send returned 0")
            written += n.toInt()
        }
    }

    override fun close() {
        if (open) {
            open = false
            posixClose(fd)
        }
    }

    override val isOpen: Boolean
        get() = open
}

actual class SyncServer actual constructor() {

    @Volatile
    private var running = false

    @Volatile
    private var listenFd: Int = -1

    actual fun start(port: Int, handler: SyncServerHandler) {
        if (running) return
        running = true
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) {
            running = false
            throw SyncException("socket() failed")
        }
        try {
            applySocketOptions(fd, reuseAddress = true, receiveTimeoutMs = ACCEPT_TIMEOUT_MS, sendTimeoutMs = 0)
            memScoped {
                val addr = alloc<sockaddr_in>()
                addr.sin_family = AF_INET.convert()
                addr.sin_port = htons(port.toUShort())
                addr.sin_addr.s_addr = INADDR_ANY
                if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) < 0) {
                    throw SyncException("bind failed on port $port: errno=${errno}")
                }
            }
            if (listen(fd, BACKLOG) < 0) {
                throw SyncException("listen failed: errno=${errno}")
            }
        } catch (e: Throwable) {
            running = false
            posixClose(fd)
            throw if (e is SyncException) e else SyncException("server start failed", e)
        }
        listenFd = fd
        NSThread.detachNewThread {
            acceptLoop(fd, handler)
        }
    }

    actual fun stop() {
        if (!running) return
        running = false
        val fd = listenFd
        listenFd = -1
        if (fd >= 0) posixClose(fd)
    }

    private fun acceptLoop(fd: Int, handler: SyncServerHandler) {
        val hostBuffer = ByteArray(HOST_BUFFER_SIZE)
        while (running) {
            val peer = memScoped {
                val addr = alloc<sockaddr_in>()
                val len = alloc<socklen_tVar>()
                len.value = sizeOf<sockaddr_in>().convert()
                val clientFd = accept(fd, addr.ptr.reinterpret(), len.ptr)
                if (clientFd < 0) {
                    // SO_RCVTIMEO 超时或监听套接字已关闭：继续/退出
                    null
                } else {
                    val host = hostBuffer.usePinned { pinned ->
                        inet_ntop(AF_INET, addr.sin_addr.ptr, pinned.addressOf(0), HOST_BUFFER_SIZE.convert())
                            ?.toKString() ?: ""
                    }
                    clientFd to host
                }
            } ?: continue
            // TODO(互通加固): 这里没有像 androidMain 那样限制并发连接数。
            //   需要的是 kotlin.concurrent.AtomicInt 之类的原生原子计数，而本文件在本机无法编译验证，
            //   所以宁可留一个明确的缺口，也不写没跑过的原生并发代码。
            val clientFd = peer.first
            NSThread.detachNewThread {
                val conn = PosixSyncConnection(clientFd)
                try {
                    handler.onConnection(conn, peer.second)
                } catch (_: Throwable) {
                    // 处理器内部已收敛错误；此处仅兜底
                } finally {
                    conn.close()
                }
            }
        }
    }

    private companion object {
        const val ACCEPT_TIMEOUT_MS = 1000
        const val BACKLOG = 16
    }
}

actual class SyncClient actual constructor() {

    actual fun connect(host: String, port: Int, timeoutMs: Int): SyncConnection {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) throw SyncException("socket() failed")
        try {
            memScoped {
                val addr = alloc<sockaddr_in>()
                addr.sin_family = AF_INET.convert()
                addr.sin_port = htons(port.toUShort())
                addr.sin_addr.s_addr = inet_addr(host)
                if (connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) < 0) {
                    throw SyncException("connect failed: $host:$port errno=${errno}")
                }
            }
            // 连接建立后套用超时（connect 本身是阻塞的，timeoutMs 作为读写兜底超时）。
            applySocketOptions(fd, reuseAddress = false, receiveTimeoutMs = timeoutMs, sendTimeoutMs = timeoutMs)
        } catch (e: Throwable) {
            posixClose(fd)
            throw if (e is SyncException) e else SyncException("connect failed: $host:$port", e)
        }
        return PosixSyncConnection(fd)
    }
}

actual class DiscoveryBeacon actual constructor() {

    @Volatile
    private var announceRunning = false

    @Volatile
    private var listenRunning = false

    @Volatile
    private var announceFd: Int = -1

    @Volatile
    private var listenFd: Int = -1

    @Volatile
    private var announcePayload: ByteArray? = null

    @Volatile
    private var announceNudge = false

    actual fun startAnnounce(payload: String, intervalMs: Long) {
        announcePayload = payload.encodeToByteArray()
        if (announceRunning) {
            // 已经在广播：只换载荷、催一轮。之前这里无条件 stopAnnounce() 再新建 socket 和线程，
            // 于是每次 ON_RESUME 都拆建一次，还留出一个没人广播的空窗——恰好是对方发现不到你的时候。
            announceNudge = true
            return
        }
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        if (fd < 0) throw SyncException("udp socket() failed")
        applySocketOptions(fd, reuseAddress = false, receiveTimeoutMs = 0, sendTimeoutMs = 0)
        setBroadcast(fd)
        announceFd = fd
        announceRunning = true
        val interval = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        NSThread.detachNewThread {
            try {
                while (announceRunning) {
                    announcePayload?.let { broadcastOnce(fd, it) }
                    announceNudge = false
                    sleepInterruptibly(interval)
                }
            } finally {
                posixClose(fd)
                announceFd = -1
            }
        }
    }

    actual fun nudgeAnnounce() {
        // 只置标志。没在广播时什么都不做——refreshPeers 不应该把已停掉的信标偷偷重启。
        announceNudge = true
    }

    actual fun startListen(onPayload: (payload: String, host: String) -> Unit) {
        stopListen()
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        if (fd < 0) throw SyncException("udp socket() failed")
        try {
            applySocketOptions(fd, reuseAddress = true, receiveTimeoutMs = LISTEN_TIMEOUT_MS, sendTimeoutMs = 0)
            setBroadcast(fd)
            memScoped {
                val addr = alloc<sockaddr_in>()
                addr.sin_family = AF_INET.convert()
                addr.sin_port = htons(SyncProtocol.UDP_PORT.toUShort())
                addr.sin_addr.s_addr = INADDR_ANY
                if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) < 0) {
                    throw SyncException("udp bind failed: errno=${errno}")
                }
            }
        } catch (e: Throwable) {
            posixClose(fd)
            throw if (e is SyncException) e else SyncException("udp listen start failed", e)
        }
        listenFd = fd
        listenRunning = true
        NSThread.detachNewThread {
            listenLoop(fd, onPayload)
        }
    }

    actual fun stop() {
        stopAnnounce()
        stopListen()
    }

    private fun stopAnnounce() {
        announceRunning = false
        val fd = announceFd
        announceFd = -1
        if (fd >= 0) posixClose(fd)
    }

    private fun stopListen() {
        listenRunning = false
        val fd = listenFd
        listenFd = -1
        if (fd >= 0) posixClose(fd)
    }

    private fun broadcastOnce(fd: Int, data: ByteArray) {
        memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = htons(SyncProtocol.UDP_PORT.toUShort())
            addr.sin_addr.s_addr = inet_addr(BROADCAST_ADDR)
            data.usePinned { pinned ->
                sendto(
                    fd,
                    pinned.addressOf(0),
                    data.size.convert(),
                    0,
                    addr.ptr.reinterpret(),
                    sizeOf<sockaddr_in>().convert(),
                )
            }
        }
    }

    private fun listenLoop(fd: Int, onPayload: (payload: String, host: String) -> Unit) {
        val buffer = ByteArray(UDP_BUFFER_SIZE)
        val hostBuffer = ByteArray(HOST_BUFFER_SIZE)
        try {
            while (listenRunning) {
                memScoped {
                    val srcAddr = alloc<sockaddr_in>()
                    val srcLen = alloc<socklen_tVar>()
                    srcLen.value = sizeOf<sockaddr_in>().convert()
                    val n = buffer.usePinned { pinned ->
                        recvfrom(
                            fd,
                            pinned.addressOf(0),
                            buffer.size.convert(),
                            0,
                            srcAddr.ptr.reinterpret(),
                            srcLen.ptr,
                        )
                    }
                    if (n <= 0L) return@memScoped
                    val text = buffer.decodeToString(0, n.toInt())
                    if (!text.startsWith(SyncProtocol.PROTO_MAGIC)) return@memScoped
                    val payload = text.substring(SyncProtocol.PROTO_MAGIC.length)
                    val host = hostBuffer.usePinned { pinned ->
                        inet_ntop(AF_INET, srcAddr.sin_addr.ptr, pinned.addressOf(0), HOST_BUFFER_SIZE.convert())
                            ?.toKString() ?: ""
                    }
                    try {
                        onPayload(payload, host)
                    } catch (_: Throwable) {
                        // 回调异常不影响监听循环
                    }
                }
            }
        } finally {
            posixClose(fd)
            listenFd = -1
        }
    }

    private fun sleepInterruptibly(totalMs: Long) {
        var remaining = totalMs
        // 分片睡，所以 nudge 最多 SLEEP_CHUNK_MS 就能被兑现。
        while (announceRunning && !announceNudge && remaining > 0) {
            val chunk = if (remaining > SLEEP_CHUNK_MS) SLEEP_CHUNK_MS else remaining
            platform.posix.usleep((chunk * 1000).toUInt())
            remaining -= chunk
        }
    }

    private companion object {
        const val BROADCAST_ADDR = "255.255.255.255"
        const val LISTEN_TIMEOUT_MS = 1000
        const val UDP_BUFFER_SIZE = 8192
        const val HOST_BUFFER_SIZE = 64
        const val MIN_INTERVAL_MS = 50L
        const val SLEEP_CHUNK_MS = 100L
    }
}

/** 统一的套接字选项设置：reuseAddress / 收发超时（0 表示不设超时）。 */
private fun applySocketOptions(fd: Int, reuseAddress: Boolean, receiveTimeoutMs: Int, sendTimeoutMs: Int) {
    memScoped {
        if (reuseAddress) {
            val one = alloc<IntVar>()
            one.value = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
        }
        if (receiveTimeoutMs > 0) {
            val tv = alloc<timeval>()
            tv.tv_sec = (receiveTimeoutMs / 1000).toLong()
            tv.tv_usec = ((receiveTimeoutMs % 1000) * 1000)
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
        }
        if (sendTimeoutMs > 0) {
            val tv = alloc<timeval>()
            tv.tv_sec = (sendTimeoutMs / 1000).toLong()
            tv.tv_usec = ((sendTimeoutMs % 1000) * 1000)
            setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, tv.ptr, sizeOf<timeval>().convert())
        }
    }
}

/** UDP 广播开关。 */
private fun setBroadcast(fd: Int) {
    memScoped {
        val one = alloc<IntVar>()
        one.value = 1
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, one.ptr, sizeOf<IntVar>().convert())
    }
}