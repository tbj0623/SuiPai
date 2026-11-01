package com.suipai.app.ui.collage

import androidx.compose.runtime.Immutable

/**
 * 拼图模板表：**纯数据**，一行坐标都不画。
 *
 * 这一层刻意不碰 Canvas / Compose：模板对不对是几何问题，能在 JVM 上一次算清
 * （见 `CollageLayoutsTest`），不该等到装到手机上才看出来。
 *
 * 所有坐标归一化到 0..1，原点在**左上角**，`x` 向右、`y` 向下。
 * 一张模板的不变量（测试逐条钉住）：
 * 1. 每个格子的 `x/y/w/h` 都在 [0,1] 内；
 * 2. 任意两格不重叠（交叠面积 ≤ 1e-9）；
 * 3. 全部格子面积之和 = 1.0，即**无缝隙、无留白**；
 * 4. `slots.size` 等于 id 里宣称的格数，`slots.size in 2..9`。
 *
 * 「面积 = 1 且互不重叠」在矩形集合上等价于「恰好铺满整张画布」——
 * 所以它同时排除了「少铺一块」和「多铺一块压在上面」两种错法。
 */
@Immutable
data class CollageSlot(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) {
    /** 右边界（开区间）。 */
    val right: Float get() = x + w

    /** 下边界（开区间）。 */
    val bottom: Float get() = y + h

    val area: Float get() = w * h

    /** 格子的中心点：拖拽换位时用来做命中与"拖动中"的实时预览。 */
    val centerX: Float get() = x + w / 2f
    val centerY: Float get() = y + h / 2f

    /** 点 (px, py) 是否落在这格里（归一化坐标系）。 */
    fun contains(px: Float, py: Float): Boolean =
        px >= x && px < right && py >= y && py < bottom
}

/**
 * 一张拼图模板。
 *
 * `id` 必须唯一且稳定（它会被写进偏好，用来记住"上次用的那套"）；
 * `name` 是界面上那两个字，模板横条上直接用。
 */
@Immutable
data class CollageLayout(
    val id: String,
    val name: String,
    val slots: List<CollageSlot>,
) {
    val count: Int get() = slots.size

    /** 第 i 格（越界时返回最后一格：模板横条换模板的那一帧会出现下标溢出）。 */
    fun slotAt(index: Int): CollageSlot = slots[index.coerceIn(0, slots.lastIndex)]

    /**
     * 归一化点落在第几格。**没有命中返回 -1**，不是 0 ——
     * 报 0 会让"点在画布外"变成"换到第一格"，一次误拖就把第 1 张换掉了。
     */
    fun hit(px: Float, py: Float): Int = slots.indexOfFirst { it.contains(px, py) }
}

/**
 * 12 张模板：2 格 2 种 / 3 格 3 种 / 4 格 3 种 / 6 格 2 种 / 9 格 1 种 / 横竖各一套。
 *
 * 「横竖各一套」落在 2 格与 4 格两组：`v2` 竖切、`h2` 横切；`row4` 四列（横幅）、
 * `leftbar4` 左一竖条（竖幅）、`top4` 上一条通栏（横幅）。任何画幅下都能挑到合适的切法，
 * 不需要一张单独的"横屏模板表"。
 *
 * 坐标全部用 `1f / 3f` 这类分数写成乘式，避免 0.3333333 那种手抄小数带来的浮点缝隙。
 */
object CollageLayouts {

    private fun s(x: Float, y: Float, w: Float, h: Float) = CollageSlot(x, y, w, h)

    /** 2 格：左右（竖切）。 */
    val v2 = CollageLayout(
        id = "v2",
        name = "两格·左右",
        slots = listOf(
            s(0f, 0f, 0.5f, 1f),
            s(0.5f, 0f, 0.5f, 1f),
        ),
    )

