package com.suipai.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.Immutable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 回收站里的一条记录。
 *
 * `id` 是**原** MediaStore 行号（移动之后那一行已经没了），只用来去重与显示；
 * 真身是 [trashUri] —— 回收站目录里新插的那一行。
 *
 * 标 `@Immutable` 的理由和 [PhotoItem] 一样：这个模型会直接进 `LazyVerticalGrid`，
 * 不标的话每变一次（恢复一张、清一次）整屏缩略图全部重组。
 */
@Immutable
data class TrashItem(
    /** 原 MediaStore id（仅用于去重/显示）。 */
    val id: Long,
    val displayName: String,
    /** 移动之后在「回收站」目录里的 `content://` uri。 */
    val trashUri: String,
    /** 进回收站的时刻（毫秒）。 */
    val deletedAt: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    /**
     * **原始拍摄时间**（毫秒，`DATE_TAKEN`），恢复时原样写回去。
     *
     * `0` = 不知道（老版本账单里没有这个字段，或者那一行的 `DATE_TAKEN` 本来就是空）。
     * 它**必须**有默认值，否则老 `trash.json` 与所有既有构造点会一起红。
     *
     * 为什么不拿 [deletedAt] 兜底：那会把"三年前拍的照片"排进删除那一天的时间线里，
     * 用户看到的是照片顺序被悄悄改掉。宁可不知道（`0` → 不写 `DATE_TAKEN`，
     * 行为与改前完全一致），也不许编一个假时间。
     */
    val dateTaken: Long = 0L,
)

/**
 * 回收站：把「删除」从**立刻销毁**改成**先搬进一个 30 天的候车室**。
 *
 * ## 为什么是"搬家"而不是打标记
 * 系统相册（照片/Google 相册）看的是 MediaStore 的行。只在自家 json 里记一句"这张删了"，
 * 系统相册里那张还在、别的 app 还能扫到 —— 用户看到的是"删了没删掉"。
 * 所以删除 = **把行搬到 `Pictures/SuiPai/回收站`**：行没了，系统相册就看不到它；
 * 但字节还在，30 天内还能搬回来。
 *
 * ## 为什么元数据单独落一个 json
 * 移动之后「原来叫什么名字、多大、多宽多高、什么时候删的」都没有地方存了
 * （新行是我们自己 insert 的，`DISPLAY_NAME` / `DATE_ADDED` 不再是用户那份）。
 * `filesDir/trash.json` 就是那份账单。它同时是**唯一的真值**：读不到就说"回收站是空的"，
 * 绝不因为 json 坏了就把回收站目录里的东西当成可以清掉 —— 那才是真的丢照片。
 *
 * ## "外来照片不能进回收站"
 * 只有**本 app 拥有的行**才搬得动。`MediaStore` 里别人（相机、微信、截图）插的行，
 * 我们 `delete()` 会抛 `RecoverableSecurityException` / 静默失败；Android 11+ 又要求
 * 每次删除都走一次用户确认对话框。所以这里的入口只对 `Pictures/SuiPai` 下、
 * 由本 app 写入的照片开放（见 docs/product/TRASH-COLLECTIONS.md）。
 *
 * @param context 只用 `filesDir` 与 `contentResolver`；JVM 单测不构造这个类，
 *   只测 [Trash.decode] / [daysLeftOf] / [expiredItems] 这些不碰 Context 的纯函数。
 *
 * **请一律通过 [of] 拿实例，不要 `Trash(context)`。** 原因见 [lock]。
 *
 * ## 为什么不把 `context` 存成字段
 * 本类被 [Companion.instance] 持有在全进程的静态字段里，字段里放 `Context` 就是一条
 * lint 会报（`StaticFieldLeak`）的泄漏链 —— 今天传的是 `applicationContext` 所以不泄漏，
 * 但那条规则挡的是"以后有人改成 Activity context"的那一天。
 * 这里只需要 `filesDir` 与 `contentResolver` 两样东西，收窄成两个字段之后误用不可能发生
 * （与 `Collections` 同一条做法）；构造函数里再取一次 `applicationContext`，双保险。
 */
class Trash(context: Context) {

    /** 私有目录。收窄成 [File]：这里用不到 Context 的其它任何东西。 */
    private val dir: File = context.applicationContext.filesDir

