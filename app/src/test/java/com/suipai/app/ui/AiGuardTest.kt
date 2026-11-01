package com.suipai.app.ui

import com.suipai.app.engine.AiAdvisor
import com.suipai.app.engine.Shift
import com.suipai.app.ui.yishun.LookFilter
import com.suipai.app.ui.yishun.YishunFlag
import com.suipai.core.ai.AiShiftLimits
import com.suipai.core.engine.LookLibrary
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「智能配方」的结构守卫，全部在 JVM 上跑（不需要设备、不需要 Key、不发一个请求）。
 *
 * 这一层要防的不是崩溃，是**说谎**：随拍对外申报的是「照片不上传、依赖只有 androidx/Kotlin/Coil、
 * 关掉开关不出网」。这三句任何一句和代码对不上，Play 的数据安全表与「欺骗性行为」政策
 * 会同时踩中，而且是在用户毫无察觉的情况下踩中。所以这里钉的是**出网面**与**文案与代码的一致性**。
 */
class AiGuardTest {

    /** 出网面只有一处：app 里唯一能建 HTTPS 连接的文件就是 UrlAiHttp。 */
    @Test
    fun httpsIsOpenedInExactlyOneFile() {
        val hits = appSources().filter {
            "HttpURLConnection" in it.second || "java.net.URL(" in it.second
        }.map { it.first }
        assertTrue("出网代码散到了多处：$hits", hits == listOf("UrlAiHttp.kt"))
    }

    /** 没有第三个网络栈：一旦引入 OkHttp/Ktor/Retrofit，申报里的依赖表就得整段重写。 */
    @Test
    fun noThirdPartyHttpStackIsDeclared() {
        val banned = listOf("okhttp3", "retrofit", "ktor-", "cronet")
        val gradle = listOf(repoFile("app/build.gradle.kts"), repoFile("shared/build.gradle.kts"))
        for (f in gradle) {
            val text = f.readText()
            for (b in banned) {
                assertTrue("${f.name} 里出现了 $b，数据安全申报的依赖表要同步改", b !in text)
            }
        }
        // 自检：拿一个确实含依赖的文件跑同一个判据，证明「找不到」不是因为扫了个空清单
        assertTrue(
            "两份 gradle 文件都是空的，这条守卫等于没跑",
            gradle.any { "implementation" in it.readText() },
        )
    }

    /**
     * AI 的可信区间必须与滑杆的可信区间逐条相等。
     *
     * 两边都是「7 个偏移能落在哪」：shared 用它夹模型输出，app 用它落盘与显示。
     * 分开写迟早对不上——AI 给 0.3 而滑杆只到 0.25 时，界面显示 0、预览却已经变了，
     * 用户和开发者都看不出为什么。
     */
    @Test
    fun aiShiftLimitsMatchTheSliderTrustRanges() {
        val limits = mapOf(
            Shift.Temp to AiShiftLimits.TEMP,
            Shift.Contrast to AiShiftLimits.CONTRAST,
            Shift.Sat to AiShiftLimits.SAT,
            Shift.Shadow to AiShiftLimits.SHADOW,
            Shift.Fade to AiShiftLimits.FADE,
            Shift.Highlight to AiShiftLimits.HIGHLIGHT,
            Shift.Soft to AiShiftLimits.SOFT,
        )
        assertTrue(
            "滑杆加了新的一项而 AI 的区间表没跟上：${limits.size} vs ${Shift.entries.size}",
            limits.size == Shift.entries.size,
        )
        // ClosedFloatingPointRange 不保证 equals，逐端点比
        val bad = limits.filter { (s, l) -> s.clamp.start != l.start || s.clamp.endInclusive != l.endInclusive }
            .map { (s, l) -> "${s.name}：滑杆 ${s.clamp.start}..${s.clamp.endInclusive} 对不上 AI ${l.start}..${l.endInclusive}" }
        assertTrue(bad.joinToString("; "), bad.isEmpty())
        // 自检：区间表不是「所有滑杆都长一样」，否则上面那条比较是恒真的
        assertTrue(
            "所有滑杆的可信区间都相同，比较没有意义",
            limits.keys.map { it.clamp.start to it.clamp.endInclusive }.distinct().size > 3,
        )
    }

