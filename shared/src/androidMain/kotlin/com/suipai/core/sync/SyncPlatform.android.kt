// Android actual：基于 java.net 的阻塞式 socket 实现。
// 所有后台循环用普通 Thread（daemon=true），与 commonMain 的阻塞契约一致。
package com.suipai.core.sync

import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** java.net.Socket 之上的 [SyncConnection]。 */
private class JavaSyncConnection(private val socket: Socket) : SyncConnection {

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    override fun readFully(buf: ByteArray, offset: Int, len: Int) {
        var read = 0
        while (read < len) {
            val n = try {
                input.read(buf, offset + read, len - read)
            } catch (e: SocketTimeoutException) {
                throw SyncException("read timed out", e)
            } catch (e: SocketException) {
                throw SyncEofException(e.message ?: "socket closed")
            }
            if (n < 0) throw SyncEofException()
            if (n == 0) continue
            read += n
        }
    }

    override fun writeFully(buf: ByteArray, offset: Int, len: Int) {
        output.write(buf, offset, len)
        output.flush()
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Throwable) {
            // 关闭失败无所谓
        }
    }

    override val isOpen: Boolean
        get() = !socket.isClosed
}

actual class SyncServer actual constructor() {

    private val running = AtomicBoolean(false)

    /** 并发连接上限。按实例计，别按进程计，否则第二个 SyncServer 会抢第一个的名额。 */
    private val slots = Semaphore(MAX_CONCURRENT_CONNECTIONS)

    @Volatile
    private var serverSocket: ServerSocket? = null

    actual fun start(port: Int, handler: SyncServerHandler) {
        if (!running.compareAndSet(false, true)) return
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
                soTimeout = ACCEPT_TIMEOUT_MS
            }
        } catch (e: Throwable) {
            running.set(false)
            throw SyncException("server bind failed on port $port", e)
        }
        serverSocket = socket
        Thread({
            acceptLoop(socket, handler)
        }, "suipai-sync-accept").apply {
            isDaemon = true
            start()
        }
    }

    actual fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
            // ignore
        }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket, handler: SyncServerHandler) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: SocketException) {
                if (!running.get()) break else continue
            } catch (_: Throwable) {
                if (!running.get()) break else continue
            }
            // 先抢名额再读，否则一个握手后不发数据的对端可以无限堆积线程。
            if (!slots.tryAcquire()) {
                try {
                    client.close()
                } catch (_: Throwable) {
                    // ignore
                }
                continue
            }
            val host = client.inetAddress?.hostAddress ?: ""
            try {
                // 空闲读超时：没有它，一条静默连接会永久占住一个线程 + 一个名额。
                client.soTimeout = CONNECTION_IDLE_TIMEOUT_MS
            } catch (_: Throwable) {
                // 设不上也继续服务这条连接
            }
            Thread({
                val conn = JavaSyncConnection(client)
                try {
                    handler.onConnection(conn, host)
                } catch (_: Throwable) {
                    // 处理器内部已收敛错误；此处仅兜底
                } finally {
                    conn.close()
                    slots.release()
                }
            }, "suipai-sync-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private companion object {
        const val ACCEPT_TIMEOUT_MS = 1000
        const val CONNECTION_IDLE_TIMEOUT_MS = 30_000
        const val MAX_CONCURRENT_CONNECTIONS = 8
    }
}

actual class SyncClient actual constructor() {

    private companion object {
        /** 够容纳一张 64MB 的照片在慢速 Wi-Fi 上分块到达，又不至于永久挂死。 */
        const val IDLE_READ_TIMEOUT_MS = 60_000
    }

    actual fun connect(host: String, port: Int, timeoutMs: Int): SyncConnection {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.tcpNoDelay = true
            // 之前这里是 soTimeout = 0（永久阻塞）：对端握手后不再回话，本端这条
            // 协程/线程就永远挂着。给一个足够宽裕但有限的空闲读超时。
            socket.soTimeout = IDLE_READ_TIMEOUT_MS
        } catch (e: Throwable) {
            try {
                socket.close()
            } catch (_: Throwable) {
                // ignore
            }
            throw SyncException("connect failed: $host:$port", e)
        }
        return JavaSyncConnection(socket)
    }
}

