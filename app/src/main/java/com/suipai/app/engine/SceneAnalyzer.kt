package com.suipai.app.engine

import com.suipai.core.engine.*

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

object SceneAnalyzer {
    fun analyze(bitmap: Bitmap, exif: ExifInterface?): Adaptation {
        val sample = downsample(bitmap, 256)
        try {
            val w = sample.width
            val h = sample.height
            val pixels = IntArray(w * h)
            sample.getPixels(pixels, 0, w, 0, 0, w, h)
            val faces = detectFaces(sample)
            val data = ExifData(
                iso = exif?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0),
                flashFired = exif?.getAttributeInt(ExifInterface.TAG_FLASH, 0)?.let { it and 1 == 1 },
                exposureSec = exif?.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toFloatOrNull(),
                focal35mm = exif?.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0),
                wbMode = exif?.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1),
                hourOfDay = hourOfDay(exif),
            )
            return SceneAnalyzerCore.analyze(pixels, w, h, data, faces)
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    private fun downsample(src: Bitmap, maxEdge: Int): Bitmap {
        val scale = maxEdge / max(src.width, src.height).toFloat()
        if (scale >= 1f) return src
        val w = max(1, (src.width * scale).toInt())
        val h = max(1, (src.height * scale).toInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun detectFaces(bitmap: Bitmap): List<FaceBox> {
        return try {
            val evenWidth = bitmap.width - (bitmap.width % 2)
            if (evenWidth < 2) return emptyList()
            val cropped = if (evenWidth != bitmap.width) {
                Bitmap.createBitmap(bitmap, 0, 0, evenWidth, bitmap.height)
            } else {
                bitmap
            }
            val rgb = if (cropped.config == Bitmap.Config.RGB_565) {
                cropped
            } else {
                cropped.copy(Bitmap.Config.RGB_565, false) ?: return emptyList()
            }
            val detector = FaceDetector(rgb.width, rgb.height, 4)
            val faces = arrayOfNulls<FaceDetector.Face>(4)
            val n = detector.findFaces(rgb, faces)
            val result = ArrayList<FaceBox>(n)
            for (i in 0 until n) {
                val face = faces[i] ?: continue
                val mid = PointF()
                face.getMidPoint(mid)
                val half = face.eyesDistance().coerceAtLeast(1f)
                result += FaceBox(
                    (mid.x - half).toInt(),
                    (mid.y - half).toInt(),
                    (half * 2f).toInt(),
                    (half * 2f).toInt(),
                )
            }
            if (rgb !== cropped) rgb.recycle()
            if (cropped !== bitmap) cropped.recycle()
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun hourOfDay(exif: ExifInterface?): Int? {
        val raw = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        val time = raw.substringAfter(' ', missingDelimiterValue = "")
        return time.substringBefore(':').toIntOrNull()
    }
}