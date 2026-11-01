package com.suipai.core.ai

/**
 * 一个「OpenAI 兼容」的对话端点档位。
 *
 * 只收官方对开发者开放的端点。豆包 / 腾讯元宝那类消费级聊天产品没有公开 API，
 * 逆向它们的私有接口要签请求、要绑用户登录态、随时失效，而且会把账号写进我们的 App，
 * 所以不在候选里。
 *
 * [vision] 为 false 时 [OpenAiCompatible.chatBody] 不会带图，
 * 免得用户以为开了图却其实只发了文字。
 */
data class AiEndpoint(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val vision: Boolean,
    /** 聚合商 = 它收到请求后再转给别家模型。界面要按这个把取舍说清楚。 */
    val aggregator: Boolean = false,
    /**
     * 20 张有实测色偏的真照片上「所选配方有问题」的张数（与画面实测色偏矛盾，或者给一条
     * 什么都不改的安全牌），越小越好；写区间而不是单值，因为**同一套配置重跑一次就会差一张**
     * （2026-09-22 智谱两臂 8 与 7、Agnes 两臂 4 与 3）。null = 这一档没量过，界面就不许引用这个数。
     */
    val pickConflictsOf20: IntRange? = null,
) {
    /**
     * 界面上那一句话的原文，放在 commonMain 是为了能被测到（`AiLayerTest`）：
     * 措辞必须说清它数的是哪两类问题，不能只说"色偏矛盾"——安全牌不越任何阈值。
     */
    val pickConflictsNote: String?
        get() = pickConflictsOf20?.let {
            val n = if (it.first == it.last) "${it.first} 张" else "${it.first}~${it.last} 张"
            "实测 20 张里 $n 有问题：与画面实测的色偏矛盾，或给一条什么都不改的安全牌。"
        }
    companion object {
        /**
         * 2026-09-21 用同一张 1×1 PNG 实测过的档位（延迟是当次往返，含建连）。
         * 注意智谱的 `GET /models` 只列文本模型，**视觉模型不在列表里但能按名字调**，
         * 所以这里按名字写死而不是从列表里挑。
         *
         * 默认是智谱，不是因为它更强，而是因为它是这些端点里唯一「模型厂商＝接口厂商」的一家：
         * Agnes 是聚合商，把用户的照片发给一家说不清是谁的中间方，是比发给智谱更大的一条隐私申报。
         * 代价要写明白——同一批 20 张真照片、同一套 prompt 下量的「所选配方有问题」张数
         * （与画面实测色偏矛盾，或给一条什么都不改的安全牌）：
         * 智谱 **7~8/20**，Agnes **3~4/20**，且 Agnes 给的 `seen` 明显更贴画面，用到的配方种类
         * 11~13 种 vs 智谱 7~8 种。**Agnes 质量更好，是已知的。**
         *
         * 两个数都是**同一套配置重跑一次**得到的区间（2026-09-22：智谱 8 与 7、Agnes 4 与 3），
         * 所以别再对外承诺单值。同一个实验还量到一条对候选表的判断：
         * 候选表里那 30 条自动抽的配方，智谱 20 张里 **0 次**选中（它只抄自己认识的 22 条基础配方），
         * Agnes 选中 6 次、并把种类从 11 撑到 13。留着这 30 条的理由是
         * 「AI 的上限不该低于本地智能适配」，不是它们能救智谱的质量。
         *
         * 要换默认就在 `AiSettings` 里改初始档位，同时把 `AboutSheet` 与两份政策文案里
         * 「发往你自己填写的那一家官方接口」的措辞再核一遍。
         */
        val ALL = listOf(
            AiEndpoint(
                "zhipu-vision", "智谱 视觉", "https://open.bigmodel.cn/api/paas/v4", "glm-4v-flash",
                true, pickConflictsOf20 = 7..8,
            ),
            AiEndpoint("zhipu-text", "智谱 文本", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", false),
            AiEndpoint(
                "agnes-vision", "Agnes 视觉", "https://api.agnes-ai.cn/v1", "agnes-2.5-flash",
                true, aggregator = true, pickConflictsOf20 = 3..4,
            ),
            AiEndpoint(
                "agnes-pro", "Agnes 强档", "https://api.agnes-ai.cn/v1", "agnes-2.5-pro",
                true, aggregator = true,
            ),
        )

        fun byId(id: String): AiEndpoint = ALL.firstOrNull { it.id == id } ?: ALL.first()
    }
}
