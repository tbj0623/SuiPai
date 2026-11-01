package com.suipai.app.engine

import com.suipai.core.engine.*

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

object ColorPipeline {
    /**
     * 挂起是故意的：这里要开最多 8 个 CPU 任务。以前它是普通函数 + 内部 `runBlocking`，
     * 于是谁在主线程上按下去都会把 UI 冻住（导出那条路径真踩过一次）。现在自己切线程，
     * 调用方在哪个线程都卡不到主线程。
     */
    suspend fun apply(
        source: Bitmap,
        look: Look,
        intensity: Float,
        /** 缩放目标长边（只管工作缓冲大小）。 */
        maxEdge: Int = 4096,
        /** 颗粒调校用的成片长边；预览要传**将要导出的边长**，否则预览和成片不是一份颗粒。 */
        grainEdge: Int = maxEdge,
        seed: Int = ColorCore.grainSeedFor(look.id),
        grainMul: Float = 1f,
        vignetteMul: Float = 1f,
        shifts: Shifts = Shifts.None,
        /**
         * 专业分级的三组可选算子。全部走默认值，所以现有调用点一个字都不用改；
         * 中性时 [EngineParams] 里的守卫会整段跳过，预览/导出的开销与 v2 一致。
         */
        curves: ToneCurves = ToneCurves(),
        hsl: HslBands = HslBands(),
        masks: List<LocalMask> = emptyList(),
    ): Bitmap = withContext(Dispatchers.Default) {
        val working = fit(source, maxEdge)
        val w = working.width
        val h = working.height
        val pixels = IntArray(w * h)
        working.getPixels(pixels, 0, w, 0, 0, w, h)
        val params = EngineParams(
            look = look,
            intensity = intensity,
            w = w,
            h = h,
            grainEdge = grainEdge,
            seed = seed,
            grainMul = grainMul,
            vignetteMul = vignetteMul,
            tempShift = shifts.temp,
            contrastShift = shifts.contrast,
            satShift = shifts.sat,
            shadowShift = shifts.shadow,
            fadeShift = shifts.fade,
            highlightShift = shifts.highlight,
            softShift = shifts.soft,
            curves = curves,
            hsl = hsl,
            masks = masks,
        )

        val blocks = min(8, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
        val chunk = ((h + blocks - 1) / blocks).coerceAtLeast(1)
        coroutineScope {
            (0 until h step chunk).map { yStart ->
                async(Dispatchers.Default) {
                    ColorCore.processRange(pixels, w, h, yStart, min(h, yStart + chunk), params)
                }
            }.awaitAll()
        }

        // 绝不修改 source：working === source 时输出为副本。
        val out = if (working !== source && working.isMutable && working.config == Bitmap.Config.ARGB_8888) {
            working
        } else {
            working.copy(Bitmap.Config.ARGB_8888, true)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        if (working !== source && working !== out) working.recycle()
        out
    }

    suspend fun preview(
        source: Bitmap,
        look: Look,
        intensity: Float,
        grainEdge: Int = 4096,
        grainMul: Float = 1f,
        vignetteMul: Float = 1f,
        shifts: Shifts = Shifts.None,
        curves: ToneCurves = ToneCurves(),
        hsl: HslBands = HslBands(),
        masks: List<LocalMask> = emptyList(),
    ): Bitmap = apply(
        source,
        look,
        intensity,
        maxEdge = previewEdge(),
        grainEdge = grainEdge,
        grainMul = grainMul,
        vignetteMul = vignetteMul,
        shifts = shifts,
        curves = curves,
        hsl = hsl,
        masks = masks,
    )

    fun lumaHistogram(bitmap: Bitmap, bins: Int = 48): IntArray {
        if (bitmap.isRecycled) return IntArray(bins)
        val hist = IntArray(bins)
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return hist
        val stepX = max(1, w / 96)
        val stepY = max(1, h / 96)
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p = row[x]
                val y8 = (((p shr 16) and 0xFF) * 54 + ((p shr 8) and 0xFF) * 183 + (p and 0xFF) * 19) shr 8
                hist[(y8 * bins) shr 8]++
                x += stepX
            }
            y += stepY
        }
        return hist
    }

    fun rgbHistogram(bitmap: Bitmap, bins: Int = 48): Array<IntArray> {
        val r = IntArray(bins)
        val g = IntArray(bins)
        val b = IntArray(bins)
        if (bitmap.isRecycled) return arrayOf(r, g, b)
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return arrayOf(r, g, b)
        val stepX = max(1, w / 96)
        val stepY = max(1, h / 96)
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p = row[x]
                r[(((p shr 16) and 0xFF) * bins) shr 8]++
                g[(((p shr 8) and 0xFF) * bins) shr 8]++
                b[((p and 0xFF) * bins) shr 8]++
                x += stepX
            }
            y += stepY
        }
        return arrayOf(r, g, b)
    }

    fun previewEdge(): Int {
        val mb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        return when {
            mb < 192L -> 960
            mb < 384L -> 1280
            else -> 1600
        }
    }

    fun exportEdge(): Int {
        val mb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        return when {
            mb < 192L -> 1600
            mb < 384L -> 2560
            else -> 4096
        }
    }

    private fun fit(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxEdge) {
            return if (source.config == Bitmap.Config.ARGB_8888) source
            else source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val scale = maxEdge / longest.toFloat()
        val w = max(1, (source.width * scale).toInt())
        val h = max(1, (source.height * scale).toInt())
        return Bitmap.createScaledBitmap(source, w, h, true)
    }
}