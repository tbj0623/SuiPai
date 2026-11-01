package com.suipai.core

import com.suipai.core.engine.ColorCore
import com.suipai.core.engine.EngineParams
import com.suipai.core.engine.Look
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorCoreTest {
    private val w = 64
    private val h = 48

    private fun randomPixels(seed: Int): IntArray {
        val rnd = Random(seed)
        return IntArray(w * h) { rnd.nextInt() }
    }

    private fun params(
        look: Look = Look(id = "test", name = "测试", summary = "测试"),
        grainEdge: Int = 4096,
        seed: Int = 12345,
    ): EngineParams =
        EngineParams(
            look = look,
            intensity = 0.6f,
            w = w,
            h = h,
            grainEdge = grainEdge,
            seed = seed,
        )

    @Test
    fun chunkedProcessMatchesFullProcess() {
        val p = params()
        val input = randomPixels(7)

        val full = input.copyOf()
        ColorCore.process(full, w, h, p)

        val chunked = input.copyOf()
        ColorCore.processRange(chunked, w, h, 0, 16, p)
        ColorCore.processRange(chunked, w, h, 16, 32, p)
        ColorCore.processRange(chunked, w, h, 32, h, p)

        assertContentEquals(full, chunked)
    }

    @Test
    fun processIsDeterministic() {
        val p = params()
        val input = randomPixels(99)
        val a = input.copyOf()
        val b = input.copyOf()
        ColorCore.process(a, w, h, p)
        ColorCore.process(b, w, h, p)
        assertContentEquals(a, b)
    }

    @Test
    fun outputKeepsAlphaAndRgbRange() {
        val p = params(grainEdge = 1280)
        val input = randomPixels(3)
        val out = input.copyOf()
        ColorCore.process(out, w, h, p)
        for (i in out.indices) {
            val before = (input[i] ushr 24) and 0xFF
            val after = (out[i] ushr 24) and 0xFF
            assertEquals(before, after, "alpha changed at $i")
            val r = (out[i] shr 16) and 0xFF
            val g = (out[i] shr 8) and 0xFF
            val b = out[i] and 0xFF
            assertTrue(r in 0..255 && g in 0..255 && b in 0..255, "channel out of range at $i")
        }
    }

    @Test
    fun monochromeLookOutputsGray() {
        val look = Look(
            id = "t",
            name = "t",
            summary = "t",
            monochrome = true,
            grain = 0f,
            vignette = 0f,
        )
        val p = params(look = look)
        val input = randomPixels(11)
        val out = input.copyOf()
        ColorCore.process(out, w, h, p)
        for (i in out.indices) {
            val r = (out[i] shr 16) and 0xFF
            val g = (out[i] shr 8) and 0xFF
            val b = out[i] and 0xFF
            assertEquals(r, g, "R != G at $i")
            assertEquals(g, b, "G != B at $i")
        }
    }
}