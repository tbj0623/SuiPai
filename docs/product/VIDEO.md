# 视频（只播不编）

> 代码：`data/VideoItem.kt`（模型 + 纯逻辑）、`data/VideoRepository.kt`（只读仓库 + 封面缓存 `VideoThumbs`）、
> `ui/video/VideoGrid.kt`（网格）、`ui/video/VideoPlayerScreen.kt`（播放器）、
> `app/src/test/java/com/suipai/app/data/VideoDurationTest.kt`（纯逻辑单测）。
> 与 `docs/product/PLAY.md`（拼图/回忆/幻灯片）、`docs/product/TOOLS.md`（重复/存储）平级。
>
> 状态：数据层与两个屏幕**已通过 `:app:compileDebugKotlin`**；
> **没有在设备上验证过**（这台机器上的模拟器一次都没为本功能起过）。
> Android 13+ 视频权限已改为视频页按需申请，Android 14+ 区分完整 / 部分授权；未做设备实测。见 §6、§7。

---

## 1. 范围：只播不编

一句话：**随拍能播你自己相册里的视频，能把它分享出去，能把当前那一帧存成一张照片。**
除此之外什么都不做。

| 做 | 不做 |
| --- | --- |
| 读 `MediaStore.Video` 的分页清单 | 写 `MediaStore.Video`（不删、不改、不重命名） |
| `VideoView` 播放本机视频 | 剪辑 / 裁剪 / 拼接 / 调速 |
| `ACTION_SEND` 把视频交给别的应用 | 转码 / 压缩 / 改容器 |
| `MediaMetadataRetriever` 取一帧 → 存进 `Pictures/SuiPai` | 旋转 / 滤镜 / 加水印（取到的帧已经是摆正的，见 §3.4） |
| 封面缓存落 `filesDir/video_thumbs/` | 后台播放 / 画中画 / 锁屏控制 / 播放队列 |

这条边界不是"还没做"，是**产品口径**。理由在 §5。

### 1.1 为什么写操作只有"存一帧"

唯一的写操作是"把一帧存成照片"，而它走的是 `MediaRepository.saveBitmap` ——
与相机、一瞬、副本、拼图**同一条落盘口径**（`Pictures/SuiPai`、`IS_PENDING` 两段式）。
多一条自己写的 MediaStore 通道，就多一处"扩展名和内容不符""半截文件留在库里"的机会。

文件名是 `SuiPai_视频帧_<时间戳>.jpg`（`videoFrameFileName`）。第二个下划线让它
**不算「随拍原片」**：`GalleryScreen.isSuiPaiOriginalName` 的判据是"`SuiPai_` 之后不许再有下划线"，
所以这一张会正确地落进"加工件"，而不是混进「仅随拍」那一档（那一档的空态写着
「还没有随拍**原片**」）。`VideoDurationTest.frameFileNameIsNotAnOriginalShot` 钉住了这一条，
并且带一条自检：真正的原片形状必须过那条判据，否则断言是恒真的。

---

## 2. 为什么不引 Media3 / ExoPlayer

这一轮的三个动作全是系统能力，`VideoView` + `MediaMetadataRetriever` 就够：

| 需求 | 系统 API | 够不够 |
| --- | --- | --- |
| 播本机 mp4/mkv/webm | `VideoView`（内部 `MediaPlayer`） | 够。相册里的视频就是本机文件，不是网络流 |
| 播放/暂停/seek | `start` / `pause` / `seekTo` | 够 |
| 分享 | `Intent.ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION` | 够，而且是**系统**在授予读权限 |
| 取一帧 | `MediaMetadataRetriever.getFrameAtTime` | 够，`API 27+` 还有 `getScaledFrameAtTime` |
| 取首帧做封面 | 同上 | 够 |

引 Media3 的代价是具体的、可申报的：

1. **包体** +1.5–3 MB（`media3-exoplayer` + `media3-ui` + 传递依赖）。
   而这个 app 的整个卖点是"轻、不上传、只做相册"。
2. **Data safety 申报**要重写依赖表。本仓库的 `AiGuardTest.noThirdPartyHttpStackIsDeclared`
   已经在盯着"申报与代码不一致"这件事；每加一个依赖都要重新过一遍。
3. **换回来的是什么**：HLS / DASH / 自适应码率 / DRM / 字幕轨 / 多音轨切换。
   相册里一段本机视频**一个都用不上**。
4. **多一层要维护的播放器状态机**：Media3 的 `Player` 有自己的生命周期与线程模型，
   而这一屏现在的状态只有 `prepared / playing / positionMs` 三个。

什么时候**该**重新考虑：真要做"剪辑""转码""后台播放""画中画"，那时 `VideoView` 不够，
Media3 才值得那份代价。在那之前，"不引"是结论，不是拖延。