    /** 读写入参。同样只留这一样 —— 它不持有 Activity。 */
    private val resolver: android.content.ContentResolver = context.applicationContext.contentResolver

    /**
     * 账单文件。**不上锁**读、上锁写：读发生在组合/IO 上，写只有
     * [moveToTrash] / [restore] / [purge] 这一路（同一时刻只会有一个在跑，
     * 都是用户点出来的动作，而且每一步都在 IO 线程上）。
     *
     * 需要一把锁的是 `read-modify-write` 那份快照：两个动作挨着点（恢复一张、
     * 再清空）时，后一个不能拿着前一个的旧表去覆盖 —— 那会把刚恢复的那条
     * 又写回账单里，重启之后它就成了"删不掉也恢复不了"的幽灵。
     *
     * ## 这把锁是**实例字段**，所以实例必须是进程级单例
     * 改前工程里有三个 `Trash(context)`（启动清理 / 回收站页面 / 删除搬家），
     * 各持一把自己的锁 —— 于是"启动时的 `purgeExpired`"与"用户点删除"并发时
     * 是两份旧快照互相覆盖，最后写进去的那份会吞掉另一份的条目。
     * `docs/arch/APP-ARCH.md` 早写了"必须是同一个实例"，但代码没做到。
     * 现在改由 [of] 发同一个实例；**不要**再在调用点 `Trash(context)`。
     */
    private val file: File get() = File(dir, FILE_NAME)
    private val lock = Any()

    // ── 读 ────────────────────────────────────────────────────────────

    /** 整份账单，按 `deletedAt` 从新到旧。坏文件 / 缺字段 / 不存在都返回空表。 */
    fun list(): List<TrashItem> = readAll().sortedByDescending { it.deletedAt }

    /** (张数, 总字节)。总字节按 `Long` 加，别用 Int —— 30 天的原图能到几个 GB。 */
    fun stats(): Pair<Int, Long> {
        val items = list()
        return items.size to items.sumOf { it.sizeBytes }
    }

    /** 这一条离自动清理还有几天，下限 0（已经过期的就是 0，不是负数）。 */
    fun daysLeft(item: TrashItem, now: Long, retentionDays: Int = DEFAULT_RETENTION_DAYS): Int =
        daysLeftOf(item.deletedAt, now, retentionDays)

    // ── 写 ────────────────────────────────────────────────────────────

