package com.suipai.app.ui.gallery

import android.net.Uri
import com.suipai.app.data.PhotoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * 「只看 X」 × 「隐藏 Y」的全表扫描：31 个类别 × 26 个隐藏开关 = 806 个组合。
 *
 * 立在这里的唯一契约是用户能说的话：
 *
 *   **说「只看副本」，那一张副本必须在。不管哪些「隐藏」开关开着。**
 *
 * 为什么原来会漏：筛选链是 26 段 `if (hideX && filter != N)`，每一段只豁免**与自己同名**的
 * 那一格；而 `hideEdits`（隐藏裁切件）是一条**并集**开关，它覆盖 20 个前缀、却一段豁免都没写。
 * 结果：只要用户开着「藏裁切件」，「只看副本 / 只看裁切 / 只看灰度 / 只看 2K …」这 18 格
 * 一律恒空，空状态还谎报「没有灰度照片」。这类洞在界面上点不出来——必须先开那个隐藏开关。
 *
 * 放在 androidTest 而不是 test：`PhotoItem` 持有 `android.net.Uri`，JVM 单测里造不出真实的 uri。
 * 被测函数是纯的（只读 dateTaken/宽高/文件名），所以这里不需要模拟器界面。
 */
@RunWith(AndroidJUnit4::class)
class GalleryOnlyKindTest {

    private fun item(name: String, id: Long, bucket: String = "Camera"): PhotoItem = PhotoItem(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        displayName = name,
        dateTaken = 1_700_000_000_000L,
        bucketId = bucket,
        bucketName = bucket,
        width = 4000,
        height = 3000,
        sizeBytes = 2_000_000L,
    )

    /**
     * 每个「只看 X」一张代表照，名字按各类别自己的命名前缀写（这是**独立复述**，不是从代码抄：
     * 前缀来自导出文件名契约，见 `ViewerActions` 与一瞬导出的命名）。
     * 另有 4 张中性的：原片（随拍）、收藏、隐藏、截图。
     */
    private val reps: Map<Int, PhotoItem> = linkedMapOf(
        1 to item("SuiPai_一瞬_A.jpg", 101),
        2 to item("IMG_fav.jpg", 102),
        3 to item("IMG_plain.jpg", 103),
        4 to item("SuiPai_plain.jpg", 104),
        5 to item("IMG_hidden.jpg", 105),
        6 to item("SuiPai_对比_A.jpg", 106),
        7 to item("SuiPai_副本_A.jpg", 107),
        8 to item("SuiPai_裁切_A.jpg", 108),
        9 to item("SuiPai_转向_A.jpg", 109),
        10 to item("SuiPai_裁切黄金_A.jpg", 110),
        11 to item("SuiPai_裁切239_A.jpg", 111),
        12 to item("SuiPai_裁切185_A.jpg", 112),
        13 to item("SuiPai_裁切53_A.jpg", 113),
        14 to item("SuiPai_镜像_A.jpg", 114),
        15 to item("SuiPai_灰度_A.jpg", 115),
        16 to item("SuiPai_上下翻_A.jpg", 116),
        17 to item("SuiPai_圆角_A.jpg", 117),
        18 to item("SuiPai_2K_A.jpg", 118),
        19 to item("SuiPai_1080_A.jpg", 119),
        20 to item("SuiPai_720_A.jpg", 120),
        21 to item("SuiPai_480_A.jpg", 121),
        22 to item("SuiPai_320_A.jpg", 122),
        23 to item("SuiPai_240_A.jpg", 123),
        24 to item("SuiPai_160_A.jpg", 124),
        25 to item("SuiPai_120_A.jpg", 125),
        26 to item("SuiPai_96_A.jpg", 126),
        27 to item("SuiPai_64_A.jpg", 127),
        28 to item("SuiPai_48_A.jpg", 128),
        29 to item("SuiPai_32_A.jpg", 129),
        30 to item("SuiPai_24_A.jpg", 130),
        31 to item("SuiPai_16_A.jpg", 131),
    )

    /** 26 个隐藏开关的键，和 `GalleryFlag(prefKey)` 一一对应。 */
    private val hideKeys = listOf(
        "hide_screenshots", "hide_copies", "hide_edits", "hide_yishun", "hide_compare",
        "hide_turned", "hide_wide", "hide_postcard", "hide_mirror", "hide_gray", "hide_flip",
        "hide_round", "hide_2k", "hide_1080", "hide_720", "hide_480", "hide_320", "hide_240",
        "hide_160", "hide_120", "hide_96", "hide_64", "hide_48", "hide_32", "hide_24", "hide_16",
        "hide_golden_crop", "hide_academy_crop",
    )

    private val library: List<PhotoItem> =
        reps.values + item("Screenshot_20240101.png", 200, "Screenshots")