> 同一件事的另一面：`VideoView` 的毛病是**没有进度回调**（只能轮询，见 §4.2），
> 以及"哪台设备能播哪种编码"由设备的解码器决定，不由我们决定。
> 这两条都写在 §5.3 的"没做"里。

---

## 3. 封面缓存策略

### 3.1 为什么必须落盘

`MediaMetadataRetriever.getFrameAtTime` 是这一屏**最贵的一步**：一段 4K 视频取一帧量级
在几十到几百毫秒，还带一次解码器初始化。而 `LazyVerticalGrid` 在滚动时**会反复
销毁/重建**同一格 —— 不缓存的话，往回滚一屏就是几十次取帧。

两层缓存（`VideoThumbs`）：

| 层 | 位置 | 上限 | 作用 |
| --- | --- | --- | --- |
| 磁盘 | `filesDir/video_thumbs/` | 48 MB | 跨进程、跨冷启动的那一份 |
| 内存 | `LruCache<String, Bitmap>` | 8 MB（约 20 张 320×320） | "这一屏不用重复解 JPEG"的短路 |

**为什么不进内存缓存就完事**：进程被杀、用户切出去再回来，内存缓存一并清空，
而用户看到的应该是"立刻就有封面"。

### 3.2 路径与命名

```
filesDir/video_thumbs/<id>_<边长>_<字节数>.jpg
```

例：`48_320_5242880.jpg`。生成函数是 `coverCacheName(id, edgePx, sizeBytes)`。

**为什么把 `sizeBytes` 写进名字**：MediaStore 的 id 是"行"的 id，就地替换/重写同一行的
内容（某些同步工具会这么干）时 id 不变而画面变了 —— 只按 id 命名就会一直显示**上一段视频的封面**。
带上字节数之后，内容一变名字就变，旧文件交给容量上限去淘汰。
`VideoDurationTest.coverCacheKeyMovesWhenTheContentChanges` 钉住这一条。

**为什么把边长也写进名字**：换网格尺寸（平板/折叠屏展开）时不许串用一张糊图。

封面边长 `VideoThumbs.EDGE = 320` px（长边）。120 dp 的格子 × 3 倍屏 ≈ 360 px，取 320 已经够。

### 3.3 写入与并发

- 两个格子同时取同一段视频的帧时，`diskLock`（`Mutex`）只让一个真的动磁盘。
- 写盘是**先写 `.tmp` 再改名**：半截 JPEG 落在缓存里，下次会被当成"已缓存"解出一张花屏。
  这与 `MediaRepository` 落盘时用 `IS_PENDING` 是同一个道理，只是 `filesDir` 里没有系统帮忙。
- 内存 LRU **淘汰时不 `recycle`**：一张正在被 `Image` 画的位图被 recycle 会直接 FATAL
  （`Canvas: trying to use a recycled bitmap`，一瞬那个位图袋 2026-09-21 实测 12 次翻片全崩）。
  淘汰只是"不再持有"，交给 GC —— 这就是多花的那点内存买来的安全性。

### 3.4 首帧取 1 秒，不是 0 秒

`VideoThumbs.load` 里取的是 `extractFrame(uri, 1000L, EDGE)`。**0 秒位置在不少编码上是黑场**
（录屏、渐入的开头），而封面要的是"这段视频长什么样"。

取帧**不做额外旋转**：`getFrameAtTime` 给的是**已经摆正**的画面（旋转写在容器元数据里，
解码器已经应用过）。

### 3.5 清理时机

只有一处自动清理：**写新封面之后**，在 `diskLock` 里跑一次 `trimLocked`。
判据是 `shouldTrimThumbCache(totalBytes, DISK_BUDGET_BYTES)`（`total > budget` 才动手），
所以绝大多数写入只是一次目录遍历，不删东西。

超出预算时按 **`lastModified` 从旧到新**淘汰到 **80%**。
按 `lastModified` 而不是文件名里的 id：id 是自增的，按 id 淘汰会先删掉最新录的那几段，
而它们恰好是用户最常回看的。

**手动清空**：`VideoThumbs.clear(context)` 已经写好（清内存 + 删整个目录），
但**目前没有调用点** —— 设置页「清缓存」还没接它（见 §5.2）。
`VideoRepository.kt` 里那条注释是诚实的："现在没有调用点，所以只做它说的事，不假装统计"。

---

## 4. 性能预算

### 4.1 取帧（唯一的重活）

| 场景 | 量级 | 说明 |
| --- | --- | --- |
| 4K 视频取一帧（`API 27+`，`getScaledFrameAtTime` 到 320） | 几十 ~ 几百 ms | 解码器初始化 + 一次解关键帧 |
| 4K 视频取一帧（`API 26`，退回整帧 `getFrameAtTime`） | 峰值内存高 30 MB 左右 | 3840×2160×4 B ≈ 33 MB 整帧 |
| 命中磁盘缓存后的解码 | 与普通缩略图同量级 | 20–40 KB 的 JPEG |
| 命中内存 LRU | 0 I/O | 只做一次 map 查找 |