    /**
     * 把一张照片搬进回收站。
     *
     * 步骤（任何一步失败都要回到"什么都没发生"）：
     *  1. 打开源 uri 的 InputStream —— 打不开就 false，此时还没写任何一个字节；
     *  2. 在回收站目录 insert 一行 `IS_PENDING=1`；
     *  3. 写字节；
     *  4. `IS_PENDING=0` 让它可见；
     *  5. 删源行；
     *  6. 记账。
     *
     * 2~5 之间失败：把已 insert 的那一行删掉（[deleteRow]），然后 false。
     * 第 5 步失败：把新行删掉、源行留着 —— **宁可没搬成，也不能两份都在**
     * （两份都在意味着用户删了一次、库里却还有一张，下次再删就是删副本）。
     *
     * @param dateTaken 原照片的拍摄时间（`DATE_TAKEN` 毫秒）。`0` = 不知道，
     *   此时新行**不写** `DATE_TAKEN`，MediaProvider 会给它补"现在" —— 与改前一致。
     *   传进来的时候顺手写进回收站那一行：系统相册里的「回收站」相册于是也按原始
     *   拍摄时间排（而不是按删除时间），而且以后能从行里反查回原时间。
     */
    suspend fun moveToTrash(
        photoId: Long,
        uri: Uri,
        displayName: String,
        sizeBytes: Long,
        width: Int,
        height: Int,
        dateTaken: Long = 0L,
    ): Boolean = withContext(Dispatchers.IO) {
        val source = try {
            resolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.w(TAG, "打不开源图，没搬：$uri", e)
            null
        } ?: return@withContext false

        source.use { input ->
            val target = insertImage(TRASH_RELATIVE_PATH, displayName, sizeBytes, width, height, dateTaken)
                ?: return@withContext false
            try {
                resolver.openOutputStream(target)?.use { out ->
                    input.copyTo(out, DEFAULT_BUFFER_SIZE)
                } ?: run {
                    Log.w(TAG, "回收站那一行开不出输出流：$target")
                    deleteRow(target)
                    return@withContext false
                }
                if (!publish(target)) {
                    deleteRow(target)
                    return@withContext false
                }
                if (!deleteRow(uri)) {
                    // 源行删不掉：把新行收回去，宁可"没删成"也不留两份。
                    Log.w(TAG, "源行删不掉，已收回新行：$uri")
                    deleteRow(target)
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.w(TAG, "搬家途中抛了，回滚新行：$target", e)
                deleteRow(target)
                return@withContext false
            }
            record(
                TrashItem(
                    id = photoId,
                    displayName = displayName,
                    trashUri = target.toString(),
                    deletedAt = System.currentTimeMillis(),
                    sizeBytes = sizeBytes,
                    width = width,
                    height = height,
                    // 负值一律当"不知道"：`DATE_TAKEN` 的合法值是正毫秒，负数只可能是脏数据，
                    // 记进账单以后会被当成 1970 年之前的照片原样写回去。
                    dateTaken = dateTakenToWrite(dateTaken),
                ),
            )
        }
        true
    }

    /**
     * 把一条搬回 `Pictures/SuiPai`，返回新行的 uri；失败返回 null（账单不动）。
     *
     * 与 [moveToTrash] 完全反向，但失败判据不同：这里**源行删不掉不算失败**吗？
     * 算。恢复之后回收站里还留一份，用户下次看到的是一张重复的照片，
     * 而且它已经"恢复过"了却还在倒计时 —— 那比恢复失败更难解释。
     *
     * **原始拍摄时间要原样带回去**（[TrashItem.dateTaken]）：不带的话恢复出来的那一行
     * 会被 MediaProvider 补成"现在"，照片就从三年前的时间线跳到了今天 —— 用户删了又恢复
     * 一次，库的顺序就被改掉了。`0`（不知道）时不写，行为与改前一致。
     */
    suspend fun restore(item: TrashItem): Uri? = withContext(Dispatchers.IO) {
        val source = item.trashUri.toUriOrNull() ?: return@withContext null
        val input = try {
            resolver.openInputStream(source)
        } catch (e: Exception) {
            Log.w(TAG, "打不开回收站里的那一行：$source", e)
            null
        } ?: return@withContext null

        input.use {
            val target = insertImage(
                HOME_RELATIVE_PATH,
                item.displayName,
                item.sizeBytes,
                item.width,
                item.height,
                // 负数只可能是历史脏数据，`dateTakenToWrite` 之后它就是"不知道"。
                dateTakenToWrite(item.dateTaken),
            ) ?: return@withContext null
            try {
                resolver.openOutputStream(target)?.use { out ->
                    input.copyTo(out, DEFAULT_BUFFER_SIZE)
                } ?: run {
                    deleteRow(target)
                    return@withContext null
                }
                if (!publish(target)) {
                    deleteRow(target)
                    return@withContext null
                }
                if (!deleteRow(source)) {
                    deleteRow(target)
                    return@withContext null
                }
            } catch (e: Exception) {
                Log.w(TAG, "恢复途中抛了，回滚新行：$target", e)
                deleteRow(target)
                return@withContext null
            }
            forget(item)
            target
        }
    }

    /** 真删：删回收站里那一行 + 从账单里划掉。行已经不在（用户从系统相册清过）也算成功。 */
    suspend fun purge(item: TrashItem): Boolean = withContext(Dispatchers.IO) {
        val gone = deleteRow(item.trashUri.toUriOrNull())
        // 行本来就不在了也要把账单划掉，否则这条永远占着「张数」。
        forget(item)
        gone || !rowExists(item.trashUri)
    }

    /**
     * 清掉所有过期条目，返回清掉的张数。
     *
     * `now` 由调用方注入：这条规则是"30 天前删的自动清"，而它的全部风险
     * 都在边界上（第 29 天还在、第 31 天没了）。注入时钟才能在 JVM 上钉死它。
     */
    suspend fun purgeExpired(now: Long, retentionDays: Int = DEFAULT_RETENTION_DAYS): Int =
        withContext(Dispatchers.IO) {
            val doomed = expiredItems(readAll(), now, retentionDays)
            var purged = 0
            for (item in doomed) {
                if (purge(item)) purged++
            }
            purged
        }

    // ── 账单 ──────────────────────────────────────────────────────────

    /** 读整份账单。任何异常都退化成空表 —— 回收站读不出来是"空"，不是"崩"。 */
    internal fun readAll(): List<TrashItem> {
        val text = try {
            if (!file.isFile) return emptyList()
            file.readText()
        } catch (e: Exception) {
            Log.w(TAG, "读 $FILE_NAME 失败，当成空回收站", e)
            return emptyList()
        }
        return decode(text)
    }

    /** 整表重写。**不是**逐条 append：坏文件里那些解不出来的条目要在这一写里被丢掉。 */
    private fun writeAll(items: List<TrashItem>): Boolean = synchronized(lock) {
        try {
            file.writeText(encode(items))
            true
        } catch (e: Exception) {
            Log.w(TAG, "写 $FILE_NAME 失败", e)
            false
        }
    }

    private fun record(item: TrashItem) {
        val next = readAll().filterNot { it.trashUri == item.trashUri } + item
        if (!writeAll(next)) Log.w(TAG, "搬进回收站了，但账单没写进去：${item.displayName}")
    }

    private fun forget(item: TrashItem) {
        val next = readAll().filterNot { it.trashUri == item.trashUri }
        if (!writeAll(next)) Log.w(TAG, "账单没划掉：${item.displayName}")
    }

    // ── MediaStore ────────────────────────────────────────────────────

    /**
     * 在 [relativePath] 下插一行；`IS_PENDING=1` 期间别的 app 看不到它。
     *
     * [dateTaken] 是**可选**的：`0` 表示不知道，此时一个字段都不写（`DATE_TAKEN` 缺省时
     * MediaProvider 会自己补，改前一直如此）。写进去的用处有两个：
     *  - 系统相册里那个「回收站」相册按原始拍摄时间排，而不是按"删的时候"；
     *  - 以后能从回收站那一行反查回原时间（比如账单坏了要重建）。
     * 门槛是 `> 0`：`DATE_TAKEN` 的 0 是"1970 年以前"，不是有效拍摄时间。
     */
    private fun insertImage(
        relativePath: String,
        displayName: String,
        sizeBytes: Long,
        width: Int,
        height: Int,
        dateTaken: Long = 0L,
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeOf(displayName))
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            if (dateTaken > 0) put(MediaStore.Images.Media.DATE_TAKEN, dateTaken)
            if (width > 0) put(MediaStore.Images.Media.WIDTH, width)
            if (height > 0) put(MediaStore.Images.Media.HEIGHT, height)
            if (sizeBytes > 0) put(MediaStore.Images.Media.SIZE, sizeBytes)
        }
        return try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            Log.w(TAG, "回收站目录插不进新行（$relativePath）", e)
            null
        }
    }

    /** `IS_PENDING=0`。Android 10 以下没有 pending 语义，直接算成功。 */
    private fun publish(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        return try {
            resolver.update(uri, values, null, null) >= 0
        } catch (e: Exception) {
            Log.w(TAG, "IS_PENDING=0 失败：$uri", e)
            false
        }
    }

    /**
     * 删一行，返回**真的删掉了**没有。
     *
     * `>= 0` 在这里是错的判据：Android 11+ 删别人拥有的行不抛异常，只是静默返回 0。
     * 拿 0 当成功，就会出现"用户点了删除、界面说删了、系统相册里那张还在" ——
     * 而且账单里已经记了一条指向还在原地的行。所以要 `> 0`。
     */
    private fun deleteRow(uri: Uri?): Boolean {
        if (uri == null) return false
        return try {
            resolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            Log.w(TAG, "删行失败：$uri", e)
            false
        }
    }

    private fun rowExists(trashUri: String): Boolean {
        val uri = trashUri.toUriOrNull() ?: return false
        return try {
            resolver.query(
                uri,
                arrayOf(MediaStore.Images.Media._ID),
                null,
                null,
                null,
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        /** 账单文件名。放在私有 `filesDir` 下 —— 它记录"用户删掉了什么"，不进云备份。 */
        const val FILE_NAME = "trash.json"

        /** 回收站目录（相对 `Pictures/`）。 */
        const val TRASH_RELATIVE_PATH = "Pictures/SuiPai/回收站"

        /** 恢复的目标目录。 */
        const val HOME_RELATIVE_PATH = "Pictures/SuiPai"

        /** 保留天数。到期由 [purgeExpired] 清，界面上显示为「还剩 N 天」。 */
        const val DEFAULT_RETENTION_DAYS = 30

        private const val TAG = "Trash"

        @Volatile
        private var instance: Trash? = null

        /**
         * 进程级单例。
         *
         * [Trash.lock] 是**实例字段**，而 `trash.json` 只有一份 —— 两个实例就是两把锁
         * 保护同一个文件，`read-modify-write` 一定丢更新。改前工程里有三个构造点
         * （`MainActivity` 启动清理、回收站页面、`AppState` 删除搬家），所以
         * 「启动时的 `purgeExpired`」与「用户点删除」并发时，后写的那一份会吞掉
         * 另一份刚加的/刚删的条目。
         *
         * 写法照抄 [IdLists.of]：`@Volatile` + 双检，`applicationContext` 防持有 Activity。
         * `Trash` 构造只记两个引用（`filesDir` 是惰性求值的 getter），没有副作用，
         * 所以这里不需要像 `IdLists.of` 那样顺手踢一次迁移。
         */
        fun of(context: Context): Trash = instance ?: synchronized(this) {
            val app = context.applicationContext
            instance ?: Trash(app).also { instance = it }
        }

        /**
         * 真正要写给 `MediaStore.DATE_TAKEN` 的值：`0` = 不写。
         *
         * 它同时被三处用：搬家时记进账单、从账单解出来、恢复时写回媒体库。
         * 抽成纯函数是为了能在 JVM 上钉住"0 与负数都不许写回去"这条判据 ——
         * `insertImage` 要 `ContentResolver`，在单测里是返回默认值的桩，测不到真行为。
         *
         * 为什么负数也要挡：`DATE_TAKEN` 的合法值只有正毫秒；负数一旦写回媒体库，
         * 那张照片的时间会落到 1970 年之前，时间线把它排到最后一行。
         */
        internal fun dateTakenToWrite(raw: Long): Long = if (raw > 0L) raw else 0L

        /**
         * 把账单 json 解成条目表。
         *
         * **每一层都容错**：不是数组、条目不是对象、字段缺失/类型不对，都跳过那一条
         * 而不是抛。这个文件是 app 自己写的，但它可能写了一半就被杀 —— 那时
         * `readText()` 回来的是截断的 json。
         *
         * 解析器是 [TinyJson] 而不是 Android 的 `org.json`：后者在 JVM 单测里是一组
         * **返回默认值的桩**（`unitTests.isReturnDefaultValues = true`），`JSONArray(文本)`
         * 拿到的是个空数组 —— 于是"往返"与"坏样本兜底"两条最该钉的断言会全部空跑，
         * 而且跑起来还是绿的。自带解析器之后，同一份代码在真机与 JVM 上是同一个行为。
         */
        internal fun decode(json: String): List<TrashItem> {
            if (json.isBlank()) return emptyList()
            val root = try {
                TinyJson.parse(json)
            } catch (e: Exception) {
                return emptyList()
            }
            // 兼容整份是 `{"items":[...]}` 与直接是 `[...]` 两种形状。
            val array = TinyJson.arrayOf(root)
                ?: TinyJson.objectOf(root)?.let { TinyJson.arrayOf(it[KEY_ITEMS]) }
                ?: return emptyList()
            val out = ArrayList<TrashItem>(array.size)
            for (entry in array) {
                val obj = TinyJson.objectOf(entry) ?: continue
                val trashUri = TinyJson.stringOf(obj[KEY_URI])?.takeIf { it.isNotBlank() } ?: continue
                val deletedAt = TinyJson.longOf(obj[KEY_DELETED_AT]) ?: continue
                // 没有删除时间就没法算天数，这条会被立刻当成过期清掉 —— 跳过更安全。
                if (deletedAt <= 0L) continue
                out += TrashItem(
                    id = TinyJson.longOf(obj[KEY_ID]) ?: 0L,
                    displayName = TinyJson.stringOf(obj[KEY_NAME])?.ifBlank { DEFAULT_NAME } ?: DEFAULT_NAME,
                    trashUri = trashUri,
                    deletedAt = deletedAt,
                    sizeBytes = (TinyJson.longOf(obj[KEY_SIZE]) ?: 0L).coerceAtLeast(0L),
                    width = (TinyJson.longOf(obj[KEY_WIDTH]) ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    height = (TinyJson.longOf(obj[KEY_HEIGHT]) ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    // 老账单里没有这个键 → 0（"不知道"）。负值同样收成 0：
                    // `DATE_TAKEN` 的合法值只有正毫秒，负数是脏数据，恢复时不许写回去。
                    // **不要**用 `deletedAt` 兜底 —— 那会把老照片错排进删除那一天的时间线。
                    dateTaken = (TinyJson.longOf(obj[KEY_DATE_TAKEN]) ?: 0L).coerceAtLeast(0L),
                )
            }
            return out
        }

        /** 账单写盘。字段顺序固定，diff 与人工排查都看得懂。 */
        internal fun encode(items: List<TrashItem>): String = buildString {
            append("{\"").append(KEY_ITEMS).append("\":[")
            items.forEachIndexed { index, item ->
                if (index > 0) append(',')
                append('{')
                append("\"").append(KEY_ID).append("\":").append(item.id)
                append(",\"").append(KEY_NAME).append("\":").append(TinyJson.quote(item.displayName))
                append(",\"").append(KEY_URI).append("\":").append(TinyJson.quote(item.trashUri))
                append(",\"").append(KEY_DELETED_AT).append("\":").append(item.deletedAt)
                append(",\"").append(KEY_SIZE).append("\":").append(item.sizeBytes)
                append(",\"").append(KEY_WIDTH).append("\":").append(item.width)
                append(",\"").append(KEY_HEIGHT).append("\":").append(item.height)
                append(",\"").append(KEY_DATE_TAKEN).append("\":").append(dateTakenToWrite(item.dateTaken))
                append('}')
            }
            append("]}")
        }

        /**
         * 剩余天数，下限 0。
         *
         * `deletedAt` 在未来（时钟被调过、或刚写入时机器时间就慢了）算"还剩满额"，
         * 不返回负数也不返回爆表的数。
         */
        internal fun daysLeftOf(deletedAt: Long, now: Long, retentionDays: Int = DEFAULT_RETENTION_DAYS): Int {
            if (retentionDays <= 0) return 0
            val elapsed = now - deletedAt
            // 减法会溢出（账单里混进一个 Long.MIN_VALUE 的日期）：溢出成负数就当"未来"。
            if (elapsed <= 0L) return retentionDays
            val days = elapsed / DAY_MILLIS.toLong()
            val left = retentionDays.toLong() - days
            return left.coerceIn(0L, retentionDays.toLong()).toInt()
        }

        /** 已经过期的那些。边界：恰好 30 天整算过期（`>= retentionDays` 天）。 */
        internal fun expiredItems(
            items: List<TrashItem>,
            now: Long,
            retentionDays: Int = DEFAULT_RETENTION_DAYS,
        ): List<TrashItem> {
            if (retentionDays <= 0) return items
            return items.filter { daysLeftOf(it.deletedAt, now, retentionDays) == 0 }
        }

        private val DEFAULT_NAME = "未命名.jpg"

        private const val KEY_ITEMS = "items"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "displayName"
        private const val KEY_URI = "trashUri"
        private const val KEY_DELETED_AT = "deletedAt"
        private const val KEY_SIZE = "sizeBytes"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"

        /**
         * 原始拍摄时间。**老账单里没有这个键**，`decode` 遇到缺失就填 0，
         * `encode` 一定写出来 —— 所以第一次 `record` / `forget` 的整表重写
         * （[writeAll]）之后老文件就自然升级了，不需要 schema 版本号。
         */
        private const val KEY_DATE_TAKEN = "dateTaken"

        /** 一天。纯逻辑里当常量用，测试直接拿它算边界。 */
        internal const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heic"
            else -> "image/jpeg"
        }

        /** `content://media/.../123` → Uri。历史账单里可能有空串或非 content 的串，一律当无效。 */
        internal fun String.toUriOrNull(): Uri? = try {
            if (isBlank() || !startsWith("content://")) null else Uri.parse(this)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 一份够用就好的 JSON 读写：对象 / 数组 / 字符串 / 数字 / 真 / 假 / null。
 *
 * ## 为什么不直接用 `org.json`
 * 这个项目里所有数据层单测都跑在 JVM 上，而 `testOptions.unitTests.isReturnDefaultValues = true`
 * 会把 `android.jar` 里那些方法变成**返回默认值**的桩。`org.json` 恰好就在 `android.jar` 里：
 * `JSONArray(文本)` 不会解析，`optJSONObject` 恒返回 null，`JSONObject().put(...).toString()`
 * 是一句 `"{}"`。于是"往返"与"坏样本兜底"这两条最该被钉的断言会**空跑且全绿** ——
 * 那比没有测试更糟，因为它给的是"这块有人看着"的错觉。
 *
 * ## 为什么不是再加一个依赖
 * `data/` 下能改的文件只有本文件与 [Collections]；加依赖要动 `app/build.gradle.kts`，
 * 那是别人的写作用域。而且这里要的只是"读回自己刚写的那几个字段"，
 * 一个 80 行的递归下降足够，也不需要提防恶意输入（文件在私有目录里）。
 *
 * 约定：解析失败一律抛 [TinyJson.ParseException]，调用方接住就退化成空表；
 * 读字段用下面那几个 `xOf`，**类型不对返回 null 而不是抛** —— 账单里混进一个
 * `"sizeBytes":"很大"` 不该让整份账单消失。
 */
internal object TinyJson {

    class ParseException(message: String) : Exception(message)

    /** 解析成功返回 `Map<String, Any?>` / `List<Any?>` / `String` / `Double` / `Boolean` / null。 */
    fun parse(text: String): Any? = Parser(text).run {
        val value = value()
        skipWhitespace()
        if (pos != text.length) throw ParseException("尾部还有 ${text.length - pos} 个字符")
        value
    }

    /** 把字符串包成 JSON 字面量。控制字符按 `\uXXXX` 转义 —— 文件名里什么都有。 */
    fun quote(text: String): String = buildString(text.length + 2) {
        append('"')
        for (ch in text) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    @Suppress("UNCHECKED_CAST")
    fun objectOf(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arrayOf(value: Any?): List<Any?>? = value as? List<Any?>

    fun stringOf(value: Any?): String? = value as? String

    /** 数字 / 数字字符串都收。超出 Long 的（json 里写成 `1e300`）返回 null。 */
    fun longOf(value: Any?): Long? = when (value) {
        is Double -> if (value.isFinite() && value >= -9.2e18 && value <= 9.2e18) value.toLong() else null
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private class Parser(private val text: String) {
        var pos = 0

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWhitespace()
            if (pos >= text.length) throw ParseException("到结尾了，还缺一个值")
            return when (val ch = text[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (ch == '-' || ch.isDigit()) num() else throw ParseException("位置 $pos 的 <$ch> 不像一个值")
            }
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skipWhitespace()
                val key = str()
                skipWhitespace()
                expect(':')
                out[key] = value()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw ParseException("位置 $pos 缺 , 或 }")
                }
            }
        }

        private fun arr(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { pos++; return out }
            while (true) {
                out += value()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw ParseException("位置 $pos 缺 , 或 ]")
                }
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) throw ParseException("字符串没闭合")
                when (val ch = text[pos]) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        pos++
                        if (pos >= text.length) throw ParseException("转义没写完")
                        when (val esc = text[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 >= text.length) throw ParseException("\\u 后面不足 4 位")
                                val hex = text.substring(pos + 1, pos + 5)
                                sb.append((hex.toIntOrNull(16) ?: throw ParseException("\\u$hex 不是十六进制")).toChar())
                                pos += 4
                            }
                            else -> throw ParseException("不认识的转义 \\$esc")
                        }
                        pos++
                    }
                    else -> { sb.append(ch); pos++ }
                }
            }
        }

        private fun num(): Double {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val slice = text.substring(start, pos)
            return slice.toDoubleOrNull() ?: throw ParseException("<$slice> 不是一个数")
        }

        private fun literal(word: String, result: Any?): Any? {
            if (!text.startsWith(word, pos)) throw ParseException("位置 $pos 不是 $word")
            pos += word.length
            return result
        }

        private fun peek(): Char? = if (pos < text.length) text[pos] else null

        private fun expect(ch: Char) {
            if (peek() != ch) throw ParseException("位置 $pos 期望 <$ch>，实际 <${peek()}>")
            pos++
        }
    }
}
