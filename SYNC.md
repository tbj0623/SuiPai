# 随拍互通协议 v1

双端（Android / iOS）在**同一局域网**内直传照片与一瞬配方。不经过任何云端服务器，遵守「原图不上传」的产品原则。

## 总览

- **发现**：UDP 广播。每台设备周期性在 `47470/udp` 广播自己的存在；同时监听同端口收集其他设备。
- **传输**：TCP。每台设备在 `47471/tcp` 起一个服务端，同时作为客户端连接对端拉取/推送。
- **配方**：一瞬调色配方（Look + 强度 + 微调）以 JSON 写入照片 EXIF `UserComment`，随文件本体走，无需额外握手。

## 发现（UDP）

- 端口：`47470`，广播地址 `255.255.255.255`，间隔 2 秒。
- 载荷：`SPDISCOVER1|{"id":"<uuid>","name":"<设备名>","platform":"android|ios","tcpPort":47471}`
- 接收方：剥掉 `SPDISCOVER1|` 前缀后解析 JSON；来源地址即对端 host。
- 设备去重键为 `id`；TTL 8 秒，超时未再广播则从设备列表移除；排除自身 `id`。

## 传输（TCP）

- 端口：`47471`。
- 帧格式（大端）：

```
+--------------+---------------------+--------------+--------------+
| 4B headerLen | headerLen B 的 JSON | 4B bodyLen   | bodyLen B    |
+--------------+---------------------+--------------+--------------+
```

- headerLen / bodyLen 为 0 表示对应段不存在；bodyLen 超过 64MB 直接断开。
- header 为 UTF-8 JSON，公共字段：`type`（消息类型）、`ok`（成功与否）、`error`（失败原因）。

## 消息

| type | 方向 | 字段 | 应答 |
| --- | --- | --- | --- |
| `hello` | 双向 | `deviceId` `name` `platform` | 对端回自己的 `hello` |
| `list-photos` | 请求 | `offset` `limit` | `photo-list`（`items: [照片元信息]`） |
| `get-thumb` | 请求 | `photoId` `maxEdge` | `photo-bytes`（body=JPEG 缩略图；无图则 `ok=false`） |
| `get-photo` | 请求 | `photoId` | `photo-bytes`（body=原图 JPEG 含 EXIF） |
| `send-photo` | 请求 | `meta`（照片元信息），body=JPEG | `send-ok` / `send-error`（`error` 见下） |
| `bye` | 任一方 | — | 不回应答，直接关连接 |
| 未知 | — | — | 同名 type + `ok=false` + `error:"unknown type"` |

照片元信息（`SyncPhotoMeta`）：`id`、`name`、`dateTaken`、`width`、`height`、`sizeBytes`。

### `bye`

收到即跳出收帧循环、关闭这条连接（不再回帧）。**当前客户端一侧从不发它**：Kotlin 的
`SyncHub` 客户路径一律 `close()` 直接断，Python 对端在 `probe` 里也不发。协议保留它是因为
「干净收尾」和「网络掉了」在服务端日志里应该能分辨 —— 实现已就绪（`SyncHub.kt` 的
`TYPE_BYE -> break`），只是还没人用；不要因为"没人发"就在服务端删掉这一支。

### `send-error` 的几种 `error`

| `error` | 含义 | 发送端该做什么 |
| --- | --- | --- |
| `not supported` | 对端没配 sink（只发不收） | 别发了 |
| `missing meta` / `missing body` / `body too large` | 帧本身不合规（>64MB 拒收） | 修请求，重试没用 |
| `busy` | 对端**在途队列已满** | **等一下再发**，不是失败 |

`busy` 的阈值是硬编码的两个上限（`SyncHub.kt` 的 `MAX_INCOMING_ITEMS` / `MAX_INCOMING_BYTES`）：
**未决照片 8 条、合计 160MB**。收到的照片先原样驻留内存，等用户在界面上「接受 / 拒绝」，
所以不封顶就等于把本机 RAM 交给对端支配。用户接受或拒绝任意一张都会腾出名额 ——
因此 `busy` 是暂时的，批量发送遇到它应该退避重试而不是把这批标成失败。

### 浏览授权（读我相册要本机同意）

`hello` 只证明「有个东西连上来了」，不证明任何身份。**服务端默认拒绝** `list-photos` /
`get-thumb` / `get-photo`：本机没显式允许过这个对端 IP，一律回

