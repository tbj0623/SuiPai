package com.suipai.core.ai

import com.suipai.core.engine.Look
import kotlin.math.roundToInt

/**
 * 发给模型的候选配方表，以及整条 prompt。
 *
 * 为什么不把 5446 条配方全发：实测（2026-09-21，glm-4v-flash）把 200 个名字写进 prompt 之后，
 * 模型会在「银盐 / 墨灰」这两个它认得的词上塌缩，12 张里 10 张给同一个答案；
 * 换成**带数值签名的几十条候选**之后，12/12 交出表里真实存在的 id，且分散到 8 条不同配方。
 * 也就是说小模型需要的是「能读的属性」，不是「更多的选项」。
 *
 * 表是**从配方库现算的**，不是写死的清单：库以后加配方，候选自动跟上，不会出现 prompt 与库脱节。
 */
object AiRecipeTable {

    /** 智能适配本来就会用的那 22 条，永远在表里——AI 至少不该比本地启发式还窄。 */
    private val BASE_IDS = listOf(
        "daylight", "portrait", "indoor", "film", "celadon", "neon", "moon", "inkgold",
        "dawn", "night", "dusk", "wood", "blossom", "autumn", "snow", "mist", "bronze",
        "hazeblue", "cyan", "clear", "silver", "rain",
    )

    /** 色族桶：按名字/描述里出现的字归类，只为保证候选表覆盖各色相。 */
    private val HUE_BUCKETS = listOf(
        "黑白" to "黑白银盐墨", "紫" to "紫罗兰葡", "蓝" to "蓝靛藏", "青" to "青碧翠靛",
        "绿" to "绿松柏苔草叶", "黄" to "黄姜杏栗金", "红" to "红朱丹胭", "橙" to "橙柿橘柑",
        "粉" to "粉桃樱脂", "棕" to "棕褐咖栗泥土", "白" to "白霜雪乳纸瓷", "灰" to "灰埃尘雾烟",
        "暖" to "暖晴阳火烛", "冷" to "冷冰寒露泉",
    )

    /** 极低饱和的线：整库只有一条 monochrome=true（银盐），黑白向靠这条线补。 */
    private const val NEUTRAL_SAT = -0.16f

    fun hueBucket(look: Look): String {
        if (look.monochrome || look.saturation <= NEUTRAL_SAT) return "黑白"
        val text = look.summary.take(14) + look.name
        return HUE_BUCKETS.firstOrNull { (_, chars) -> chars.any { it in text } }?.first ?: "其他"
    }

    /**
     * 22 条基础配方 + 每个色族按饱和梯度取的 [perBucket] 条。
     *
     * 排序先按饱和再按色温，然后等间隔取样——这样同一族里拿到的是「低饱和→高饱和」的不同段，
     * 而不是库里挨着写的同类。
     */
    fun build(all: List<Look>, perBucket: Int = 2): List<Look> {
        val base = BASE_IDS.mapNotNull { id -> all.firstOrNull { it.id == id } }
        val baseIds = base.map { it.id }.toSet()
        val out = ArrayList(base)
        val buckets = LinkedHashMap<String, MutableList<Look>>()
        for (look in all) {
            if (look.id in baseIds) continue
            buckets.getOrPut(hueBucket(look)) { mutableListOf() }.add(look)
        }
        for ((name, _) in HUE_BUCKETS) {
            out += spread(buckets[name], perBucket)
        }
        out += spread(buckets["其他"], perBucket)
        return out.distinctBy { it.id }
    }

    /**
     * 从一族的池子里取 [perBucket] 条，**两头都要够到**。
     *
     * 原来的写法是「排序后每 `size/perBucket` 个取一个」：perBucket=2 时拿到的是下标 0 和 n/2，
     * 也就是「最灰的那条 + 中位数」，一族里最饱和的那条永远取不到——和上面 `build` 的注释
     * （"低饱和→高饱和的不同段"）说的不是一回事。现在按 [0, n-1] 均匀取点：2 条就是最淡与最浓。
     *
     * 顺带把一句臆断钉死：有人（2026-09-22 的我）会以为"候选表太灰是模型不肯换配方的原因"。
     * 量过了：整库 5446 条的饱和上限是 **+0.14**（p95 +0.08，sat>0.2 的一条都没有），
     * 这是一套胶片模拟库的本性。修抽样让每族的高那头进表，表顶从 +0.12 抬到 +0.14——
     * 它让候选表忠于库，但它不会、也不可能让推荐变浓艳。别拿它当质量改法的解释。
     */
    private fun spread(pool: List<Look>?, perBucket: Int): List<Look> {
        if (pool.isNullOrEmpty() || perBucket <= 0) return emptyList()
        val sorted = pool.sortedWith(compareBy({ it.saturation }, { it.temperature }, { it.id }))
        val take = minOf(perBucket, sorted.size)
        if (take == 1) return listOf(sorted[sorted.size / 2])
        val last = sorted.size - 1
        return (0 until take)
            .map { i -> sorted[((i * last).toFloat() / (take - 1)).roundToInt().coerceIn(0, last)] }
            .distinct()
    }

