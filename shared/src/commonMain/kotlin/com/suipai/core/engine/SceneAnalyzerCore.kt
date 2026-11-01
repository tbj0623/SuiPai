package com.suipai.core.engine

import kotlin.math.max
import kotlin.math.min

@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("ExifData", exact = true)
data class ExifData(
    val iso: Int? = null,
    val flashFired: Boolean? = null,
    val exposureSec: Float? = null,
    val focal35mm: Int? = null,
    val wbMode: Int? = null,
    val hourOfDay: Int? = null,
)

@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("FaceBox", exact = true)
data class FaceBox(val x: Int, val y: Int, val w: Int, val h: Int)

@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("SceneAnalyzerCore", exact = true)
object SceneAnalyzerCore {
    /** 纯逻辑场景分析：直方图统计 + EXIF 规则 + 场景级联映射。 */
    fun analyze(pixels: IntArray, w: Int, h: Int, exif: ExifData, faces: List<FaceBox>): Adaptation {
        val stats = histogram(pixels)
        val faceCount = faces.size
        val iso = exif.iso ?: 0
        val hour = exif.hourOfDay
        val flash = exif.flashFired == true
        val exposureSec = exif.exposureSec
        val slowShutter = exposureSec != null && exposureSec >= 0.05f
        val focal35 = exif.focal35mm ?: 0
        val wbAuto = exif.wbMode == 0

        val night = stats.mean < 0.28f || iso >= 800 ||
            (hour != null && (hour < 6 || hour >= 20)) ||
            (slowShutter && stats.mean < 0.42f)
        val indoorWarm = stats.temperature > 0.08f && stats.mean < 0.55f && !night &&
            (focal35 in 1..50 || !wbAuto || iso >= 200)
        val portrait = faceCount > 0 ||
            (focal35 >= 70 && stats.mean in 0.28f..0.72f && !night && stats.saturation < 0.28f)
        val woody = indoorWarm && stats.temperature > 0.12f &&
            stats.saturation in 0.08f..0.22f && !portrait
        val foodish = !portrait && stats.saturation > 0.18f && stats.temperature > 0.04f &&
            stats.mean in 0.30f..0.70f && !night
        val blossomy = !portrait && !night && !foodish && stats.saturation > 0.16f &&
            stats.temperature in 0.02f..0.12f && stats.highlights > 0.80f
        val misty = !portrait && !night && stats.contrast < 0.13f && stats.mean > 0.42f
        val celadonish = !portrait && !night && !misty &&
            stats.temperature in -0.08f..0.02f && stats.saturation in 0.06f..0.16f &&
            stats.mean in 0.38f..0.62f
        val cool = !portrait && stats.temperature < -0.05f && stats.mean in 0.22f..0.62f
        val golden = hour != null && hour in 16..19 && !night && stats.temperature > 0.02f
        val airy = !portrait && !night && stats.mean > 0.52f &&
            stats.highlights > 0.82f && stats.contrast in 0.10f..0.22f
        val snowy = !portrait && !night && stats.mean > 0.58f &&
            stats.saturation < 0.10f && stats.temperature < 0.02f
        val autumnish = !portrait && !night && stats.temperature > 0.06f &&
            stats.saturation > 0.14f && hour != null && hour in 9..16
        val rainy = night && stats.highlights > 0.72f && stats.saturation < 0.20f
        val neonish = night && !rainy && stats.saturation > 0.16f && stats.highlights > 0.75f
        val moonlit = night && !rainy && !neonish && stats.mean < 0.24f &&
            stats.highlights < 0.70f && stats.saturation < 0.16f
        val inkgold = night && !rainy && !neonish && !moonlit &&
            stats.temperature > 0.03f && stats.highlights > 0.62f
        val dawning = hour != null && hour in 5..8 && !night &&
            stats.mean in 0.28f..0.62f && !portrait
        val hazey = misty && stats.temperature < -0.02f
        val bronzy = !portrait && !night && !indoorWarm && !woody &&
            stats.temperature in 0.00f..0.10f &&
            stats.saturation in 0.06f..0.18f &&
            stats.mean in 0.26f..0.50f &&
            stats.contrast > 0.13f

        val scene = when {
            faceCount > 0 -> SceneKind.PORTRAIT
            rainy -> SceneKind.RAIN
            neonish -> SceneKind.NEON
            moonlit -> SceneKind.MOON
            inkgold -> SceneKind.INKGOLD
            night -> SceneKind.NIGHT
            dawning -> SceneKind.DAWN
            portrait -> SceneKind.PORTRAIT
            golden -> SceneKind.DUSK
            woody -> SceneKind.WOOD
            indoorWarm -> SceneKind.INDOOR
            foodish -> SceneKind.FOOD
            blossomy -> SceneKind.BLOSSOM
            autumnish -> SceneKind.AUTUMN
            snowy -> SceneKind.SNOW
            hazey -> SceneKind.HAZE
            bronzy -> SceneKind.BRONZE
            misty -> SceneKind.MIST
            celadonish -> SceneKind.CELADON
            cool -> SceneKind.CYAN
            airy -> SceneKind.CLEAR
            stats.contrast > 0.22f && stats.shadows < 0.10f -> SceneKind.STREET
            else -> SceneKind.DAYLIGHT
        }

        val look = when (scene) {
            SceneKind.PORTRAIT -> LookLibrary.portrait
            SceneKind.RAIN -> LookLibrary.rain
            SceneKind.NEON -> LookLibrary.neon
            SceneKind.MOON -> LookLibrary.moon
            SceneKind.INKGOLD -> LookLibrary.inkgold
            SceneKind.DAWN -> LookLibrary.dawn
            SceneKind.NIGHT -> LookLibrary.night
            SceneKind.DUSK -> LookLibrary.dusk
            SceneKind.WOOD -> LookLibrary.wood
            SceneKind.INDOOR -> LookLibrary.indoor
            SceneKind.FOOD -> LookLibrary.film
            SceneKind.BLOSSOM -> LookLibrary.blossom
            SceneKind.AUTUMN -> LookLibrary.autumn
            SceneKind.SNOW -> LookLibrary.snow
            SceneKind.MIST -> LookLibrary.mist
            SceneKind.CELADON -> LookLibrary.celadon
            SceneKind.BRONZE -> LookLibrary.bronze
            SceneKind.HAZE -> LookLibrary.hazeblue
            SceneKind.CYAN -> LookLibrary.cyan
            SceneKind.CLEAR -> LookLibrary.clear
            SceneKind.STREET -> LookLibrary.silver
            SceneKind.DAYLIGHT -> LookLibrary.daylight
        }

        var intensity = look.defaultIntensity
        if (stats.contrast > 0.32f) intensity -= 0.08f
        if (stats.highlights > 0.92f) intensity -= 0.06f
        if (stats.shadows < 0.06f) intensity -= 0.04f
        if (faceCount > 0) intensity = min(intensity, 0.52f)
        if (flash) intensity -= 0.05f
        if (night && stats.mean < 0.18f) intensity += 0.04f
        if (focal35 >= 70 && faceCount > 0) intensity = min(intensity, 0.48f)
        intensity = intensity.coerceIn(0.28f, 0.72f)

        val reason = buildString {
            append(look.name)
            if (faceCount > 0) append(" · 检测到人")
            else if (portrait) append(" · 长焦人像")
            if (rainy) append(" · 雨夜高光")
            if (neonish) append(" · 霓虹")
            if (moonlit) append(" · 月光")
            if (inkgold) append(" · 墨金")
            if (dawning) append(" · 清晨")
            if (night) append(" · 低光")
            if (woody) append(" · 木色")
            if (indoorWarm) append(" · 暖光室内")
            if (foodish) append(" · 高饱和")
            if (blossomy) append(" · 花色")
            if (hazey) append(" · 雾蓝")
            if (bronzy) append(" · 青铜")
            if (misty) append(" · 低反差")
            if (celadonish) append(" · 青瓷")
            if (cool) append(" · 冷色")
            if (golden) append(" · 黄昏")
            if (airy) append(" · 高光通透")
            if (snowy) append(" · 高调")
            if (autumnish) append(" · 暖秋")
        }

        return Adaptation(scene, look, intensity, reason)
    }

    private data class Stats(
        val mean: Float,
        val contrast: Float,
        val temperature: Float,
        val saturation: Float,
        val highlights: Float,
        val shadows: Float,
    )

    private fun histogram(pixels: IntArray): Stats {
        var sum = 0.0
        var warm = 0.0
        var sat = 0.0
        val hist = IntArray(64)
        val step = max(1, pixels.size / 4000)
        var count = 0
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            sum += y
            hist[(y * 63f).toInt().coerceIn(0, 63)]++
            warm += r - b
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            sat += if (mx > 1e-4f) (mx - mn) / mx else 0f
            count++
            i += step
        }
        val n = max(1, count).toFloat()
        fun percentile(p: Float): Float {
            val target = (count * p).toInt()
            var acc = 0
            for (bin in hist.indices) {
                acc += hist[bin]
                if (acc >= target) return bin / 63f
            }
            return 1f
        }
        return Stats(
            mean = (sum / n).toFloat(),
            contrast = percentile(0.90f) - percentile(0.10f),
            temperature = (warm / n).toFloat(),
            saturation = (sat / n).toFloat(),
            highlights = percentile(0.98f),
            shadows = percentile(0.10f),
        )
    }
}