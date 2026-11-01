package com.suipai.app.data

import android.net.Uri
import androidx.compose.runtime.Immutable

/**
 * 三个模型都是**只读快照**：一行 MediaStore 记录不会在手里变。
 *
 * 标 @Immutable 不是文档，是性能开关：Compose 认不出平台类 Uri，默认把整个 data class
 * 判成 unstable，于是 selectedIds / favoriteIds 一变，屏幕上每一格缩略图全部重组。
 * 印样阵一屏十几格，这个税每次选择都要交一遍。
 */

@Immutable
data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateTaken: Long,
    val bucketId: String,
    val bucketName: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)

@Immutable
data class AlbumItem(
    val id: String,
    val name: String,
    val cover: Uri?,
    val count: Int,
    val lastTaken: Long = 0L,
)

@Immutable
data class PhotoLibrary(
    val photos: List<PhotoItem>,
    val albums: List<AlbumItem>,
)
