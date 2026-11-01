package com.suipai.app.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suipai.core.engine.YishunRecipe
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 微调表 ↔ 配方 7 个字段的互转。
 *
 * 这两边都是**按名字**对齐的（temp/contrast/sat/shadow/fade/highlight/soft 对
 * tempShift/contrastShift/…），表驱动重构最容易出错的就是把某两条的 lambda 写串——
 * 写串之后照片会「色温和阴影互换」这种谁也发现不了的错，所以每条都要单独喂不同的值。
 */
@RunWith(AndroidJUnit4::class)
class ShiftRecipeRoundTripTest {

    /** 每项一个互不相同、且在各自滑杆范围内的值。 */
    private val distinct = Shifts(
        temp = 0.11f,
        contrast = -0.13f,
        sat = 0.21f,
        shadow = -0.07f,
        fade = 0.17f,
        highlight = -0.09f,
        soft = 0.13f,
    )

    @Test
    fun everyShiftMapsToItsOwnRecipeField() {
        val recipe = recipeWithShifts(
            lookId = "daylight",
            intensity = 0.5f,
            grainMul = 1.2f,
            vignetteMul = 0.8f,
            exportEdge = 2048,
            shifts = distinct,
        )
        assertEquals(0.11f, recipe.tempShift, 0f)
        assertEquals(-0.13f, recipe.contrastShift, 0f)
        assertEquals(0.21f, recipe.satShift, 0f)
        assertEquals(-0.07f, recipe.shadowShift, 0f)
        assertEquals(0.17f, recipe.fadeShift, 0f)
        assertEquals(-0.09f, recipe.highlightShift, 0f)
        assertEquals(0.13f, recipe.softShift, 0f)
        // 非微调项不能被表吃掉
        assertEquals("daylight", recipe.lookId)
        assertEquals(1.2f, recipe.grainMul, 0f)
        assertEquals(0.8f, recipe.vignetteMul, 0f)
        assertEquals(2048, recipe.exportEdge)
    }

    @Test
    fun recipeToShiftsIsTheExactInverse() {
        val recipe = recipeWithShifts(
            lookId = "renox",
            intensity = 0.35f,
            grainMul = 1f,
            vignetteMul = 1f,
            exportEdge = 4096,
            shifts = distinct,
        )
        assertEquals(distinct, recipe.asShifts())
    }

    /** 配方是外部输入：越界的值要夹回滑杆能表达的范围，而不是原样进渲染。 */
    @Test
    fun outOfRangeRecipeValuesAreClampedPerField() {
        val wild = YishunRecipe(
            lookId = "daylight",
            intensity = 0.5f,
            tempShift = 999f,
            contrastShift = -999f,
            satShift = 0.9f,
            shadowShift = 2f,
            fadeShift = -3f,
            highlightShift = 7f,
            softShift = -8f,
        )
        val got = wild.asShifts()
        assertEquals(0.25f, got.temp, 0f)
        assertEquals(-0.25f, got.contrast, 0f)
        assertEquals(0.35f, got.sat, 0f)
        assertEquals(0.25f, got.shadow, 0f)
        assertEquals(-0.2f, got.fade, 0f)
        assertEquals(0.3f, got.highlight, 0f)
        assertEquals(-0.15f, got.soft, 0f)
    }

    /** 表的顺序决定了「复制微调」的换行位置（前 3 项一行），别让人随手重排。 */
    @Test
    fun tableOrderIsFixed() {
        assertEquals(
            listOf("temp", "contrast", "sat", "shadow", "fade", "highlight", "soft"),
            Shift.entries.map { it.name.lowercase() },
        )
    }
}
