package com.suipai.app.ui.collections

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.suipai.app.data.Collections
import com.suipai.app.data.PhotoCollection
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
 * 收藏夹列表。
 *
 * 与 [TrashScreen] 同一套分工：只画 + 回调，不持有仓库、不导航。
 * `covers` 是 `collectionId → 封面 uri`，由宿主按"第一张照片"取好 ——
 * 这一屏不该知道 `MediaStore` 的存在。
 *
 * 长按出「重命名 / 删除」：不用 ActionMode，也不用下拉菜单 ——
 * 一个只有两项的菜单在拇指区是纯粹多一跳，`AlertDialog` 里两颗按钮更短。
 * 两个动作都带 `onLongClickLabel`，读屏的"操作"列表里才说得清长按是干什么的。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionsScreen(
    collections: List<PhotoCollection>,
    covers: Map<String, String?>,
    onBack: () -> Unit,
    onOpen: (PhotoCollection) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    /** 宿主手里当前选中了多少张。0 = 「加入选中」这一颗自己就是灰的（守卫 #19 的口径）。 */
    selectedCount: Int = 0,
    /** 用户在这个夹上确认了「把这批照片加进去」；id 是被选中的收藏夹。 */
    onAddPhotos: (String) -> Unit = {},
    /**
     * 宿主的一句话结论（"把 3 张加进了「夏」" / "建好了「夏」"）。
     *
     * `AppNavHost` 从 557 行起就在写这个值，「建好了 / 改名了 / 删掉了」三句都攒在 `collectionsNote`，
     * 但这一屏一直没有 note 参数 —— 写了没人读，用户做完动作只看得到列表变了。
     */
    note: String? = null,
) {
    val colors = SuiPaiColor.current
    // null = 没开框；"" = 正在新建；id = 正在给这个改名。
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    // 长按出来的那一张：先问"改名还是删掉"，再进各自的确认框。
    var menuFor by remember { mutableStateOf<PhotoCollection?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        AppBarRow(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            AppBarCell(label = "返回", on = false, onClick = onBack)
            AppBarText(
                "收藏夹",
                style = SuiPaiSheet.type.pageHead,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            AppBarText(
                "${collections.size} 个",
                style = SuiPaiSheet.type.frameNo,
                color = SuiPaiSheet.colors.number,
                layout = Modifier.weight(1f),
                modifier = Modifier.padding(start = 8.dp),
            )
            // 「加入选中」摆在这里而不是长按菜单里：这个动作的对象是"手里那批照片"，
            // 收藏夹只是目的地。放进长按菜单会让读屏把"加进去"和"对这个夹改名/删掉"列成同一组操作。
            //
            // 一个夹都没有时也灰着：此时点开只能看到一个空的选择框，
            // 用户要的是"先建一个"，不是"选一个不存在的夹"。
            AppBarCell(
                label = "加入选中",
                on = false,
                enabled = selectedCount > 0 && collections.isNotEmpty(),
                modifier = Modifier.testTag("collections_add_selected"),
                // 走 `editing` 而不是再开一个 `pickerForSelection` 布尔：
                // 两个变量描述同一件事（"现在开着哪个框"），迟早会互相盖掉。
                onClick = { editing = EditTarget.AddingToExisting },
            )
            AppBarCell(
                label = "新建",
                on = false,
                onClick = { editing = EditTarget.New },
                enabled = collections.size < Collections.MAX_COLLECTIONS,
            )
        }
        // 宿主那句结论必须在 AppBarRow **之外**：工具条里只许出现 AppBarText（守卫 #21），
        // 而这句话是会变的正文，不是页头。
        if (note != null) {
            Text(
                note,
                style = SuiPaiType.bodySmall,
                color = colors.inkSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = 2.dp),
            )
        }
        if (collections.isEmpty()) {
            EmptyState(
                title = "还没有收藏夹",
                kicker = "00 / 00",
                note = "把挑出来的照片收进一个夹子，以后按主题翻。" +
                    "最多 ${Collections.MAX_COLLECTIONS} 个，每个 ${Collections.MAX_PHOTOS} 张。",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                contentPadding = PaddingValues(Spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gutter),
            ) {
                items(collections, key = { it.id }) { collection ->
                    CollectionCard(
                        collection = collection,
                        cover = covers[collection.id],
                        onOpen = { onOpen(collection) },
                        onLongPress = { menuFor = collection },
                    )
                }
            }
        }
    }

    // 长按：两颗动作摆在一个对话框里，比下拉菜单少一跳。
    menuFor?.let { target ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(target.name) },
            text = { Text("${target.photoIds.size} 张照片") },
            confirmButton = {
                TextButton(onClick = {
                    menuFor = null
                    editing = EditTarget.Existing(target)
                }) { Text("重命名") }
            },
            dismissButton = {
                TextButton(onClick = {
                    menuFor = null
                    editing = EditTarget.Deleting(target)
                }) { Text("删除") }
            },
        )
    }

    // 改名：只有「新建」与「重命名」会走到这里。删除单独一支（不可逆）。
    val renaming = when (val target = editing) {
        is EditTarget.New -> ""
        is EditTarget.Existing -> target.collection.name
        else -> null
    }
    if (renaming != null) {
        val isNew = editing is EditTarget.New
        NameDialog(
            title = if (isNew) "新建收藏夹" else "重命名",
            initial = renaming,
            confirmLabel = if (isNew) "新建" else "保存",
            onDismiss = { editing = null },
            onConfirm = { name ->
                val target = editing
                editing = null
                when (target) {
                    is EditTarget.New -> onCreate(name)
                    is EditTarget.Existing -> onRename(target.collection.id, name)
                    else -> Unit
                }
            },
        )
    }

    // 删除也要确认：收藏夹本身是不可重建的（照片还在，分组没了）。
    val doomed = (editing as? EditTarget.Deleting)?.collection
    if (doomed != null) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("删除「${doomed.name}」？") },
            text = { Text("照片不会被删，只是这个分组没了。") },
            confirmButton = {
                TextButton(onClick = {
                    editing = null
                    onDelete(doomed.id)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            },
        )
    }

    // 「选一个夹把这批照片加进去」。
    //
    // 用 `LazyColumn` 而不是 `Column`：上限是 `Collections.MAX_COLLECTIONS`（200 个），
    // 一次性给 200 行会让对话框的测量与语义树都白做一遍，而用户可能只想要第一个。
    if (editing is EditTarget.AddingToExisting) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("加进哪个收藏夹？") },
            text = {
                Column {
                    Text(
                        // 把"要加几张"说清楚：用户可能已经忘了自己选了几张，
                        // 而这一屏平时只显示收藏夹的个数。
                        "选中了 $selectedCount 张，挑一个夹放进去。",
                        style = SuiPaiType.bodySmall,
                    )
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        // 这里必须用别名导入：`androidx.compose.foundation.lazy.grid.items`
                        // 与 `androidx.compose.foundation.lazy.items` 同名，两个裸导入冲突。
                        lazyItems(collections, key = { it.id }) { collection ->
                            TextButton(
                                onClick = {
                                    editing = null
                                    onAddPhotos(collection.id)
                                },
                            ) {
                                Text(
                                    "${collection.name}（${collection.photoIds.size} 张）",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            // 选中某个夹就是确认：再要求用户按一次「加进去」是多一跳。
            // 但这个框必须留一颗明确的「取消」—— 误触进来时用户要能退出去。
            confirmButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            },
        )
    }
}

/** 名称输入框开着时指是谁。 */
private sealed interface EditTarget {
    data object New : EditTarget

    data class Existing(val collection: PhotoCollection) : EditTarget

    data class Deleting(val collection: PhotoCollection) : EditTarget

    /**
     * 「把这批照片加进某个已有的夹」。不携带照片 —— 照片是宿主手里的选择集，
     * 这一屏只负责说"用户挑中了哪个夹"，具体的 id 清单由 `onAddPhotos` 的调用方取。
     */
    data object AddingToExisting : EditTarget
}

/**
 * 一张卡片：封面 + 名称 + 张数。
 *
 * 封面没有照片时画一块带框的空相纸 —— 不用占位插画，也不留纯白（纯白在深色主题下刺眼）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionCard(
    collection: PhotoCollection,
    cover: String?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = SuiPaiColor.current
    val label = "${collection.name}，${collection.photoIds.size} 张"
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = "打开",
                role = Role.Button,
                onLongClickLabel = "重命名或删除",
                onLongClick = onLongPress,
                onClick = onOpen,
            )
            .clearAndSetSemantics { contentDescription = label },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(colors.canvasSunken)
                .border(Spacing.hair, colors.hairline, SuiPaiShapes.frame),
        ) {
            if (cover != null) {
                CollectionCover(cover)
            } else {
                Text(
                    "空",
                    style = SuiPaiType.marker,
                    color = colors.inkTertiary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Text(
            collection.name,
            style = SuiPaiType.labelLarge,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xxs, start = Spacing.xxs, end = Spacing.xxs),
        )
        Text(
            "${collection.photoIds.size} 张",
            style = SuiPaiType.frameNo,
            color = colors.inkTertiary,
            maxLines = 1,
            modifier = Modifier.padding(start = Spacing.xxs, end = Spacing.xxs, bottom = Spacing.xxs),
        )
    }
}

@Composable
private fun CollectionCover(uri: String) {
    val context = LocalContext.current
    // 与 TrashCell 同一条理由：ImageRequest 没有 equals，必须 remember。
    val request = remember(uri) {
        ImageRequest.Builder(context)
            .data(uri)
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * 名字输入框。
 *
 * `singleLine = true` + `maxLines = 1` **两个都要**：前者管用户输入，
 * 后者管提示语。装机量到只写 singleLine 时提示语自己折行，把输入框撑高
 * （画廊搜索框被撑到 528 px，网格被顶光）—— `PerfSourceGuardTest` 第 22 条钉的就是它。
 */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("名称") },
                placeholder = { Text("例如：2024 夏天", maxLines = 1) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
