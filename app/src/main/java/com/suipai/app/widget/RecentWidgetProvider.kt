package com.suipai.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.widget.RemoteViews
import com.suipai.app.MainActivity
import com.suipai.app.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面小组件：最近几张照片。
 *
 * ## 为什么是传统 `RemoteViews`，不是 Glance
 * Glance 是 `androidx.glance:glance-appwidget`，一个新依赖 —— 本仓库"不新增依赖"是硬约束
 * （每加一个依赖都要重写 Data safety 申报里的依赖表，见 `docs/handoff.md` §8.5）。
 * 而这里要画的东西只是"四张图 + 一行字"，RemoteViews 完全够用。
 * 更详细的取舍写在 `docs/product/WIDGET.md`。
 *
 * ## 线程模型（三条都不许省）
 * 1. **查询与解码一律在 `goAsync()` 之后的后台线程**。`onUpdate` 跑在主线程上，
 *    在那儿查 MediaStore 是"每次桌面上滑过一屏都可能触发一次磁盘 IO"——
 *    而 ANR 的账会记在我们头上。
 * 2. **`goAsync()` 的 `finish()` 必须在 `finally` 里**。少调一次，系统会记一次
 *    "broadcast 没结束"，连记几次之后这个接收器就再也不被唤醒了（表现为"小组件永远停在旧画面"）。
 * 3. **单例锁**：launcher 在滚动、转屏、加一个同名组件时会连发好几次 UPDATE，
 *    几个并发刷新会把同一份缩略图反复解码。已经有刷新在跑就**直接返回**，
 *    让那一次跑完 —— 它读到的库只会更新，不会更旧。
 *
 * ## 一句话说清"没读到"与"没有照片"的区别
 * 见 `WidgetState`：读不到时**保留上一次的画面**，而不是刷成「0 张」。
 * 媒体提供方抖一下（撤权限、Android 14 的 partial access、进程被杀）都会走到那条路。
 */
class RecentWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // 空 ids 是合法的（最后一个组件被移除时系统会给一次空更新），此时什么也不用做。
        if (appWidgetIds.isEmpty()) return
        submit(context.applicationContext, appWidgetIds)
    }

    /**
     * 尺寸变了（用户拖了组件边、折叠屏换外屏、分屏）。换布局只看 [WidgetSize]，
     * 数据不用重查 —— 但缩略图的**尺寸**要跟着换，所以还是整趟重刷一次。
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        submit(context.applicationContext, intArrayOf(appWidgetId))
    }

    /** 第一个组件被摆到桌面上。`onUpdate` 随后会来，这里只负责把快捷方式也补一遍。 */
    override fun onEnabled(context: Context) {
        val app = context.applicationContext
        Thread {
            runCatching { DynamicShortcuts.refresh(app) }
                .onFailure { android.util.Log.w(TAG, "小组件上线时刷快捷方式没成", it) }
        }.start()
    }

    /** 自定义刷新（`adb shell am broadcast` 或 App 内主动推）。 */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, RecentWidgetProvider::class.java))
            if (ids.isNotEmpty()) submit(context.applicationContext, ids)
            return
        }
        super.onReceive(context, intent)
    }

    /**
     * 真的开一趟刷新。
     *
     * 这里的 `AtomicBoolean` 是**进程级**的：`AppWidgetProvider` 每次广播都会新建一个实例，
     * 拿实例字段做锁等于没锁（这是这个类最容易写错的一处）。
     */
    private fun submit(context: Context, ids: IntArray) {
        if (!inFlight.compareAndSet(false, true)) {
            android.util.Log.d(TAG, "已经有一趟刷新在跑，这次跳过（ids=${ids.size}）")
            return
        }
        val pending = goAsync()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        scope.launch {
            try {
                refresh(context, ids)
            } catch (t: Throwable) {
                // 接住 Throwable：`goAsync` 里逃出去的异常会直接杀掉进程，
                // 而桌面上那一次刷新本来是可以只失败的。
                android.util.Log.w(TAG, "刷新小组件时抛了", t)
            } finally {
                inFlight.set(false)
                job.cancel()
                // finish() 必须在 finally：见类头第 2 条。
                pending.finish()
            }
        }
    }

    /** 查一次库、画一次所有组件（几个组件共用同一趟查询 —— 它们看的是同一个库）。 */
    private suspend fun refresh(context: Context, ids: IntArray) {
        val mgr = AppWidgetManager.getInstance(context)
        val result = loadWithRetry(context)
        val photos = (result as? WidgetState.Loaded)?.photos.orEmpty()
        for (id in ids) {
            draw(context, mgr, id, result, photos)
        }
    }

    /**
     * 查库 + 失败重试。重试的判据全在 [WidgetRetry]（纯的、单测覆盖），这里只负责等。
     *
     * 只对"没读到"重试：库里确实没有照片（[WidgetState.Empty]）重试一百次也还是空的。
     */
    private suspend fun loadWithRetry(context: Context): WidgetState {
        var retry = WidgetRetry()
        while (true) {
            val (photos, total) = queryRecent(context)
            val state = stateOf(photos, total)
            if (state !is WidgetState.Unavailable) return state
            if (retry.shouldGiveUp) {
                android.util.Log.w(TAG, "小组件查库失败，已试 ${retry.attempt} 次：${state.reason}")
                return state
            }
            delay(retry.backoffMillis)
            retry = retry.next(state.reason)
        }
    }

    /**
     * MediaStore 里最近的那几行 + 库里总数。
     *
     * 返回的第一项 **`null` 表示没读到**（查询抛了 / 游标是 null），
     * 与"读到空表"是两件不同的事 —— 见 [WidgetState] 的头注释。
     *
     * 排序用 `DATE_TAKEN DESC, DATE_ADDED DESC`，与 `MediaRepository.query` 逐字一致：
     * 两处排法不同会让桌面第一张和相册第一张不是同一张，而这只在装了新照片之后才看得出来。
     */
    private suspend fun queryRecent(context: Context): Pair<List<WidgetPhoto>?, Int> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val sort = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
        val rows = try {
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sort)?.use { c ->
                readRows(c)
            }
        } catch (e: SecurityException) {
            // 权限被撤 / 只给了"选中的照片"：这不是错误，是这个 App 现在看不到库。
            android.util.Log.w(TAG, "没有读照片的权限，桌面保持上一次的画面", e)
            null
        } catch (e: Exception) {
            android.util.Log.w(TAG, "查最近照片失败", e)
            null
        }
        if (rows == null) return@withContext null to 0
        val total = countAll(context) ?: rows.size
        rows to total
    }

    /**
     * 游标里读前 [WidgetShape.MAX_THUMBS] 行就停。
     *
     * 不读到五千行再 `take(4)`：媒体库大的机器上（5000 张）那一趟是几十毫秒的纯浪费，
     * 而小组件每次桌面重绘都可能被叫醒。
     */
    private fun readRows(cursor: Cursor): List<WidgetPhoto> {
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
        val addedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
        val out = ArrayList<WidgetPhoto>(WidgetShape.MAX_THUMBS)
        while (cursor.moveToNext() && out.size < WidgetShape.MAX_THUMBS) {
            val id = cursor.getLong(idCol)
            if (id <= 0L) continue
            // DATE_TAKEN 在某些设备上对截图/微信图是 0：退回 DATE_ADDED（秒 → 毫秒）。
            val taken = if (takenCol >= 0) cursor.getLong(takenCol) else 0L
            val added = if (addedCol >= 0) cursor.getLong(addedCol) else 0L
            out += WidgetPhoto(
                id = id,
                uri = contentUriOf(id).toString(),
                dateTaken = taken.takeIf { it > 0L } ?: (added * 1000L),
            )
        }
        return out
    }

    private fun contentUriOf(id: Long): Uri =
        android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    /**
     * 库里一共有多少张。
     *
     * 只取 `_ID` 一列、只数游标长度：这是 MediaProvider 上最便宜的一次全表读。
     * 失败返回 null，调用点退回"只报这几张"——绝不把"数不出来"写成"0 张"。
     */
    private fun countAll(context: Context): Int? = runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            null,
        )?.use { it.count }
    }.onFailure { android.util.Log.w(TAG, "数不出照片总数，退回只报抓到的张数", it) }.getOrNull()

    /** 把一份状态画到**一个**组件上。 */
    private fun draw(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int,
        state: WidgetState,
        photos: List<WidgetPhoto>,
    ) {
        val size = sizeOf(mgr, appWidgetId)
        val layout = when (size) {
            WidgetSize.Small2x2 -> R.layout.widget_recent_2x2
            WidgetSize.Wide4x2 -> R.layout.widget_recent_4x2
        }
        val views = RemoteViews(context.packageName, layout)
        val slots = WidgetShape.visibleSlots(size, photos.size)
        val edge = WidgetLogic.thumbEdgePx(size, context.resources.displayMetrics.density)

        // 空槽一律 GONE：留一个空框比少一格更像"坏了"。
        for (i in 0 until WidgetShape.MAX_THUMBS) {
            val viewId = slotId(size, i) ?: continue
            if (i < slots) {
                val bitmap = loadThumb(context, photos[i].uri, edge)
                if (bitmap != null) {
                    views.setImageViewBitmap(viewId, bitmap)
                } else {
                    // 缩略图取不到（文件被删 / 云盘没挂上）不该拖垮整块：画占位，其余几张照常。
                    views.setImageViewResource(viewId, R.drawable.widget_thumb_placeholder)
                }
                views.setViewVisibility(viewId, android.view.View.VISIBLE)
                // 名字跟着**第几张**走，不是一句写死的文案：读屏用户听到的第 1 张和第 4 张必须能区分。
                views.setContentDescription(viewId, context.getString(R.string.widget_thumb_desc, i + 1))
                views.setOnClickPendingIntent(viewId, openApp(context, VIEWER_REQUEST + i))
            } else {
                views.setViewVisibility(viewId, android.view.View.GONE)
            }
        }

        views.setTextViewText(R.id.widget_caption, state.caption)
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context, ROOT_REQUEST))
        // 整块的名字：读屏在桌面上聚焦到组件时念的是这一句，而不是「随拍」两个字。
        views.setContentDescription(R.id.widget_root, context.getString(R.string.widget_root_desc, state.caption))
        mgr.updateAppWidget(appWidgetId, views)
    }

    /** 组件的实际宽度（dp）→ 用哪套布局。拿不到就当 2×2（见 [WidgetSize.forOptions] 的理由）。 */
    private fun sizeOf(mgr: AppWidgetManager, appWidgetId: Int): WidgetSize {
        val options = runCatching { mgr.getAppWidgetOptions(appWidgetId) }.getOrNull()
            ?: return WidgetSize.Small2x2
        return WidgetSize.forOptions(
            minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
            maxWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH),
        )
    }

    /** 2×2 只有一格（大图），4×2 有四格。返回 null = 这套布局没有这一格。 */
    private fun slotId(size: WidgetSize, index: Int): Int? = when (size) {
        WidgetSize.Small2x2 -> if (index == 0) R.id.widget_thumb_0 else null
        WidgetSize.Wide4x2 -> when (index) {
            0 -> R.id.widget_thumb_0
            1 -> R.id.widget_thumb_1
            2 -> R.id.widget_thumb_2
            3 -> R.id.widget_thumb_3
            else -> null
        }
    }

    /**
     * 取一张缩略图。**两条路**，因为 `loadThumbnail` 是 API 29 才有的：
     *  - 29+：`ContentResolver.loadThumbnail`，系统那边的解码器会做采样，最省内存；
     *  - 26..28：`MediaStore.Images.Thumbnails.getThumbnail`（已废弃但对老系统是唯一的路）。
     *
     * 任何异常都收成 null：一张缩略图坏掉不该让整块组件停在旧画面上。
     * 回收由 [RemoteViews] 的位图缓存负责 —— 每次 `updateAppWidget` 之后系统会带着它们一起收，
     * 所以**不要**在这里 recycle（那是 `Canvas: trying to use a recycled bitmap` 的经典来源）。
     */
    private fun loadThumb(context: Context, uri: String, edge: Int): Bitmap? = runCatching {
        val u = Uri.parse(uri)
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(u, Size(edge, edge), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver,
                android.content.ContentUris.parseId(u),
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            )
        }
    }.onFailure {
        android.util.Log.w(TAG, "这张缩略图没取到：$uri", it)
    }.getOrNull()

    /**
     * 点组件 / 点缩略图都落到 `MainActivity` 的**普通启动**上。
     *
     * 为什么不发 `suipai://viewer?index=N`：`MainActivity.collectHop` 只认
     * `camera|sync|yishun` 三个 host，别的 host 会被**静默**丢掉（app 会打开，但停在相册页）。
     * 而本轮不允许改 `MainActivity`。所以按 `SupportedDeepLinks` 的判据只发它认得的。
     *
     * 这一条是本轮**明确的缺口**，见 `docs/product/WIDGET.md` 的「已知限制」与交付报告。
     */
    private fun openApp(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            // `singleTask` + 已经在栈里时，这一句让系统走 onNewIntent 而不是再开一个实例。
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        // 31+ 强制要求写明可变性；这里 intent 完全由我们自己填，不可能被第三方改，所以 IMMUTABLE。
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }

    companion object {
        private const val TAG = "SuiPaiWidget"

        /** 外部（App 内 / adb）主动要求刷新的动作。 */
        const val ACTION_REFRESH = "com.suipai.app.action.REFRESH_WIDGET"

        private const val ROOT_REQUEST = 9001

        /**
         * 每格一个 requestCode 起点：全用同一个码时 `FLAG_UPDATE_CURRENT` 会把它们并成
         * 同一个 PendingIntent，将来真要给"第 N 张"带额外参数就没法带了。
         * 现在四条 intent 内容相同，分开只是为了不把路堵死。
         */
        private const val VIEWER_REQUEST = 9100

        /**
         * 进程级的"正在刷新"闸。**必须是 companion / static**：
         * `AppWidgetProvider` 每次广播都新建实例，实例字段一点用都没有。
         */
        private val inFlight = AtomicBoolean(false)

        /** 请系统刷新所有已放置的组件（App 内改了照片之后可以叫一声）。 */
        fun requestRefresh(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, RecentWidgetProvider::class.java).setAction(ACTION_REFRESH)
            runCatching { app.sendBroadcast(intent) }
                .onFailure { android.util.Log.w(TAG, "刷新小组件的广播没发出去", it) }
        }
    }
}
