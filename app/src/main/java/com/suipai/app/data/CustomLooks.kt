package com.suipai.app.data

import com.suipai.core.engine.HslBands
import com.suipai.core.engine.LocalAdjust
import com.suipai.core.engine.LocalMask
import com.suipai.core.engine.LinearMask
import com.suipai.core.engine.Look
import com.suipai.core.engine.LookLibrary
import com.suipai.core.engine.RadialMask
import com.suipai.core.engine.RecipeCodec
import com.suipai.core.engine.RecipeCurves
import com.suipai.core.engine.RecipeHsl
import com.suipai.core.engine.RecipeMask
import com.suipai.core.engine.RecipePro
import com.suipai.core.engine.ToneCurves
import com.suipai.core.engine.YishunRecipe
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一条用户自建配方。
 *
 * 与 [YishunRecipe] 的关系是「超集」：`lookId` + 7 根偏移就是 v2 配方的全部内容，
 * 曲线/HSL/蒙版是 v3 的增量。分成两个类型而不是一个，是因为两者的生命周期不同：
 * 配方跟着照片走（EXIF，可能被别的相册读到，必须极简且版本严格），
 * 自定义配方只活在本机（可以带名字、时间戳、排序这些本地元数据）。
 */
@Serializable
data class CustomLook(
    /** `user_<时间戳>`。前缀是硬的：`asLooks()` 靠它把用户配方和内置库分开。 */
    val id: String,
    val name: String,
    /** 基础配方 id；找不到时回落到 [LookLibrary.daylight]。 */
    val baseLookId: String,
    val intensity: Float = 0.55f,
    val temp: Float = 0f,
    val contrast: Float = 0f,
    val sat: Float = 0f,
    val shadow: Float = 0f,
    val fade: Float = 0f,
    val highlight: Float = 0f,
    val soft: Float = 0f,
    /** 曲线（扁平控制点）；null = 不动。 */
    val curve: RecipeCurves? = null,
    val hsl: RecipeHsl? = null,
    val masks: List<RecipeMask>? = null,
    val created: Long = 0L,
    val modified: Long = 0L,
) {
    val curves: ToneCurves get() = curve?.toToneCurves() ?: ToneCurves()

    val bands: HslBands get() = hsl?.toHslBands() ?: HslBands.NONE

    val localMasks: List<LocalMask>
        get() = masks?.mapNotNull { it.toLocalMask() }?.take(4) ?: emptyList()

    /** 引擎参数里那 7 根偏移，按 `Shifts` 的字段顺序组装。 */
    fun shifts(): com.suipai.app.engine.Shifts = com.suipai.app.engine.Shifts(
        temp = temp,
        contrast = contrast,
        sat = sat,
        shadow = shadow,
        fade = fade,
        highlight = highlight,
        soft = soft,
    )

    /** 导出成 [YishunRecipe]（v3），用来写 EXIF。 */
    fun asRecipe(): YishunRecipe = RecipePro.withPro(
        YishunRecipe(
            lookId = baseLookId,
            intensity = intensity.coerceIn(0f, 1f),
            tempShift = temp,
            contrastShift = contrast,
            satShift = sat,
            shadowShift = shadow,
            fadeShift = fade,
            highlightShift = highlight,
            softShift = soft,
        ),
        curves,
        bands,
        localMasks,
    )

    companion object {
        /** id 前缀：`asLooks()` 与 UI 都按它判「这是不是用户自己存的」。 */
        const val ID_PREFIX = "user_"

        /**
         * 由时间戳造 id。**纯函数**，给定 [now] 结果固定（测试 `newIdUsesTimestampAndPrefix` 钉着）。
         *
         * 它**不保证唯一**：同一毫秒调两次会得到同一个 id。需要"连续两次操作必须拿到
         * 两个 id"的地方（导入、粘贴同一条配方两次）走 [freshId]，见那里的注释。
         */
        fun newId(now: Long = System.currentTimeMillis()): String = ID_PREFIX + now

        /**
         * 进程内**单调**的最后一次发号时间，用来挡住"同一毫秒撞号"。
         *
         * 为什么需要它：`CustomLooksTest.importedItemGetsFreshIdAndTimestamps` 连着导入
         * 同一段文本两次，两次 `System.currentTimeMillis()` 落在同一毫秒 —— 纯时间戳 id
         * 会撞，于是第二次导入的 id 与第一次相同，紧接着的 `save()` 按 id 判"这是覆盖"
         * 就把第一条冲掉了。用户看到的现象是「导入第二条，像什么都没发生」。
         *
         * 为什么不用 `taken` 集合去重：`importText` 只解析、**不落盘**，所以"磁盘上已有的 id"
         * 挡不住这一段（拿到的 taken 里没有刚解析出的那条）。而且 UI 也可能先连着解析两条
         * 再一次保存。进程内单调计数对这两种用法都成立，并且不依赖 IO。
         *
         * 为什么仍然以时间戳为基底：id 形状是 `user_<毫秒>`，人肉看过、`asLooks()` 认前缀、
         * 老文件里全是这个形状。保持形状不变，只是保证**严格递增**。
         */
        private val idClock = java.util.concurrent.atomic.AtomicLong(0L)

        /**
         * 发一个保证**本进程内不重复**的 id。
         *
         * 实现是"取 `max(now, 上次发号+1)`"：正常情况就是当前毫秒；同一毫秒内连发多次时
         * 依次 +1（`user_1730000000000`、`user_1730000000001`…）。这样 id 仍然可读、
         * 仍然单调递增（顺带让"最近修改"排序在极端情况下也不会打平）。
         *
         * @param now 可注入，测试用它验证"同一毫秒两次必须拿到两个 id"。
         */
        fun freshId(now: Long = System.currentTimeMillis()): String {
            // 取 max 是原子的：CAS 循环自己保证不会有两个线程拿到同一个值。
            var prev = idClock.get()
            while (true) {
                val next = if (now > prev) now else prev + 1
                if (idClock.compareAndSet(prev, next)) return ID_PREFIX + next
                prev = idClock.get()
            }
        }

        fun from(
            name: String,
            baseLookId: String,
            intensity: Float,
            shifts: com.suipai.app.engine.Shifts,
            curves: ToneCurves = ToneCurves(),
            hsl: HslBands = HslBands.NONE,
            masks: List<LocalMask> = emptyList(),
            id: String? = null,
            created: Long = 0L,
            modified: Long = 0L,
            now: Long = System.currentTimeMillis(),
        ): CustomLook = CustomLook(
            // 不传 id = "新建一条"，必须走 [freshId]：同一毫秒连存两条（批量保存、
            // 连点两次「存配方」）用 [newId] 会撞号，第二次 `save()` 按 id 当覆盖，
            // 用户第一次存的那条就没了。
            id = id ?: freshId(now),
            name = name,
            baseLookId = baseLookId,
            intensity = intensity,
            temp = shifts.temp,
            contrast = shifts.contrast,
            sat = shifts.sat,
            shadow = shifts.shadow,
            fade = shifts.fade,
            highlight = shifts.highlight,
            soft = shifts.soft,
            curve = RecipeCurves.from(curves),
            hsl = RecipeHsl.from(hsl),
            masks = masks.take(4).map { RecipeMask.from(it) }.takeIf { it.isNotEmpty() },
            created = if (created == 0L) now else created,
            modified = if (modified == 0L) now else modified,
        )
    }
}

