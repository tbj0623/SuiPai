package com.suipai.app.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import androidx.compose.runtime.Immutable
import com.suipai.app.settings.SettingKey
import com.suipai.app.settings.SettingsKeys
import com.suipai.app.settings.SettingsSnapshot
import com.suipai.app.settings.SettingsStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 导出与恢复：把「随拍自己写下的东西」打成一个文件，再在另一台设备上读回来。
 *
 * ## 这个文件里有什么、没有什么
 *
 * 有：设置中心的偏好（`cfg_` 那一层）、自定义配方、收藏夹、收藏与隐藏的 id 清单、
 * 相册搜索历史。
 *
 * **没有：照片本身。** 随拍不存照片，照片在系统媒体库（`Pictures/SuiPai`）。
 * 收藏/隐藏及收藏夹成员仍以 MediaStore 数字 id 记录，但这些 id **不可跨安装迁移**。
 * 新备份带一个保存在 noBackupFilesDir 的随机安装标识；同一安装才应用这些关联。
 * 换设备或导入缺标识的旧备份时，只恢复可移植的设置、配方与搜索历史，
 * 本机收藏夹和照片关联保留原样，避免 id 碰撞导致错误收藏或隐藏。
 *
 * 也没有：AI Key（加密封装、只在 `suipai.ai` 里）、回收站账单（记录"用户删了什么"，
 * 属隐私且换机后 uri 全失效）、搜索索引（可重建的缓存）、相机/一瞬/相册三张老表。
 *
 * ## 为什么 JSON 是手写的（而不是 `org.json`，也不是 kotlinx-serialization）
 *
 * - **不能是 `org.json`**：`unitTests.isReturnDefaultValues = true` 会把 `android.jar`
 *   里的 `JSONObject` 变成**返回默认值的桩**，于是"往返"与"坏样本兜底"两条最该钉的断言
 *   会空跑且全绿（详见 `Trash.kt` 末尾那段说明，同一个坑）。
 * - **不引 kotlinx-serialization**：虽然依赖已在（`CustomLook` 用它），但它要一个稳定的
 *   `@Serializable` 模型；而这份文件的形状里有一项是"设置值按类型编码成字符串"，
 *   写成 `@Serializable` 就得给每个类型做多态适配，比手写一遍更难读。这里要的只是
 *   "读回自己刚写的那几个字段"，所以照 `Trash.kt` 的做法，复用同一个 [TinyJson] 递归下降。
 *
 * ## 坏数据一律不抛
 *
 * 这个文件来自用户手里的任意一个 uri（SAF 里选错文件是常态：一张照片、一个 APK、
 * 半截下载的备份）。所以：
 * - 结构不对 → [decode] 返回 null，[read] 说清是哪一种不对；
 * - **schema 比当前版本新 → 整包拒绝**。不能"按旧语义尽量解"：新版本可能给某个字段
 *   换了含义（例如 `favorites` 从"照片 id"改成"照片 + 日期"），旧版本照旧读会把
 *   用户的数据解释成另一件东西，而这比"读不出来"糟得多；
 * - 单个字段缺失 / 类型不对 → 用默认值、跳过那一条，不影响整份文件。
 */
@Immutable
data class BackupBundle(
    /**
     * 数据结构版本。**必须显式写在文件里**，且是恢复时的第一道闸。
     *
     * 加字段不一定要升它（缺字段有默认值）；**改含义必须升**，因为旧版本读到新语义
     * 会把它解释成别的东西。见 [Backup.CURRENT_SCHEMA] 的注释。
     */
    val schema: Int = Backup.CURRENT_SCHEMA,
    /** 打包时刻（毫秒）。界面上「这份备份是什么时候的」。 */
    val createdAt: Long = 0L,
    /** 打包那台设备上的版本号（`versionName`），只用于显示与排查。 */
    val appVersion: String = "",
    /** 生成备份的安装标识。旧版备份缺失，恢复时不得假设照片编号仍对应。 */
    val sourceInstallId: String = "",
    /**
     * 设置中心的偏好：键 → 字符串值。
     *
     * 为什么值也是字符串：这份文件可能被手改、被别的工具读；`true` / `"true"` / `"开"`
     * 三种写法之间的歧义只在**读**的时候炸，而按 `SettingsKeys.byId(key)` 的类型去解
     * 就不会有歧义（见 [settingWrites]）。
     *
     * 只装**真正落过盘**的键（`SettingsSnapshot.has`）：没落过 = 用默认值，恢复之后
     * 仍然是默认值。这样「我从没改过」与「我改成了默认档」在文件里是两件事。
     */
    val settings: Map<String, String> = emptyMap(),
    /**
     * 自定义配方，每条一个 `SPLOOK1:` 文本（`CustomLooks.exportText` 的形状）。
     *
     * 用现成的传输格式而不是自己再定义一份：这样备份里的配方能直接粘到
     * 「导入配方」那个输入框里，反过来也一样 —— 一份格式，两处入口。
     */
    val looks: List<String> = emptyList(),
    /** 收藏夹（id / 名称 / 照片 id 顺序 / 两个时间）。**照片本身不在里面**。 */
    val collections: List<PhotoCollection> = emptyList(),
    /** 收藏的照片 id。 */
    val favorites: List<Long> = emptyList(),
    /** 隐藏的照片 id。 */
    val hidden: List<Long> = emptyList(),
    /** 相册搜索的最近词（`SearchHistory`，最多 10 条、按时间倒序）。 */
    val searchHistory: List<String> = emptyList(),
) {
    /** 这一包有几条东西。界面上的「上次导出：设置 3 项 · 配方 2 条…」就是它。 */
    fun counts(): BackupCounts = BackupCounts(
        settings = settings.size,
        looks = looks.size,
        collections = collections.size,
        favorites = favorites.size,
        hidden = hidden.size,
        searchHistory = searchHistory.size,
    )
}

