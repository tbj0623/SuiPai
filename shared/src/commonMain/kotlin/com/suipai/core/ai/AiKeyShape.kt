package com.suipai.core.ai

/**
 * 粘贴进来的 Key 长得对不对——**不是**校验它有没有权限，那只能问服务端。
 *
 * 存在的理由是一次设备实测：2026-09-21 在 suipai_r4 上自动化输入 49 个字符的 Key 时漏了字符，
 * 应用照单收下，之后每次点「看这张图」都拿到 401，界面只能说「Key 不对或已失效」。
 * 用户当时看到的是一句**把锅甩给他**的话，而真相是「这一步根本没存进去对的串」。
 * 这两件事必须分开说：形状不对在本地就能知道，不必发一个注定失败的请求出去。
 *
 * 规则故意宽松：只挡明显不可能是 Key 的输入（空、太短、带空白、带全角/非 ASCII），
 * 不去猜各家的格式——猜格式的下一步就是「我们的 Key 长度限制」这种把用户挡在外面的东西。
 */
object AiKeyShape {

    /** 最短可接受长度。实测最短的一家（智谱 32+1+16 = 49）远大于它，Agnes 的 `sk-…` 是 51。 */
    const val MIN_LENGTH = 16

    /** 返回 null 表示可以存；否则返回该直接显示给用户的那句话。 */
    fun problem(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return "还没有粘贴 Key"
        if (t.length < MIN_LENGTH) return "太短了（不足 $MIN_LENGTH 个字符），像是没粘贴全"
        if (t.any { it.isWhitespace() }) return "Key 里混进了空格或换行，只贴那一串字符本身就行"
        if (t.any { it.code < 0x21 || it.code > 0x7E }) {
            return "Key 里有非 ASCII 字符（常见于输入法把符号换成全角了），请改用英文半角再贴一次"
        }
        return null
    }
}
