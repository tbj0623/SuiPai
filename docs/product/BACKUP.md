# 导出与恢复（用户数据的备份）

> 代码：`data/Backup.kt`（数据层）、`ui/settings/BackupScreen.kt`（界面）、
> `app/src/test/java/com/suipai/app/data/BackupTest.kt`（纯逻辑单测）。
> 本文只讲**口径**：里面有什么、没有什么、版本怎么走、隐私怎么说。

---

## 1. 为什么要有这个东西

在它之前，用户的**设置、自定义配方、收藏夹、收藏/隐藏清单、搜索历史**全都只在本机：

| 数据 | 落盘位置 | 系统备份带不带 |
| --- | --- | --- |
| 设置中心偏好（`cfg_` 键） | `suipai.xml` | ✅ 带（`backup_rules.xml` 唯一 include 的文件） |
| 收藏 / 隐藏 id 清单 | `suipai.private.xml` | ❌ **不带**（两张规则文件都显式排除，属隐私） |
| 自定义配方 | `filesDir/custom_looks.json` | ❌ 不带 |
| 收藏夹 | `filesDir/collections.json` | ❌ 不带 |
| 相册搜索历史 | `filesDir/search_history.json` | ❌ 不带 |
| 回收站账单 | `filesDir/trash.json` | ❌ 不带 |
| AI 档位与 Key | `suipai.ai` | ❌ 不带（而且换机之后主密钥没了，密文也解不开） |
| 照片 | MediaStore `Pictures/SuiPai` | — 照片不归备份管 |

于是换一台手机 = 配方、收藏夹、收藏清单全丢。系统备份只保住了"设置"这一层，
而且只在同一个账号下可用。导出与恢复给这条缺口一个**显式、用户可控**的出口：
自己挑一个文件、自己带走、自己在新设备上读回来。

**它不是同步**：没有账号、没有服务器、没有后台任务。只有用户按下按钮的那一刻才读写文件。

---

## 2. 里面有什么

一个 UTF-8 纯文本 `.json`，顶层字段如下（0.8 Beta 的 `schema` 是 `2`；旧 `schema=1` 仍可读）：

| 字段 | 类型 | 内容 |
| --- | --- | --- |
| `format` | `"suipai-backup"` | 身份标记。选错文件（照片 / APK / 别的应用的 json）靠它挡掉 |
| `schema` | 整数 | 数据结构版本，见 §5 |
| `createdAt` | 整数（毫秒） | 打包时刻 |
| `appVersion` | 字符串 | 打包那台设备上的 `versionName`，只用于显示与排查 |
| `sourceInstallId` | 字符串 | 本次安装的随机标识；旧备份可能缺失。用于避免跨设备错配照片编号，并非密码或身份认证 |
| `settings` | 对象 | 键 → 字符串。**只装真的落过盘的键** |
| `looks` | 字符串数组 | 每条一个 `SPLOOK1:` 文本（与「导入配方」那个输入框同一个格式） |
| `collections` | 对象数组 | `id` / `name` / `photoIds` / `createdAt` / `updatedAt` |
| `favorites` | 整数数组 | 收藏的照片 id |
| `hidden` | 整数数组 | 隐藏的照片 id |
| `searchHistory` | 字符串数组 | 最近的搜索词，最多 10 条 |

三条实现口径值得单独说：

1. **设置只导出落过盘的键**。「我从没改过」与「我改成了默认档」在文件里是两件事：
   前者不出现，后者出现。恢复时只写文件里有的键，所以不会把用户的默认值"钉死"。
2. **配方用 `SPLOOK1:` 而不是另定义一份**。备份里的配方能直接粘进「导入配方」，
   反过来也一样 —— 一份格式，两处入口，不会出现"备份里的配方导入失败"。
3. **字符串集合（今天是 `cfg_settings_search_history`）编码成 JSON 数组文本**。
   用逗号拼接是不可逆的：用户真的会搜 `2024, 夏天`。

---

## 3. 里面**没有**什么（重要）

### 3.1 照片本身不在里面