/**
 * 自定义配方的本地仓库：`filesDir/custom_looks.json`。
 *
 * 为什么不进 SharedPreferences：一份配方带上曲线/分色/蒙版之后是几 KB 的嵌套结构，
 * 塞进 prefs 会让整个 prefs 文件在每次收藏/隐藏变更时被一起重写（那两份清单已经
 * 因为这个问题搬过一次家，见 [IdLists] 的注释）。而且 prefs 是要备份的，
 * 自定义配方里有地理位置相关的蒙版参数，不该默认上云——见两个备份规则文件里的排除项。
 *
 * 线程模型：所有公开方法都是同步的普通函数，调用点自己切 IO。
 * 本类不缓存：配方总量以十计，读一次文件几毫秒，而缓存会让「另一个进程刚写完」
 * 的场景读到旧值（这个 App 有只在设置页开一个进程的历史）。
 */
@OptIn(ExperimentalEncodingApi::class)
class CustomLooks(private val file: File) {

    constructor(context: android.content.Context) : this(File(context.filesDir, FILE_NAME))

    /** 全部配方，按修改时间倒序（最近改过的先出现）。读盘/解析失败返回空表，不抛。 */
    fun all(): List<CustomLook> {
        val text = runCatching { if (file.isFile) file.readText() else null }.getOrNull() ?: return emptyList()
        val parsed = runCatching { json.decodeFromString(Store.serializer(), text) }.getOrNull()
            ?: return emptyList()
        return parsed.items
            .filter { it.id.startsWith(CustomLook.ID_PREFIX) }
            .sortedByDescending { it.modified }
    }

