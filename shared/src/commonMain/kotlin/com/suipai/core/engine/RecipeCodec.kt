package com.suipai.core.engine

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 一瞬配方：随照片 EXIF UserComment 走的调色参数，双端通用。
 * 编码格式：SPRECIPE1: + Base64Url(JSON UTF-8)。
 *
 * 注意没有 seed：颗粒种子由 [ColorCore.grainSeedFor]（lookId 的 hashCode）单点决定，
 * 放进配方只会让人以为它可以独立设定——它以前就从来没被回读过。
 */
@OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlin.experimental.ExperimentalObjCName::class,
)
@Serializable
@kotlin.native.ObjCName("YishunRecipe", exact = true)
data class YishunRecipe(
    /** 写出去时总是带上（见 @EncodeDefault），这样老 App 读到超出自己认得的版本能整条拒绝而不是错色。 */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val v: Int = CURRENT_VERSION,
    val lookId: String,
    val intensity: Float,
    val tempShift: Float = 0f,
    val contrastShift: Float = 0f,
    val satShift: Float = 0f,
    val shadowShift: Float = 0f,
    val fadeShift: Float = 0f,
    val highlightShift: Float = 0f,
    val softShift: Float = 0f,
    val grainMul: Float = 1f,
    val vignetteMul: Float = 1f,
    /**
     * 成片长边。颗粒是按成片长边调校的（见 [EngineParams.grainEdge]），
     * 不记它就没法在另一台内存不同、因而 exportEdge 分档不同的机器上还原出同一份颗粒。
     */
    val exportEdge: Int = 4096,
    /**
     * v3 新增：调色曲线。null / 空对象 = 不改。
     *
     * 序列化用 `encodeDefaults = false`，所以 v2 配方解出来这些字段就是 null——
     * v2 的 14 个字段语义**一个都没动**，老配方读进来的结果与 v2 时代逐位相同。
     */
    val curve: RecipeCurves? = null,
    /** v3 新增：HSL 分色。null = 8 带全 0。 */
    val hsl: RecipeHsl? = null,
    /** v3 新增：局部蒙版。null = 无蒙版。超过 4 个在读取时截断。 */
    val masks: List<RecipeMask>? = null,
) {
    companion object {
        const val CURRENT_VERSION = 3

        /** v2 时代的字段数，测试用它钉「v2 语义不许动」。 */
        const val V2_FIELD_COUNT = 14
    }
}

/**
 * 曲线在配方里的表示：控制点数组，扁平存成 `[x0,y0,x1,y1,…]`。
 *
 * 为什么不用对象数组：`CurvePoint` 是 data class，序列化后每个点要 4 个引号加 2 个键，
 * 16 个点的配方会膨胀到 base64 里放不进 EXIF 的单字段预算（64 KB 上限下其实放得下，
 * 但 UserComment 越短越不容易被别的工具截断）。扁平 FloatArray 也方便 iOS 侧直读。
 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@Serializable
@kotlin.native.ObjCName("RecipeCurves", exact = true)
data class RecipeCurves(
    val master: List<Float>? = null,
    val r: List<Float>? = null,
    val g: List<Float>? = null,
    val b: List<Float>? = null,
) {
    fun toToneCurves(): ToneCurves = ToneCurves(
        master = master?.toCurve(),
        r = r?.toCurve(),
        g = g?.toCurve(),
        b = b?.toCurve(),
    )

    companion object {
        fun from(c: ToneCurves): RecipeCurves? {
            if (c.isEmpty) return null
            return RecipeCurves(
                master = c.master?.flatten(),
                r = c.r?.flatten(),
                g = c.g?.flatten(),
                b = c.b?.flatten(),
            )
        }

        private fun ToneCurve.flatten(): List<Float> =
            points.flatMap { listOf(it.x, it.y) }

        /** 扁平数组 → [ToneCurve]；长度非法（奇数 / 少于 4）时返回 null 而不是抛。 */
        private fun List<Float>.toCurve(): ToneCurve? {
            if (size < 4 || size % 2 != 0) return null
            val pts = ArrayList<CurvePoint>(size / 2)
            var i = 0
            while (i + 1 < size) {
                pts += CurvePoint(this[i], this[i + 1])
                i += 2
            }
            return ToneCurve(pts)
        }
    }
}