随拍**不存照片**。照片是系统媒体库（`Pictures/SuiPai`）里的普通文件，
归用户与系统相册管，不归随拍管。备份里只有"哪几张被收藏了"这样的**行号**，
而换一台设备之后这些行号指向的是另一批照片。

**跨安装恢复的安全门（0.8 Beta）**：新备份写入随机 `sourceInstallId`，该标识
保存在 Android 的 `noBackupFilesDir`，不会随着系统云备份迁到另一安装。
只有来源与本机标识一致，才能恢复收藏夹成员、收藏/隐藏照片编号。
另一设备、重装后的备份、缺少标识的旧文件均只恢复设置、配方、搜索历史；
本机收藏夹/收藏/隐藏原样保留，界面明确报告「部分完成」。
这避免了比「照片丢失」更严重的情况：编号碰巧相同，误把另一张照片标为隐藏。
目前没有按哈希/EXIF 重建跨设备照片关联；搬过去的照片仍需用户手动重新收藏。

### 3.2 其它不带的

- **AI 的 API Key**：加密封装在 `suipai.ai`，用的是 Android 密钥库里那把**不可导出**的
  主密钥。就算把它打进备份，换机之后主密钥也没了，那段密文解不开 —— 带上是"看起来能恢复
  其实不能"的假承诺。所以不带，用户在新设备上重新填一次。
- **回收站账单**（`trash.json`）：记录的是"用户删掉了什么"，属隐私；而且里面的
  `content://` uri 换机之后全部失效。
- **搜索索引**（`search_index.json`）：可重建的缓存，下次打开相册自己会重建。
- **相机 / 一瞬 / 相册三张老表**（`camera_jpeg`、`yishun_*`、`gallery_*` 这些裸键）：
  它们今天仍被各屏直接读写，但设置中心这一层（`cfg_`）还没统一到它们；备份的是
  **设置中心那一层**。老键的迁移是另一轮的事（见 `docs/handoff.md` §7「已知但本轮不修」）。
- **照片的 EXIF、位置、拍摄时间**：这些跟着照片文件走，不跟着随拍走。

---

## 4. 导出与恢复怎么做

### 4.1 导出

1. 用户点「导出到文件」→ 接线方起 `ActivityResultContracts.CreateDocument`；
2. 用户在系统文件选择器里挑位置（本机、U 盘、网盘都行）→ 拿到一个 `content://` uri；
3. `Backup.exportTo(context, uri)` 把 `collect()` 的结果编码后写进去。

**不需要任何存储权限**：SAF 给的是一次性的写授权，权限跟着那一个 uri 走，用完就没。
所以 `AndroidManifest.xml` 里没有、也不需要多出任何权限。

写文件用 `"wt"`（write + truncate）：用户可能在选择器里挑了一个**已存在**的旧备份，
`"w"` 在部分 provider 上不截断，会把新内容写在旧内容前面，得到一个前半截新、
后半截旧的废文件。个别 provider 不认 `"wt"`，退回 `"w"`。

### 4.2 恢复

1. 用户先选一种合并方式（见 §4.3），再点确认；
2. 接线方起 `ActivityResultContracts.OpenDocument`（MIME 收 `application/json` + `*/*`，
   因为不少网盘把 `.json` 报成 `text/plain` 甚至 `application/octet-stream`）；
3. `Backup.importFrom(context, uri, mode)` 读回来 → 校验 → 应用。

读的时候有 **16 MB 上限**：选错文件时用户可能挑了一个几百 MB 的视频，直接
`readBytes()` 会当场 OOM —— 那不是"坏数据处理"，是整个应用被杀。

### 4.3 两种合并方式

| | `REPLACE`（以文件为准） | `UNION`（两边合并） |
| --- | --- | --- |
| 设置 | 覆盖文件里有的键，本机其它键不动 | 同键**本机赢**，文件独有的补上 |
| 配方 | 同 id 以文件为准；**本机多出来的会被删掉** | 同 id 留本机那条；文件独有的补上 |
| 收藏夹 | 整份换成文件里的 | 同 id：名称与 `createdAt` 取本机，照片并集（本机顺序在前），`updatedAt` 取较大 |
| 收藏 / 隐藏 | 只剩文件里那些 id | 并集去重，本机顺序在前 |
| 搜索历史 | 只剩文件里那些词 | 并集去重（不区分大小写），本机在前，截到 10 条 |

