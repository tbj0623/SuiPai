package com.suipai.app.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import com.suipai.app.data.Bucket
import com.suipai.app.data.ReclaimSuggestion
import com.suipai.app.data.SizeClass
import com.suipai.app.data.StorageItem
import com.suipai.app.data.StorageKind
import com.suipai.app.data.StorageSummary
import com.suipai.app.data.humanBytes
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.design.EmptyState
import com.suipai.app.ui.design.Spacing
import com.suipai.app.ui.design.SuiPaiColor
import com.suipai.app.ui.design.SuiPaiShapes
import com.suipai.app.ui.design.SuiPaiType
import com.suipai.app.ui.theme.SuiPaiSheet

/**
 * 存储分析。
 *
 * ## 这一屏回答三个问题
 * 1. **一共占多少**（顶部大字：`12,345 张 · 38.2 GB`）；
 * 2. **大的都是什么**（按大小分桶的堆叠条 + 最大 20 个文件）；
 * 3. **哪些可以放心删**（按来源分类，只给数量与大小，**不代删**）。
 *
 * ## 为什么不给"一键清理"
 * 相册的"清理"和缓存不同：删掉就是永久没了。所以这一屏**只报告、不执行**——
 * 每一类给一个「去查看」入口（`onOpenCategory`），真正的删除交给那一类自己的界面
 * （比如重复照片会走 [DuplicatesScreen] 的系统确认框）。
 * 唯一敢用"建议清理"语气的是随拍加工件：它们能从原片重新生成。
 *
 * ## 图表不引库
 * 堆叠条就是一个 `Canvas` 里画 6 个矩形，宽度按字节占比。
 * 引一个图表库要几 MB 包体，换来的是一条矩形——不值。
 *
 * @param summary 已算好的汇总（宿主从 [com.suipai.app.data.StorageInsight.scan] 拿）
 * @param scanning 正在扫
 * @param onOpenCategory 点某一类 → 宿主决定去哪（回收站 / 重复 / 相册筛选…）
 * @param note 宿主的一句话（"已扫描 5,231 张" / "没读到媒体库"）
 */
@Composable
fun StorageInsightScreen(
    summary: StorageSummary,
    scanning: Boolean,
    onBack: () -> Unit,
    onOpenCategory: (StorageKind) -> Unit,
    note: String? = null,
) {
    val colors = SuiPaiColor.current

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        AppBarRow {
            AppBarCell(label = "返回", on = false, onClick = onBack)
            AppBarText(
                "存储分析",
                style = SuiPaiSheet.type.frameNo,
                color = colors.ink,
                layout = Modifier.weight(1f),
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        if (note != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.panelOverlay)
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            ) {
                Text(note, style = SuiPaiType.bodySmall, color = colors.inkSecondary)
            }
        }

        if (scanning) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = colors.safelight, strokeWidth = 2.dp)
                Spacer(Modifier.height(Spacing.md))
                Text("正在统计", style = SuiPaiType.bodyMedium, color = colors.ink)
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "只读文件信息，不解码图片",
                    style = SuiPaiType.bodySmall,
                    color = colors.inkTertiary,
                )
            }
            return@Column
        }

        if (summary.count == 0) {
            EmptyState(
                title = "媒体库里没有照片",
                kicker = "或者没读到",
                note = "如果相册里明明有照片，可能是读取权限被收回了。",
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Spacing.xxl),
        ) {
            item("total") { TotalHeader(summary) }
            item("dist") { DistributionBar(summary.buckets) }
            item("hint") { SizeHint(summary.byClass) }
            item("reclaim") {
                SectionTitle("按来源")
            }
            items(summary.suggestions, key = { it.kind.name }) { s ->
                SuggestionRow(s, onClick = { onOpenCategory(s.kind) })
            }
            if (summary.largest.isNotEmpty()) {
                item("largest_title") { SectionTitle("最大的 ${summary.largest.size} 个文件") }
                items(summary.largest, key = { "big_${it.id}" }) { item ->
                    LargestRow(item)
                }
            }
        }
    }
}

/** 顶部大字：张数 + 总量。数字用等宽（印样语义）。 */
@Composable
private fun TotalHeader(summary: StorageSummary) {
    val colors = SuiPaiColor.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.lg),
    ) {
        Text(
            humanBytes(summary.totalBytes),
            style = SuiPaiType.displaySmall,
            color = colors.ink,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            "共 ${formatCount(summary.count)} 张照片",
            style = SuiPaiType.parameter,
            color = colors.inkTertiary,
        )
    }
}

/** 千分位。不用 `String.format`（会跟 Locale 走，中文环境下也一致，但显式更稳）。 */
internal fun formatCount(n: Int): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val sb = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
        sb.append(c)
    }
    return sb.toString()
}

/**
 * 堆叠条：6 个桶按字节占比重排。
 *
 * 颜色从琥珀到定影红——**不是装饰**：越靠右（越大）越红，
 * 让用户一眼看出"钱花在哪一档"。
 */
