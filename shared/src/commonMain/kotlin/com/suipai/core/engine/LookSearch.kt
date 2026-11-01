package com.suipai.core.engine

/**
 * 按文字在配方库里找。
 *
 * 存在的理由：AI 只能看到候选表里那几十条（把 5446 条全塞进 prompt 会把延迟和成本都抬起来，
 * 小模型也读不动），但它给出的『质感词』要能在**整库**里落地，否则智能推荐等于把配方库
 * 缩成几十条。这里做的是「以已选配方为锚 + 模型给的词」的检索，不是模糊字符串匹配。
 *
 * 锚很重要：模型的 query 经常是「暖色调 低饱和」这种空泛词，只按它搜会捞出一堆不相干的；
 * 把选中配方自己的名字与描述一起当锚，结果就收在同一色族里。
 */
object LookSearch {

    /** 名字与描述里的标点/空白都不参与匹配。 */
    private fun norm(s: String): String = buildString {
        for (c in s) if (c !in PUNCT && !c.isWhitespace()) append(c)
    }

    private val PUNCT = charArrayOf('。', '，', '、', '；', '：', '！', '？', '（', '）', '/', '~', '-', '—', '_', '"', '\'')

    private fun grams(s: String): Set<String> =
        if (s.length < 2) (if (s.isEmpty()) emptySet() else setOf(s))
        else (0..s.length - 2).map { s.substring(it, it + 2) }.toSet()

    /** 把「冷、青 低饱和」这类自由文本切成去重后的检索词。 */
    fun terms(text: String): List<String> =
        text.split(' ', '\n', '\t', ',', '，', '、', '；', ';', '/')
            .map { norm(it) }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * 以 [anchor] 这条配方为中心，在 [all] 里找最贴近的 [limit] 条（不含 anchor 自己）。
     *
     * 权重：anchor 的名字/描述词 ×3，模型额外给的词 ×2；
     * 命中名字 > 命中描述 > 二元组重叠。同一分再按 id 排，保证结果稳定可测。
     */
    fun around(anchor: Look, extraQuery: String, all: List<Look>, limit: Int = 3): List<Look> {
        val weights = HashMap<String, Int>()
        for (t in terms(anchor.name) + terms(summaryBody(anchor.summary))) {
            weights[t] = maxOf(weights[t] ?: 0, 3)
        }
        for (t in terms(extraQuery)) {
            weights[t] = maxOf(weights[t] ?: 0, 2)
        }
        if (weights.isEmpty()) return emptyList()
        return rank(all, weights, excludeId = anchor.id, limit = limit)
    }

    /**
     * 只有一串词时的检索（模型给了一个表里不存在的 id，但给了 query）。
     *
     * 权重全部按 ×2 算，并且**要求至少有一分像样的命中**：[MIN_FALLBACK] 以下说明这些词
     * 在整库的名字与描述里根本落不了地，此时宁可不给推荐，也不要拿一条不相干的配方糊弄用户。
     */
    fun byWords(words: String, all: List<Look>, limit: Int = 3): List<Look> {
        val weights = HashMap<String, Int>()
        for (t in terms(words)) weights[t] = maxOf(weights[t] ?: 0, 2)
        if (weights.isEmpty()) return emptyList()
        val ranked = rank(all, weights, excludeId = null, limit = limit)
        val best = ranked.firstOrNull()?.let { score(it, weights) } ?: 0
        return if (best < MIN_FALLBACK) emptyList() else ranked
    }

    /** 一次名字/描述命中（2 字词命中名字 = 120×2×词长系数）就够过线；纯二元组重叠过不了。 */
    private const val MIN_FALLBACK = 240

    private fun rank(all: List<Look>, weights: Map<String, Int>, excludeId: String?, limit: Int): List<Look> =
        all.asSequence()
            .filter { it.id != excludeId }
            .map { it to score(it, weights) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Look, Int>> { it.second }.thenBy { it.first.id })
            .take(limit)
            .map { it.first }
            .toList()

    private fun score(look: Look, weights: Map<String, Int>): Int {
        val n = norm(look.name)
        val sm = norm(look.summary)
        var total = 0
        for ((t, w) in weights) {
            if (t == n) total += 300 * w
            else if (n.isNotEmpty() && t in n) total += 120 * w
            if (t in sm) total += 40 * w else total += 6 * w * grams(t).count { it in sm }
        }
        return total
    }

    /** 描述里去掉开头的「名字。」——那部分和名字重复，会把同一条自己顶回候选。 */
    private fun summaryBody(summary: String): String =
        if ('。' in summary) summary.substringAfter('。') else summary
}
