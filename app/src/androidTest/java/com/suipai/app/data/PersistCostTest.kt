package com.suipai.app.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「收藏 / 隐藏」那一下点下去，主线程不许碰磁盘、也不许把整表来回倒一遍。
 *
 * 钉这条的理由是实测出来的两笔账（都在这台 suipai_r4 上量的）：
 *
 *  1. 改之前 `write()` 用 `commit()` 同步落盘：写 100 条 id 要 **69 ms**、5000 条 **132 ms** ——
 *     每一下点击都冻一次界面；
 *  2. 只把 `commit()` 换成 `apply()` 还不够：`toggle` 原来要把整份清单
 *     String→Long 再 Long→String 倒一遍（两次全量装箱 + 重建哈希表），
 *     5000 条时主线程仍然要 **52 ms**（同一趟里「隐藏」那颗量到 204 ms）。
 *
 * 现在两笔都改掉了：落盘挪到 IO 上的 `persist()`，改动直接在字符串形态上做。
 * 而"有没有写进设置"仍然观察得到 —— 那是"藏了 N 张"这句话说得算不算的前提（§30）。
 */
@RunWith(AndroidJUnit4::class)
class PersistCostTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun mainCost(block: () -> Unit): Long {
        val ins = InstrumentationRegistry.getInstrumentation()
        val t0 = SystemClock.uptimeMillis()
        ins.runOnMainSync(block)
        return SystemClock.uptimeMillis() - t0
    }

    private fun ioCost(block: suspend () -> Unit): Long {
        val t0 = SystemClock.uptimeMillis()
        runBlocking { withContext(Dispatchers.IO) { block() } }
        return SystemClock.uptimeMillis() - t0
    }

    private fun sweep(
        name: String,
        read: () -> Set<Long>,
        add: (Collection<Long>) -> Unit,
        remove: (Collection<Long>) -> Unit,
        toggle: (Long) -> Unit,
        persist: suspend () -> Boolean,
    ) {
        val probe = (1L..5000L).toSet() + 9_999_999L
        val rows = StringBuilder()
        val stored = booleanArrayOf(false)
        try {
            for (n in listOf(100, 1000, 5000)) {
                ioCost {
                    remove(read())
                    add((1L..n).toSet())
                    persist()
                }
                val tap = mainCost { toggle(9_999_999L) }
                val budget = if (n >= 5000) 40L else 16L
                assertTrue("$name：清单 $n 条时点一下要 $tap ms（预算 ${budget}ms；一帧才 16ms）", tap <= budget)
                assertTrue("$name：内存改动没立刻生效", !read().isEmpty())
                rows.append("n=$n tap=${tap}ms; ")
            }
            val ms = ioCost { stored[0] = persist() }
            assertTrue("$name：落盘必须给得出写没写进去", stored[0])
            Log.i("FavCost", "$name 主线程点击成本：$rows 落盘=${ms}ms（在 IO 上）")
        } finally {
            ioCost {
                remove(probe)
                persist()
            }
        }
    }

    @Test
    fun favouriteAndHiddenTapsStayInsideABudgetAtEveryRealisticSize() {
        val fav = Favorites(context)
        sweep(
            "收藏",
            { fav.ids() },
            { fav.addAll(it) },
            { fav.removeAll(it) },
            { fav.toggle(it) },
            { fav.persist() },
        )
        val hid = Hidden(context)
        sweep(
            "隐藏",
            { hid.ids() },
            { hid.addAll(it) },
            { hid.removeAll(it) },
            { hid.toggle(it) },
            { hid.persist() },
        )
    }
}