**设置项在两种模式下都不删本机键**。因为"文件里没有"既可能是"用户没改过（用默认值）"、
也可能是"老一点的那份备份没有这个键"；删掉会丢配置，而少写一条只是保留现状。

**同一个 id 同时出现在收藏与隐藏里是允许的**：应用里这两份本来就是独立的
（藏起来的那张也能被收藏），合并时不做互斥。

**配方 id 冲突为什么保留本机**：本机那份是用户此刻正在用的，可能是他刚在那边改过的；
而"合并"这个动作在用户心里是"把另一台的搬过来"，不是"把这台的退回去"。

界面上的默认值**一个都不选**（`mode == null`）：任何默认值都会变成"没读就下一步"
的快捷方式，而 `REPLACE` 的默认值会直接删掉用户本机的东西。没选之前确认按钮点不动。

### 4.4 恢复时怎么落地

| 数据 | 落地方式 | 为什么 |
| --- | --- | --- |
| 设置 | 逐条走 `SettingsStore.put` | 那条路径已经管了类型安全与坏值兜底 |
| 配方 | **按 id** `decodeLook` 出 `CustomLook`，再 `save` / `delete` | 保住 id 才能让"同一个备份恢复两次"是幂等的。`CustomLooks.importText` **不能**用：它的契约是"一定重新发号"（给剪贴板导入别人的配方用的），拿它恢复会让每次恢复都多出一条。见 `BackupTest.decodedLookKeepsItsOwnId` |
| 收藏夹 | **整份重写 `collections.json`** | 走 `Collections.create` 会自己发号，恢复两次就得到两套同名收藏夹 |
| 收藏 / 隐藏 | 算差集后 `addAll` / `removeAll`，再 `persist()` 等落盘 | 这一份是隐私，"界面说恢复了而磁盘没落"不能靠用户别在此刻杀进程来避免 |
| 搜索历史 | 先 `clear()` 再倒序 `record()`，最后 `save()` | `record` 会把词插到最前，倒着录一遍才能得到文件里的顺序 |

各类别独立落盘。只有所有适用类别都成功才返回 `Ok`；有写入异常时返回 `Partial`
并列出失败类别。来自另一安装或缺标识的旧备份如果有照片关联被跳过，同样返回 `Partial`。
收藏夹改为 `AtomicFile` 写入，替换中断时保留上一版，避免半截 JSON。

---

## 5. schema 版本策略

`Backup.CURRENT_SCHEMA = 2`，写在文件的 `schema` 字段上。**新版仍可读取 schema 1，但无来源标识的照片 id 一律不恢复。旧版只能识别 schema 1，会拒绝新版备份，防止旧代码忽略安全门后误套照片编号。**

**什么时候要 +1**

- 加字段：**不用**（读的时候缺就取默认值）；
- 改字段含义、删字段、改 id 清单的口径（例如 `favorites` 从"照片 id"改成"照片 id + 日期"）：
  **必须 +1**。旧版本读到新含义会把它解释成另一件东西 —— 那是"静默改数据"，
  比读不出来糟得多。

**读到比当前大的 schema：整包拒绝**（`BackupRead.TooNew`）。不许"按旧语义尽量解"。
界面上说的是「这份备份来自更新的随拍（数据版本 N，本机只认到 M），请先升级随拍再恢复；
本机数据没有改动」—— 用户由此知道下一步该干什么。

**读到 schema 缺失 / 不是正整数：同样拒绝**（`BackupRead.Broken`）。
不能猜成"当前版本"：猜错方向就是上面那种静默改数据。

**坏数据一律不抛**。解析失败 → `null`；单条记录字段类型不对 → 跳过那一条；
字段缺失 → 用默认值。这个文件来自用户手里的任意一个 uri，SAF 里选错文件是常态。

---

## 6. 隐私口径

### 6.1 这个文件是敏感文件

它**不含照片**，但含：