actual class DiscoveryBeacon actual constructor() {

    private val announceRunning = AtomicBoolean(false)
    private val listenRunning = AtomicBoolean(false)

    @Volatile
    private var announceSocket: DatagramSocket? = null
    @Volatile
    private var listenSocket: DatagramSocket? = null
    @Volatile
    private var announceThread: Thread? = null
    @Volatile
    private var listenThread: Thread? = null

    @Volatile
    private var announcePayload: ByteArray? = null

    @Volatile
    private var announceNudge = false

    actual fun startAnnounce(payload: String, intervalMs: Long) {
        announcePayload = payload.encodeToByteArray()
        if (announceRunning.get()) {
            // 已经在广播：只换载荷、催一轮。之前这里无条件 stopAnnounce() 再起新线程新 socket，
            // 于是每次 ON_RESUME 都拆建一次，还留出一个没人广播的空窗——恰好是对方发现不到你的时候。
            announceNudge = true
            return
        }
        if (!announceRunning.compareAndSet(false, true)) return
        val thread = Thread({
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                announceSocket = socket
                val address = InetAddress.getByName(BROADCAST_ADDR)
                while (announceRunning.get()) {
                    val data = announcePayload
                    if (data != null) {
                        val packet = DatagramPacket(data, data.size, address, SyncProtocol.UDP_PORT)
                        try {
                            socket.send(packet)
                        } catch (_: Throwable) {
                            // 单次广播失败忽略，继续下一轮
                        }
                    }
                    announceNudge = false
                    sleepInterruptibly(intervalMs)
                }
            } catch (_: Throwable) {
                // 套接字创建失败则退出线程
            } finally {
                try {
                    socket?.close()
                } catch (_: Throwable) {
                    // ignore
                }
                announceSocket = null
            }
        }, "suipai-beacon-announce").apply {
            isDaemon = true
            start()
        }
        announceThread = thread
    }

    actual fun nudgeAnnounce() {
        // 只置标志。没在广播时什么都不做——refreshPeers 不应该把已停掉的信标偷偷重启。
        announceNudge = true
    }

    actual fun startListen(onPayload: (payload: String, host: String) -> Unit) {
        stopListen()
        if (!listenRunning.compareAndSet(false, true)) return
        val thread = Thread({
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = LISTEN_TIMEOUT_MS
                    bind(InetSocketAddress(SyncProtocol.UDP_PORT))
                }
                listenSocket = socket
                val buffer = ByteArray(UDP_BUFFER_SIZE)
                while (listenRunning.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: Throwable) {
                        if (!listenRunning.get()) break else continue
                    }
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    if (!text.startsWith(SyncProtocol.PROTO_MAGIC)) continue
                    val payload = text.substring(SyncProtocol.PROTO_MAGIC.length)
                    val host = packet.address?.hostAddress ?: ""
                    try {
                        onPayload(payload, host)
                    } catch (_: Throwable) {
                        // 回调异常不影响监听循环
                    }
                }
            } catch (_: Throwable) {
                // 绑定失败则退出线程
            } finally {
                try {
                    socket?.close()
                } catch (_: Throwable) {
                    // ignore
                }
                listenSocket = null
            }
        }, "suipai-beacon-listen").apply {
            isDaemon = true
            start()
        }
        listenThread = thread
    }

    actual fun stop() {
        stopAnnounce()
        stopListen()
    }

    private fun stopAnnounce() {
        announceRunning.set(false)
        try {
            announceSocket?.close()
        } catch (_: Throwable) {
            // ignore
        }
        announceSocket = null
        announceThread?.interrupt()
        announceThread = null
    }

    private fun stopListen() {
        listenRunning.set(false)
        try {
            listenSocket?.close()
        } catch (_: Throwable) {
            // ignore
        }
        listenSocket = null
        listenThread?.interrupt()
        listenThread = null
    }

    private fun sleepInterruptibly(intervalMs: Long) {
        var remaining = intervalMs.coerceAtLeast(MIN_SLEEP_MS)
        // 按 SLEEP_CHUNK_MS 分片睡，所以 nudge 最多 100ms 就能被兑现，不用叫醒整轮。
        while (announceRunning.get() && !announceNudge && remaining > 0) {
            val chunk = if (remaining > SLEEP_CHUNK_MS) SLEEP_CHUNK_MS else remaining
            try {
                Thread.sleep(chunk)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            remaining -= chunk
        }
    }

    private companion object {
        const val BROADCAST_ADDR = "255.255.255.255"
        const val LISTEN_TIMEOUT_MS = 1000
        const val UDP_BUFFER_SIZE = 8192
        const val MIN_SLEEP_MS = 50L
        const val SLEEP_CHUNK_MS = 100L
    }
}