    /** 一行一条：`celadon 青瓷 温-0.02 反0.14 饱-0.06 粒0.06 褪0.04`。 */
    fun render(rows: List<Look>): String = rows.joinToString("\n") { line(it) }

    fun line(look: Look): String {
        val flags = buildString {
            if (look.monochrome) append(" 黑白")
            else if (look.saturation <= NEUTRAL_SAT) append(" 近黑白")
            if (look.skinProtect >= 0.4f) append(" 护肤")
        }
        return look.id + " " + look.name +
            " 温" + num(look.temperature) +
            " 反" + num(look.contrast) +
            " 饱" + num(look.saturation) +
            " 粒" + num(look.grain) +
            " 褪" + num(look.fade) + flags
    }

    /** 0.14 / -0.02 / 0：去掉 float 尾巴的 0，省 token 也少给模型制造噪声。 */
    private fun num(v: Float): String {
        val r = (v * 100).toInt() / 100f
        val asInt = r.toInt()
        return if (r == asInt.toFloat()) asInt.toString() else r.toString().trimEnd('0').trimEnd('.')
    }
}

/**
 * 随拍「看这张图」的 prompt。
 *
 * 措辞是 2026-09-21 在 12 张真图上逐版调出来的（v1 自由发挥→模型乱给词；
 * v3 只给名字→在银盐/墨灰上塌缩；本版给数值签名→12/12 交出表内真实 id）。
 * 改动这里请重跑那批对照，不要凭读感改。
 */
object AiPrompt {

    const val USER_TEXT = "这张照片该用哪个配方？只回 JSON。"

    fun system(candidates: List<Look>): String = SYSTEM_HEAD + AiRecipeTable.render(candidates) + "\n" + SYSTEM_TAIL

    /**
     * 提问文本。带上本机量得的色偏/明度/饱和（[AiPhotoFacts]）是有原因的，
     * 见那个类的注释：让模型自己判冷暖会判错，用规则骂它则整体服从度更差。
     */
    fun userText(facts: AiPhotoFacts?): String =
        if (facts == null) USER_TEXT else facts.line() + "\n" + USER_TEXT

    private const val SYSTEM_HEAD =
        "你是随拍 App 的胶片调色助手。下面是候选配方，每行依次是：id 名称 温(色温) 反(反差) 饱(饱和) 粒(颗粒) 褪(褪色)。" +
            "数值带正负，越大越偏那个方向；标 近黑白 的是极低饱和，标 护肤 的适合人脸。\n"

    private const val SYSTEM_TAIL =
        "先看照片再回答。只输出一个 JSON 对象，不要解释、不要代码围栏、不要新增字段：\n" +
            """{"seen":"不超过10字：主体+光线+主色","lookId":"表里某一行的 id 那一段",""" +
            """"why":"不超过14字，为什么这条适合","query":"2~3个色相质感词 空格分隔",""" +
            """"temp":0,"contrast":0,"sat":0,"shadow":0,"fade":0,"highlight":0,"soft":0}""" + "\n" +
            "硬性要求：\n" +
            "a) lookId 只写 id 那一段英文小写（例：celadon），不要带名称、不要带空格或竖线。\n" +
            "b) 七个偏移默认全 0；只有照片确实需要在这条配方之上再加减时才改，最多改 3 个。" +
            "取值范围 temp ±0.22、contrast ±0.2、sat ±0.3、shadow -0.16~0.2、" +
            "fade -0.12~0.22、highlight -0.16~0.24、soft -0.12~0.22。\n" +
            "c) 配方已经做到的事不要重复调：选了低饱和配方就不要再给负的 sat。\n" +
            "d) 只有照片本身接近黑白时才选 近黑白 那几条；有人脸的优先带 护肤 的。\n" +
            "e) query 用于在同族里找备选，只写色相与质感词（冷、青、低饱和…），不要写摄影术语。\n"
}
