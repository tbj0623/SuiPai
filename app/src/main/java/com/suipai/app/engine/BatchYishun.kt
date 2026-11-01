package com.suipai.app.engine

import com.suipai.core.engine.*

import android.content.Context
import android.net.Uri
import com.suipai.app.data.ImageLoader
import com.suipai.app.data.MediaRepository
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class BatchFailure(val index: Int, val reason: String)

data class BatchReport(val succeeded: Int, val failures: List<BatchFailure>)

object BatchYishun {
    const val MAX = 30

    suspend fun run(
        context: Context,
        uris: List<Uri>,
        lookId: String = LookLibrary.AUTO_ID,
        onProgress: suspend (Int, Int) -> Unit,
    ): Int = runDetailed(context, uris, lookId, onProgress).succeeded

    suspend fun runDetailed(
        context: Context,
        uris: List<Uri>,
        lookId: String = LookLibrary.AUTO_ID,
        onProgress: suspend (Int, Int) -> Unit,
    ): BatchReport {
        val repo = MediaRepository(context)
        val slice = uris.take(MAX)
        var ok = 0
        val failures = ArrayList<BatchFailure>()
        slice.forEachIndexed { index, uri ->
            coroutineContext.ensureActive()
            onProgress(index + 1, slice.size)
            runCatching {
                val edge = ColorPipeline.exportEdge()
                val (bitmap, exif) = ImageLoader.decode(context, uri, maxEdge = edge)
                try {
                    val look: Look
                    val intensity: Float
                    if (lookId == LookLibrary.AUTO_ID) {
                        val adaptation = SceneAnalyzer.analyze(bitmap, exif)
                        look = adaptation.look
                        intensity = adaptation.intensity
                    } else {
                        look = LookLibrary.byId(lookId) ?: LookLibrary.daylight
                        intensity = look.defaultIntensity
                    }
                    val out = ColorPipeline.apply(
                        bitmap,
                        look,
                        intensity,
                        maxEdge = edge,
                    )
                    try {
                        repo.saveBitmap(
                            out,
                            "SuiPai_一瞬_${look.name}_${(intensity * 100).toInt()}_${System.currentTimeMillis()}.jpg",
                            quality = 95,
                            sourceUri = uri,
                        ) ?: error("save failed")
                        ok++
                    } finally {
                        if (out !== bitmap) out.recycle()
                    }
                } finally {
                    bitmap.recycle()
                }
            }.onFailure { e ->
                failures += BatchFailure(index, e.message ?: e.javaClass.simpleName)
            }
        }
        return BatchReport(ok, failures)
    }
}