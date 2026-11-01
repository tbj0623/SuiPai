package com.suipai.core

import com.suipai.core.engine.ColorCore
import com.suipai.core.engine.EngineParams
import com.suipai.core.engine.Look
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 钉住「同一配方在任何一端出同一张图」这条产品承诺所依赖的不变式。
 * 逐像素主路径只有 IEEE single 的乘加和截断，所以只要种子和调校边长一致，结果就该逐位一致。
 */
class EngineDeterminismTest {

    private fun look(
        mono: Boolean = false,
        grain: Float = 0.3f,
    ) = Look(
        id = "probe",
        name = "探针",
        summary = "探针",
        contrast = 0.12f,
        saturation = 0.06f,
        temperature = 0.04f,
        highlightRoll = 0.18f,
        shadowLift = 0.06f,
        splitShadowBlue = 0.05f,
        splitHighlightWarm = 0.07f,
        grain = grain,
        vignette = 0.14f,
        fade = 0.05f,
        softness = 0.04f,
        skinProtect = 0.1f,
        monochrome = mono,
    )

    private fun params(
        w: Int,
        h: Int,
        grainEdge: Int = 4096,
        seed: Int = 12345,
        mono: Boolean = false,
    ) = EngineParams(
        look = look(mono = mono),
        intensity = 0.6f,
        w = w,
        h = h,
        grainEdge = grainEdge,
        seed = seed,
    )

    private fun pixels(w: Int, h: Int, seed: Int): IntArray {
        val rnd = Random(seed)
        return IntArray(w * h) { rnd.nextInt() }
    }

    // ---- 种子（审计 B2 / B3）------------------------------------------------

    @Test
    fun grainSeedFollowsJavaStringHashContract() {
        // 这张表是按 java.lang.String.hashCode 的规范（31 进制 Int32 溢出累加）独立算出来的。
        // iOS 曾经自己写了个 FNV-1a 的 stableSeed，值完全不同，于是同一配方两端两套颗粒。
        // 任何一端改哈希算法都会让这条红。
        assertEquals(1942983418, ColorCore.grainSeedFor("daylight"))
        assertEquals(729267099, ColorCore.grainSeedFor("portrait"))
        assertEquals(3143044, ColorCore.grainSeedFor("film"))
        assertEquals(-1666004444, ColorCore.grainSeedFor("ri_guang"))
        assertEquals(533638262, ColorCore.grainSeedFor("zhuangyuanhong2"))
    }

    @Test
    fun grainSeedDoesNotDependOnWorkingResolution() {
        // 之前是 seed xor (w*31 + h)，而 w/h 来自按设备 maxMemory() 分档的 exportEdge，
        // 于是同一配方在不同内存的手机上颗粒不一样。
        val small = params(w = 320, h = 240)
        val large = params(w = 4096, h = 3072)
        assertEquals(small.grainSeed, large.grainSeed)
        val other = params(w = 320, h = 240, seed = 999)
        assertNotEquals(small.grainSeed, other.grainSeed)
    }

    // ---- 颗粒调校边长（审计 M1）---------------------------------------------

    @Test
    fun grainTuningFollowsGrainEdgeNotWorkingSize() {
        // 预览缓冲很小（1280），但成片是 4096。颗粒必须按成片调校，
        // 否则预览永远 0.7x 细颗粒、成片永远 1.0x 混合颗粒（实测差约 43%）。
        val previewSizedForBigPrint = params(w = 1280, h = 960, grainEdge = 4096)
        assertFalse(previewSizedForBigPrint.fineGrainOnly)
        assertTrue(previewSizedForBigPrint.addChromaGrain)

        val smallPrint = params(w = 1280, h = 960, grainEdge = 1280)
        assertTrue(smallPrint.fineGrainOnly)
        assertFalse(smallPrint.addChromaGrain)

        // 同一个 grainEdge 下，工作缓冲大小不得改变颗粒强度
        val a = params(w = 64, h = 48, grainEdge = 4096)
        val b = params(w = 4096, h = 4096, grainEdge = 4096)
        assertEquals(a.grainAmt, b.grainAmt)
    }

    @Test
    fun chromaGrainNeverAppliedToMonochrome() {
        assertFalse(params(w = 64, h = 48, mono = true, grainEdge = 4096).addChromaGrain)
    }

    // ---- 行带切分的纯度与并发（审计 Q1）-------------------------------------

    @Test
    fun chunkedProcessMatchesFullProcessForEveryBandSize() {
        // 粗颗粒按 2x2 格子取样，所以**奇数**行带边界才会暴露跨带问题。
        // 老测试只覆盖 16/32/48 这类偶数边界，等于没测到点子上。
        val w = 37
        val h = 23
        val p = params(w = w, h = h)
        val reference = pixels(w, h, 5).also { ColorCore.process(it, w, h, p) }

        for (chunk in 1..h + 3) {
            val got = pixels(w, h, 5)
            var y = 0
            while (y < h) {
                ColorCore.processRange(got, w, h, y, minOf(h, y + chunk), p)
                y += chunk
            }
            assertContentEquals(reference, got, "chunk=$chunk 的结果和整图处理不一致")
        }
    }

    @Test
    fun outOfOrderBandProcessingStillMatches() {
        val w = 41
        val h = 17
        val p = params(w = w, h = h)
        val reference = pixels(w, h, 6).also { ColorCore.process(it, w, h, p) }

        val got = pixels(w, h, 6)
        // 倒序、乱序处理行带：行带之间必须真的互不依赖
        for (y in h - 1 downTo 0) ColorCore.processRange(got, w, h, y, y + 1, p)
        assertContentEquals(reference, got)
    }

    // ---- 金标准向量（审计 T1）----------------------------------------------

    @Test
    fun processMatchesGoldenDigest() {
        // 固定输入 + 固定参数 -> 固定摘要。JVM 与 Native 都跑这一条，
        // 两端一旦在算术、LUT 的 pow 舍入或截断上分叉，立刻在这里红。
        val w = 8
        val h = 8
        val p = EngineParams(
            look = Look(
                id = "daylight",
                name = "日光",
                summary = "户外与旅行。暖光留下，阴影可以偏冷。",
                contrast = 0.14f,
                saturation = 0.08f,
                temperature = 0.06f,
                highlightRoll = 0.20f,
                shadowLift = 0.08f,
                splitShadowBlue = 0.06f,
                splitHighlightWarm = 0.10f,
                grain = 0.10f,
                vignette = 0.12f,
                fade = 0.03f,
            ),
            intensity = 0.55f,
            w = w,
            h = h,
            grainEdge = 4096,
            seed = ColorCore.grainSeedFor("daylight"),
        )
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or ((x * 31 + 8) shl 16) or ((y * 29 + 40) shl 8) or ((x * y * 13 + 60))
        }
        ColorCore.process(px, w, h, p)

        var digest = 0
        for (v in px) digest = (digest * 1000003) xor v
        assertEquals(GOLDEN_DIGEST, digest, "逐像素结果变了：要么算术分叉了，要么记得同步更新金标准并说明理由")
    }

    private companion object {
        /** 由本用例在 JVM 上产出；Kotlin/Native 必须给出同一个值，否则说明算术或 LUT 舍入分叉了。 */
        const val GOLDEN_DIGEST = 1235174103
    }
}