- **收藏清单**与**隐藏清单** —— 也就是「我把哪些照片收藏了、藏起来了」；
- 设置中心全部偏好（主题、网格、相机画质……）；
- 自定义配方的参数与名字（用户给配方起的名字常常是"夏天""给妈妈的"这种）；
- 最近搜索过的 10 个词。

这些加起来足以描述一个人的使用习惯。所以：

- 导出按钮**之前**有一块定影红的提醒（`BackupScreen.SensitiveBox`），
  说明"请放在只有你能看到的位置：自己的网盘、自己的电脑。不要发到群里"；
- 导出之后**随拍不保留副本**：文件在用户挑的那个位置，随拍不缓存、不影印；
- 想删：在系统文件管理器里删掉那个 `.json` 就行。

### 6.2 与系统备份的关系

系统备份（`backup_rules.xml` / `data_extraction_rules.xml`）继续**只带** `suipai.xml`
那一个文件，即"设置中心这一层偏好"。本功能**不改变**那两张规则文件：

- `suipai.private.xml` 仍然排除（收藏/隐藏清单仍是隐私，不随云备份出设备）；
- `custom_looks.json` / `collections.json` / `search_history.json` / `trash.json` 仍然排除。

也就是说：**导出是用户在知情之后主动做的一次动作，系统备份不是**。两者的授权语义不同，
所以两处口径故意不一样。

### 6.3 不含 Key

见 §3.2。这一条在上架材料（`store/privacy-policy-zh.md` 的"数据安全"一节）里已有对应表述，
改这里时同步看一眼那边。

---

## 7. 界面（`BackupScreen`）

自包含：吃数据 + 回调，**不 import `state/**`、不自己导航、不持有仓库**。
所以它能被 `SettingsUiGuardTest` 那一族读源码的守卫扫到，也不需要在 JVM 上起假 `Context`。

签名：

```kotlin
@Composable
fun BackupScreen(
    localCounts: BackupCounts,          // 本机现在有多少东西
    lastExportAt: Long,                 // 上次导出成功的时刻，0 = 没导出过
    lastExportCounts: BackupCounts?,    // 上次导出那一包有多大，null = 不知道
    note: String?,                      // 上一次动作的结论（成功与失败都写这里）
    busy: Boolean,                      // 正在读写，按钮全灰
    onExport: () -> Unit,               // 接线方起 CreateDocument，再把 uri 交给 Backup.exportTo
    onImport: (MergeMode) -> Unit,      // 接线方起 OpenDocument，再把 uri 交给 Backup.importFrom(mode)
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)
```

三组内容，顺序是刻意的：

1. **备份里有什么 / 照片不在里面** —— 在按钮之前说清最大的误解；
2. **本机现在 / 上次导出** —— 恢复之前用户能对比两个数；
3. **导出 → 敏感提醒 → 从文件恢复（两步闸）**。

`testTag` 清单（接线方与自动化用）：

| tag | 是什么 |
| --- | --- |
| `backup_list` | 整屏的 `LazyColumn` |
| `backup_local_counts` | 本机条数那一行 |
| `backup_last_export` | 上次导出时间/条数那一行 |
| `backup_export` | 导出主按钮 |
| `backup_restore_arm` | 「从文件恢复」第一步（展开） |
| `backup_mode_replace` / `backup_mode_union` | 两种合并方式 |
| `backup_restore_confirm` | 第二步的确认按钮（模式未选时灰） |
| `backup_note` | 上一次动作的结论 |

---

## 8. 接线（已完成）

接线方（lead 的 lane）已经把这一屏接进去了，本文件记录**实际状态**，不是计划：