    private fun filters(only: Int, hides: Set<String>) = GalleryFilters(
        filter = only,
        favoriteIds = setOf(102L),
        favoritePhotos = listOf(reps.getValue(2)),
        hiddenIds = setOf(105L),
        hiddenPhotos = listOf(reps.getValue(5)),
        hideShots = "hide_screenshots" in hides,
        hideCopies = "hide_copies" in hides,
        hideEdits = "hide_edits" in hides,
        hideYishun = "hide_yishun" in hides,
        hideCompare = "hide_compare" in hides,
        hideTurned = "hide_turned" in hides,
        hideWide = "hide_wide" in hides,
        hidePostcard = "hide_postcard" in hides,
        hideMirror = "hide_mirror" in hides,
        hideGray = "hide_gray" in hides,
        hideFlip = "hide_flip" in hides,
        hideRound = "hide_round" in hides,
        hide2k = "hide_2k" in hides,
        hide1080 = "hide_1080" in hides,
        hide720 = "hide_720" in hides,
        hide480 = "hide_480" in hides,
        hide320 = "hide_320" in hides,
        hide240 = "hide_240" in hides,
        hide160 = "hide_160" in hides,
        hide120 = "hide_120" in hides,
        hide96 = "hide_96" in hides,
        hide64 = "hide_64" in hides,
        hide48 = "hide_48" in hides,
        hide32 = "hide_32" in hides,
        hide24 = "hide_24" in hides,
        hide16 = "hide_16" in hides,
        hideGoldenCrop = "hide_golden_crop" in hides,
        hideAcademy = "hide_academy_crop" in hides,
    )

    /** 契约本体：每个类别在每种隐藏组合下都保得住自己那张代表照。 */
    private fun violations(lib: List<PhotoItem>, keys: List<String>): List<String> {
        val out = ArrayList<String>()
        for ((only, rep) in reps) {
            for (hides in combos(keys)) {
                val got = visiblePhotos(lib, filters(only, hides)).map { it.id }
                if (rep.id !in got) {
                    out += "只看#$only 在隐藏${hides.ifEmpty { listOf("无") }}开着时丢了代表照 ${rep.displayName}"
                }
            }
        }
        return out
    }

    /** 全子集会爆 2^26；这里要的是「全关」「单开一个」与「全开」三种，合 28 组。 */
    private fun combos(keys: List<String>): List<Set<String>> =
        listOf(emptySet<String>()) + keys.map { setOf(it) } + listOf(keys.toSet())

    @Test
    fun everyOnlyKindSurvivesEveryHideSwitch() {
        val bad = violations(library, hideKeys)
        assertTrue("「只看 X」被隐藏开关掉掉：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /** 扫描的覆盖面本身也要钉住：漏掉一个 prefKey，上面那条就成了半覆盖的绿。 */
    @Test
    fun theSweepTogglesEveryHideFlagThatExists() {
        val flags = GalleryFlag.inGroup(GalleryFlagGroup.HIDE).map { it.prefKey }.toSet()
        assertEquals("隐藏开关与扫描的键集合不一致（新增开关要同步补进这张表）", flags, hideKeys.toSet())
        assertEquals(28, hideKeys.size)
        assertEquals("31 个「只看」编号都得有代表照", (1..31).toSet(), reps.keys)
        // 代表照必须真的在库里，否则扫描是空跑（绿色但毫无意义）。
        val ids = library.map { it.id }.toSet()
        assertTrue(reps.values.all { it.id in ids })
    }

    /**
     * 反方向也要钉：**每个隐藏开关都得真的藏掉点东西**。
     *
     * 上面那条只问"只看 X 活不活得下来"，把 `on` 读错字段（比如「藏副本」其实读的是 `hideGray`）
     * 照样全绿 —— 那一档什么都藏不住，界面上就是"开关拨了没反应"。
     * 这里对 28 个开关逐个量：单独开它，时间线必须变短。
     */
    @Test
    fun everyHideSwitchStillHidesSomething() {
        val all = visiblePhotos(library, filters(0, emptySet())).map { it.id }.toSet()
        assertTrue("库里样本太少，这条量不出东西：${all.size}", all.size >= 20)
        val silent = ArrayList<String>()
        for (k in hideKeys) {
            val kept = visiblePhotos(library, filters(0, setOf(k))).map { it.id }.toSet()
            if (kept.size == all.size) silent += k
        }
        assertTrue("这些隐藏开关一个像素都没藏掉（on 读错字段 / 谓词写错）：$silent", silent.isEmpty())
    }

    /** 自检：代表照不属于那个类别时，检查器必须立刻报警——否则整篇断言是死的。 */
    @Test
    fun sweepReportsWhenTheRepresentativeIsNotItsKind() {
        val mislabeled = reps + (7 to item("Screenshot_x.png", 999))
        val bad = violations(mislabeled.values.toList(), hideKeys)
        assertTrue(
            "检查器是死的：把「只看副本」的代表照换成一张截图，扫描仍然全绿",
            bad.any { it.contains("只看#7") },
        )
    }
}