/** 一包备份里各类东西的条数。纯计数，不含任何内容。 */
@Immutable
data class BackupCounts(
    val settings: Int = 0,
    val looks: Int = 0,
    val collections: Int = 0,
    val favorites: Int = 0,
    val hidden: Int = 0,
    val searchHistory: Int = 0,
) {
    val total: Int get() = settings + looks + collections + favorites + hidden + searchHistory

    /**
     * 一句话说全。**不四舍五入、不省略**：恢复是一个"会覆盖东西"的动作，
     * 用户要知道自己按下确认时到底换回了多少。
     */
    fun line(): String =
        "设置 $settings 项 · 配方 $looks 条 · 收藏夹 $collections 个 · " +
            "收藏 $favorites 张 · 隐藏 $hidden 张 · 搜索 $searchHistory 条"
}

/** 合并方式。两种都会把本机变成「合并结果」的样子，区别只在结果怎么算。 */
enum class MergeMode {
    /**
     * **以文件为准**：结果 = 文件里那一包。
     *
     * 本机有、文件里没有的东西**会被去掉**（配方会被删除、收藏夹会被替换、
     * 收藏与隐藏会只剩文件里那些 id、搜索历史会只剩文件里那几个词）。
     * 设置只覆盖文件里有的键，不删本机其它键（见 [Backup.importFrom] 的注释）。
     */
    REPLACE,

    /**
     * **并集去重**：结果 = 本机 + 文件里多出来的部分。
     *
     * 冲突一律**保留本机**（同一份设置、同一条配方 id、同一个收藏夹 id：
     * 名字与时间取本机的），因为本机这份是用户此刻正在用的。
     * 收藏夹里同一个 id 的照片两份会并起来（本机顺序在前）。
     */
    UNION,
}

/** 导出 / 恢复的结果。**不抛异常**：失败也带一句能直接显示的中文原因。 */
sealed interface BackupResult {
    /** 成功。[at] 是完成时刻（毫秒），界面上「上次导出」用它。 */
    data class Ok(val counts: BackupCounts, val at: Long) : BackupResult

    /** 部分恢复：列出跳过的照片关联或实际写入失败的类别，不能报成完整成功。 */
    data class Partial(val counts: BackupCounts, val at: Long, val reason: String) : BackupResult

    /** 失败。[reason] 是给用户看的一句话，不含堆栈、不甩英文异常名。 */
    data class Failed(val reason: String) : BackupResult

    /** 一行可直接写进页眉的结论（成功与失败都有话说）。 */
    fun line(action: String): String = when (this) {
        // `${action}` 的花括号不能省：中文是合法的标识符字符，`$action完成` 会被当成
        // 一个叫 `action完成` 的变量（编译期才会发现，报错还指向"未解析的引用"）。
        is Ok -> "${action}完成：${counts.line()}"
        is Partial -> "${action}部分完成：$reason。请检查本机数据；文件仍可重新导入。"
        is Failed -> reason
    }
}

/** [Backup.read] 的三种结果。分开成类型而不是只回 null，是为了让界面说清「为什么不行」。 */
sealed interface BackupRead {
    data class Ok(val bundle: BackupBundle) : BackupRead

    /**
     * 文件来自**更新**的随拍（`schema` 比当前大）。整包拒绝 —— 见 `Backup` 类注释里
     * 关于"旧语义乱解"的那一段。
     */
    data class TooNew(val schema: Long) : BackupRead

    /** 不是随拍的备份，或者已经损坏（截断、字段类型不对、根本不是 JSON）。 */
    object Broken : BackupRead
}

/** 恢复目标与被安全跳过的照片编号关联。 */
internal data class RestorePlan(val target: BackupBundle, val photoLinksSkipped: Boolean)

/**
 * 导出与恢复的唯一入口。
 *
 * 纯函数部分（[encode] / [decode] / [read] / [merge] / [settingWrites]）不碰任何
 * android 类型，所以能在 JVM 单测里钉死；只有 [collect] / [exportTo] / [importFrom]
 * 需要 `Context`（真正读写文件与四份清单）。
 */
object Backup {

    private const val TAG = "Backup"

    /**
     * 当前数据结构版本。
     *
     * 规矩（与 `YishunRecipe` 的版本纪律同一条）：
     * - **加字段**：不用升（读的时候缺就取默认值）；
     * - **改字段含义、删字段、改 id 清单的口径**：必须 +1，否则旧版本会把新语义
     *   解释成另一件东西 —— 那是"静默改数据"，比读不出来糟；
     * - 旧版本读到更大的 [CURRENT_SCHEMA]**整包拒绝**（[BackupRead.TooNew]）。
     *
     * v2 增加 sourceInstallId 安全边界：旧版不能忽略它后继续套用照片 id。
     * v1 仍可读取，但来源无法验证，因此照片关联一律跳过。
     */
    const val CURRENT_SCHEMA = 2

    /** 文件里的身份标记。选错文件（照片、APK、别的应用的 json）靠它挡掉。 */
    const val FORMAT = "suipai-backup"

    /** 导入时最多读这么多字符。超过它的一定不是备份，不读进内存（选错文件时可能是个视频）。 */
    private const val MAX_IMPORT_BYTES = 16 * 1024 * 1024

    // ── 编码 ──────────────────────────────────────────────────────────

