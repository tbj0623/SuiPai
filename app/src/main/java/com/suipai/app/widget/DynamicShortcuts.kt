package com.suipai.app.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.suipai.app.MainActivity
import com.suipai.app.R
import android.provider.MediaStore

/**
 * 动态快捷方式：「最近一张」与「回收站」。
 *
 * 静态那三条（拍照 / 互通 / 一瞬）在 `res/xml/shortcuts.xml` 里，装机时就在。
 * 动态这两条是运行时发的 —— 因为它们要么依赖库里的状态（有没有照片），
 * 要么是**后来才做的页面**（回收站），写进静态 xml 会让老版本的图标下也出现一个点不动的入口。
 *
 * ## 数量预算
 * 部分 launcher 总容量小于常见的 5 个；先查 `getMaxShortcutCountPerActivity`，
 * 扣掉 3 条静态入口，再由 [budgetedDynamicShortcuts] 裁掉超出当前设备容量的动态入口。
 * 总预算还有 [ShortcutBudget.MAX_DYNAMIC] 这一层工程上限（最多 2 条）。
 *
 * ## 不支持的设备必须静默跳过
 * API 25 才有平台动态快捷方式；按 `Build.VERSION.SDK_INT >= 25` 加容量探测判断。
 * [ShortcutManagerCompat.isRequestPinShortcutSupported] 不能作为门槛：有些启动器支持长按
 * 动态入口但不支持把它固定到桌面。读取容量、发布和更新均在 `runCatching` 内，
 * 个别定制启动器抛异常时只写日志，不影响 App 启动。
 *
 * ## 为什么不新增依赖
 * `ShortcutManagerCompat` / `ShortcutInfoCompat` / `IconCompat` 全在
 * `androidx.core:core-ktx`（已在依赖里）。Glance 与 `androidx.core:core-splashscreen`
 * 那类都是新依赖，本轮禁止。
 */
object DynamicShortcuts {

    private const val TAG = "SuiPaiShortcuts"

    /**
     * 重新发布动态快捷方式。**幂等**：每次调用都是"把这几条设成当前集合"，
     * 多调几次没有副作用，所以调用点（App 启动、小组件上线、相册刷新完）不用互相避让。
     *
     * 判据不满足时**静默返回**，只在 logcat 里留一句 —— 这些调用点都在启动路径上，
     * 为了"老设备没有动态快捷方式"给用户弹个窗是纯噪声。
     */
    fun refresh(context: Context) {
        val app = context.applicationContext
        // 固定到桌面的支持与长按菜单里的动态入口是两回事，按 launcher 实际容量分配。
        val launcherMax = runCatching {
            ShortcutManagerCompat.getMaxShortcutCountPerActivity(app)
        }.onFailure { android.util.Log.w(TAG, "问不出启动器的快捷方式名额，按不支持处理", it) }
            .getOrDefault(0)
        if (!ShortcutSupport.shouldPublish(Build.VERSION.SDK_INT, launcherMax)) {
            android.util.Log.d(TAG, "这台设备没有动态入口名额（API ${Build.VERSION.SDK_INT}，max=$launcherMax），跳过")
            return
        }
        runCatching {
            val hasPhotos = hasAnyPhoto(app)
            val availableSlots = launcherMax - ShortcutBudget.STATIC_COUNT
            val specs = budgetedDynamicShortcuts(dynamicShortcutSpecs(hasPhotos), availableSlots)
            // 预算里那两条（最近一张 / 回收站）是"库状态"驱动的；
            // 「拍照」那条归属不同 —— 它在静态 xml 里，由**设置中心的开关**收放，
            // 见 `applyCameraShortcutSetting`。
            if (specs.isEmpty()) {
                // 理论上到不了（回收站那条永远发）。真到了就是预算被算成 0 —— 说一句，别静默。
                android.util.Log.w(TAG, "预算算出来是空的，这次不发动态快捷方式")
                return
            }
            applyCameraShortcutSetting(app)
            val infos = specs.map { it.toShortcutInfo(app) }
            // 用 setDynamicShortcuts 而不是逐条 pushDynamicShortcut：
            // push 是"追加并挤掉最旧的"，而我们要的是**当前集合就是这几条**——
            // 上次发过、这次不该有的（比如照片删光之后的「最近一张」）必须真的消失。
            val ok = ShortcutManagerCompat.setDynamicShortcuts(app, infos)
            android.util.Log.d(TAG, "动态快捷方式 ${infos.size} 条，结果=$ok")
        }.onFailure {
            // 宿主（某些定制 launcher）会在这里抛 —— 快捷方式少两条不是崩溃的理由。
            android.util.Log.w(TAG, "发布动态快捷方式失败，跳过", it)
        }
    }

