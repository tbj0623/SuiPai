package com.suipai.app

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.memory.MemoryCache
import com.suipai.app.data.ThumbnailFetcher
import com.suipai.core.engine.LookLibrary

class SuiPaiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 全局 crossfade：缩略图到位时是「显影」出来，而不是啪一下从空框变成照片。
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components { add(ThumbnailFetcher.Factory()) }
                .crossfade(true)
                .memoryCache {
                    MemoryCache.Builder(this)
                        // 印样阵是整屏刷新的，缓存太小会一边滚一边掉，滚回去就是空框
                        .maxSizePercent(0.25)
                        .build()
                }
                .build(),
        )
        // 配方库第一次被碰的时候要做的事：5446 个 Look 的构造 + 121 个子包 `+` 起来（每加一次
        // 都整表复制）+ 建索引。桌面 JVM warm 量到 8.9 ms，ART 上是几十毫秒的一跳，
        // 而它原本落在**一瞬那一帧**里（`LookLibrary.byId(...)` 在 composition 里）。
        // `all` / `index` 都是 `by lazy(SYNCHRONIZED)`，后台先摸一次是安全的，行为一字不变。
        Thread { runCatching { LookLibrary.all } }.start()
    }
}