    fun byId(id: String): CustomLook? = all().firstOrNull { it.id == id }

    /**
     * 新建或覆盖（按 id 判）。返回落盘后的整份列表。
     *
     * 同一个 id 再次保存时保留 `created`、刷新 `modified`：UI 上「最近修改」排序
     * 靠的正是它，而覆盖时把创建时间也刷掉会让「我什么时候存的」永久丢失。
     */
    fun save(look: CustomLook): List<CustomLook> {
        val current = all().toMutableList()
        val now = look.modified.takeIf { it > 0L } ?: System.currentTimeMillis()
        val idx = current.indexOfFirst { it.id == look.id }
        // 覆盖时**以磁盘上的 created 为准**：调用方常常是从 UI 现场拼一个对象出来，
        // 它手里的 created 可能是 0、也可能被默认填成"现在"，直接采信会把
        // "我什么时候存的"永久丢光（这正是这条测试最初红的那个 bug）。
        val existing = if (idx >= 0) current[idx] else null
        val normalized = look.copy(
            // 带 `user_` 前缀的 id 是"覆盖那一条"，必须原样保留；
            // 裸 id（调用方手拼的、或从别的类型借来的）重新发号 —— 这里以前是
            // `ID_PREFIX + now`，同一毫秒连存两条会撞号，于是第二条把第一条冲掉。
            id = if (look.id.startsWith(CustomLook.ID_PREFIX)) look.id else CustomLook.freshId(now),
            created = existing?.created ?: look.created.takeIf { it > 0L } ?: now,
            modified = now,
            masks = look.masks?.take(4)?.takeIf { it.isNotEmpty() },
        )
        if (idx >= 0) current[idx] = normalized else current.add(normalized)
        write(current)
        return current.sortedByDescending { it.modified }
    }

    fun delete(id: String): List<CustomLook> {
        val current = all().filterNot { it.id == id }
        write(current)
        return current
    }

    fun rename(id: String, name: String): List<CustomLook> {
        val current = all().map {
            if (it.id == id) it.copy(name = name, modified = System.currentTimeMillis()) else it
        }
        write(current)
        return current.sortedByDescending { it.modified }
    }

    /**
     * 导出成可复制粘贴的文本：`SPLOOK1:` + Base64Url(JSON UTF-8, 无 padding)。
     *
     * 与 [RecipeCodec] 的 `SPRECIPE1:` 分开一个前缀，是因为两者语义不同：
     * 配方是「怎么调这张照片」，自定义配方还带名字与时间。混用前缀会让导入方
     * 拿到一条能解但缺字段的 payload，然后在 UI 上显示成一条空名字配方。
     */
    fun exportText(look: CustomLook): String =
        MARKER + Base64.UrlSafe
            .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
            .encode(json.encodeToString(CustomLook.serializer(), look).encodeToByteArray())