    /** 2 格：上下（横切）。 */
    val h2 = CollageLayout(
        id = "h2",
        name = "两格·上下",
        slots = listOf(
            s(0f, 0f, 1f, 0.5f),
            s(0f, 0.5f, 1f, 0.5f),
        ),
    )

    /** 3 格：左一大、右两张。 */
    val v3 = CollageLayout(
        id = "v3",
        name = "三格·左大",
        slots = listOf(
            s(0f, 0f, 0.5f, 1f),
            s(0.5f, 0f, 0.5f, 0.5f),
            s(0.5f, 0.5f, 0.5f, 0.5f),
        ),
    )

    /** 3 格：上一条通栏、下一排两张。 */
    val h3 = CollageLayout(
        id = "h3",
        name = "三格·上大",
        slots = listOf(
            s(0f, 0f, 1f, 0.5f),
            s(0f, 0.5f, 0.5f, 0.5f),
            s(0.5f, 0.5f, 0.5f, 0.5f),
        ),
    )

    /** 3 格：三列等宽。 */
    val col3 = CollageLayout(
        id = "col3",
        name = "三格·三列",
        slots = listOf(
            s(0f, 0f, 1f / 3f, 1f),
            s(1f / 3f, 0f, 1f / 3f, 1f),
            s(2f / 3f, 0f, 1f / 3f, 1f),
        ),
    )

    /** 4 格：2×2。 */
    val grid2x2 = CollageLayout(
        id = "grid2x2",
        name = "四格·田字",
        slots = listOf(
            s(0f, 0f, 0.5f, 0.5f),
            s(0.5f, 0f, 0.5f, 0.5f),
            s(0f, 0.5f, 0.5f, 0.5f),
            s(0.5f, 0.5f, 0.5f, 0.5f),
        ),
    )

    /** 4 格：四列（横幅）。 */
    val row4 = CollageLayout(
        id = "row4",
        name = "四格·四列",
        slots = listOf(
            s(0f, 0f, 0.25f, 1f),
            s(0.25f, 0f, 0.25f, 1f),
            s(0.5f, 0f, 0.25f, 1f),
            s(0.75f, 0f, 0.25f, 1f),
        ),
    )

    /** 4 格：左一竖条 + 右三横带（少见的非对称版式）。 */
    val leftBar4 = CollageLayout(
        id = "leftbar4",
        name = "四格·左条",
        slots = listOf(
            s(0f, 0f, 1f / 3f, 1f),
            s(1f / 3f, 0f, 2f / 3f, 1f / 3f),
            s(1f / 3f, 1f / 3f, 2f / 3f, 1f / 3f),
            s(1f / 3f, 2f / 3f, 2f / 3f, 1f / 3f),
        ),
    )

    /** 4 格：上一条通栏 + 下三列（横幅版式的另一种切法）。 */
    val top4 = CollageLayout(
        id = "top4",
        name = "四格·上条",
        slots = listOf(
            s(0f, 0f, 1f, 0.5f),
            s(0f, 0.5f, 1f / 3f, 0.5f),
            s(1f / 3f, 0.5f, 1f / 3f, 0.5f),
            s(2f / 3f, 0.5f, 1f / 3f, 0.5f),
        ),
    )

    /** 6 格：三列 × 两行。 */
    val grid3x2 = CollageLayout(
        id = "grid3x2",
        name = "六格·三列",
        slots = listOf(
            s(0f, 0f, 1f / 3f, 0.5f),
            s(1f / 3f, 0f, 1f / 3f, 0.5f),
            s(2f / 3f, 0f, 1f / 3f, 0.5f),
            s(0f, 0.5f, 1f / 3f, 0.5f),
            s(1f / 3f, 0.5f, 1f / 3f, 0.5f),
            s(2f / 3f, 0.5f, 1f / 3f, 0.5f),
        ),
    )

