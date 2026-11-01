package com.suipai.app.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suipai.app.state.AppRoute
import com.suipai.app.state.ChromeState
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.common.AppSheet
import com.suipai.app.ui.common.AppSheetSection
import com.suipai.app.ui.common.rememberShortViewport
import com.suipai.app.ui.theme.SuiPaiSheet

/**
 * 根外壳：底栏 + 印样页眉。
 *
 * 这一整块是从 `MainActivity` 的 `else ->` 分支**逐行搬**过来的，一个 testTag、
 * 一句语义、一处注释的结论都不许变 —— 它被四个读源码的守卫钉着
 * （`NavTabSemanticsGuardTest`、`CompactChromeGuardTest`、`PerfSourceGuardTest` 的
 * `rawTextsInToolbars` / `toolbarRowThatStarvesCells`）。搬家只动了宿主文件，形状原样；
 * 两个守卫的扫描路径因此从 `MainActivity.kt` 改到本文件（详见 `docs/arch/APP-ARCH.md`
 * 的「迁移账」）。
 *
 * 两处**必须**保留的结构，读屏与探针都依赖：
 *  1. 矮屏（手机横屏）不放底栏，三个 tab 搬进页眉 —— `if (!shortChrome)` 那道闸门；
 *  2. 底栏那颗可点节点自己带名字与选中态（`clearAndSetSemantics` 在 `clickable` **之后**），
 *     图标那颗 `contentDescription = null`。
 */

/**
 * 底栏。矮屏（手机横屏 411 dp）那一档底部导航条占 272 px = 25 % 屏高，
 * 所以那里整条不出现，三个 tab 改由 [AppHeader] 里的格子承担。
 */