**没有实测数据**。上面这些是量级估算，不是这台机器上量出来的 —— 而按本仓库的纪律
（`docs/handoff.md` §8.1「量不到就说量不到」），这里必须这么标。
要变成实测，需要起模拟器 + 推几段 4K 视频进 `DCIM` + 用 `adb logcat` 打时间戳。

### 4.2 播放器进度轮询 250 ms 一拍

`VideoView` **不提供进度回调**，只能按拍子问 `currentPosition`。250 ms 一拍：
滑块看起来是连续走的，而每拍只写一个 `Long` —— 整屏 5 次/秒的重组，
比"每帧问一次"省得多（这台机器没有硬件 GPU，`docs/handoff.md` §1b 记着 gfxinfo 一律贴着 100% jank）。

拖动进度条期间**跳过写入**（`scrubbing` 标志），否则滑块会在拖动时被轮询改回去、自己跳。

### 4.3 网格

- `LazyVerticalGrid` + `GridCells.Adaptive(112.dp)`。
- 封面 `remember(item.id)` + `LaunchedEffect(item.id, item.sizeBytes)`：
  **不随重组重建**。少了这一层，滚一下、选一下、主题一翻，整屏格子都会重新派发一次取帧。
- 格子模型直接吃 `@Immutable data class VideoItem`（数据层已经标了）。
  这一条由 `PerfSourceGuardTest.lazyGridModelsAreImmutable` 守着。

### 4.4 提取帧（用户点「存帧」时的一次性开销）

`extractFrame(uri, currentPosition, maxEdge = 2560)` → `saveBitmap` → `MediaStore`。

`maxEdge = 2560` 是长边上限：从 4K 视频里取一张原尺寸帧是 3840×2160×4 B ≈ 33 MB，
连点两下就能让一台中端机开始甩页。落盘的是照片，不需要超过 2560。

整条链在 `Dispatchers.IO` 上：取帧、压缩、`insert`、`IS_PENDING` 两段式落盘。

---

## 5. 明确没做的

### 5.1 剪辑 / 转码 / 画中画 / 后台播放

四件都不做，而且**不是这一轮的时间问题**：

| 没做 | 为什么 |
| --- | --- |
| 剪辑（裁剪 / 拼接 / 调速） | 需要 `Transformer`（Media3）或 `MediaCodec` 手写一遍，还要一套时间轴 UI。产品口径是"只看"，不是"编辑视频" |
| 转码（压小、改容器） | 转码 = CPU 满载几分钟 + 一次写 MediaStore。用户要的是"照片能存"，不是"视频变小" |
| 画中画（`PictureInPicture`） | 需要 `MainActivity` 声明 `supportsPictureInPicture` + 一套进入/退出状态机。相册看一段 10 秒的视频不需要它 |
| 后台播放 / 锁屏控制 | 需要 `MediaSession` + 前台服务 + 通知。这是音乐播放器的活 |
| 播放队列 / 连播 | 播放器一次只吃一个 `VideoItem`。要做连播，宿主传进来的是一个列表，那是另一个屏 |

### 5.2 没有接的东西（具体到行）

1. **设置页「清缓存」不认视频封面**：`VideoThumbs.clear` 写好了但还要核对调用点。
2. **分页**：`VideoRepository.loadPage(offset)` 写好了，但两个屏幕都只吃"宿主给的一页"。
   网格不自己翻页 —— 视频比照片少得多（一页 400 条），这一版先不做无限滚动。

### 5.3 已知限制（不是 bug，是设备的）

1. **能不能播由设备的解码器决定**。同一段 HEVC 10bit，旗舰机能播、老机器可能不能。
   播不了时 `setOnErrorListener` 会说一句人话（"这段视频播不了（解码器回报 what/extra）"），
   **不弹系统那个英文框**（返回 `true` 表示已处理）。
2. **首帧之前 `duration` 是 0**。所以时长有三个来源：`VideoView.duration` →
   `MediaStore.DURATION` → `-1`。`-1` 的显示是**破折号**而不是 `0:00`
   （后者会被读成"这段视频长度是零"）。
3. **`VideoView` 的 `seekTo` 精度依编码而定**。落在非关键帧上时，某些解码器会跳到最近的关键帧。
   `clampSeekMs` 至少保证不会把越界值送进播放器。
4. **没有缩略图旋转信息**：`getFrameAtTime` 给的是已经摆正的画面，不做二次旋转。

---

## 6. 两个 Composable 的签名（给接线的人）

