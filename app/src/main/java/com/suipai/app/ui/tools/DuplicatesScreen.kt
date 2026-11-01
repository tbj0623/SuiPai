package com.suipai.app.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.suipai.app.data.DuplicateGroup
import com.suipai.app.data.DuplicatePhoto
import com.suipai.app.data.KeepRule
import com.suipai.app.data.humanBytes
import com.suipai.app.data.pickKeep
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarPrimary
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.design.EmptyState
import com.suipai.app.ui.design.Spacing
import com.suipai.app.ui.design.SuiPaiColor
import com.suipai.app.ui.design.SuiPaiShapes
import com.suipai.app.ui.design.SuiPaiType
import com.suipai.app.ui.theme.SuiPaiSheet

/**
 * 重复照片合并。
 *
 * ## 这一屏的全部善意是「不让用户误删」
 * 合并 = 删照片。所以界面上做了四件克制的事：
 *   1. **默认折叠**每一组，只显示"3 张 · 可省 2.4 MB"——用户必须主动展开才看见具体照片；
 *   2. 保留的那张**固定排第一且打标**，其余打"将删除"，不靠颜色差（色弱看不出来）；
 *   3. 删除走**系统确认框**（`createDeleteRequest`），系统还会再问一次；
 *   4. 底部那颗粒子写"删除其余 N 张"，**不写"一键清理"**——后者会让人以为不可逆也能反悔。
 *
 * ## 为什么是数据 + 回调
 * 这一屏不知道照片从哪来、也不导航：`onDeleteGroup` / `onDeleteAll` 由宿主接上
 * `Duplicates.deletionUrisOf` + `createDeleteRequest`，宿主决定成功后要不要重扫。
 * 与 `TrashScreen` / `CollectionsScreen` 同一套分工。
 *
 * @param groups 已聚好的组（宿主从 [com.suipai.app.data.Duplicates.scan] 拿）
 * @param scanning 正在扫 —— 此时只显示进度，不显示"没有重复"（那会是假安慰）
 * @param progress (已处理, 总数)；扫描前为 null
 * @param note 宿主的一句话结论（"已删 4 张" / "你取消了系统确认框"）
 */
