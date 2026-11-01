package com.suipai.core.engine

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("EngineParams", exact = true)
class EngineParams(
    val look: Look,
    val intensity: Float,
    val w: Int,
    val h: Int,
    /**
     * 颗粒按**成片长边**调校，不是当前工作缓冲的边长。
     * 之前它叫 maxEdge 且被复用成缩放目标，于是预览（960–1600）永远吃 0.7× 细颗粒、
     * 成片永远 1.0× 混合颗粒，实测差约 43%——所见非所得。
     */
    val grainEdge: Int = 4096,
    val seed: Int = 0,
    val grainMul: Float = 1f,
    val vignetteMul: Float = 1f,
    val tempShift: Float = 0f,
    val contrastShift: Float = 0f,
    val satShift: Float = 0f,
    val shadowShift: Float = 0f,
    val fadeShift: Float = 0f,
    val highlightShift: Float = 0f,
    val softShift: Float = 0f,
    /**
     * 专业分级的三组可选算子。默认全是中性：默认路径一个分支都不进，
     * 因此 v2 配方与所有老调用点的输出**逐位不变**（有测试钉住）。
     */
    val curves: ToneCurves = ToneCurves(),
    val hsl: HslBands = HslBands(),
    val masks: List<LocalMask> = emptyList(),
) {
    val contrast: Float
    val sat: Float
    val temp: Float
    val roll: Float
    val lift: Float
    val blue: Float
    val warm: Float
    val grainAmt: Float
    val vig: Float
    val soft: Float
    val fade: Float
    val skinProtect: Float
    val cx: Float
    val cy: Float
    val maxR: Float
    val toneLut: FloatArray
    val grainSeed: Int
    val fineGrainOnly: Boolean
    val addChromaGrain: Boolean

    /**
     * 曲线：主通道与 R/G/B 三条通道的 LUT。
     *
     * 只在 [curves].hasAny 时非 null —— 中性时连 1024×4 个 Float 都不分配，
     * 这是「默认路径零开销」承诺里最容易被忽略的一半。
     */
    val curveMaster: FloatArray?
    val curveR: FloatArray?
    val curveG: FloatArray?
    val curveB: FloatArray?

    /** 局部蒙版权重场；无蒙版或全中性时为 null。 */
    val maskField: LocalMaskField?

    val hasHsl: Boolean

    /**
     * 曲线烘焙后的**输出表**：8 位输入 → 8 位输出。
     *
     * 曲线定义在显示域（gamma 编码后的 0..1），而主循环手里是线性值；
     * 如果把「线性 → sRGB → 曲线」逐像素做，就是一次 pow，1200 万像素要多花约 0.35 秒。
     * 这里在构造期把两种域之间的换算做完，热循环每像素每通道只剩一次数组读。
     *
     * 中性时为 null，主循环整段跳过 —— 这就是「零曲线时逐位不变」的来源。
     */
    val curveByteR: IntArray?
    val curveByteG: IntArray?
    val curveByteB: IntArray?

    init {
        val t = intensity.coerceIn(0f, 1f)
        contrast = look.contrast * t + contrastShift.coerceIn(-0.25f, 0.25f)
        sat = look.saturation * t + satShift.coerceIn(-0.35f, 0.35f)
        temp = look.temperature * t + tempShift.coerceIn(-0.25f, 0.25f)
        roll = (look.highlightRoll * t + highlightShift.coerceIn(-0.2f, 0.3f)).coerceAtLeast(0f)
        lift = (look.shadowLift * t + shadowShift.coerceIn(-0.2f, 0.25f)).coerceAtLeast(0f)
        blue = look.splitShadowBlue * t
        warm = look.splitHighlightWarm * t
        grainAmt = look.grain * t * grainMul.coerceIn(0f, 2f) * if (grainEdge <= 1600) 0.7f else 1f
        vig = look.vignette * t * vignetteMul.coerceIn(0f, 2f)
        soft = (look.softness * t + softShift.coerceIn(-0.15f, 0.25f)).coerceAtLeast(0f)
        fade = (look.fade * t + fadeShift.coerceIn(-0.2f, 0.3f)).coerceAtLeast(0f)
        skinProtect = look.skinProtect * t
        cx = (w - 1) / 2f
        cy = (h - 1) / 2f
        maxR = (cx * cx + cy * cy).let { if (it < 1f) 1f else sqrt(it).toFloat() }
        toneLut = FloatArray(1024) { i -> tone(i / 1023f, contrast, roll, lift) }
        // 不再把 w/h 混进种子：颗粒场已经按绝对 x/y 取值、天然与分辨率无关，
        // 混进去只会让同一配方在「不同内存的手机」上出不同的颗粒（exportEdge 按 maxMemory 分档）。
        grainSeed = seed
        fineGrainOnly = grainEdge <= 1280
        addChromaGrain = !look.monochrome && grainEdge > 1280

        curveMaster = if (curves.master?.isIdentity == false) curves.master.buildLut() else null
        curveR = if (curves.r?.isIdentity == false) curves.r.buildLut() else null
        curveG = if (curves.g?.isIdentity == false) curves.g.buildLut() else null
        curveB = if (curves.b?.isIdentity == false) curves.b.buildLut() else null
        hasHsl = hsl.hasAny
        maskField = if (masks.isEmpty()) null else LocalMaskField(w, h, masks).takeIf { it.hasAny }
        val bakedR = bakeByteTable(0)
        val bakedG = bakeByteTable(1)
        val bakedB = bakeByteTable(2)
        curveByteR = bakedR
        curveByteG = bakedG
        curveByteB = bakedB
    }

    /**
     * 把「主曲线 → 通道曲线」合成为一张 256 项的 8 位表（作用在 sRGB 字节上）。
     * 返回 null 表示该通道不需要专用表（两条曲线都是中性）。
     */
    private fun bakeByteTable(channel: Int): IntArray? {
        val m = curveMaster
        val ch = when (channel) {
            0 -> curveR
            1 -> curveG
            else -> curveB
        }
        if (m == null && ch == null) return null
        val out = IntArray(256)
        for (v in 0..255) {
            var c = v / 255f
            if (m != null) c = curveAt(m, c)
            if (ch != null) c = curveAt(ch, c)
            out[v] = (c.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        }
        return out
    }

    private fun curveAt(lut: FloatArray, v: Float): Float {
        val i = (v.coerceIn(0f, 1f) * 1023f + 0.5f).toInt()
        return lut[i]
    }
}

@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("ColorCore", exact = true)
object ColorCore {
    private val toLin = FloatArray(256).also { lut ->
        for (i in 0..255) {
            val c = i / 255f
            lut[i] = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }
    }
    private val toSrgb = IntArray(4096).also { lut ->
        for (i in 0..4095) {
            val c = i / 4095f
            val s = if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
            lut[i] = (s.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        }
    }

    fun process(pixels: IntArray, w: Int, h: Int, p: EngineParams) {
        processRange(pixels, w, h, 0, h, p)
    }

    /**
     * 颗粒种子的唯一算法。**两端都必须走这里**：iOS 之前自己写了个 FNV-1a 的 stableSeed，
     * 和 Kotlin 的 String.hashCode（31 进制 Int32 溢出累加）结果完全不同，
     * 于是同一配方在两台设备上长出两套颗粒。Kotlin/JVM 与 Kotlin/Native 的
     * String.hashCode 是同一份规范，所以这里直接用它即可跨端一致。
     */
    fun grainSeedFor(lookId: String): Int = lookId.hashCode()

    /** 处理像素数组的行区间 [yStart, yEnd)。ARGB int、行主序、宽 w。 */
    fun processRange(pixels: IntArray, w: Int, h: Int, yStart: Int, yEnd: Int, p: EngineParams) {
        val start = yStart.coerceIn(0, h)
        val end = yEnd.coerceIn(start, h)
        // 守卫：三组专业算子全中性时，一次分支都不进，走的是和 v2 完全相同的指令路径。
        // 主循环结束后再按需补算曲线/分色/蒙版（它们都定义在 8 位显示域，见各算子注释）。
        processBase(pixels, w, h, start, end, p)
        val maskField = p.maskField
        if (maskField != null && maskField.hasAny) {
            LocalMasks.applyRange(pixels, w, h, start, end, maskField)
        }
        if (p.hasHsl) {
            Hsl.applyRange(pixels, w, h, start, end, p.hsl)
        }
        if (p.curveByteR != null || p.curveByteG != null || p.curveByteB != null) {
            applyCurves(pixels, w, h, start, end, p)
        }
    }

    private fun processBase(pixels: IntArray, w: Int, h: Int, start: Int, end: Int, p: EngineParams) {
        val look = p.look
        val toneLut = p.toneLut
        for (y in start until end) {
            val rowBase = y * w
            for (x in 0 until w) {
                val i = rowBase + x
                val px = pixels[i]
                var r = toLin[(px shr 16) and 0xFF]
                var g = toLin[(px shr 8) and 0xFF]
                var b = toLin[px and 0xFF]
                val a = (px ushr 24) and 0xFF

                if (look.lockDaylightWb) {
                    r *= 1f + p.temp * 0.35f
                    b *= 1f - p.temp * 0.45f
                } else {
                    r *= 1f + p.temp
                    b *= 1f - p.temp
                }

                val y0 = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val yv = toneLut[(y0.coerceIn(0f, 1f) * 1023f + 0.5f).toInt()]
                val scale = if (y0 > 1e-5f) yv / y0 else 1f
                r *= scale
                g *= scale
                b *= scale

                if (look.monochrome) {
                    val mono = toneLut[
                        ((0.28f * r + 0.62f * g + 0.10f * b).coerceIn(0f, 1f) * 1023f + 0.5f).toInt(),
                    ]
                    r = mono
                    g = mono
                    b = mono
                } else {
                    val gray = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    r = lerp(gray, r, 1f + p.sat)
                    g = lerp(gray, g, 1f + p.sat)
                    b = lerp(gray, b, 1f + p.sat)
                    val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    r += p.warm * lum - p.blue * (1f - lum)
                    g += p.warm * lum * 0.4f
                    b += p.blue * (1f - lum) - p.warm * lum * 0.2f
                    if (p.soft > 0f) {
                        r = lerp(r, lum, p.soft * 0.35f)
                        g = lerp(g, lum, p.soft * 0.20f)
                        b = lerp(b, lum, p.soft * 0.35f)
                    }
                    if (p.skinProtect > 0f) {
                        val skin = skinWeight(r, g, b) * p.skinProtect
                        if (skin > 0f) {
                            r = lerp(r, lum * 1.03f, skin * 0.28f)
                            g = lerp(g, lum * 1.01f, skin * 0.18f)
                            b = lerp(b, lum * 0.97f, skin * 0.22f)
                        }
                    }
                }

                if (p.fade > 0f) {
                    val fog = (1f - yv).coerceIn(0f, 1f) * p.fade
                    r = r * (1f - fog) + fog * 0.14f
                    g = g * (1f - fog) + fog * 0.12f
                    b = b * (1f - fog) + fog * 0.10f
                }

                if (p.vig > 0f) {
                    val dx = (x - p.cx) / p.maxR
                    val dy = (y - p.cy) / p.maxR
                    val d = dx * dx + dy * dy
                    val falloff = 1f - p.vig * d.coerceIn(0f, 1f)
                    r *= falloff
                    g *= falloff
                    b *= falloff
                }

                if (p.grainAmt > 0f) {
                    val fine = hash01(x, y, p.grainSeed) - 0.5f
                    val n = if (p.fineGrainOnly) {
                        fine * p.grainAmt * (0.28f + 0.72f * (1f - yv).coerceIn(0f, 1f))
                    } else {
                        val coarse = hash01(x shr 1, y shr 1, p.grainSeed * 31) - 0.5f
                        (fine * 0.55f + coarse * 0.45f) *
                            p.grainAmt * (0.28f + 0.72f * (1f - yv).coerceIn(0f, 1f))
                    }
                    r += n
                    g += n
                    b += n
                    if (p.addChromaGrain) {
                        val c = (hash01(x, y, p.grainSeed + 97) - 0.5f) * p.grainAmt * 0.18f
                        r += c
                        b -= c * 0.6f
                    }
                }

                pixels[i] = (a shl 24) or
                    (toSrgbByte(r) shl 16) or
                    (toSrgbByte(g) shl 8) or
                    toSrgbByte(b)
            }
        }
    }

    private fun toSrgbByte(c: Float): Int {
        val i = (c.coerceIn(0f, 1f) * 4095f + 0.5f).toInt()
        return toSrgb[i]
    }

    /**
     * 曲线：对已经量化到 8 位的像素再卷一次。逐通道查 256 项，无浮点。
     *
     * 之所以放在 8 位域而不是线性域：曲线是用户在「显示」上拖的，
     * 放在线性域会让同一条 S 曲线在中暗部与高光的力度完全不成比例（用户会以为滑杆坏了）。
     * 构造期已经把「sRGB 编码 → 主曲线 → 通道曲线」烘成 256 项，
     * 所以这里每像素每通道只有一次数组读。
     */
    private fun applyCurves(pixels: IntArray, w: Int, h: Int, start: Int, end: Int, p: EngineParams) {
        val lutR = p.curveByteR
        val lutG = p.curveByteG
        val lutB = p.curveByteB
        for (y in start until end) {
            val rowBase = y * w
            for (x in 0 until w) {
                val i = rowBase + x
                val px = pixels[i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val nr = lutR?.get(r) ?: r
                val ng = lutG?.get(g) ?: g
                val nb = lutB?.get(b) ?: b
                if (nr == r && ng == g && nb == b) continue
                pixels[i] = (px and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }
}

private fun skinWeight(r: Float, g: Float, b: Float): Float {
    val sum = r + g + b
    if (sum < 0.12f) return 0f
    val rn = r / sum
    val gn = g / sum
    val bn = b / sum
    val inRange = rn > 0.36f && gn in 0.26f..0.38f && bn < 0.32f && r > g && g >= b * 0.85f
    if (!inRange) return 0f
    return (1f - abs(gn - 0.32f) * 8f).coerceIn(0f, 1f)
}

private fun tone(y: Float, contrast: Float, roll: Float, lift: Float): Float {
    var v = y.coerceIn(0f, 1f)
    v += lift * (1f - v) * (1f - v)
    val centered = v - 0.5f
    v = (0.5f + centered * (1f + contrast) * (1f - 0.35f * abs(centered))).coerceIn(0f, 1f)
    val highlight = 1f - (1f - v).pow(1f + roll)
    return lerp(v, highlight, roll.coerceIn(0f, 1f))
}

private fun hash01(x: Int, y: Int, seed: Int): Float {
    var n = x * 374761393 + y * 668265263 + seed
    n = (n xor (n ushr 13)) * 1274126177
    n = n xor (n ushr 16)
    return (n and 0x7fffffff) / 2147483647f
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
