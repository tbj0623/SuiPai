package com.suipai.app.ui.play

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 投屏意图：**系统能接就交给系统，接不住就老实说接不住**。
 *
 * 本项目不引 Media3 / Cast SDK（`docs/ROADMAP.md` §4.6「不新增依赖」），
 * 所以投屏只能走"标准 ACTION_VIEW + 常见投屏器的包名探测"这条路。
 * 探测不到就返回 null，界面据此显示「未找到投屏设备」——**不许崩，也不许假装成功**。
 *
 * ## 为什么不是 `MediaStore.createPlaylist` / `MediaRouter`
 *
 * - `MediaStore.createPlaylist` 是**建播放列表**（写库），和投屏毫无关系；
 * - `MediaRouter` + `Presentation` 需要 `media-router` 依赖，且真投屏器（Chromecast）
 *   走的是 Cast SDK 的私有协议，不是 `Presentation`。
 * 所以这里只做一件事：把"我要放这张图"这件事**说给系统听**，
 * 由已安装的投屏器自己决定接不接。
 *
 * ## 为什么判定层是 [CastRoute] + [decide] 而不是直接返回 Intent
 *
 * 本工程的 JVM 单测里 `android.jar` 全是桩（`unitTests.isReturnDefaultValues = true`）：
 * `Uri.parse` 返回 null、`Intent` 的方法抛 `Stub!`。**任何直接对着
 * `Context` / `PackageManager` / `Uri` 断言的测试要么绿着空跑、要么在
 * 「`Uri.parse` 返回 null」那一行崩掉**（第一版就是这么红的）。
 *
 * 所以判定层的输入输出全是纯 Kotlin（`String` / 布尔谓词 / sealed 路由），
 * `android.content.Intent` 只在最外面那一层被构造一次。
 */
object CastIntent {

    /** 探不到 MIME 时的通配。写死 image/jpeg 会让 HEIC / PNG 的接收端挑不出来。 */
    const val MIME_WILDCARD: String = "image/*"

    /**
     * 已知的投屏器包名。**只放"确实会接 ACTION_VIEW 的图片"的那些**：
     * Google Cast（Chromecast 的接收端 App）、以及几家常被预装的投屏 / 视频工具。
     *
     * 为什么允许写死包名：`PackageManager` 的包可见性（Android 11+）下，
     * 不写 `<queries>` 时 `queryIntentActivities` 拿不到别人。
     * 而这一串是**按包名直查**（`getPackageInfo`），属于可见性规则里
     * "本应用主动点名的包"那一档 —— 不依赖 `<queries>`。
     * 代价是：没列到的投屏器只能走宽松路径（会弹选择框），
     * 这是可接受的降级，不是缺陷。
     */
    val knownCasters: List<String> = listOf(
        "com.google.android.gms.cast",            // Google Cast 的接收端入口
        "com.google.android.apps.chromecast.app", // Google Home（含投屏）
        "com.amazon.dcp.contracts.mdcs",          // Fire TV 遥控
        "com.mediatek.wwtv.tvcenter",             // MTK TV
        "com.miui.video",                         // 小米视频（部分机型接管投屏）
        "com.tencent.qqlive",                     // 腾讯视频（DLNA 投屏）
        "com.qiyi.video",                         // 爱奇艺
        "com.youku.phone",                        // 优酷
    )

    /**
     * 造一个"把这张图投出去"的意图。**探测不到就返回 null。**
     *
     * @param uri 要投的图。必须已经用 `FileProvider` 或 `MediaStore` 授权过可读
     *            （本函数只负责把 `FLAG_GRANT_READ_URI_PERMISSION` 加上，不负责生成 uri）。
     * @param mime MIME 类型；空串按 [MIME_WILDCARD] 处理。
     */
    fun castIntent(context: Context, uri: Uri, mime: String): Intent? {
        if (!isCastableTarget(uri.toString())) return null
        val pm = context.packageManager
        val route = decide(
            isInstalled = { pkg ->
                runCatching {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }.isSuccess
            },
            resolves = { pkg ->
                // 两条判据都用 `queryIntentActivities` 而不是 `resolveActivity`：
                // API 30+ 的包可见性下 `resolveActivity` 会因"看不见对方"返回 null，
                // 而 `queryIntentActivities` 在**本应用主动点名 setPackage** 时仍能命中。
                // 两条都 runCatching：任何一条抛了都按"接不住"处理，不许把异常漏到调用点。
                runCatching {
                    val probe = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, normalizedMime(mime))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (pkg != null) setPackage(pkg)
                    }
                    pm.queryIntentActivities(probe, 0).isNotEmpty() ||
                        probe.resolveActivity(pm) != null
                }.getOrDefault(false)
            },
        )
        return intentFor(route, uri, mime)
    }

    /**
     * **纯判定**：选该走哪条路。四种出口都能在 JVM 单测里真的走到。
     *
     * 1. 已知投屏器装了**且接得住** → [CastRoute.Precise]；
     * 2. 装了但接不住 → 继续试下一个（**这是最容易写漏的一条**：
     *    `getPackageInfo` 成功 ≠ 这个包接得住 `ACTION_VIEW`，
     *    只判"装没装"会让用户看到系统那句「没有应用可执行此操作」）；
     * 3. 都没命中但系统里有应用能接 → [CastRoute.Generic]（交给系统选）；
     * 4. 一个能接的都没有 → [CastRoute.None]。
     *
     * @param resolves 传入 `null` 表示问"有没有**任何**应用能接这条通配 intent"。
     */
    internal fun decide(
        isInstalled: (String) -> Boolean,
        resolves: (String?) -> Boolean,
    ): CastRoute {
        for (pkg in knownCasters) {
            if (!isInstalled(pkg)) continue
            if (!resolves(pkg)) continue
            return CastRoute.Precise(pkg)
        }
        return if (resolves(null)) CastRoute.Generic else CastRoute.None
    }

    /** 按路由造 Intent。`None` 回 null —— 调用点据此显示「未找到投屏设备」。 */
    internal fun intentFor(route: CastRoute, uri: Uri, mime: String): Intent? = when (route) {
        is CastRoute.Precise -> Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, normalizedMime(mime))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(route.pkg)
        }
        CastRoute.Generic -> Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, normalizedMime(mime))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        CastRoute.None -> null
    }

    /** MIME 归一：空白 → 通配。纯函数，可测。 */
    internal fun normalizedMime(mime: String): String = mime.trim().ifBlank { MIME_WILDCARD }

    /**
     * 空 uri 没有任何可投的东西 —— 直接早退，**连一次 PackageManager 都不问**
     * （每次探测都是 binder 调用）。
     */
    internal fun isCastableTarget(uriString: String): Boolean = uriString.isNotBlank()

    /**
     * 界面要说的话。**成功与失败都说**，因为"点了没反应"是这个功能最容易变成的样子。
     * 纯函数：只吃 [CastRoute]，不碰 Intent。
     */
    fun noteFor(route: CastRoute): String = when (route) {
        CastRoute.None -> "未找到投屏设备（这台机器上没有能接住投屏的应用）"
        is CastRoute.Precise -> "正在投屏到 ${route.pkg}"
        CastRoute.Generic -> "正在让系统挑选投屏应用"
    }
}

/** 投屏目标：精确命中的包 / 交给系统选 / 一个都没有。 */
sealed interface CastRoute {

    /** 精确命中某个已知投屏器。 */
    data class Precise(val pkg: String) : CastRoute

    /** 让系统弹选择器（不指定包名）。 */
    data object Generic : CastRoute

    /** 没有任何应用能接住这次投屏。 */
    data object None : CastRoute
}