@Composable
fun DuplicatesScreen(
    groups: List<DuplicateGroup>,
    scanning: Boolean,
    progress: Pair<Int, Int>?,
    onBack: () -> Unit,
    onDeleteGroup: (DuplicateGroup) -> Unit,
    onDeleteAll: () -> Unit,
    note: String? = null,
) {
    val colors = SuiPaiColor.current
    var expanded by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirmAll by remember { mutableStateOf(false) }

    val totalExtra = remember(groups) { groups.sumOf { it.items.size - 1 } }
    val totalBytes = remember(groups) { groups.sumOf { it.reclaimableBytes } }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        AppBarRow {
            AppBarCell(label = "返回", on = false, onClick = onBack)
            AppBarText(
                "重复照片",
                style = SuiPaiSheet.type.frameNo,
                color = colors.ink,
                layout = Modifier.weight(1f),
                modifier = Modifier.padding(start = 4.dp),
            )
            if (!scanning && groups.isNotEmpty()) {
                AppBarText(
                    "$totalExtra 张 · ${humanBytes(totalBytes)}",
                    style = SuiPaiType.parameter,
                    color = colors.inkTertiary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        if (note != null) {
            NoteLine(note)
        }

        if (scanning) {
            ScanProgress(progress)
            return@Column
        }

        if (groups.isEmpty()) {
            EmptyState(
                title = "没有找到重复照片",
                kicker = "按大小 + 拍摄时间 + 画面相似度三重比对",
                note = "只有三条判据全部吻合才会列出来 —— 宁可漏报，不会误报。",
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Spacing.xxl),
        ) {
            items(groups, key = { it.items.first().id }) { group ->
                val gid = group.items.first().id
                val open = expanded.contains(gid)
                DuplicateGroupCard(
                    group = group,
                    expanded = open,
                    onToggle = {
                        expanded = if (open) expanded - gid else expanded + gid
                    },
                    onDelete = { onDeleteGroup(group) },
                )
            }
        }

        // 底部动作：只在有组的时候出现。用 danger 色，因为它会删东西。
        if (totalExtra > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.panel)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            ) {
                AppBarPrimary(
                    label = if (confirmAll) "确认删除 $totalExtra 张？" else "删除其余 $totalExtra 张",
                    onClick = {
                        if (confirmAll) {
                            confirmAll = false
                            onDeleteAll()
                        } else {
                            confirmAll = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun NoteLine(note: String) {
    val colors = SuiPaiColor.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.panelOverlay)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
    ) {
        Text(note, style = SuiPaiType.bodySmall, color = colors.inkSecondary)
    }
}

@Composable
private fun ScanProgress(progress: Pair<Int, Int>?) {
    val colors = SuiPaiColor.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = colors.safelight, strokeWidth = 2.dp)
        Spacer(Modifier.height(Spacing.md))
        Text("正在比对画面相似度", style = SuiPaiType.bodyMedium, color = colors.ink)
        if (progress != null && progress.second > 0) {
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                "${progress.first} / ${progress.second}",
                style = SuiPaiType.parameter,
                color = colors.inkTertiary,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "只对大小相同的照片解码缩略图，不会遍历整库",
            style = SuiPaiType.bodySmall,
            color = colors.inkTertiary,
        )
    }
}

/**
 * 一组。
 *
 * 折叠态只给摘要——这是刻意的：用户不需要一进来就看 200 张缩略图，
 * 他需要先知道"这里有 12 组、能省 340MB"。展开才看细节。
 */
@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = SuiPaiColor.current
    val extra = group.items.size - 1
    val summary = "${group.items.size} 张 · 可省 ${humanBytes(group.reclaimableBytes)}"
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .border(1.dp, colors.hairline, SuiPaiShapes.frame)
            .background(colors.panel, SuiPaiShapes.frame),
    ) {
        // 组头：整行可点，语义写在可点节点上
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (expanded) "收起这一组" else "展开这一组") { onToggle() }
                .semantics(mergeDescendants = true) {
                    contentDescription = "${group.reasonText}，$summary，${if (expanded) "已展开" else "已收起"}"
                }
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    group.reasonText,
                    style = SuiPaiType.bodyMedium,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(summary, style = SuiPaiType.parameter, color = colors.inkTertiary)
            }
            Text(
                if (expanded) "收起" else "展开",
                style = SuiPaiType.marker,
                color = colors.safelight,
                modifier = Modifier.padding(start = Spacing.xs),
            )
        }

        if (expanded) {
            // 展开：逐张列。第一张是保留的那张。
            group.items.forEach { photo ->
                DuplicateRow(photo = photo, keep = photo.id == group.keep)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(Spacing.sm),
            ) {
                AppBarPrimary(
                    label = "删除这一组的其余 $extra 张",
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DuplicateRow(photo: DuplicatePhoto, keep: Boolean) {
    val colors = SuiPaiColor.current
    val context = LocalContext.current
    // 守卫：ImageRequest.Builder 必须写在 remember 里（PerfSourceGuardTest.imageRequestsAreRemembered）
    val request = remember(photo.id) {
        ImageRequest.Builder(context)
            .data(android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                photo.id,
            ))
            .size(160)
            .build()
    }
    val label = buildString {
        append(photo.displayName.ifBlank { "照片 ${photo.id}" })
        append("，")
        append("${photo.width}×${photo.height}")
        append("，")
        append(humanBytes(photo.sizeBytes))
        append("，")
        append(if (keep) "将保留" else "将删除")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = label }
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(SuiPaiShapes.frame)
                .background(colors.canvasSunken),
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                photo.displayName.ifBlank { "照片 ${photo.id}" },
                style = SuiPaiType.bodySmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${photo.width}×${photo.height} · ${humanBytes(photo.sizeBytes)}",
                style = SuiPaiType.parameter,
                color = colors.inkTertiary,
            )
        }
        // 保留/删除用**文字**标，不靠颜色（色弱也要能分辨）
        Text(
            if (keep) "保留" else "将删除",
            style = SuiPaiType.marker,
            color = if (keep) colors.safelight else colors.fixer,
            modifier = Modifier.padding(start = Spacing.xs),
        )
    }
}

/**
 * 保留策略切换（给宿主做设置项时复用；本屏默认用分辨率）。
 *
 * 单独拎出来是因为它是**唯一会影响"删哪张"的用户选择**，
 * 放在设置里比放在这一屏更安全——这一屏不该有让人边看边改判据的入口。
 */
@Composable
fun KeepRuleRow(current: KeepRule, onPick: (KeepRule) -> Unit) {
    val colors = SuiPaiColor.current
    Column(Modifier.fillMaxWidth().padding(Spacing.md)) {
        Text("保留规则", style = SuiPaiType.labelLarge, color = colors.ink)
        Spacer(Modifier.height(Spacing.xs))
        KeepRule.entries.forEach { rule ->
            val on = rule == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.RadioButton, onClickLabel = "选择${rule.label}") { onPick(rule) }
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (on) "●" else "○",
                    style = SuiPaiType.marker,
                    color = if (on) colors.safelight else colors.outline,
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(rule.label, style = SuiPaiType.bodyMedium, color = colors.ink)
            }
        }
    }
}

/** 供宿主计算"这一组会删几张"用（避免宿主自己 filter 出不一致的数）。 */
fun extraCountOf(group: DuplicateGroup): Int = group.items.size - 1

/** 供宿主展示"保留哪张"（与 [pickKeep] 同一判据，避免两处各算一遍）。 */
fun keptOf(group: DuplicateGroup): DuplicatePhoto? = group.items.firstOrNull { it.id == group.keep }