```json
{"type": "<原请求 type>", "ok": false, "error": "对方还没允许本机浏览其照片"}
```

- 拒绝时 `type` 是**原请求的 type**（不是 `photo-list` / `photo-bytes`），对端才知道拒的是哪一条。
- 那句话是给对方**用户**看的（对端界面直接显示 `error`），所以写成能读的中文，而不是 `denied`。
  静默或回 `unknown type` 会让对端以为「版本不支持」—— 该做的事完全相反。
- 授权状态在 `SyncState.browseGrants`（键 = 对端 IP，`grantBrowse` / `revokeBrowse` 增删）；
  被拒过的对端进 `browseAsked`，界面据此给一颗「允许浏览」。授权是**本机内存态**，
  目前不落盘：重启 App 要重新允许一次（保守侧的默认）。
- `send-photo` 不在这道闸里：收照片本来就有「接受 / 拒绝」一次确认，闸加在供片这一侧。

### `list-photos` 分页：**没有 `total`，也没有 `hasMore`**

应答 `photo-list` 只带 `items`，**不带总数、也不带「还有没有下一页」**。客户端判断末页的唯一依据是：

> 本页返回条数 **< 请求的 `limit`** ⇒ 这是最后一页。

连带三条要说清的：

1. 条数**正好等于** `limit` 时，即使真的到底了也要再问一次（相册张数恰好是 `limit` 的整数倍）。
   这一次会拿回空表，多一个往返，但不会少一页 —— 判多的代价远小于判少。
2. `limit <= 0` 服务端按约定回空表；把它读成「还有更多」会变成永远翻不完的死循环。
3. 翻页用 `offset`，而相册是活的：两页之间对端新拍一张，后面每页的 offset 都整体后移一格，
   上一页的最后一张会在下一页再出现。客户端**必须按 `id` 去重**（`SyncHub.appendRemotePage`），
   否则 `LazyVerticalGrid(key = { it.id })` 会因重复 key 直接抛异常（整页崩，不是显示两张）。

**为什么不用 `FrameHeader` 加一个 `total` 字段**：它要同时改 Kotlin 与 Python 两个实现
（`ignoreUnknownKeys` 能让旧端读得下去，但旧端永远不填这个字段，于是"有总数"在混跑时是假的），
而「短页 = 末页」已经够用。真到需要总张数那一步，加成可选字段并在本文件里写清两端的
「不认识就当没有」策略，再改 `FrameHeader`。

**接收确认**：收到 `send-photo` 后接收端先回 `send-ok`（含义是「已收到并暂存」），把字节暂存内存，随后在 UI 弹出「接受 / 拒绝」——接受则写入本机相册（Android 落到 `Pictures/SuiPai`；iOS 落到「SuiPai」相簿），拒绝则丢弃。协议层不做二次往返，用户确认是本地行为；因此发送端看到的「成功」指送达，不代表对端用户最终接受。

## 一瞬配方（EXIF）

- JSON（`YishunRecipe`，`v=2`）：`v`、`lookId`、`intensity`、`tempShift`、`contrastShift`、`satShift`、
  `shadowShift`、`fadeShift`、`highlightShift`、`softShift`、`grainMul`、`vignetteMul`、`exportEdge`。
  - **没有 `seed`**（v1 里有，是死字段）：颗粒种子只能由 `lookId` 单点推出
    （`ColorCore.grainSeedFor` = Java 的 `String.hashCode`），两端各自实现一套 seed 算法就会长出两套颗粒。
  - **有 `exportEdge`**：颗粒是按成片长边调校的，不记它就换台手机（内存不同→分档不同）就对不上。
  - `v` 总是写出（哪怕等于默认值）；读到不认识的 `v`（> 当前版本或 < 1）整条拒绝，宁可不还原也不要静默错色。
- 编码：`SPRECIPE1:` + Base64(URL-safe, 无 padding)(JSON UTF-8)。
- **写进 EXIF `UserComment` 时必须在最前面带 8 字节字符码 `ASCII\0\0\0`**（EXIF 2.32 §4.6.4）。
  androidx 的 `ExifInterface` 不会自己补，而且它读的时候从 offset 0 宽容解析——
  所以**只有我们这端**能读回不带码的写法，iOS ImageIO / exiftool / 别的相册会把前 8 字节当字符码吃掉，
  配方跨端就失效了。别改用 `UNICODE\0`：androidx 会按 ASCII 落盘、读回来只剩 `"UNICODE"`。
  两端都只经由 `RecipeCodec.encodeForExif()` 写，别直接写 `encode()`。