@Composable
private fun DistributionBar(buckets: List<Bucket>) {
    val colors = SuiPaiColor.current
    val total = remember(buckets) { buckets.sumOf { it.totalBytes } }
    if (total <= 0L) return
    val shades = bucketShades()
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md)) {
        val desc = remember(buckets, total) {
            "按大小分布：" + buckets.joinToString("，") { b ->
                "${b.label} ${b.count} 张 ${humanBytes(b.totalBytes)}"
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(SuiPaiShapes.frame)
                .clearAndSetSemantics { contentDescription = desc },
        ) {
            var x = 0f
            val h = size.height
            for ((i, b) in buckets.withIndex()) {
                if (b.totalBytes <= 0L) continue
                // 最后一段吃掉浮点余量，避免右侧出现 1px 缝
                val isLast = buckets.drop(i + 1).all { it.totalBytes <= 0L }
                val w = if (isLast) size.width - x
                else (size.width * (b.totalBytes.toFloat() / total.toFloat()))
                if (w > 0f) {
                    drawRect(
                        color = shades[i % shades.size],
                        topLeft = Offset(x, 0f),
                        size = Size(w, h),
                    )
                    x += w
                }
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        // 图例：只列非空桶
        val nonEmpty = remember(buckets) { buckets.filter { it.count > 0 } }
        Column {
            nonEmpty.forEach { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(
                        b.label,
                        style = SuiPaiType.parameter,
                        color = colors.inkTertiary,
                        modifier = Modifier.width(76.dp),
                    )
                    Text(
                        "${b.count} 张",
                        style = SuiPaiType.parameter,
                        color = colors.inkSecondary,
                        modifier = Modifier.width(72.dp),
                    )
                    Text(
                        humanBytes(b.totalBytes),
                        style = SuiPaiType.parameter,
                        color = colors.ink,
                    )
                }
            }
        }
    }
}

/** 各尺寸档的张数。一行压平显示，不占太多纵向。 */
@Composable
private fun SizeHint(byClass: List<Pair<SizeClass, Int>>) {
    val colors = SuiPaiColor.current
    val text = remember(byClass) {
        byClass.filter { it.second > 0 }
            .joinToString(" · ") { "${it.first.label} ${it.second}" }
    }
    if (text.isBlank()) return
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Text(text, style = SuiPaiType.parameter, color = colors.inkTertiary)
    }
}

@Composable
private fun SectionTitle(title: String) {
    val colors = SuiPaiColor.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md, end = Spacing.md, top = Spacing.lg, bottom = Spacing.xs),
    ) {
        Text(title, style = SuiPaiType.labelLarge, color = colors.ink)
    }
}

/**
 * 一类来源。
 *
 * 只给数量与大小 + 一个入口。**不显示"删除"按钮**：这一屏不做删除（见文件头）。
 */
@Composable
private fun SuggestionRow(s: ReclaimSuggestion, onClick: () -> Unit) {
    val colors = SuiPaiColor.current
    val label = "${s.kind.label}，${s.count} 张，${humanBytes(s.totalBytes)}，${s.note}"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "查看${s.kind.label}") { onClick() }
            .clearAndSetSemantics { contentDescription = label }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.kind.label, style = SuiPaiType.bodyMedium, color = colors.ink)
            Spacer(Modifier.height(2.dp))
            Text(s.note, style = SuiPaiType.bodySmall, color = colors.inkTertiary)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(humanBytes(s.totalBytes), style = SuiPaiType.parameter, color = colors.ink)
            Text("${s.count} 张", style = SuiPaiType.parameter, color = colors.inkTertiary)
        }
        Text(
            "›",
            style = SuiPaiType.titleMedium,
            color = colors.inkTertiary,
            modifier = Modifier.padding(start = Spacing.xs),
        )
    }
}

@Composable
private fun LargestRow(item: StorageItem) {
    val colors = SuiPaiColor.current
    val context = LocalContext.current
    // 守卫：ImageRequest.Builder 必须在 remember 里
    val request = remember(item.id) {
        ImageRequest.Builder(context)
            .data(android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                item.id,
            ))
            .size(120)
            .build()
    }
    val label = "${item.displayName.ifBlank { "照片 ${item.id}" }}，" +
        "${item.width}×${item.height}，${humanBytes(item.sizeBytes)}"
    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = label }
            .padding(horizontal = Spacing.md, vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
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
                item.displayName.ifBlank { "照片 ${item.id}" },
                style = SuiPaiType.bodySmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${item.width}×${item.height}",
                style = SuiPaiType.parameter,
                color = colors.inkTertiary,
            )
        }
        Text(humanBytes(item.sizeBytes), style = SuiPaiType.parameter, color = colors.ink)
    }
}

/**
 * 堆叠条的配色（6 档）。
 *
 * 暴露成函数是因为"哪一档配哪个色"是**视觉契约**：别处若要画同样的条，
 * 必须取这里的值，不许自己再挑一遍色。
 */
@Composable
fun bucketShades(): List<Color> {
    val colors = SuiPaiColor.current
    return remember(colors) {
        listOf(
            colors.safelightWash,
            colors.safelightPressed,
            colors.safelight,
            colors.grease,
            colors.fixerInk,
            colors.fixer,
        )
    }
}
