package com.suipai.core

import com.suipai.core.engine.LookLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LookLibraryTest {
    @Test
    fun allLookIdsAreUnique() {
        val ids = LookLibrary.all.map { it.id }
        val dups = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(
            dups.isEmpty(),
            "LookLibrary.all 里有重复 id：$dups —— 一瞬的预设 LazyRow 用 id 作 key，重复会直接崩溃",
        )
    }

    @Test
    fun byIdResolvesEveryLook() {
        for (look in LookLibrary.all) {
            assertEquals(look, LookLibrary.byId(look.id), "byId(${look.id}) 没有返回它自己")
        }
    }

    @Test
    fun autoAndSyntheticChipPrefixesCannotCollide() {
        for (look in LookLibrary.all) {
            assertTrue(look.id != LookLibrary.AUTO_ID, "有预设占用了 AUTO_ID：${look.id}")
            assertTrue(!look.id.startsWith("last:"), "有预设 id 以 last: 开头：${look.id}")
            assertTrue(!look.id.startsWith("near:"), "有预设 id 以 near: 开头：${look.id}")
        }
        assertNotNull(LookLibrary.byId(LookLibrary.all.first().id))
    }

    @Test
    fun libraryIsNotTruncated() {
        assertTrue(
            LookLibrary.all.size >= 5000,
            "预设总数 ${LookLibrary.all.size} 明显低于文档口径（README/PRODUCT 写的是 5446）",
        )
    }
}