- 解码：在 `UserComment` 全文里找 `SPRECIPE1:`，从该处按 Base64Url 字母表收口（**不能按「非空白」收口**：
  NUL 不算空白，任何补零的写入方都会把垃圾拼进 payload）；解析失败静默忽略。
- 一瞬打开含配方的照片时提示「检测到一瞬配方」，一键还原参数，可再调整强度。
- 这条约定由两层测试钉住：`RecipeCodecTest`（JVM，钉内存里的返回值）+
  `RecipeExifRoundTripTest`（**设备 instrumented，钉磁盘上的字节**）。改 EXIF 写入路径时以能跑通后者为准。


## 平台实现

| 层 | Android | iOS |
| --- | --- | --- |
| socket | `java.net`（shared androidMain） | POSIX BSD socket（shared iosMain） |
| 编排 | `com.suipai.core.sync.SyncHub`（共享） | 同左（SuiPaiKit 框架） |
| 照片源/汇 | MediaStore（app 层实现接口） | PHAsset / PHPhotoLibrary（Swift 实现接口） |

服务端 accept 循环与 UDP 收发都在各自后台线程；阻塞 IO 由上层协程（Android）/ Timer+Task（iOS）包裹。

## 已知限制（v1）

- 仅同一局域网可用；跨网段、蜂窝网络不可用（设计如此，不出局域网）。
- 单文件上限 64MB；超过拒收。
- 配方只记录一瞬参数，不含自定义裁剪等破坏性操作。
- iOS 端互通界面 v1 只做「发现设备 + 发送照片 + 接收确认」，浏览对端相册在 Android 端先行。
- Android 端浏览对端相册按 60 张一页翻：底部一颗显式「加载下一页」（不做自动触底 —— 可见即可点才验得动），
  末页之后那颗格子留着、变灰并写「没有更多了」；已加载张数写在网格下方，
  因为那是「翻页真的生效了」唯一可读的信号。

## 怎么验（跨实现，不用两台手机）

`tools/sync_peer.py` 是本协议的另一套**独立实现**（Python，标准库，按上面这份文档重写而不是翻译 Kotlin 代码），
`tools/run_sync_interop.sh` 一条命令跑完整套：起对端 → `adb reverse` → `SyncPeerInteropTest` → 读对端 journal 做宿主机侧断言。

手工分开跑也行：

```bash
python tools/sync_peer.py serve --port 47481 --dir <放几张jpg的目录> --journal /tmp/j.json
adb -s <dev> reverse tcp:47481 tcp:47481          # 设备里的测试连 127.0.0.1:47481 就是连宿主机
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.suipai.app.sync.SyncPeerInteropTest
```

反过来让 Python 当客户端打本机服务端（要先在 App 里打开「互通」页把服务起起来）：

```bash
adb -s <dev> forward tcp:27471 tcp:47471
python tools/sync_peer.py probe --host 127.0.0.1 --port 27471 --send some.jpg
```

翻页可以单机验（不需要模拟器，也不需要两台手机）：`probe` 加了 `--offset/--limit`，
跑两次把 `list.ids` 对一眼就能确认两页不重叠、第二页的 `count` 小于 `limit` 就是末页。

```bash
python tools/sync_peer.py probe --host 127.0.0.1 --port 27471 --offset 0  --limit 5
python tools/sync_peer.py probe --host 127.0.0.1 --port 27471 --offset 5  --limit 5
```

覆盖到：hello / list-photos 分页 / get-thumb / get-photo / send-photo、未知 type 的具名错误应答、
UDP 信标解析与自身过滤、TTL 过期清除、来件队列封顶与 busy、按 hello 自报身份认领来件、
**没被授权时 `list-photos`/`get-thumb`/`get-photo` 一律被拒且带回那句拒绝语**（`SyncPeerInteropTest.unGrantedClientIsRefusedAndToldWhy`）。
设备侧看不见的「一次批量取图只用一条连接」由对端 journal 断言。

翻页的两条判据（末页怎么认、新页怎么接）是纯函数，由 `shared/src/commonTest` 的
`SyncRemotePagingTest` 在 JVM 上钉住，不需要设备。**界面那一层的「加载下一页 → 张数变多」只能在
真机/模拟器上验**（`testTag("sync_more_remote")` 可点、`testTag("sync_remote_count")` 的文案变化）。