    /**
     * 序列化。字段顺序固定、`settings` 按键名排序 —— 同一份数据每次都得到同一串字符，
     * 这样 diff 与"这份文件有没有变"都看得懂。
     *
     * 全部字符串走 [TinyJson.quote]：引号、反斜杠、换行、控制字符都会转义，
     * 中文与 emoji 原样留着（UTF-8 文件，不转 `\u`：用户会打开这个文件看）。
     */
    fun encode(bundle: BackupBundle): String = buildString(1024) {
        append("{\n")
        append("  \"").append(K_FORMAT).append("\": ").append(TinyJson.quote(FORMAT)).append(",\n")
        append("  \"").append(K_SCHEMA).append("\": ").append(bundle.schema).append(",\n")
        append("  \"").append(K_CREATED_AT).append("\": ").append(bundle.createdAt).append(",\n")
        append("  \"").append(K_APP_VERSION).append("\": ")
            .append(TinyJson.quote(bundle.appVersion)).append(",\n")

        append("  \"").append(K_SOURCE_INSTALL_ID).append("\": ")
            .append(TinyJson.quote(bundle.sourceInstallId)).append(",\n")
        append("  \"").append(K_SETTINGS).append("\": {")
        val keys = bundle.settings.keys.sorted()
        keys.forEachIndexed { i, k ->
            if (i > 0) append(',')
            append("\n    ").append(TinyJson.quote(k)).append(": ")
                .append(TinyJson.quote(bundle.settings[k].orEmpty()))
        }
        if (keys.isNotEmpty()) append("\n  ")
        append("},\n")

        append("  \"").append(K_LOOKS).append("\": [")
        bundle.looks.forEachIndexed { i, text ->
            if (i > 0) append(',')
            append("\n    ").append(TinyJson.quote(text))
        }
        if (bundle.looks.isNotEmpty()) append("\n  ")
        append("],\n")

        append("  \"").append(K_COLLECTIONS).append("\": [")
        bundle.collections.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append("\n    {\"").append(K_ID).append("\": ").append(TinyJson.quote(c.id))
            append(", \"").append(K_NAME).append("\": ").append(TinyJson.quote(c.name))
            append(", \"").append(K_PHOTO_IDS).append("\": [")
            c.photoIds.forEachIndexed { j, id ->
                if (j > 0) append(',')
                append(id)
            }
            append("], \"").append(K_CREATED_AT).append("\": ").append(c.createdAt)
            append(", \"").append(K_UPDATED_AT).append("\": ").append(c.updatedAt)
            append('}')
        }
        if (bundle.collections.isNotEmpty()) append("\n  ")
        append("],\n")

        // 两份 id 清单一行写完：几千个 id 摊成几千行，人眼与 diff 都读不动。
        append("  \"").append(K_FAVORITES).append("\": [")
            .append(bundle.favorites.joinToString(", ")).append("],\n")
        append("  \"").append(K_HIDDEN).append("\": [")
            .append(bundle.hidden.joinToString(", ")).append("],\n")