    /**
     * 政策文案里报的缩略图像素数必须与代码实际发的一致。
     *
     * 这是全项目最容易被无声改掉的一个数字：[AiAdvisor.SEND_EDGE] 从 256 调到 1024，
     * 代码、app 内文案、商店文案三处只改一处，申报就成了假话，而它看起来完全正常。
     * app 内的「关于」抽屉故意写成引用常量（只有一个真值来源），所以那里查的是引用而不是数字。
     */
    @Test
    fun privacyTextsQuoteTheThumbnailSizeTheCodeActuallySends() {
        val edge = AiAdvisor.SEND_EDGE.toString()
        val policy = repoFile("store/privacy-policy.html").readText()
        val listing = repoFile("store/listing-zh.md").readText()
        val about = repoFile("app/src/main/java/com/suipai/app/ui/about/AboutSheet.kt").readText()
        assertTrue("隐私政策没写缩略图长边 $edge", edge in policy)
        assertTrue("商店文案没写缩略图长边 $edge", edge in listing)
        assertTrue("关于抽屉应当引用 AiAdvisor.SEND_EDGE 而不是重抄一个数字", "AiAdvisor.SEND_EDGE" in about)
        // 政策里连 JPEG 质量都报了，那也得对上
        assertTrue("隐私政策没报质量 ${AiAdvisor.SEND_QUALITY}", AiAdvisor.SEND_QUALITY.toString() in policy)
    }

    /** 一瞬顶栏多了一颗「AI」，它不能和任何已有格子撞名（同名 = 同一个状态两把钥匙）。 */
    @Test
    fun aiEntryIsWiredAndDoesNotClashWithExistingCells() {
        val ai = repoFile("app/src/main/java/com/suipai/app/ui/ai/AiSheet.kt").readText()
        val yishun = repoFile("app/src/main/java/com/suipai/app/ui/yishun/YishunScreen.kt").readText()
        assertTrue("一瞬顶栏没有 AI 入口", """label = "AI"""" in yishun)
        assertTrue("AI 抽屉没被一瞬挂上", "AiSheet(" in yishun)
        assertTrue("AI 抽屉没有 testTag，设备端验证抓不到它", "ai_run" in ai)
        val labels = Regex("""label = "([^"]+)"""").findAll(ai).map { it.groupValues[1] }.toList()
        assertTrue("AI 抽屉一个 label 都没抽到，抽取器失效了", labels.isNotEmpty())
        val dup = labels.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("AI 抽屉里有同名格：$dup", dup.isEmpty())
        // 与一瞬表驱动格的撞名检查：那颗新格子进的是同一条顶栏，撞名就等于两把钥匙
        val tabled = YishunFlag.entries.flatMap { listOf(it.labelOff, it.labelOn) } +
            LookFilter.entries.flatMap { listOf(it.labelOff, it.labelOn) }
        assertTrue("「AI」与一瞬已有的表驱动格撞名", "AI" !in tabled)
    }

    /**
     * 分组标题不能和同一抽屉里的按钮文案同名。
     *
     * 2026-09-21 在设备上实测过这个坑：抽屉里既写了 `AppSheetSection("看这张图")`
     * 又有一颗 `AppBarPrimary(label = "看这张图")`，uiautomator 里标题节点先出现，
     * 自动化按文字点过去点到的是一段不可点的文字——现象和「功能坏了」一模一样，
     * 而人眼看截图根本看不出点错了。同名文案对无障碍服务和自动化都是歧义源。
     */
    @Test
    fun sectionTitlesDoNotEqualButtonLabels() {
        val ai = repoFile("app/src/main/java/com/suipai/app/ui/ai/AiSheet.kt").readText()
        val titles = Regex("""AppSheetSection\("([^"]+)"""").findAll(ai).map { it.groupValues[1] }.toList()
        val labels = Regex("""label = "([^"]+)"""").findAll(ai).map { it.groupValues[1] }.toList()
        assertTrue("没抽到分组标题，抽取器失效了", titles.isNotEmpty())
        assertTrue("没抽到按钮文案，抽取器失效了", labels.isNotEmpty())
        val both = titles.filter { it in labels }
        assertTrue("分组标题与按钮同名，按文字定位会点错：$both", both.isEmpty())
    }

    /** 候选表在设备上现算：慢到能被察觉时这条会变红，提醒改成启动预热。 */
    @Test
    fun candidateTableBuildsFastEnoughToComputeOnTap() {
        assertTrue("配方库没加载出来", LookLibrary.all.size > 1000)
        val started = System.currentTimeMillis()
        val table = AiAdvisor.candidates
        val millis = System.currentTimeMillis() - started
        assertTrue("候选表只有 ${table.size} 条，AI 无从选择", table.size >= 30)
        assertTrue("现算候选表花了 ${millis}ms，得改成启动时预热", millis < 1500)
        // 自检：候选表必须是真配方的子集，不是凭空造的
        val ids = LookLibrary.all.map { it.id }.toSet()
        assertTrue("候选表里有库外的 id", table.all { it.id in ids })
    }

    private fun repoFile(relative: String): File {
        // :app 的单测工作目录是 app/，所以仓库根要往上退一层
        val f = File("../$relative")
        assertTrue("找不到 $relative（从 ${File("").absolutePath} 出发）", f.isFile)
        return f
    }

    private fun appSources(): List<Pair<String, String>> {
        val root = File("src/main/java/com/suipai/app")
        assertTrue("找不到源码根，工作目录变了：${root.absolutePath}", root.isDirectory)
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.readText() }
            .toList()
    }
}
