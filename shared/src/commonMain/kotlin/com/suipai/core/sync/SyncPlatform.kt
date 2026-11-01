package com.suipai.core.sync

/**
 * TCP 服务端。平台 actual 负责后台 accept 循环，[start] 非阻塞返回。
 */
expect class SyncServer() {
    /** 在 [port] 上监听；每条连接交给 [handler]，在平台后台线程上执行。 */
    fun start(port: Int, handler: SyncServerHandler)

    /** 停止监听并关闭监听套接字；不等待在途连接结束。 */
    fun stop()
}

/**
 * TCP 客户端。[connect] 阻塞直到连接建立或超时。
 */
expect class SyncClient() {
    /** 连接 [host]:[port]；[timeoutMs] 为连接超时。 */
    fun connect(host: String, port: Int, timeoutMs: Int): SyncConnection
}

/**
 * UDP 发现信标。
 *
 * [startAnnounce] 会立即广播一轮，随后每 [intervalMs] 广播一轮；
 * 已经在一轮一轮地广播时重复调用是空操作（不会拆掉重建）。
 */
expect class DiscoveryBeacon() {
    /** 周期向 255.255.255.255:[SyncProtocol.UDP_PORT] 广播 [payload]。 */
    fun startAnnounce(payload: String, intervalMs: Long = 2000)

    /**
     * 打断当前这轮的等待，立刻再广播一次。
     * 给「回到前台，想马上被附近设备发现」用——比重建 socket 便宜，而且不会留出广播空窗。
     */
    fun nudgeAnnounce()

    /** 监听 [SyncProtocol.UDP_PORT]；仅回调带 [SyncProtocol.PROTO_MAGIC] 前缀的载荷（前缀已去掉）。 */
    fun startListen(onPayload: (payload: String, host: String) -> Unit)

    /** 停止 announce 与 listen。 */
    fun stop()
}