        append("  \"").append(K_SEARCH).append("\": [")
        bundle.searchHistory.forEachIndexed { i, term ->
            if (i > 0) append(',')
            append("\n    ").append(TinyJson.quote(term))
        }
        if (bundle.searchHistory.isNotEmpty()) append("\n  ")
        append("]\n")
        append("}\n")
    }

    // ── 解码 ──────────────────────────────────────────────────────────

    /** 看一份文本是什么：[Ok] / [TooNew] / [Broken]。**绝不抛**。 */
    fun read(text: String): BackupRead {
        if (text.isBlank()) return BackupRead.Broken
        val root = runCatching { TinyJson.parse(text) }.getOrNull() ?: return BackupRead.Broken
        val obj = TinyJson.objectOf(root) ?: return BackupRead.Broken
        // 身份标记先挡：SAF 里选错文件是常态，别拿别的应用的 json 往下解。
        if (TinyJson.stringOf(obj[K_FORMAT]) != FORMAT) return BackupRead.Broken
        // schema 缺失 = 不知道这份文件按哪一版解释，只能拒绝（不能猜成当前版本）。
        val schema = schemaOf(obj[K_SCHEMA]) ?: return BackupRead.Broken
        if (schema > CURRENT_SCHEMA) return BackupRead.TooNew(schema)
        return BackupRead.Ok(parseV1(obj, schema))
    }

    /**
     * 版本号只认**正整数**。
     *
     * 为什么不用 `TinyJson.longOf`：那个函数是给"宽松读账单"写的，它会把 `1.5` 截成 `1`、
     * 把字符串 `"1"` 也当数字收。对一张账单无所谓，对版本闸是致命的 —— `1.5` 被读成 `1`
     * 意味着"一份按半新语义写的文件被当成旧语义解"，正是这条闸要挡住的那件事。
     */
    private fun schemaOf(value: Any?): Long? = when (value) {
        is Double -> value.takeIf { it.isFinite() && it % 1.0 == 0.0 && it >= 1.0 && it <= 1e9 }
            ?.toLong()

        else -> null
    }

    /** 只要结果的那一版：读不出来（含 schema 太新）一律 null。 */
    fun decode(text: String): BackupBundle? = (read(text) as? BackupRead.Ok)?.bundle

    /**
     * v1 的字段解析。**每一层都容错**：类型不对就跳过那一条，缺字段取默认值。
     * 只有"整份结构不对"才在外层被判成 [BackupRead.Broken]。
     */
    private fun parseV1(obj: Map<String, Any?>, schema: Long): BackupBundle {
        val settings = TinyJson.objectOf(obj[K_SETTINGS])
            ?.entries
            ?.mapNotNull { (k, v) -> TinyJson.stringOf(v)?.let { k to it } }
            ?.toMap()
            ?: emptyMap()
        return BackupBundle(
            schema = CURRENT_SCHEMA,
            createdAt = (TinyJson.longOf(obj[K_CREATED_AT]) ?: 0L).coerceAtLeast(0L),
            appVersion = TinyJson.stringOf(obj[K_APP_VERSION]).orEmpty(),
            sourceInstallId = if (schema >= 2L) TinyJson.stringOf(obj[K_SOURCE_INSTALL_ID]).orEmpty() else "",
            settings = settings,
            looks = dedupeLooks(
                TinyJson.arrayOf(obj[K_LOOKS]).orEmpty().mapNotNull { TinyJson.stringOf(it) },
            ),
            collections = parseCollections(obj[K_COLLECTIONS]),
            favorites = idsOf(obj[K_FAVORITES]),
            hidden = idsOf(obj[K_HIDDEN]),
            // 搜索历史按"去重 + 截到上限"归一：文件是手也能改的，一个 200 条的清单
            // 会让历史区把整屏吃光，而"最多 10 条"是这一块唯一的硬约束。
            searchHistory = unionHistory(
                emptyList(),
                TinyJson.arrayOf(obj[K_SEARCH]).orEmpty().mapNotNull { TinyJson.stringOf(it) },
            ),
        )
    }

    private fun parseCollections(value: Any?): List<PhotoCollection> {
        val array = TinyJson.arrayOf(value).orEmpty()
        val out = ArrayList<PhotoCollection>(array.size)
        val used = HashSet<String>(array.size)
        for (entry in array) {
            val obj = TinyJson.objectOf(entry) ?: continue
            val id = TinyJson.stringOf(obj[K_ID])?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            // 同一个 id 出现两次：留第一条。否则"删除"永远只删掉一条。
            if (!used.add(id)) continue
            val created = (TinyJson.longOf(obj[K_CREATED_AT]) ?: 0L).coerceAtLeast(0L)
            out += PhotoCollection(
                id = id,
                name = Collections.normalizeName(TinyJson.stringOf(obj[K_NAME]).orEmpty()),
                photoIds = idsOf(obj[K_PHOTO_IDS]).take(Collections.MAX_PHOTOS),
                createdAt = created,
                updatedAt = (TinyJson.longOf(obj[K_UPDATED_AT]) ?: created).coerceAtLeast(0L),
            )
            if (out.size >= Collections.MAX_COLLECTIONS) break
        }
        return out
    }

    /** id 表：只要正整数，去重保序（同一份规则由 [Collections.orderedAdd] 实现）。 */
    private fun idsOf(value: Any?): List<Long> {
        val raw = TinyJson.arrayOf(value).orEmpty().mapNotNull { TinyJson.longOf(it) }
        return Collections.orderedAdd(emptyList(), raw.filter { it > 0L })
    }

    /** 配方按 **payload 里的 id** 去重（不是按整段文本）：改过一次的配方文本会变，
     *  但 id 不变 —— 按文本去重会让同一条配方在恢复后变成两条。 */
    private fun dedupeLooks(payloads: List<String>): List<String> {
        val out = LinkedHashMap<String, String>(payloads.size)
        for (text in payloads) {
            val id = lookIdOf(text) ?: continue     // 解不出 id 的 payload 一律跳过
            if (!out.containsKey(id)) out[id] = text
        }
        return out.values.toList()
    }

    /**
     * 从一条 `SPLOOK1:` 文本里取出**整条配方**；取不到返回 null。
     *
     * 这里用 kotlinx-serialization 而不是手写解析：[CustomLook] 本来就是
     * `@Serializable` 的落盘模型，复用它的序列化器才不会出现"备份认得的字段"与
     * "配方文件认得的字段"两套。文件外壳仍然用手写的 [TinyJson]（理由见文件头）。
     *
     * **保留 payload 里的 id** —— 与 `CustomLooks.importText` 刻意不同。那个接口是给
     * 剪贴板用的，必须重新发号（否则导入一条别人的配方会顶掉本机同 id 的那条）；
     * 而恢复是**同一个用户的同一份数据**，id 变了就意味着"再恢复一次会多出一条"。
     */
    @OptIn(ExperimentalEncodingApi::class)
    internal fun decodeLook(text: String): CustomLook? {
        val start = text.indexOf(CustomLooks.MARKER)
        if (start < 0) return null
        val from = start + CustomLooks.MARKER.length
        // 只按 Base64Url 字母表收口：文本里可能跟着一句说明（见 CustomLooks.importText）。
        var end = from
        while (end < text.length &&
            (text[end].isLetterOrDigit() || text[end] == '-' || text[end] == '_' || text[end] == '=')
        ) {
            end++
        }
        if (end == from) return null
        val bytes = runCatching {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                .decode(text.substring(from, end))
        }.getOrNull() ?: return null
        return runCatching {
            looksJson.decodeFromString(CustomLook.serializer(), bytes.decodeToString())
        }.getOrNull()
    }

    /**
     * 配方 id —— 去重与"同一条"判定的唯一依据。
     *
     * 只认 `user_` 前缀的 id：`CustomLooks.all()` 在**读盘时**就把别的 id 过滤掉了
     * （见那里的 `filter { it.id.startsWith(ID_PREFIX) }`），所以一个不带前缀的 payload
     * 即使存回去也不会再出现，等于没恢复。
     */
    internal fun lookIdOf(text: String): String? =
        decodeLook(text)?.id?.takeIf { it.startsWith(CustomLook.ID_PREFIX) }

    /** 配方 payload 的序列化器。选项与 `CustomLooks` 的落盘 json 对齐。 */
    private val looksJson = Json {
        ignoreUnknownKeys = true
        allowStructuredMapKeys = true
        encodeDefaults = false
    }

    // ── 合并（纯函数） ────────────────────────────────────────────────

    /**
     * 把两份备份合成一份。**纯函数**：不读时钟、不碰磁盘。
     *
     * [MergeMode.REPLACE]：结果就是 [remote]（只把 schema 与时间戳修正成本机认识的形状）。
     *
     * [MergeMode.UNION]：逐类去重，**冲突保留 [local]**：
     * - `settings`：本机先，同键本机赢；
     * - `looks`：按 payload 里的配方 id；同 id 留本机那条（用户在这台机器上可能刚改过）；
     * - `collections`：按收藏夹 id；同 id 时照片**并集且本机顺序在前**，
     *   名称与 `createdAt` 取本机，`updatedAt` 取两者较大；
     * - `favorites` / `hidden`：各自并集去重。**同一个 id 同时出现在两份清单里是允许的**
     *    —— 应用里这两份本来就是独立的（藏起来的那张也能被收藏），这里不做互斥；
     * - `searchHistory`：去重（不区分大小写），本机顺序在前，截到 [SearchHistory.MAX]。
     */
    fun merge(local: BackupBundle, remote: BackupBundle, mode: MergeMode): BackupBundle {
        val createdAt = maxOf(local.createdAt, remote.createdAt)
        val appVersion = remote.appVersion.ifBlank { local.appVersion }
        return when (mode) {
            MergeMode.REPLACE -> remote.copy(
                schema = CURRENT_SCHEMA,
                createdAt = createdAt,
                appVersion = appVersion,
            )

            MergeMode.UNION -> BackupBundle(
                schema = CURRENT_SCHEMA,
                createdAt = createdAt,
                appVersion = appVersion,
                sourceInstallId = local.sourceInstallId,
                // `remote + local` 而不是 `local + remote`：Kotlin 的 `Map.plus` 在键冲突时
                // **右边赢**，与 `Int.plus` 的直觉相反。写反的话每次合并都会把本机的设置
                // 悄悄换成文件里那份 —— 单测 `unionSettingsKeepLocalAndAddRemote` 就是为这条
                // 存在的（第一版正是写反了）。
                settings = remote.settings + local.settings,
                looks = unionLooks(local.looks, remote.looks),
                collections = unionCollections(local.collections, remote.collections),
                favorites = Collections.orderedAdd(local.favorites, remote.favorites),
                hidden = Collections.orderedAdd(local.hidden, remote.hidden),
                searchHistory = unionHistory(local.searchHistory, remote.searchHistory),
            )
        }
    }

    /**
     * 恢复前的纯函数安全门：MediaStore 数字 id 只有同一安装才有可比性。
     *
     * 不同安装与旧备份（缺标识）只恢复可移植的数据；本机收藏/隐藏/收藏夹原封不动，
     * 防止把本机一张无关照片误判为隐藏或收藏。此标识不是身份认证。
     */
    internal fun planRestore(
        local: BackupBundle,
        remote: BackupBundle,
        mode: MergeMode,
        localInstallId: String,
    ): RestorePlan {
        val merged = merge(local, remote, mode).copy(sourceInstallId = localInstallId)
        if (localInstallId.isNotBlank() && remote.sourceInstallId == localInstallId) {
            return RestorePlan(merged, photoLinksSkipped = false)
        }
        val changed = merged.collections != local.collections ||
            merged.favorites != local.favorites || merged.hidden != local.hidden
        return RestorePlan(
            target = merged.copy(
                collections = local.collections,
                favorites = local.favorites,
                hidden = local.hidden,
            ),
            photoLinksSkipped = changed,
        )
    }

    private fun unionLooks(local: List<String>, remote: List<String>): List<String> {
        val out = LinkedHashMap<String, String>(local.size + remote.size)
        for (text in local + remote) {
            val id = lookIdOf(text) ?: continue
            out.putIfAbsent(id, text)   // 先到的（本机）赢
        }
        return out.values.toList()
    }

    private fun unionCollections(
        local: List<PhotoCollection>,
        remote: List<PhotoCollection>,
    ): List<PhotoCollection> {
        val out = LinkedHashMap<String, PhotoCollection>(local.size + remote.size)
        for (c in local) out[c.id] = c
        for (c in remote) {
            val have = out[c.id]
            out[c.id] = if (have == null) {
                c
            } else {
                // 同 id：照片并起来（本机顺序在前），名称与本机的创建时间不动。
                have.copy(
                    photoIds = Collections.orderedAdd(have.photoIds, c.photoIds)
                        .take(Collections.MAX_PHOTOS),
                    updatedAt = maxOf(have.updatedAt, c.updatedAt),
                )
            }
        }
        return out.values.toList().take(Collections.MAX_COLLECTIONS)
    }

    private fun unionHistory(local: List<String>, remote: List<String>): List<String> {
        val seen = HashSet<String>(local.size + remote.size)
        val out = ArrayList<String>(SearchHistory.MAX)
        for (raw in local + remote) {
            val term = raw.trim()
            if (term.isEmpty()) continue
            if (!seen.add(term.lowercase(Locale.ROOT))) continue
            out += term
            if (out.size >= SearchHistory.MAX) break
        }
        return out
    }

    // ── 设置：字符串 ↔ 类型 ───────────────────────────────────────────

    /**
     * 把一份设置快照导出成「键 → 字符串」。**纯函数**（[SettingsSnapshot] 不吃 Context）。
     *
     * 只导出 `has(key)` 的键，理由见 [BackupBundle.settings]。
     * 字符串集合（今天只有 `cfg_settings_search_history` 一个）编码成 **JSON 数组文本**：
     * `Map<String, String>` 装不下重复与顺序，而用逗号拼接会让"搜索词里本来就有逗号"
     * 变成不可逆（用户真的会搜 `2024, 夏天`）。
     */
    internal fun exportSettings(snapshot: SettingsSnapshot): Map<String, String> = buildMap {
        for (key in SettingsKeys.all) {
            if (!snapshot.has(key)) continue
            when (key) {
                is SettingKey.Bool -> put(key.id, snapshot.bool(key).toString())
                is SettingKey.Int -> put(key.id, snapshot.int(key).toString())
                is SettingKey.Text -> put(key.id, snapshot.text(key))
                is SettingKey.Choice<*> -> put(key.id, snapshot.choiceName(key))
                is SettingKey.Strings -> put(key.id, stringsToJson(snapshot.strings(key)))
            }
        }
    }

    /**
     * 把「键 → 字符串」解成**待写入的**设置项。**纯函数**，因此每一条跳过规则都能单测。
     *
     * 跳过的四种情况（都只是少写一条，不抛）：
     * 1. 键不在 [SettingsKeys] 里（别的版本写的、手改的）——跳过，不往 prefs 里塞野键；
     * 2. 整数的值不是数字，或落在 `SettingKey.Int.range` 之外——跳过（不做夹取：
     *    "磁盘上有个非法值"会一直存在，下一个读它的人未必记得夹）；
     * 3. 枚举的 name 不在这张表里——跳过（大小写不符的救回来，与 `SettingsStore.read` 一致）；
     * 4. 字符串集合不是一段 JSON 数组——跳过（当成空集会**清掉**用户的历史，那更糟）。
     */
    internal fun settingWrites(settings: Map<String, String>): List<SettingWrite> {
        val out = ArrayList<SettingWrite>(settings.size)
        for ((id, raw) in settings) {
            val key = SettingsKeys.byId(id) ?: continue
            when (key) {
                is SettingKey.Bool ->
                    raw.trim().toBooleanStrictOrNull()?.let { out += SettingWrite.BoolValue(key, it) }

                is SettingKey.Int -> raw.trim().toIntOrNull()
                    ?.takeIf { it in key.range }
                    ?.let { out += SettingWrite.IntValue(key, it) }

                is SettingKey.Text -> out += SettingWrite.TextValue(key, raw)

                is SettingKey.Choice<*> -> choiceNameOf(key, raw)
                    ?.let { out += SettingWrite.ChoiceName(key, it) }

                is SettingKey.Strings -> stringsFromJson(raw)
                    ?.let { out += SettingWrite.StringSet(key, it) }
            }
        }
        return out
    }

    /** 档位名归一：先按原样找，再按大小写无关找；都对不上返回 null（= 不写）。 */
    private fun choiceNameOf(key: SettingKey.Choice<*>, raw: String): String? {
        val name = raw.trim()
        key.options.firstOrNull { it.name == name }?.let { return it.name }
        return key.options.firstOrNull { it.name.equals(name, ignoreCase = true) }?.name
    }

    private fun stringsToJson(values: Set<String>): String = buildString {
        append('[')
        values.sorted().forEachIndexed { i, s ->
            if (i > 0) append(',')
            append(TinyJson.quote(s))
        }
        append(']')
    }

    private fun stringsFromJson(raw: String): Set<String>? {
        val parsed = runCatching { TinyJson.parse(raw) }.getOrNull() ?: return null
        val array = TinyJson.arrayOf(parsed) ?: return null
        return array.mapNotNull { TinyJson.stringOf(it) }.toSet()
    }

    // ── 读本机现状 ────────────────────────────────────────────────────

    /**
     * 把本机现在的样子打成一包。导出用它，恢复时也用它（算合并结果）。
     *
     * `now` 由调用方注入：`createdAt` 要出现在界面上，也要能被测试钉住。
     */
    suspend fun collect(
        context: Context,
        now: Long = System.currentTimeMillis(),
    ): BackupBundle = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val snapshot = SettingsStore.of(app).load()
        BackupBundle(
            schema = CURRENT_SCHEMA,
            createdAt = now,
            appVersion = versionName(app),
            sourceInstallId = BackupInstallIdentity.current(app).orEmpty(),
            settings = exportSettings(snapshot),
            looks = runCatching {
                val repo = CustomLooks(app)
                repo.all().map { repo.exportText(it) }
            }.getOrDefault(emptyList()),
            // 收藏夹与两份 id 清单都直接读各自那份落盘，不走门面的增删接口：
            // 这一路只要"现在是什么样"，任何一次写都会把只读路径变成有副作用的路径。
            collections = runCatching {
                val file = File(app.filesDir, Collections.FILE_NAME)
                if (file.isFile) Collections.decode(file.readText()) else emptyList()
            }.getOrDefault(emptyList()),
            favorites = runCatching { Favorites(app).ids().sorted() }.getOrDefault(emptyList()),
            hidden = runCatching { Hidden(app).ids().sorted() }.getOrDefault(emptyList()),
            searchHistory = runCatching {
                SearchHistory(app).also { it.load() }.recent()
            }.getOrDefault(emptyList()),
        )
    }

    // ── 导出 ──────────────────────────────────────────────────────────

    /**
     * 写到调用方用 `ActivityResultContracts.CreateDocument` 拿到的那个 uri。
     *
     * **不需要任何存储权限**：SAF 给的是一次性的写授权，权限跟着 uri 走，不跟着应用走。
     * 所以这里既不查 `WRITE_EXTERNAL_STORAGE`，也不碰 MediaStore。
     *
     * 写入用 `"wt"`（write + truncate）打开：用户可能在系统文件选择器里挑了一个**已存在**
     * 的旧备份，`"w"` 在部分 provider 上不截断，会把新内容写在旧内容前面，得到一个
     * 前半截新、后半截旧的废文件。个别 provider 不认 `"wt"`，再退回 `"w"`。
     */
    suspend fun exportTo(context: Context, uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val bundle = collect(app)
        if (bundle.sourceInstallId.isBlank()) return@withContext BackupResult.Failed(
            "无法保存此设备的备份标识；为避免恢复时错配照片，本次没有导出。",
        )
        val text = encode(bundle)
        val failure = writeAll(app, uri, text)
        if (failure != null) {
            Log.w(TAG, "导出失败：$failure")
            BackupResult.Failed(failure)
        } else {
            BackupResult.Ok(bundle.counts(), bundle.createdAt)
        }
    }

    // ── 恢复 ──────────────────────────────────────────────────────────

    /**
     * 读回来 + 校验 + 应用。
     *
     * 应用的口径是**"把本机变成合并结果的样子"**（结果怎么算见 [merge]）：
     * - 设置：只写文件里有的键，**不删**本机其它键。因为"文件里没有"既可能是
     *   "用户没改过（用默认值）"、也可能是"老一点的那份备份没有这个键"，删掉会丢配置；
     * - 配方：按配方 id 对齐。REPLACE 时同 id 以文件内容为准（删掉本机那条再按文件导入），
     *   UNION 时保留本机那条；不在结果里的本机配方会被删掉；
     * - 收藏夹：**整份重写 `collections.json`**（保留 id 与顺序）。为什么不走
     *   `Collections.create`：那个接口自己发号，恢复两次就会得到两套同名收藏夹；
     * - 收藏 / 隐藏：算出差集后增删，并 `persist()` 等落盘 —— 这一份是隐私，
     *   "界面说恢复了而磁盘没落"不能靠用户别在此刻杀进程来避免；
     * - 搜索历史：先清再逐条 record（它会去重并截到 10 条），最后一次落盘。
     *
     * 每个类别独立写入；任一步抛异常都会报告 [BackupResult.Partial] 并列出类别。
     * 不同安装的照片关联也以 Partial 明示，不再用 Ok 假装全部迁移完成。
     */
    suspend fun importFrom(
        context: Context,
        uri: Uri,
        mode: MergeMode = MergeMode.REPLACE,
    ): BackupResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val text = runCatching { readTextFrom(app, uri) }.getOrNull()
            ?: return@withContext BackupResult.Failed("打不开这个文件（可能没有读取权限，或者它太大）")

        val remote = when (val r = read(text)) {
            is BackupRead.Ok -> r.bundle
            is BackupRead.TooNew -> return@withContext BackupResult.Failed(
                "这份备份来自更新的随拍（数据版本 ${r.schema}，本机只认到 ${CURRENT_SCHEMA}）" +
                    "。请先升级随拍再恢复；本机数据没有改动。",
            )
            BackupRead.Broken -> return@withContext BackupResult.Failed(
                "这个文件不是随拍的备份，或者已经损坏。本机数据没有改动。",
            )
        }

        val installId = BackupInstallIdentity.current(app)
            ?: return@withContext BackupResult.Failed("无法确认本机备份标识；为保护照片关联，本机数据没有改动。")
        val local = collect(app)
        val plan = planRestore(local, remote, mode, installId)
        val target = plan.target
        val failures = mutableListOf<String>()

        runCatching { applySettings(app, target.settings) }
            .onFailure { failures += "设置"; Log.w(TAG, "设置没写全", it) }
        runCatching { applyLooks(app, target, mode) }
            .onFailure { failures += "配方"; Log.w(TAG, "配方没恢复全", it) }
        // 仅同一安装的 MediaStore 行号可重用。旧备份/别的设备必须连写入都不尝试。
        if (remote.sourceInstallId == installId) {
            runCatching { applyCollections(app, target.collections) }
                .onFailure { failures += "收藏夹"; Log.w(TAG, "收藏夹没恢复全", it) }
            runCatching { applyIdLists(app, target) }
                .onFailure { failures += "收藏/隐藏"; Log.w(TAG, "收藏/隐藏没恢复全", it) }
        }
        runCatching { applySearchHistory(app, target.searchHistory) }
            .onFailure { failures += "搜索历史"; Log.w(TAG, "搜索历史没恢复全", it) }

        val reasons = buildList {
            if (plan.photoLinksSkipped) add("旧备份或另一设备的照片编号无法安全对应，收藏夹与收藏/隐藏清单已跳过，本机这些数据保持原样")
            if (failures.isNotEmpty()) add("写入失败：${failures.joinToString("、")}")
        }
        val finishedAt = System.currentTimeMillis()
        if (reasons.isEmpty()) BackupResult.Ok(target.counts(), finishedAt)
        else BackupResult.Partial(target.counts(), finishedAt, reasons.joinToString("；"))
    }

    // ── 落地的六个小步骤 ──────────────────────────────────────────────

    private fun applySettings(context: Context, settings: Map<String, String>) {
        val store = SettingsStore.of(context)
        for (write in settingWrites(settings)) {
            when (write) {
                is SettingWrite.BoolValue -> store.put(write.key, write.value)
                is SettingWrite.IntValue -> store.put(write.key, write.value)
                is SettingWrite.TextValue -> store.put(write.key, write.value)
                is SettingWrite.ChoiceName -> store.putChoiceName(write.key, write.name)
                is SettingWrite.StringSet -> store.put(write.key, write.value)
            }
        }
    }

    /**
     * 把配方对齐到 [target]。
     *
     * **保住 id**：用 [decodeLook] 解出 `CustomLook` 再交给 `save`，而不是
     * `importText`（那个一定会重新发号，是剪贴板导入的硬要求）。理由：
     * 恢复是同一个用户的同一份数据，id 一变，"再恢复一次"就会多出一条重复配方。
     * `CustomLooks.save` 按 id 判覆盖，同 id 第二次恢复是幂等的。
     */
    private fun applyLooks(context: Context, target: BackupBundle, mode: MergeMode) {
        val repo = CustomLooks(context)
        val want: Map<String, CustomLook> = target.looks
            .mapNotNull { text -> decodeLook(text)?.let { it.id to it } }
            .toMap()

        // 1) 先删掉"结果里不该有"的那几条（REPLACE 的全部含义就在这一行；
        //    UNION 时 target 是并集，本机每一条都在里面，所以这里什么都不会删）。
        for (look in repo.all()) {
            if (look.id !in want) repo.delete(look.id)
        }

        // 2) 再补上"结果里有、本机没有（或要以文件为准）"的。
        val onDisk = repo.all().map { it.id }.toHashSet()
        for ((id, look) in want) {
            // 同 id 且本机为准：什么都不做（UNION 的全部含义就这一行）。
            if (id in onDisk && mode == MergeMode.UNION) continue
            repo.save(look)
        }
        // CustomLooks.write 历史上会吞掉磁盘异常。必须重新读盘核验，
        // 不能仅凭 save() 返回的内存列表就报告「恢复成功」。
        val persisted = repo.all().associateBy { it.id }
        check(persisted.keys == want.keys) { "配方落盘后条目数量或 id 不一致" }
        for ((id, wanted) in want) {
            val actual = persisted[id] ?: error("配方 $id 未落盘")
            check(actual.copy(created = 0L, modified = 0L) ==
                wanted.copy(created = 0L, modified = 0L)) { "配方 $id 内容未正确落盘" }
        }
    }

    /**
     * 整份重写收藏夹文件。
     *
     * 直接写文件而不是走 [Collections] 的增删接口，唯一的理由是**保住 id**：
     * `create` 自己发号。通过 AtomicFile 完成原子替换；不再在 rename 失败时
     * 直接覆写原文件，避免半途被杀造成收藏夹账本损坏。
     */
    private fun applyCollections(context: Context, target: List<PhotoCollection>) {
        val file = File(context.filesDir, Collections.FILE_NAME)
        val text = Collections.encode(target.take(Collections.MAX_COLLECTIONS))
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    private suspend fun applyIdLists(context: Context, target: BackupBundle) {
        val favorites = Favorites(context)
        val wantFav = target.favorites.toSet()
        val haveFav = favorites.ids()
        favorites.addAll(wantFav - haveFav)
        favorites.removeAll(haveFav - wantFav)
        check(favorites.persist()) { "收藏清单没写入磁盘" }

        val hidden = Hidden(context)
        val wantHidden = target.hidden.toSet()
        val haveHidden = hidden.ids()
        hidden.addAll(wantHidden - haveHidden)
        hidden.removeAll(haveHidden - wantHidden)
        check(hidden.persist()) { "隐藏清单没写入磁盘" }
    }

    private suspend fun applySearchHistory(context: Context, terms: List<String>) {
        val history = SearchHistory(context)
        history.load()
        history.clear()
        // record 把词插到最前，所以倒着录一遍才能得到文件里的顺序。
        for (term in terms.asReversed()) history.record(term)
        check(history.save()) { "搜索历史没写入磁盘" }
    }

    // ── 文件进出 ──────────────────────────────────────────────────────

    /** 成功返回 null，失败返回一句中文原因。 */
    private fun writeAll(context: Context, uri: Uri, text: String): String? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val out = openOut(context, uri, "wt") ?: openOut(context, uri, "w")
            ?: return "写不进这个位置（可能只读，或者被别的应用占着）"
        return try {
            out.use {
                it.write(bytes)
                it.flush()
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "写备份文件失败", e)
            "写到一半失败了：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun openOut(context: Context, uri: Uri, mode: String) =
        runCatching { context.contentResolver.openOutputStream(uri, mode) }.getOrNull()

    /**
     * 读回来，**带体积上限**。选错文件时用户可能挑了一个几百 MB 的视频，
     * 直接 `readBytes()` 会当场 OOM —— 那不是"坏数据处理"，是整个应用被杀。
     */
    private fun readTextFrom(context: Context, uri: Uri): String {
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: error("打不开输入流")
        return input.use { readCapped(it) } ?: error("文件太大")
    }

    private fun readCapped(input: InputStream): String? {
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream()
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_IMPORT_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }

    /** 版本号，只用于文件里的 `appVersion` 那一行。取不到就空串，不影响其它字段。 */
    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)?.versionName.orEmpty()
    }.getOrDefault("")

    // ── 键名 ──────────────────────────────────────────────────────────

    private const val K_FORMAT = "format"
    private const val K_SCHEMA = "schema"
    private const val K_CREATED_AT = "createdAt"
    private const val K_APP_VERSION = "appVersion"
    private const val K_SOURCE_INSTALL_ID = "sourceInstallId"
    private const val K_SETTINGS = "settings"
    private const val K_LOOKS = "looks"
    private const val K_COLLECTIONS = "collections"
    private const val K_FAVORITES = "favorites"
    private const val K_HIDDEN = "hidden"
    private const val K_SEARCH = "searchHistory"
    private const val K_ID = "id"
    private const val K_NAME = "name"
    private const val K_PHOTO_IDS = "photoIds"
    private const val K_UPDATED_AT = "updatedAt"
}

/**
 * 一条待写入的设置。抽成值对象是为了让"类型不匹配就跳过"这件事能在 JVM 上单测
 * （写进 prefs 的那一步要 `Context`，测不到）。
 */
internal sealed interface SettingWrite {
    data class BoolValue(val key: SettingKey.Bool, val value: Boolean) : SettingWrite

    data class IntValue(val key: SettingKey.Int, val value: Int) : SettingWrite

    data class TextValue(val key: SettingKey.Text, val value: String) : SettingWrite

    /** 枚举按 **name** 走（星投影下拿不到 E，参见 `SettingChange.Choice` 的注释）。 */
    data class ChoiceName(val key: SettingKey.Choice<*>, val name: String) : SettingWrite

    data class StringSet(val key: SettingKey.Strings, val value: Set<String>) : SettingWrite
}