```kotlin
@Composable
fun VideoGrid(
    videos: List<VideoItem>,
    loading: Boolean,
    onOpen: (VideoItem) -> Unit,
    onBack: () -> Unit,
    note: String? = null,
    // 后加的参数，有默认值：不传也能编译，但**建议传**，否则分不出"没放行"和"确实没有"
    access: VideoAccess = VideoAccess.GRANTED,
    onRequestAccess: (() -> Unit)? = null, // 用户主动触发视频权限 / 部分授权补选
    readFailed: Boolean = false, // 查询失败的空态不能写成「没有视频」
)

@Composable
fun VideoPlayerScreen(
    video: VideoItem,
    onBack: () -> Unit,
    onSavedFrame: (String) -> Unit,   // 保存成功后回报一句人话
    note: String? = null,
)
```

两个都**不 import `state` 包、不导航、不持有状态**（与 `DuplicatesScreen` / `TrashScreen` /
`CollectionsScreen` 同一套分工）。宿主负责：

- 建一次 `VideoRepository(context)`（`remember(context) { … }`）；
- `readAccess()` + `loadPage(0)` 放在 `LaunchedEffect` + `Dispatchers.IO`；
- `null` = **没读到**（不是"没有视频"）→ `readFailed = true`，显示读取失败的空态；
- `onSavedFrame` 里 `state.refresh()`（存进 `Pictures/SuiPai` 之后相册那一屏要能看见）。

`VideoAccess` 的值喂给 `VideoGrid(access = …)`，现在明确区分四种状态：

| 值 | 表示 | 行为 |
| --- | --- | --- |
| `GRANTED` | 允许完整视频访问 | 查询并显示可见视频 |
| `PARTIAL` | Android 14+ 只授权部分照片 / 视频 | 查询可见视频，显示「可见」数量与「补选视频」 |
| `MISSING` | 未授权视频 | 不查库，显示授权按钮；不误报「没有视频」 |
| `UNKNOWN` | 权限检查抛异常 | 不查库，提示无法确认 |

---

## 7. manifest 与权限（按需申请）

`AndroidManifest.xml` 已声明 `READ_MEDIA_VIDEO`，但它**不会在首次照片授权时申请**：

1. Android 12L 及以下照片和视频都由 `READ_EXTERNAL_STORAGE` 授权。
2. Android 13：视频页点击「授权视频」时单独请求 `READ_MEDIA_VIDEO`。
3. Android 14+：点击「授权视频 / 补选视频」时组合请求 `READ_MEDIA_VIDEO` 与 `READ_MEDIA_VISUAL_USER_SELECTED`。如果系统只放行选中项目，网格不声称这些就是整库。
4. 视频页从系统设置返回或授权弹窗返回都会重新查询授权及媒体库。拒绝后系统不再弹窗时可进入应用设置。
5. 隐私中心与关于页已经说明视频权限的用途与触发时机。

这修复了以前 Android 13+ 有播放器和列表、却没法访问别的 app 视频的断点。**尚需真机验证** Android 13、14、15 的权限对话框及系统设置返回路径；JVM 回归测试不代表设备验收。

---

## 8. 单测

`app/src/test/java/com/suipai/app/data/VideoDurationTest.kt` 全部在 JVM 上跑，
**一个 `android.*` 的静态方法都不碰**（`VideoItem` 握着 `android.net.Uri`，
而单测里 `Uri.parse` 是返回 null 的桩 —— 谁在测试里 parse 一下，整个类的初始化就崩在那儿）。

钉住的七族：

| 族 | 钉住什么 |
| --- | --- |
| 时长 | `0:07` / `1:05` / `1:02:03`；**向下取整不四舍五入**；`-1` 是破折号不是 `0:00` |
| 大小 | `0 B` / `999 B` / `1.0 KB` / `2.5 MB` / `1.00 GB`；负数破折号 |
| 进度夹取 | `duration <= 0` 一律回 0（`VideoView` 第一帧之前是 -1，拿它当上限会 `seekTo(-1)`） |
| 权限名字 | 26/32 走 `READ_EXTERNAL_STORAGE`，33/36 走 `READ_MEDIA_VIDEO` |
| 空态文案 | 三件事分三句：没放行 / 还没读完 / 确实没有 |
| 命名与缓存键 | 提取帧不算原片；封面键带 `sizeBytes` 与边长 |
| 时间戳 | 可注入 `ZoneId`，`0` 与负数给破折号 |

每一族都配一条"喂坏例子必须变红"的自检（`docs/handoff.md` §8.2 的硬纪律）。

**这些测试没有跑过**（本轮只跑到 `:app:compileDebugKotlin`）。
按代码读，全部是纯函数、`Long`/`String`/`Int` 进 `String`/`Boolean` 出，
没有理由碰 stub —— 但"应该通过"不等于"跑过通过"。

---

## 9. 一句话总结

**随拍不碰你的视频，只让你看见它、听它、把它的一帧留下。**
