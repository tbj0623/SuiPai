package com.suipai.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.suipai.core.engine.RecipeCodec
import com.suipai.core.engine.YishunRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一瞬配方 ↔ 照片 EXIF UserComment 的落盘边界，单独成文件是为了让它能被 instrumented test
 * 真正跑一遍（见 RecipeExifRoundTripTest）：ASCII 字符码 + 4 个 NUL 这个约定以前只靠人记。
 */
object RecipeExif {
    private const val TAG = "RecipeExif"

    /**
     * 读回配方。[exif] 是调用方已经解析过的那份，能省一次开流；
     * 它可能来自缩略图解码（有些来源给的是没有 UserComment 的临时对象），所以读不到就回退到按 uri 重开。
     */
    suspend fun readRecipe(
        context: Context,
        uri: Uri,
        exif: ExifInterface? = null,
    ): YishunRecipe? = withContext(Dispatchers.IO) {
        val comment = runCatching {
            exif?.getAttribute(ExifInterface.TAG_USER_COMMENT)?.takeIf { it.isNotBlank() }
                ?: context.contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttribute(ExifInterface.TAG_USER_COMMENT)
                }
        }.getOrNull()
        comment?.let { RecipeCodec.decode(it) }
    }

    /** 写配方。失败只记日志：成片已经存好了，不该因为配方没附上就报错。 */
    suspend fun writeRecipe(
        context: Context,
        uri: Uri,
        recipe: YishunRecipe,
    ) {
        withContext(Dispatchers.IO) {
            runCatching {
                val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: return@runCatching
                pfd.use { fd ->
                    val exif = ExifInterface(fd.fileDescriptor)
                    // 字符码由 RecipeCodec.encodeForExif 统一负责，两端同一个约定。
                    exif.setAttribute(
                        ExifInterface.TAG_USER_COMMENT,
                        RecipeCodec.encodeForExif(recipe),
                    )
                    exif.saveAttributes()
                }
            }.onFailure { Log.w(TAG, "配方写入 EXIF 失败", it) }
        }
    }
}
