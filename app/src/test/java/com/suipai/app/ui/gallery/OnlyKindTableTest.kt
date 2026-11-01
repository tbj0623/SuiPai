package com.suipai.app.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「只看某一类」那张表的钉子。
 *
 * 31 档原来抄了三遍（标签 / 空态 / 取数），收成 `OnlyKinds` 之后，
 * 这一篇钉住"一张表当三张用"必须成立的几件事。
 * 真正跑 `pick` 的覆盖面在设备侧的 `GalleryOnlyKindTest`（31 类 × 27 种隐藏组合），
 * 这里不重复它——JVM 里造不出 `PhotoItem`（它持有 `android.net.Uri`）。
 */
class OnlyKindTableTest {

    /** 纯检查器：给定任意一份表，报出所有不成形的行。 */
    private fun mismatches(rows: List<OnlyKind>): List<String> {
        val out = ArrayList<String>()
        val ids = rows.map { it.id }
        if (ids != (1..31).toList()) out += "编号必须恰好是 1..31 各一次且按序（实得 $ids）"
        if (rows.map { it.label }.toSet().size != rows.size) out += "格子标签有重复"
        if (rows.map { it.hint }.toSet().size != rows.size) out += "空态文案有重复"
        for (r in rows) {
            if (r.label.isBlank()) out += "编号 ${r.id} 的标签是空的"
            if (!r.hint.contains("没有")) out += "编号 ${r.id} 的空态不像空态：${r.hint}"
        }
        return out
    }

    @Test
    fun tableIsWellFormed() {
        assertEquals(emptyList<String>(), mismatches(OnlyKinds))
    }

    @Test
    fun cycleModulusMatchesTheTableSize() {
        // 抽屉里是 `filter = (filter + 1) % 32`：0 = 没选，1..31 = 表里的 31 档。
        assertEquals(32, OnlyKinds.size + 1)
    }

    @Test
    fun labelsDoNotCollideWithTheOtherCycleCells() {
        // 抽屉里另有九格循环控件，它们的名字与「只看」的候选重名的话，
        // 语义树里就会出现两个同名可点节点，自动化与读屏都会点错（踩过：冷暖那格）。
        val others = setOf(
            "全部日期", "时段", "星期", "清掉这三格", "全部清空", "方向", "画幅", "像素",
            "体积", "短边", "格式", "只看", "筛选", "显示",
        )
        val hit = OnlyKinds.map { it.label }.filter { it in others }
        assertTrue("「只看」的候选与其它格子重名：$hit", hit.isEmpty())
    }

    @Test
    fun editedKindExemptionsPointAtRealRows() {
        // `hideEdits` 是一条覆盖 20 个前缀的并集开关，它豁免的编号必须真的存在；
        // 指到一个不存在的编号 = 白豁免，指漏 = 那一档点开恒空（§19 量到的就是这种）。
        val ids = OnlyKinds.map { it.id }.toSet()
        assertTrue("EditedKindIds 里有不存在的编号：${EditedKindIds - ids}", (EditedKindIds - ids).isEmpty())
        assertTrue("18..31 那批尺寸裁切全都在 `isEditedShot` 里，必须整体豁免",
            (18..31).toSet().minus(EditedKindIds).isEmpty())
        assertTrue(EditedKindIds.containsAll(setOf(8, 9, 14, 15, 16, 17)))
    }

    /** 「隐藏这些」那一组与 HideRules 必须一一对应：加一个开关忘了写规则，界面上就有藏不掉的类别。 */
    @Test
    fun everyHideFlagHasExactlyOneRule() {
        val flags = GalleryFlag.inGroup(GalleryFlagGroup.HIDE).toSet()
        val ruleFlags = HideRules.map { it.flag }
        assertEquals("HideRules 里有重复的开关", ruleFlags.size, ruleFlags.toSet().size)
        assertEquals("开关表与规则表不是一张", flags, ruleFlags.toSet())
    }

    /** 豁免只能指向真的存在、而且**认得出成员**的编号：指错 = 白豁免，那一档还是死胡同。 */
    @Test
    fun everyExemptKindIsRealAndHasAMembershipTest() {
        val byId = OnlyKinds.associateBy { it.id }
        for (r in HideRules) {
            for (id in r.exempt) {
                val k = byId[id]
                assertTrue("${r.flag} 豁免了一个不存在的编号 $id", k != null)
                assertTrue("${r.flag} 豁免的编号 $id 没有 match，豁免不成立", k?.match != null)
            }
            if (r.drop == null) {
                assertTrue("${r.flag} 既没有自带谓词也没有豁免编号，等于什么都不藏", r.exempt.isNotEmpty())
            }
        }
    }

    /** 自检：把一行的标签抄成另一行的，检查器必须立刻报警。 */
    @Test
    fun checkerActuallyCatchesADuplicatedLabel() {
        val dup = OnlyKind(7, OnlyKinds[7].label, OnlyKinds[6].hint, OnlyKinds[6].match)
        val swapped = OnlyKinds.map { if (it.id == 7) dup else it }
        val bad = mismatches(swapped)
        assertTrue("标签重复没被抓到，检查器是死的", bad.any { it.contains("标签有重复") })
        assertTrue("对好的表也报警了", mismatches(OnlyKinds).isEmpty())
    }
}