@Composable
fun AppBottomBar(
    currentTabIndex: Int,
    onTab: (Int) -> Unit,
) {
    val shortChrome = rememberShortViewport()
    // 矮屏不放底栏：三个 tab 搬去页眉那一行了（见 AppHeader 里的格子）。
    if (!shortChrome) {
        // 三个 tab 的名字只留一份：底栏和矮屏页眉共用，改名不会只改到一半。
        val tabNames = AppRoute.TabTitles
        val sheetColors = SuiPaiSheet.colors
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            listOf(
                Icons.Outlined.Photo,
                Icons.Outlined.Collections,
                Icons.Outlined.DevicesOther,
            ).forEachIndexed { i, icon ->
                val tabOn = currentTabIndex == i
                // 名字与选中态写在**可点这一颗**上：这颗 Box 自己带
                // `.clickable(role = Role.Tab)`，紧跟一道 `clearAndSetSemantics`。
                // 顺序不能反 —— `LayoutNode.collapseSemantics` 从最里层往外收语义，
                // 收到 clearAndSetSemantics 会把已经收进来的整片清空（连 click 动作一起，
                // `AppBarPrimary` 就是这么量到 `click=false` 的）。
                // 为什么不把 contentDescription 挂在 `NavigationBarItem` 的 modifier 上：
                // 它的 clickable 比我们传进去的 modifier 更靠内，清空会把动作清掉；不清空
                // 又落不到可点那一颗上 —— 那颗是 mergeDescendants 的（Clickable.kt:1107
                // 恒为 true），只要子树里还有语义节点，无障碍桥就不写它自己的
                // contentDescription，而是另发一颗**不可点的假子节点**去带这个名字
                // （delegate:842-850 与 SemanticsNode.kt:407-419）。装机 dump 到的
                // `nav_tab_* text='' desc=''` + 子 TextView 就是这件事。
                // 清空子树顺带把 label 那颗 TextView 摘掉，「照片」只出声一次；选中态走
                // `selected`（Role.Tab 下写进 isSelected，dump 里的 sel= 就是它），
                // 不并进标签文字，免得读屏把「已选中」念两遍。
                // 外壳用 Box 不用 Row：`AppBarCell` 就是这个形状，装机量到过
                // "一颗命名节点 + click=true"；页眉那颗「关于随拍」用 Row 写同样的语义
                // 却量到 click=false（它自己那段注释如实记着，还没量明白），不跟它赌。
                // 里面那层 Row 只为给 `NavigationBarItem` 提供 RowScope：
                // 指示胶囊、配色动画、80dp 高全部照旧，画的东西一行没改。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("nav_tab_$i")
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onTab(i) },
                        )
                        .clearAndSetSemantics {
                            contentDescription = tabNames[i]
                            selected = tabOn
                        },
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        NavigationBarItem(
                            selected = tabOn,
                            onClick = { onTab(i) },
                            icon = {
                                // 装饰：名字由外面那颗负责，图标再带一份就是重复朗读
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = if (tabOn) sheetColors.grease
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            label = {
                                Text(
                                    tabNames[i],
                                    style = SuiPaiSheet.type.frameNo,
                                    color = if (tabOn) sheetColors.grease
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = sheetColors.grease,
                                selectedTextColor = sheetColors.grease,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = sheetColors.grease.copy(alpha = 0.14f),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 印样页眉：页名（同时是「关于」入口）+ 可点的日光/暗房切换（等宽小字）。
 * 矮屏那一档额外把三个 tab 收进这一行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppHeader(
    currentTabIndex: Int,
    onTab: (Int) -> Unit,
    chrome: ChromeState,
    selectedIds: Set<Long> = emptySet(),
    onNavigate: (AppRoute) -> Unit = {},
) {
    // 「更多」抽屉的开合。**这是页眉自己的瞬时状态，不进 `ChromeState`**：
    // 它不跨屏存活（进设置页时抽屉必然已经关了），也不是用户偏好。
    // 用 `remember` 而不是 `rememberSaveable`：重建后抽屉自己关上是对的 ——
    // 用户回到 app 看到的第一眼不该是一个盖在照片上的抽屉。
    var moreOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val sheetType = SuiPaiSheet.type
    // 矮屏（手机横屏 411 dp）唯一的稀缺资源是纵向：那一档底部导航条占 272 px = 25 % 屏高。
    // 这里统一判定一次，页眉行、底栏、印样工具条三处共用；竖屏 915 dp 永远不成立。
    val shortChrome = rememberShortViewport()
    // 矮屏：页名缩号（22 sp 的行高就是这一行 105 px 的全部来源），
    // 并把三个 tab 也搬进这一行 —— 横屏这一行有 1500 px 空余，
    // 而底栏同一条导航在横屏要吃掉 272 px。竖屏不进这个分支。
    val headStyle = if (shortChrome) {
        MaterialTheme.typography.titleLarge.copy(
            fontSize = 15.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Black,
        )
    } else {
        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black)
    }
    // 页眉也按工具条那一套来：装不下就换行，不许把 tab 挤成 52 px。
    // （原来是 `Row`：720×1280 + 字级 2.0 时「相册」被压到 52 px、
    //  「DARKROOM」压到 74 px —— 标签读不出来。）
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = if (shortChrome) 0.dp else 2.dp),
    ) {
        // 页眉的页名同时是「关于」入口：三个 tab 都在同一行上，
        // 不必为政策/权限说明再占一个工具条格子。版本号跟在后面，
        // 既提示这里点得动，也是审核要截图的那一行。
        val aboutVer = remember {
            runCatching {
                context.packageManager
                    .getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(2.dp))
                // 版本号一并写进标签：清空子节点之后「随拍 / 0.5.0」两行 Text
                // 不再各自成节点（装机量的，标签确实变成了「关于随拍 0.5.0」）。
                //
                // 但它没有像 AppBarCell 那样把 clickable 合到同一个节点上：
                // uiautomator 仍然报 clickable=false、class 仍是 View。
                // 它的祖先可点，所以读屏沿祖先仍按得中（全 app「孤儿标签」扫描 0 个）。
                // 为什么同样的 modifier 形状在 AppBarCell 合得上、这里合不上，
                // 还没量明白——先如实记在这里，别把它当成已修好。
                .clickable(role = Role.Button) { chrome.openAbout() }
                .clearAndSetSemantics {
                    contentDescription =
                        if (aboutVer.isNotBlank()) "关于随拍 $aboutVer" else "关于随拍"
                }
                .padding(end = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "随拍",
                style = headStyle,
            )
            if (aboutVer.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    aboutVer,
                    style = sheetType.frameNo,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        if (shortChrome) {
            // 「关于」那颗的命中区比它的槽位宽出几 px（装机量到：
            // 横屏字级 1.0 时 x=250/251 落在「照片」的前 6 px 上，
            // 却开了关于抽屉）。留一道缝，别让两个控件抢同一条边。
            Spacer(Modifier.width(8.dp))
            AppRoute.TabTitles.forEachIndexed { i, name ->
                AppBarCell(
                    label = name,
                    on = currentTabIndex == i,
                    onClick = { onTab(i) },
                    modifier = Modifier.testTag("nav_tab_$i"),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        AppBarCell(
            label = if (chrome.darkUi) "DARKROOM" else "DAYLIGHT",
            on = !chrome.darkUi,
            onClick = { chrome.toggleDark() },
            modifier = Modifier.testTag("theme_toggle"),
        )
        // 「更多」：一整族入口（搜索 / 回忆 / 收藏夹 / 回收站 / 拼图 / 幻灯片 / 设置 / 隐私）
        // 收在一个 AppSheet 里，不占第二行工具条 —— 工具条只许一行，这是项目的既有契约。
        AppBarCell(
            label = "更多",
            on = moreOpen,
            onClick = { moreOpen = !moreOpen },
            modifier = Modifier.testTag("chrome_more"),
        )
    }

    if (moreOpen) {
        // 分组：「看照片」是内容入口，「整理」是管理入口，最后是设置与隐私。
        // 分组不是为了好看 —— 8 颗格子平铺时，用户扫不到"我要找的是设置还是回收站"。
        AppSheet(title = "更多", tag = "chrome_more_sheet", onDismiss = { moreOpen = false }) {
            AppSheetSection("看照片")
            AppChipFlow {
                AppMoreCell("搜索", "chrome_open_search", { moreOpen = false }) {
                    onNavigate(AppRoute.Search(null))
                }
                AppMoreCell("回忆", "chrome_open_memories", { moreOpen = false }) {
                    onNavigate(AppRoute.Memories)
                }
                AppMoreCell("收藏夹", "chrome_open_collections", { moreOpen = false }) {
                    onNavigate(AppRoute.Collections)
                }
                // 「视频」放在「看照片」这一组里，紧挨着收藏夹：它是**同一类东西**——
                // 用户想"看点什么"。而它不占底栏：一个 5000 张的库里通常只有几十段视频，
                // 为它挤掉三分之一的底栏，是把找照片的代价换给一个低频入口。
                // 读权限那一格由 `VideoGrid` 自己的空态说清（Android 13+ 只申请了照片权限）。
                AppMoreCell("视频", "chrome_open_videos", { moreOpen = false }) {
                    onNavigate(AppRoute.Videos)
                }
            }
            AppSheetSection("整理")
            AppChipFlow {
                AppMoreCell("回收站", "chrome_open_trash", { moreOpen = false }) {
                    onNavigate(AppRoute.Trash)
                }
                AppMoreCell("拼图", "chrome_open_collage", { moreOpen = false }) {
                    onNavigate(AppRoute.Collage(selectedIds.toList()))
                }
                AppMoreCell("幻灯片", "chrome_open_slideshow", { moreOpen = false }) {
                    onNavigate(AppRoute.Slideshow(0))
                }
                // 两个"扫一遍、告诉你哪里能省"的工具。放在「整理」而不是「看照片」：
                // 它们**不改照片正文**，只出结论（重复的删哪张 / 哪些占最多）。
                // 配方卡不在这里 —— 它是"某一次一瞬的产物"，入口必须贴着那张照片
                // （一瞬顶栏），从"更多"进等于让用户先选照片再选配方，顺序反了。
                AppMoreCell("重复照片", "chrome_open_duplicates", { moreOpen = false }) {
                    onNavigate(AppRoute.Duplicates)
                }
                AppMoreCell("存储分析", "chrome_open_storage_insight", { moreOpen = false }) {
                    onNavigate(AppRoute.StorageInsight)
                }
            }
            AppSheetSection("设置")
            AppChipFlow {
                AppMoreCell("设置", "chrome_open_settings", { moreOpen = false }) {
                    onNavigate(AppRoute.Settings)
                }
                AppMoreCell("隐私与数据", "chrome_open_privacy", { moreOpen = false }) {
                    onNavigate(AppRoute.Privacy)
                }
            }
        }
    }
}

/**
 * 「更多」抽屉里的一颗格子。
 *
 * 包一层而不是直接写 `AppBarCell(label, on = false, onClick)`：抽屉里每一颗都要
 * **先关抽屉再进页面**（否则用户从设置页按返回，抽屉还开着）。这条在每一颗上手写，
 * 迟早漏一颗；收成一个格子就不会。
 */
@Composable
private fun AppMoreCell(label: String, tag: String, close: () -> Unit, onClick: () -> Unit) {
    AppBarCell(
        label = label,
        on = false,
        modifier = Modifier.testTag(tag),
        onClick = { close(); onClick() },
    )
}