    /** 6 格：两列 × 三行（竖幅）。 */
    val grid2x3 = CollageLayout(
        id = "grid2x3",
        name = "六格·两列",
        slots = listOf(
            s(0f, 0f, 0.5f, 1f / 3f),
            s(0.5f, 0f, 0.5f, 1f / 3f),
            s(0f, 1f / 3f, 0.5f, 1f / 3f),
            s(0.5f, 1f / 3f, 0.5f, 1f / 3f),
            s(0f, 2f / 3f, 0.5f, 1f / 3f),
            s(0.5f, 2f / 3f, 0.5f, 1f / 3f),
        ),
    )

    /** 9 格：3×3。 */
    val grid3x3 = CollageLayout(
        id = "grid3x3",
        name = "九格·九宫",
        slots = listOf(
            s(0f, 0f, 1f / 3f, 1f / 3f),
            s(1f / 3f, 0f, 1f / 3f, 1f / 3f),
            s(2f / 3f, 0f, 1f / 3f, 1f / 3f),
            s(0f, 1f / 3f, 1f / 3f, 1f / 3f),
            s(1f / 3f, 1f / 3f, 1f / 3f, 1f / 3f),
            s(2f / 3f, 1f / 3f, 1f / 3f, 1f / 3f),
            s(0f, 2f / 3f, 1f / 3f, 1f / 3f),
            s(1f / 3f, 2f / 3f, 1f / 3f, 1f / 3f),
            s(2f / 3f, 2f / 3f, 1f / 3f, 1f / 3f),
        ),
    )

    /** 全部模板，按格数从少到多排（模板横条就按这个顺序）。 */
    val all: List<CollageLayout> = listOf(
        v2, h2,
        v3, h3, col3,
        grid2x2, row4, leftBar4, top4,
        grid3x2, grid2x3,
        grid3x3,
    )

    /**
     * 12 张是**下限承诺**（需求写的是"至少 12"）。这一句的存在让 `all` 被删短时
     * 立刻在类加载阶段就炸，而不是等到用户翻模板横条翻到底发现只有 9 种。
     */
    init {
        require(all.size >= 12) { "拼图模板至少 12 张，现在只有 ${all.size} 张" }
        require(all.map { it.id }.toSet().size == all.size) { "模板 id 撞车了" }
    }

    fun byId(id: String): CollageLayout = all.firstOrNull { it.id == id } ?: v2

    /**
     * 给 N 张照片挑一个默认模板。
     *
     * 规则是"能不裁就不裁"：正好有 N 格的模板优先；没有就退到**格数 ≥ N 的最小模板**
     * （多出来的格子用最后一格补，见 `CollageRenderer`），再没有就用最大的那张。
     * 2..9 张在 [all] 里都有正好匹配的模板，所以第二条今天只是兜底。
     */
    fun defaultFor(count: Int): CollageLayout {
        val n = count.coerceIn(2, 9)
        return all.firstOrNull { it.count == n }
            ?: all.filter { it.count >= n }.minByOrNull { it.count }
            ?: all.maxByOrNull { it.count }
            ?: v2
    }
}

/**
 * 「模板适合几张」在模板横条上的一句小字。
 *
 * 放在这里而不是界面里，是为了让测试也能引用同一个真值来源；
 * 界面那边的标签与读屏那句都从这里取，避免两处各抄一份。
 */
fun CollageLayout.fitLabel(count: Int): String = when {
    count == this.count -> "$count 张正好"
    // 照片比格子少：多出来的格子按 i % size 循环补图（见 CollageRenderer 的取图规则），
    // 所以这里说的是"补"，不是"空着"——空着的格子会被用户当成导出坏了。
    count < this.count -> "$count 张 · 补 ${this.count - count} 格"
    else -> "$count 张 · 放不下 ${count - this.count} 张"
}

/** 这套模板放不放得下这么多照片。界面据此把放不下的整颗灰掉，而不是让导出悄悄少几张。 */
fun CollageLayout.holds(count: Int): Boolean = count <= slots.size
