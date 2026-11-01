package com.suipai.app.ui.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「随拍原片」这个名字判据。
 *
 * 钉两件事，方向刚好相反：
 * 1. 相机自己存出去的名字**必须**被认成原片——所以直接去源码里找那条模板，
 *    哪天改了命名（加了前缀、加了序号），这里立刻红，并指向要同步改的判据。
 * 2. 任何加工件的名字**不许**被认成原片。原来这里是六个 `!startsWith` 的手抄表，
 *    于是 `SuiPai_灰度_*`、`SuiPai_2K_*` 全都算进了「仅随拍」，
 *    而那一档自己的空态写着「还没有随拍**原片**」。
 */
class SuiPaiNameTest {

    private val originals = listOf(
        "SuiPai_1790023855356.jpg",
        "SuiPai_1.jpg",
    )

    /** 加工件的前缀取自 app 自己写文件名的地方（ViewerActions 的尺寸表、SaveTransform 的 token、一瞬导出）。 */
    private val derivatives = listOf(
        "SuiPai_一瞬_暖调_70_1790023855356.jpg",
        "SuiPai_副本_1790023855356.jpg",
        "SuiPai_对比_冷调_1790023855356.png",
        "SuiPai_裁切_1790023855356.jpg",
        "SuiPai_裁切黄金_1790023855356.jpg",
        "SuiPai_裁切239_1790023855356.jpg",
        "SuiPai_裁切185_1790023855356.jpg",
        "SuiPai_裁切53_1790023855356.jpg",
        "SuiPai_旋转_1790023855356.jpg",
        "SuiPai_转向_1790023855356.jpg",
        "SuiPai_镜像_1790023855356.jpg",
        "SuiPai_灰度_1790023855356.jpg",
        "SuiPai_上下翻_1790023855356.jpg",
        "SuiPai_圆角_1790023855356.png",
        "SuiPai_2K_1790023855356.jpg",
        "SuiPai_拼图_1790023855356.jpg",
        "SuiPai_1080_1790023855356.jpg",
        "SuiPai_16_1790023855356.jpg",
        "SuiPai__1790023855356.jpg",          // 空前缀（token 漏值）也不算原片
    )

    private val foreign = listOf("IMG_1234.jpg", "Screenshot_20240101.png", "DSC_0001.jpg", "PXL_1.jpg")

    @Test
    fun cameraOwnNamesStillPassTheTest() {
        // 相机存原片的命名模板长这样（一个下划线 + 时间戳）；判据就是按这个形状写的。
        // 全模块扫一遍而不是盯死某个文件：哪天改成了 `SuiPai_RAW_${ts}.jpg`，
        // 这条会红并提醒同步 `isSuiPaiOriginalName`，而不是让「仅随拍」悄悄变成空的。
        val templates = File("src/main/java/com/suipai/app").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { Regex("\"SuiPai_[^\"]*\"").findAll(it.readText()).map { m -> m.value } }
            .toList()
        assertTrue("源码里一个 SuiPai_ 命名模板都没有，这一篇在空跑：$templates", templates.isNotEmpty())
        val originalShape = Regex("\"SuiPai_\\$\\{[^_\"]*\\}\\.jpg\"")
        assertTrue("找不到「一个下划线 + 时间戳」的原片命名模板，判据可能要跟着改。扫到：$templates",
            templates.any { originalShape.matches(it) })
    }

    @Test
    fun originalsAreRecognisedAndDerivativesAreNot() {
        for (n in originals) assertTrue("$n 应该算原片", isSuiPaiOriginalName(n))
        for (n in derivatives) assertFalse("$n 不算原片", isSuiPaiOriginalName(n))
        for (n in foreign) assertFalse("$n 不是随拍存的", isSuiPaiOriginalName(n))
    }

    /** 自检：把判据退回"只要不以六个已知前缀开头就算原片"的老写法，这一篇必须变红。 */
    @Test
    fun checkerActuallyCatchesTheOldHandCopiedRule() {
        val legacy: (String) -> Boolean = { name ->
            name.startsWith("SuiPai_") &&
                !name.startsWith("SuiPai_一瞬_") &&
                !name.startsWith("SuiPai_副本_") &&
                !name.startsWith("SuiPai_旋转_") &&
                !name.startsWith("SuiPai_转向_") &&
                !name.startsWith("SuiPai_镜像_") &&
                !name.startsWith("SuiPai_裁切_")
        }
        val leaked = derivatives.filter { legacy(it) }
        assertTrue("老写法根本没漏（那这条自检是死的）：$leaked", leaked.isNotEmpty())
        assertTrue("新写法漏了加工件：${derivatives.filter { isSuiPaiOriginalName(it) }}",
            derivatives.none { isSuiPaiOriginalName(it) })
        assertTrue("新写法把原片也拒了", originals.all { isSuiPaiOriginalName(it) })
    }
}