/** HSL 分色在配方里的表示：8 带 × 3 偏移，按 [HslBands.HUES] 的顺序。 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@Serializable
@kotlin.native.ObjCName("RecipeHsl", exact = true)
data class RecipeHsl(
    val bands: List<Float> = emptyList(),
) {
    fun toHslBands(): HslBands {
        if (bands.size < HslBands.BAND_COUNT * 3) return HslBands.NONE
        var out = HslBands.NONE
        for (b in 0 until HslBands.BAND_COUNT) {
            val i = b * 3
            out = out.withBand(b, HslBand(bands[i], bands[i + 1], bands[i + 2]))
        }
        return out
    }

    companion object {
        fun from(h: HslBands): RecipeHsl? {
            if (!h.hasAny) return null
            val list = h.bands()
            val flat = ArrayList<Float>(HslBands.BAND_COUNT * 3)
            for (b in list) {
                flat += b.hueC
                flat += b.satC
                flat += b.lumC
            }
            return RecipeHsl(flat)
        }
    }
}

/**
 * 单个蒙版在配方里的表示。
 *
 * `kind` 是 "linear" / "radial"，其余参数按形状取用；未知 kind 在读取时整条丢弃
 * （不抛、不猜）——宁可少一个蒙版，也不要拿线性参数去画一个径向。
 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@Serializable
@kotlin.native.ObjCName("RecipeMask", exact = true)
data class RecipeMask(
    val kind: String,
    /** 线性：angle/start/end；径向：cx/cy/rx/ry/feather。 */
    val p: List<Float> = emptyList(),
    val inverted: Boolean = false,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
) {
    // 具名函数 + when 作为表达式时不许在分支里 `return`（Kotlin 会报
    // "Returns are prohibited for functions with an expression body"）。
    // 所以这里用 `if/else` 求值成 null，而不是提前返回。
    fun toLocalMask(): LocalMask? = when (kind) {
        KIND_LINEAR -> if (p.size < 3) null else LocalMask(
            LinearMask(angle = p[0], start = p[1], end = p[2]),
            LocalAdjust(exposure, contrast, saturation, temperature),
        )
        KIND_RADIAL -> if (p.size < 5) null else LocalMask(
            RadialMask(cx = p[0], cy = p[1], rx = p[2], ry = p[3], feather = p[4], inverted = inverted),
            LocalAdjust(exposure, contrast, saturation, temperature),
        )
        else -> null
    }

    companion object {
        const val KIND_LINEAR = "linear"
        const val KIND_RADIAL = "radial"

        fun from(mask: LocalMask): RecipeMask {
            val a = mask.adjust
            return when (val s = mask.shape) {
                is LinearMask -> RecipeMask(
                    kind = KIND_LINEAR,
                    p = listOf(s.angle, s.start, s.end),
                    exposure = a.exposureC,
                    contrast = a.contrastC,
                    saturation = a.saturationC,
                    temperature = a.temperatureC,
                )
                is RadialMask -> RecipeMask(
                    kind = KIND_RADIAL,
                    p = listOf(s.cx, s.cy, s.rx, s.ry, s.feather),
                    inverted = s.inverted,
                    exposure = a.exposureC,
                    contrast = a.contrastC,
                    saturation = a.saturationC,
                    temperature = a.temperatureC,
                )
            }
        }
    }
}

/** 配方 ↔ 引擎参数的三组算子。空/中性时一律返回 null，保证 v2 配方里不会多出键。 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("RecipePro", exact = true)
object RecipePro {
    fun curvesOf(recipe: YishunRecipe): ToneCurves = recipe.curve?.toToneCurves() ?: ToneCurves()

    fun hslOf(recipe: YishunRecipe): HslBands = recipe.hsl?.toHslBands() ?: HslBands.NONE

    fun masksOf(recipe: YishunRecipe): List<LocalMask> =
        recipe.masks?.mapNotNull { it.toLocalMask() }?.take(LocalMaskField.MAX_MASKS) ?: emptyList()

    /** 把三组算子塞进配方（全部中性时对应字段保持 null，配方仍是合法的 v2 形状）。 */
    fun withPro(
        base: YishunRecipe,
        curves: ToneCurves = ToneCurves(),
        hsl: HslBands = HslBands(),
        masks: List<LocalMask> = emptyList(),
    ): YishunRecipe = base.copy(
        v = YishunRecipe.CURRENT_VERSION,
        curve = RecipeCurves.from(curves),
        hsl = RecipeHsl.from(hsl),
        masks = masks.take(LocalMaskField.MAX_MASKS)
            .map { RecipeMask.from(it) }
            .takeIf { it.isNotEmpty() },
    )
}

@OptIn(
    ExperimentalEncodingApi::class,
    ExperimentalSerializationApi::class,
    kotlin.experimental.ExperimentalObjCName::class,
)
@kotlin.native.ObjCName("RecipeCodec", exact = true)
object RecipeCodec {
    const val MARKER = "SPRECIPE1:"

    /**
     * EXIF UserComment 的 8 字节字符码（EXIF 2.32 §4.6.4）。
     *
     * androidx 的 ExifInterface 不会自己补这个码，而它读的时候又是从 offset 0 宽容解析的，
     * 所以**只有我们能把没带码的写法读回来**：iOS ImageIO / exiftool / 别的相册会把前 8 个字节
     * 当成字符码吃掉，配方就跨端失效了。放在 shared 是因为两端都要遵守同一个约定。
     *
     * 别改用 "UNICODE\0"：androidx 会把它按 ASCII 落盘、读回来只剩 "UNICODE"。
     */
    const val EXIF_ASCII_CHARSET = "ASCII\u0000\u0000\u0000"

    /** Base64Url 无 padding 的字母表，外加容忍写入方补的 '='。 */
    private const val BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_="

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(recipe: YishunRecipe): String {
        val raw = json.encodeToString(YishunRecipe.serializer(), recipe).encodeToByteArray()
        return MARKER + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).encode(raw)
    }

    /**
     * 直接写进 `ExifInterface.TAG_USER_COMMENT` 的完整值：字符码 + [encode]。
     * 两端都只走这个函数，避免又有一端忘了带码。
     */
    fun encodeForExif(recipe: YishunRecipe): String = EXIF_ASCII_CHARSET + encode(recipe)

    fun decode(text: String): YishunRecipe? {
        val start = text.indexOf(MARKER)
        if (start < 0) return null
        var end = start + MARKER.length
        // 只按 Base64Url 字母表收口。之前判的是「非空白且非引号」，NUL 不算空白，
        // 所以任何补零的 EXIF 写入方都会把垃圾拼进 payload，整条配方直接解不出来。
        while (end < text.length && text[end] in BASE64URL) end++
        if (end == start + MARKER.length) return null
        return try {
            val raw = Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                .decode(text.substring(start + MARKER.length, end))
            val recipe = json.decodeFromString(YishunRecipe.serializer(), raw.decodeToString())
            // 认不出的新版本一律整条拒绝：宁可提示「没有配方」，也不要拿 1.0 的语义去解 3.0 静默错色。
            if (recipe.v > YishunRecipe.CURRENT_VERSION || recipe.v < 1) null else recipe
        } catch (_: Exception) {
            null
        }
    }
}