    /**
     * 设置中心「桌面拍照快捷方式」那一项的**读者**。
     *
     * 这一项在 `SettingsModel` 里标着 `SettingLive.PENDING`，注释写着"缺的是：
     * `DynamicShortcuts.refresh` 读它并决定'拍照'那一条在不在"。这里就是那个读者 ——
     * 所以这条设置可以摘掉 PENDING 了。
     *
     * ## 为什么要动静态那一条，而不是"再发一条动态的"
     * 拍照那条在 `res/xml/shortcuts.xml` 里，装机时就在，动态发布改不了它。
     * 系统给的唯一开关是每条的 `enabled`（`ShortcutManagerCompat.enableShortcuts` /
     * `disableShortcuts`，API 25+ —— 与动态快捷方式同一道门槛，`refresh` 前面已经过完）。
     * 停用之后长按菜单里不再出现它，但它还留在清单里：同一个键再打开时
     * `enableShortcuts` 把它放回来，用户不用重新拖到桌面。
     *
     * ## 为什么不用动态那两条去顶掉它
     * 静态与动态是两套清单，动态那条再多也不会让静态这条消失，而 5 个上限是**总数** ——
     * 于是只会白吃一个名额，长按菜单里还出现两个「拍照」。
     *
     * ## 读不出来 / 从没写过时按"开"
     * 静态那条出厂就是 `enabled="true"`。读盘失败时把它藏掉，用户会以为自己的桌面入口
     * 莫名其妙没了；反过来（多留一条入口）最坏只是没按偏好隐藏。
     *
     * **"从没写过"与"写成了 false"必须分开**：`SettingsKeys.General.cameraShortcut`
     * 的默认值是 `false`，而静态快捷方式出厂是开着的 —— 两者对不上。如果直接用
     * `read()`（它把"没写过"读成默认值 false），升级到这一版的所有老用户会在下一次启动时
     * 丢掉桌面「拍照」入口，而他们根本没碰过这个开关。所以判据是
     * 「磁盘上真的存过这个键吗」：存过才按它的值收放，没存过保持出厂状态。
     * （那一行的界面显示因此仍会说「关」—— 那是 `SettingsKeys` 的默认值问题，
     * 不归这条 lane 改；这里至少保证**不造成回归**。）
     *
     * 整段 `runCatching`：某些定制 launcher 上这两个调用会抛（清单被宿主改写），
     * 而"桌面少一个入口"不是崩溃的理由。
     */
    private fun applyCameraShortcutSetting(context: Context) {
        val key = com.suipai.app.settings.SettingsKeys.General.cameraShortcut
        val enabled = runCatching {
            val snapshot = com.suipai.app.settings.SettingsStore.of(context).load()
            // 没写过 = 用户没有表达过偏好 = 保持出厂状态，不动它。
            if (!snapshot.has(key)) true else snapshot.bool(key)
        }.onFailure {
            android.util.Log.w(TAG, "读不出「桌面拍照快捷方式」，按开处理", it)
        }.getOrDefault(true)
        runCatching {
            // 直接用平台类而不是 `ShortcutManagerCompat`：本工程的 minSdk 是 26，
            // 而 `ShortcutManager` 是 25 就有的 —— 兼容层在这里不省任何东西，
            // 反而多一层"它怎么把 id 翻成 ShortcutInfo"的黑箱
            // （compat 的 `enableShortcuts` 收的是 `ShortcutInfoCompat` 列表，不是 id）。
            val manager = context.getSystemService(android.content.pm.ShortcutManager::class.java)
                ?: return
            if (enabled) {
                manager.enableShortcuts(listOf(ShortcutIds.CAMERA))
            } else {
                manager.disableShortcuts(
                    listOf(ShortcutIds.CAMERA),
                    // 停用理由最多 55 字，长按菜单里会念出来。
                    "设置里关掉了桌面拍照快捷方式",
                )
            }
        }.onFailure {
            android.util.Log.w(TAG, "按设置收放静态「拍照」快捷方式失败（enabled=$enabled）", it)
        }
    }

    /**
     * 库里有没有照片。只取 `_ID` 一列、`limit 1`，问"有没有"而不是"有几张"。
     *
     * 失败时**当有**：反过来会让「最近一张」在媒体提供方抖一下的时候消失，
     * 而那条快捷方式本身不依赖真的取到那张图（点进去是普通启动）。
     */
    private fun hasAnyPhoto(context: Context): Boolean = runCatching {
        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            null,
        )
        cursor?.use { it.count > 0 } ?: true
    }.onFailure {
        android.util.Log.w(TAG, "问不出库里有没有照片，按有处理", it)
    }.getOrDefault(true)

    /**
     * 一条 [ShortcutSpec] → 系统要的 `ShortcutInfoCompat`。
     *
     * recent / trash 都带受校验的深链；MainActivity 现在会打开真实页面。
     * 未知深链仍不会被发出，避免桌面快捷方式点了却停在首页。
     *
     * 图标用 `sc_yishun` / `sc_sync` 那套线稿里的两张（琥珀描边、与静态三条同一族），
     * `IconCompat.createWithResource` 不读文件、不占内存。
     */
    private fun ShortcutSpec.toShortcutInfo(context: Context): ShortcutInfoCompat {
        val iconRes = when (id) {
            ShortcutIds.RECENT -> R.drawable.sc_yishun
            else -> R.drawable.sc_sync
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addCategory(Intent.CATEGORY_LAUNCHER)
            // 只在 MainActivity 认得的 host 上带 data；`null` 表示普通启动。
            if (deepLink != null && SupportedDeepLinks.isSupported(deepLink)) {
                data = android.net.Uri.parse(deepLink)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(IconCompat.createWithResource(context, iconRes))
            .setIntent(intent)
            .setRank(rank)
            // 这两条都不是"会话型"的：用户点完就走了，没有"继续上次对话"的语义。
            .setLongLived(true)
            .build()
    }
}
