package com.suipai.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarPrimary
import com.suipai.app.ui.theme.SuiPaiSheet
import kotlinx.coroutines.launch

/**
 * 首启引导：三屏，可滑动、可跳过，最后一屏请求权限。
 *
 * 三屏内容对应 `docs/ROADMAP.md` 里那一句验收画像「说清这是相册 / 一瞬做什么 / 照片不出手机」，
 * 而且每一屏只讲**代码真能做到**的事（文案口径与 `AboutSheet` / `store/privacy-policy.html`
 * 一致，不许新增未验证的能力描述）：
 *
 * 1. **相册**：时间线按日分组、收藏隐藏、批量动作、大图查看器 —— 全部是现有功能；
 * 2. **一瞬**：5446 条内置配方（`LookLibrary.all` 的真实条数）、强度可调、配方随 EXIF 走；
 * 3. **照片不出手机**：原图不上传、端侧处理、联网只有两处且都要用户亲手发起。
 *
 * 为什么用 `HorizontalPager` 而不是 `AnimatedContent` 或手写 `when`：
 * 引导的滑动是用户能感知的"翻页"，而 pager 自带 `settledPage` 状态，接权限回调时
 * 能确切知道"现在停在第几屏"；`when` 手写版要自己维护三个布尔，翻页手势得自己接。
 *
 * **权限不在这里申请**：这一屏只把"最后一步是哪一屏"报给调用方（[onFinish]），
 * 由接线方按现有的 `PermissionGate` / `requiredPermissions()` 那条已经装机验证过的路径去弹。
 * 本文件不 import 任何权限 API —— 首启引导没资格自己发明一套授权流程。
 *
 * 这一屏不碰导航（`MainActivity` 由 writer-arch 正在重构），只接参数 + 回调。
 *
 * @param onFinish 走完（或跳过）：调用方在这里标记完成态并请求权限
 * @param onBack 系统返回键：引导页把它当作"跳过"（第一屏时由调用方决定是否退出应用）
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = rememberOnboardingPages()
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val atLast = pager.currentPage == pages.lastIndex

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        // 页眉：品牌 + 「跳过」。跳过在第一屏也要有 —— 新用户如果只想赶紧看照片，
        // 逼他滑完三屏换来的不是理解，是卸载。跳过 = 走完（标记完成），下次不再弹。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "随拍",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                )
                Text(
                    "SUIPAI · CONTACT SHEET",
                    style = SuiPaiSheet.type.frameNo,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            AppBarCell(
                label = "跳过",
                on = false,
                onClick = onFinish,
                modifier = Modifier.testTag("onboarding_skip"),
            )
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("onboarding_pager"),
        ) { index ->
            OnboardingPage(
                page = pages[index],
                index = index,
                total = pages.size,
            )
        }

        // 页码：等宽编号，印样语言里那一行帧号；读屏把"第几屏 / 共几屏"说在活区里，
        // 因为它是滑动时会自己变的那句话（A11ySourceGuardTest 第 2 条那一族）。
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.forEachIndexed { i, _ ->
                val on = i == pager.currentPage
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (on) 8.dp else 6.dp)
                        .background(
                            if (on) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        ),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "${pager.currentPage + 1}/${pages.size}",
                style = SuiPaiSheet.type.frameNo,
                color = SuiPaiSheet.colors.number,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "第 ${pager.currentPage + 1} 屏，共 ${pages.size} 屏"
                },
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pager.currentPage > 0) {
                AppBarCell(
                    label = "上一屏",
                    on = false,
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                    modifier = Modifier.testTag("onboarding_prev"),
                )
            }
            Spacer(Modifier.weight(1f))
            if (atLast) {
                // 最后一屏的主按钮就是"去授权"。文案说清下一步会发生什么，
                // 不写「开始使用」——那会让用户以为点完就能看照片，实际还有一个系统弹框。
                AppBarPrimary(
                    label = "授权相册并开始",
                    onClick = onFinish,
                    modifier = Modifier.testTag("onboarding_allow"),
                )
            } else {
                AppBarPrimary(
                    label = "下一屏",
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier.testTag("onboarding_next"),
                )
            }
        }
        // 返回键走调用方，本文件不接 BackHandler：导航层由 writer-arch 统一管，
        // 这里再挂一个会让"返回该退到哪"出现两个真值来源。
    }
}

/** 一屏的内容模型。纯数据，便于单测核对"三屏都在、顺序固定、每屏都有话说"。 */
data class OnboardingPage(
    val title: String,
    val kicker: String,
    val bullets: List<String>,
    val footNote: String,
)