| 接在哪 | 做了什么 |
| --- | --- |
| `state/AppRoute.kt` | `data object Backup`，`family = "settings.backup"`，`encode() = "settings.backup"`。**不带参数**：本机条数与上次导出时刻都是现读的，用户选的合并方式是一次性输入（进程被杀后重选，比"恢复一个上次选的 REPLACE"安全） |
| `nav/NavRender.kt` | 归进二级屏那一族（与存储、关于同级） |
| `nav/AppNavHost.kt` | `AppRoute.Backup -> BackupScreen(...)`，两个 SAF launcher 挂在这一层（不进 `MainActivity`），`collect()` 在 `LaunchedEffect(backupEpoch)` 里跑，每完成一次导出/恢复 `backupEpoch++` |
| `ui/settings/SettingsScreen.kt` | 存储组末尾一行手写的「导出与恢复」（`SettingsBackupRow`）。**没有**往 `SettingsModel` 加 `SettingItem.Action` —— 理由是为了不让设置模型反过来认识导航 |
| 「上次导出」落盘 | 写 `SettingKeys.Privacy.exportAt`，按那个键的既有口径写 **ISO-8601 文本**（不是毫秒：改单位会把这个键从别的读者手里弄坏） |
| 建议文件名 | `AppNavHost.backupSuggestedName(now)`，`Backup <年-月-日>.json` |

`CreateDocument("application/json")` 的**构造参数**是 MIME，`launch(defaultName)` 才是文件名。
`OpenDocument()` 的 `launch` 收 MIME 数组；这里只传 `application/json`，
如果真机上遇到网盘把 `.json` 报成 `text/plain` 而选不中，再加 `"*/*"`。

---

## 9. 测试

`data/BackupTest.kt`（34 条）只测**不碰 `Context`** 的部分：编码 / 解码往返（中文、emoji、
超长、空集合）、schema 太新与缺失、字段缺失与类型不对、截断与半截 JSON、两种合并、
设置值的字符串 ↔ 类型、配方 id 抽取、条数文案。

`exportTo` / `importFrom` 要真的读写文件与四份清单，在 JVM 上 `ContentResolver` 是
返回默认值的桩 —— 在那里测"写成功了没有"测到的是桩，不是真行为。所以那两条路径被
拆成 `settingWrites` 这类纯函数，跳过规则逐条钉在测试里；真正的读写只能装机验证
（本轮**没有**做装机验证，见 §10）。

**每条判据都配了"喂坏例子必须变红"的自检**：`checkersActuallyCatchBadData` 断言
一份好文件必须解得出非空内容（否则所有 `assertNull` 都在空转），并把同一份文件
用三种方式改坏，逐一确认 `decode` 返回 null。

---

## 10. 没做的部分（诚实清单）

- **装机验证**：本功能已通过 JVM 逻辑测试，但**没有**在模拟器或真机上走过
  「导出 → 换设备 → 恢复」的 SAF 完整路径。网盘 provider 与 `"wt"` 模式需实测。
- **恢复后的界面刷新**：宿主在 `Ok` 与 `Partial` 后都会强制刷新照片标记和设置，
  并递增 `backupEpoch`；其它缓存与子页面仍需真机回归。
- **照片 id 的跨设备对齐仍未实现**：0.8 Beta 会主动阻止跨安装的 id 被写入，
  因此不会自动带走收藏、隐藏及收藏夹成员；需要用户手动重新关联。
  按文件名/时间猜测很容易猜错并错误隐藏照片，暂不采用。
- **加密**：导出的文件是明文。想加密（口令保护）要引 KDF 与分组密码 ——
  本轮**禁止新增依赖**，而自己手写一套加密比不加密更危险。
- **增量 / 自动备份**：没有后台任务，没有"每天自动导出到某个目录"。
- **回收站账单不在备份里**：换机之后 `content://` uri 全部失效，带过去也没有意义；
  而且它记录的是"用户删了什么"，属隐私。

## 11. 这份文档与代码的对账

改这一块时，下面几处必须一起看：

| 改了什么 | 还要改哪里 |
| --- | --- |
| 加了/改了 `BackupBundle` 的字段**含义** | `Backup.CURRENT_SCHEMA` +1；`docs/product/BACKUP.md` §2、§5 |
| 换了合并口径 | `Backup.merge` 的 KDoc + §4.3 那张表 + `BackupTest` 里对应的那条 |
| 改了导出范围（例如把回收站也带上） | §2、§3.2 + `store/privacy-policy-zh.md` 的「数据安全」一节 |
| 改了界面文案 | §3.1 的口径不许说软（照片不在备份里） |
| 改了接线（路由 / launcher） | §8 |