    /**
     * 导入文本。解析失败返回 null（**不崩**）—— 这个字符串来自剪贴板，
     * 可能是半截的、带富文本标记的、或者别的 App 的链接。
     *
     * 两条硬保证：
     * 1. **payload 里的 id 一律丢掉**，用 [CustomLook.freshId] 重新发一个。
     *    沿用它就意味着"导入一条别人的配方，正好顶掉本机同 id 的那条"；
     *    而且导出方与导入方撞号几乎必然（同一个 App、同一毫秒形状）。
     * 2. **连着导入两次必须得到两条**：`freshId` 在本进程内严格递增，
     *    同一毫秒也不会撞。这是这条测试 `importedItemGetsFreshIdAndTimestamps`
     *    最初红的原因（纯时间戳 id 撞号 → `save()` 按 id 当覆盖 → 第二条静默吃掉第一条）。
     *
     * 名字为空则给一个可读的默认名。
     */
    fun importText(text: String): CustomLook? {
        val start = text.indexOf(MARKER)
        if (start < 0) return null
        // 只按 Base64Url 字母表收口：剪贴板里常见尾部跟一句中文说明，
        // 按「非空白」收口会把说明拼进 payload，整条解不出来。
        var end = start + MARKER.length
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '-' || text[end] == '_' || text[end] == '=')) end++
        if (end == start + MARKER.length) return null
        val imported = try {
            val raw = Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                .decode(text.substring(start + MARKER.length, end))
            json.decodeFromString(CustomLook.serializer(), raw.decodeToString())
        } catch (_: Exception) {
            return null
        }
        val now = System.currentTimeMillis()
        val name = imported.name.trim().ifEmpty {
            "导入配方"
        }
        return imported.copy(
            // 丢掉 payload 里的 id，重新发号（见上面两条硬保证）。
            id = CustomLook.freshId(now),
            name = name,
            created = now,
            modified = now,
            masks = imported.masks?.take(4)?.takeIf { it.isNotEmpty() },
        )
    }

    /** 用户配方 → [Look]，id 前缀 `user_`，可以直接进现有的配方选择器。 */
    fun asLooks(): List<Look> = all().map { it.asLook() }

    private fun write(items: List<CustomLook>) {
        runCatching {
            file.parentFile?.mkdirs()
            // 先写临时文件再改名：写到一半被杀掉时不会留下半截 JSON，
            // 否则下一次 all() 解析失败会把整库静默丢光。
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(Store.serializer(), Store(items)))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    @Serializable
    private data class Store(val items: List<CustomLook> = emptyList())

    companion object {
        const val FILE_NAME = "custom_looks.json"

        /** 独立的传输前缀，理由见 [exportText]。 */
        const val MARKER = "SPLOOK1:"

        @OptIn(ExperimentalSerializationApi::class)
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            allowStructuredMapKeys = true
        }
    }
}

/** [CustomLook] → [Look]：名字加「我的」后缀，id 加前缀，monochrome 继承基础配方。 */
fun CustomLook.asLook(): Look {
    val base = LookLibrary.byId(baseLookId) ?: LookLibrary.daylight
    return base.copy(
        id = id,
        name = name,
        summary = "我的配方 · 基于「${base.name}」，强度 ${(intensity * 100).toInt()}%",
        contrast = base.contrast,
        saturation = base.saturation,
        temperature = base.temperature,
        defaultIntensity = intensity,
    )
}

/**
 * 从 v3 配方反向构造一条自定义配方（用于「把照片里的配方存成我的」）。
 *
 * id 用 [CustomLook.freshId] 而不是 `newId`：连存两条（或一次批量存多张照片的配方）
 * 会落在同一毫秒，纯时间戳 id 撞号之后第二次 `save()` 就把第一条冲掉了 ——
 * 与 `importText` 是同一个坑。
 */
fun CustomLook.Companion.fromRecipe(
    name: String,
    recipe: YishunRecipe,
    now: Long = System.currentTimeMillis(),
): CustomLook = CustomLook(
    id = CustomLook.freshId(now),
    name = name,
    baseLookId = recipe.lookId,
    intensity = recipe.intensity,
    temp = recipe.tempShift,
    contrast = recipe.contrastShift,
    sat = recipe.satShift,
    shadow = recipe.shadowShift,
    fade = recipe.fadeShift,
    highlight = recipe.highlightShift,
    soft = recipe.softShift,
    curve = recipe.curve,
    hsl = recipe.hsl,
    masks = recipe.masks,
    created = now,
    modified = now,
)