/**
 * 三屏正文。
 *
 * 每一条都对得上现有代码：
 * - 「5446 条配方」= `LookLibrary.all.size`（`docs/ROADMAP.md` 与商店文案同一口径）；
 * - 「配方随照片走」= `data/RecipeExif.kt` 把参数写进 EXIF；
 * - 「联网只有两处」= `store/privacy-policy.html` 的结论段，且都需用户亲手发起。
 *
 * 不写"AI 自动修图""一键变大片"这类能力描述：调色是端侧色彩科学管线，不改画面内容。
 */
fun onboardingPages(): List<OnboardingPage> = listOf(
    OnboardingPage(
        title = "把相册当成印样页翻",
        kicker = "01 / 相册",
        bullets = listOf(
            "时间线按日分组，滚到一半时顶部会留住当前这一组的日子，点它回到本组开头。",
            "按日期、画幅、像素、体积、文件名筛；收藏、隐藏、批量删除与批量副本都在。",
            "点开是大图查看器：双指缩放、旋转、裁切、导出十五档尺寸。",
        ),
        footNote = "照片始终是系统相册里的普通文件，随拍不会把它们搬到一个你看不见的地方。",
    ),
    OnboardingPage(
        title = "一瞬：把直出做成成片",
        kicker = "02 / 一瞬",
        bullets = listOf(
            "5446 条内置配方，按名字搜，也按照片现场选。",
            "强度 0–100 连续可调；按住看原图对比，左右滑换配方，上下滑调强度。",
            "微调会写进照片 EXIF，之后在任何一端打开都能读回来重放。原图永远保留。",
        ),
        footNote = "它调的是光影与色彩，不是磨皮瘦脸 —— 画面内容一个像素都不改。",
    ),
    OnboardingPage(
        title = "原图不出这台手机",
        kicker = "03 / 照片去哪了",
        bullets = listOf(
            "没有账号、没有广告、没有统计 SDK；照片不上传，处理全部在端侧完成。",
            "会出网的只有两件事：「互通」在同一 WiFi 下把照片直传给另一台设备；" +
                "可选的「智能配方」——它默认关闭，要你自己填 Key 并亲手按下按钮。",
            "系统备份只带走偏好设置，不带照片；收藏与隐藏的清单也不参与备份。",
        ),
        footNote = "下一步是系统的相册权限弹框。只授权「部分照片」也能用，之后可以随时在设置里补。",
    ),
)

/** 现在一共几屏（接线方判断"是不是最后一屏"时用）。 */
val ONBOARDING_PAGE_COUNT: Int get() = onboardingPages().size

/**
 * 三屏内容记住一次。
 *
 * 为什么不是 `remember { onboardingPages() }` 直接写在 `OnboardingScreen` 里：
 * `onboardingPages()` 每次都会重新拼 9 条长文案，而 pager 每翻一页都会重组外层；
 * 记住它之后，文案对象在整个引导期间是同一批实例（`HorizontalPager` 的 key 也稳定）。
 */
@Composable
private fun rememberOnboardingPages(): List<OnboardingPage> =
    androidx.compose.runtime.remember { onboardingPages() }

@Composable
private fun OnboardingPage(page: OnboardingPage, index: Int, total: Int) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 24.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            page.kicker,
            style = SuiPaiSheet.type.frameNo,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            page.title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(18.dp))
        page.bullets.forEachIndexed { i, line ->
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text(
                    "${i + 1}",
                    style = SuiPaiSheet.type.frameNo,
                    color = SuiPaiSheet.colors.number,
                    modifier = Modifier.width(22.dp),
                )
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, SuiPaiSheet.frame)
                .padding(12.dp),
        ) {
            Text(
                page.footNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "第 ${index + 1} 屏 / 共 $total 屏",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
}
