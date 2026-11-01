# 回收站与收藏夹

数据层的两份新账单：`data/Trash.kt`（回收站）与 `data/Collections.kt`（收藏夹）。
界面各自一个文件：`ui/trash/TrashScreen.kt`、`ui/collections/CollectionsScreen.kt`。
两屏都只做「数据 + 回调」，不导航、不读 Activity、不持有仓库。

## 回收站

### 生命周期

```
照片（Pictures/SuiPai 下，本 app 写的行）
  │  用户点「删除」
  ▼
moveToTrash  逐字节复制 → Pictures/SuiPai/回收站，删源行，记账
  │
  ├─ 用户点「恢复」  → restore → 复制回 Pictures/SuiPai，删回收站那一行，划掉账单
  ├─ 用户点「彻底删除」→ purge  → 删回收站那一行 + 划掉账单
  └─ 满 30 天        → purgeExpired（now 注入）→ 同上
```

**保留 30 天**（`Trash.DEFAULT_RETENTION_DAYS`）。边界是「满 30 天整即可清理」：
第 29 天显示「1 天」，第 30 天显示「今天清理」，第 31 天仍是 0 —— `daysLeft` 下限是 0，
过期判定与角标读的是**同一个函数**（`daysLeftOf`），所以不可能出现"角标说还剩 1 天、
清理却已经把它删了"。

`purgeExpired` 必须在 app 启动或进入回收站时跑一次（挂在 IO 上）。它不做定时任务：
被杀掉的 app 不会自己醒来。下次打开补清是这套方案唯一可接受的语义。

### 存储位置

| 东西 | 位置 | 说明 |
| --- | --- | --- |
| 照片字节 | `Pictures/SuiPai/回收站/`（MediaStore 行） | 用户可见，系统相册的「SuiPai/回收站」相册里能看到 |
| 账单 | `filesDir/trash.json` | 私有目录，**不进云备份**（记录的是"用户删掉了什么"） |

账单是唯一真值：读不到 = 回收站是空的。**绝不**因为 json 坏了就去清
`Pictures/SuiPai/回收站` 目录 —— 那是真的丢照片。

**`Trash` 必须走 `Trash.of(context)` 拿进程级单例。** 它的 `lock` 是**实例字段**，
而 `trash.json` 只有一份：两个实例就是两把锁保护同一个文件，`read-modify-write`
一定丢更新（启动时的 `purgeExpired` 与用户点删除并发就会互相覆盖）。
改前工程里有三个构造点（`MainActivity` / `AppNavHost` / `AppState`），
现在 `MainActivity` 与 `AppState` 已改用 `of`；**`AppNavHost.kt:526` 还没改**。

### 与系统相册的关系

- **删除 = 搬家，不是打标记**。只在自家 json 里记一句"这张删了"，系统相册里那张还在、
  别的 app 还能扫到，用户看到的是"删了没删掉"。所以回收站里的照片在系统相册里
  确实**消失**了（换到了另一个相册目录下），但字节还在。
- **恢复之后保留原始拍摄时间**。`moveToTrash` 把原照片的 `DATE_TAKEN` 一起写进
  回收站那一行、也记进账单（`TrashItem.dateTaken`），`restore` 再把它写回新行 ——
  删了又恢复的照片仍然排在它原来的时间线位置，而不是跳到"今天"。
  账单里 `dateTaken` 缺失或非正数（老版本写的 json）一律当"不知道"（0），
  这时**不写** `DATE_TAKEN`，行为与加这个字段之前完全一致 —— 绝不拿 `deletedAt`
  冒充拍摄时间（那会把老照片错排进删除那一天）。
- `MediaStore` 在 Android 10+ 用 `IS_PENDING=1 → 写字节 → IS_PENDING=0`，
  中途被杀不会留下半张能被扫到的图。

### 限制：外来照片不能进回收站

只有 **本 app 拥有** 的 MediaStore 行才搬得动。相机 / 微信 / 截图插的行：

- Android 10 及以下，`delete()` 直接抛 `RecoverableSecurityException`；
- Android 11+，删除要求每次走一遍系统确认对话框（`createDeleteRequest`）；
- 而我们搬家用的是「复制 + 删源行」，删源行那一步对外来行**必然失败**。

所以 `moveToTrash` 的调用点必须只对 `Pictures/SuiPai` 下的照片开放。
失败时它返回 `false` 且不留半截状态（已 insert 的新行会被删掉、源行保留）——
界面要能说一句「这张不是随拍拍的，删不了」，而不是静默。

## 收藏夹

- 存储：`filesDir/collections.json`（同样不进备份）。
- 上限：200 个集合 / 每个 5000 张。到顶时 `create` 返回 `null`（**不是**返回一个
  没写进磁盘的假对象），界面靠 `enabled = collections.size < MAX_COLLECTIONS` 提前挡住。
- 顺序是用户的：`photoIds` 是 `List` 不是 `Set`，`orderedAdd` 保证
  「去重 + 第一次出现的位置说了算」，`replaceOrder` 存拖拽后的整份顺序。
- 名称空白回退「未命名收藏」，换行压成空格（名称会进工具条那一格，撑高行高）。
- `now` 一律由调用方注入（默认 `System.currentTimeMillis()`），
  所以 `createdAt` / `updatedAt` 的边界能在 JVM 上钉死。

## 接入建议（四处入口）

1. **删除路径**：`GalleryScreen` / `PhotoViewerScreen` 的单删与批量删。
   当前走 `MediaRepository.deleteOne`；改成「本 app 的照片 → `trash.moveToTrash`，
   外来的 → 保持原样（系统确认框）」。
2. **设置页**：`SettingsScreen` 加一行「回收站（N 张 · X MB）」，用 `Trash.stats()` 填数字。
3. **启动**：`MainActivity` 起来后在 IO 上跑一次 `purgeExpired(System.currentTimeMillis())`。
4. **收藏夹入口**：画廊工具条一颗「收藏夹」格子（`AppBarCell`），
   打开 `CollectionsScreen`；`covers` 由宿主按每个集合的第一张去取缩略图 uri。

## 测试

`TrashLogicTest` / `CollectionsLogicTest` 只测**不碰 Context** 的部分：
json 往返与坏样本兜底、剩余天数边界（0 / 29 / 30 / 31）、过期筛选、去重保序、
名称规整、id 不撞、上限截断。`ContentResolver` 在 JVM 上是返回默认值的桩，
在那里测"搬家成不成功"测到的是桩，不是真行为。

拍摄时间这块因此拆成两个可测点：账单里的 `dateTaken` 往返 / 老账单兜底，
以及 `Trash.dateTakenToWrite(raw)`（"正数照写、0 与负数都不写"的唯一实现）。
`insertImage` 里 `if (dateTaken > 0) put(DATE_TAKEN, …)` 那一句本身**没有** JVM 覆盖，
装机验证时要看的是"删一张 → 恢复 → 它在时间线里的位置没变"。
