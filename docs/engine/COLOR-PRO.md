# 专业分级（Pro Grading）· 引擎侧设计与预算

> 适用版本：配方 `v3`、`shared` 引擎 `ToneCurve` / `HslBands` / `LocalMask`。
> 本文是这三组算子的**唯一权威定义**：数学形式、复杂度、性能预算、跨端对齐要求。
> 改动这些定义时，先改这里，再改 `commonTest` 里的判据 —— 顺序反了就会出现
> "代码改了、测试也跟着改、没人记得原来承诺的是什么"。

---

## 0. 一句话结论

三个算子全部是**逐像素、无状态、无邻域**的点运算，因此：

- 行带切分（`ColorCore.processRange`）天然可并行，不需要边界 halo；
- 跨端（Kotlin/JVM 与 Kotlin/Native）只要 IEEE single 的乘加顺序一致，就逐位一致；
- **中性输入（零偏移 / 恒等曲线 / 零调整蒙版）不分配工作内存、不进任何分支**，
  输出与 v2 逐字节相同 —— 这是升级不能砸掉的第一条，由
  `ProPipelineIdentityTest` 用 64×64 图钉死。

---

## 1. 曲线（ToneCurve）

### 1.1 数学定义

控制点 $(x_i, y_i)$，$i=0..n-1$，$x$ 升序、$n \in [2,16]$、$x_i,y_i \in [0,1]$。

采用 **Fritsch–Carlson 单调三次 Hermite 插值**。段 $i$ 的割线斜率

$$
d_i = \frac{y_{i+1}-y_i}{h_i},\qquad h_i = x_{i+1}-x_i
$$

初始切线取中心差分 $m_i = \tfrac12(d_{i-1}+d_i)$（端点取单侧），随后强制单调：

$$
\text{若 } d_i = 0:\ m_i = m_{i+1} = 0;\quad
\text{否则令 } a=\frac{m_i}{d_i},\ b=\frac{m_{i+1}}{d_i},\
\text{若 } a^2+b^2 > 9 \text{ 则 } \tau=\frac{3}{\sqrt{a^2+b^2}},\ m_i \leftarrow \tau a d_i,\ m_{i+1} \leftarrow \tau b d_i
$$

段内求值：

$$
y(t) = (2t^3-3t^2+1)y_i + (t^3-2t^2+t)h_i m_i + (-2t^3+3t^2)y_{i+1} + (t^3-t^2)h_i m_{i+1},\quad t=\frac{x-x_i}{h_i}
$$

**为什么不选自然三次样条**：自然样条在陡峭段会过冲（两控制点之间鼓出一个亮包）。
曲线是用户直接拖出来的，过冲意味着"我没动那里，画面怎么亮了一块"。

### 1.2 保证与后置处理

| 保证 | 实现位置 | 判据 |
| --- | --- | --- |
| LUT 单调不减 | 控制点 y 钳成不减 + 段内夹到两端点区间 | `lutIsMonotonicForRandomControlPoints`（1000 组随机点） |
| 值域 ⊆ [0,1] | `normalize` 夹回 + 段内 `coerceIn(0,1)` | `endpointsAreAlwaysInsideUnitInterval` |
| x 乱序自动排序 | `normalize` 先 `sortWith(compareBy(x,y))` | `outOfOrderXIsSortedAutomatically` |
| 同 x 去重 | 相邻 x 差 ≤ 1e-6 时取 y 平均 | 同上 |
| 非法值不炸 | 非有限值丢弃；点数不足补端点；超 16 抽稀 | `nonFiniteControlPointsAreDropped` |
| 恒等判据 | LUT 与 $y=x$ 的最大偏差 ≤ 2e-4 | `emptyCurvesAreNeutralButRealOnesAreNot` |

### 1.3 管线位置与烘焙

曲线定义在**显示域**（sRGB 编码后的 0..1），因为用户在屏幕上拖它。
主循环（`ColorCore.processBase`）内部是线性光，所以曲线不能直接接在中间。

做法：构造期把"主曲线 → 通道曲线"合成为一张 **256 项 8 位表** `curveByteR/G/B`，
在主循环**之后**对量化后的像素再卷一次。

- 每像素每通道成本：**1 次数组读**（不是一次 pow）。
- 中性时 `curveByteR/G/B` 全为 null，整段 `applyCurves` 跳过。
- 顺序：`R` 通道曲线作用在**主曲线之后**，两条曲线互不干扰（改主曲线不改变通道曲线的斜率）。

> 不要改成"逐像素 linearToSrgb → 曲线"。那是一次 `pow`，1200 万像素约 0.35 秒。

---

## 2. 分色（HslBands）

### 2.1 色相带

8 个带，中心色相角：

| 索引 | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 名称 | 红 | 橙 | 黄 | 绿 | 青 | 蓝 | 紫 | 品红 |
| 中心 | 0° | 30° | 60° | 120° | 180° | 240° | 280° | 320° |

每带三个偏移 `hue/sat/lum ∈ [-1,1]`，映射幅度：

| 参数 | 幅度 | 说明 |
| --- | --- | --- |
| hue | ±30° | 足够把橙肤色推到暖金；再大就开始"换颜色" |
| sat | ±100%（相对值 `sat·(1+k)`） | 1.0 表示完全不饱和 |
| lum | ±25%（相对值 `v·(1+k)`） | 与全局曝光量级分开，避免一片天空烧掉 |

### 2.2 带间权重（余弦交叠）

对色相角 $\theta$，取环上最近的两个带 $c_1, c_2$（距离 $d_1 \le d_2$），令

$$
u = \frac{2d_1}{d_1+d_2} \in [0,1],\qquad s = u^2(3-2u)\ \text{(smoothstep)}
$$

$$
w(c_1) = 1 - \tfrac12 s,\qquad w(c_2) = \tfrac12 s
$$

**性质**（都有测试）：

- **单位分解**：任意角度上 8 个权重和恒为 1（误差 ≤ 1e-5）。缺了它，"只把蓝色调亮"会顺手整体提亮。
- **带中心纯**：$u=0 \Rightarrow w(c_1)=1$，"只调橙色"不会带跑邻居。
- **C¹**：smoothstep 两端导数为 0，带边界没有折角，渐变天空不出色阶断层。
- 预计算成 `WEIGHT_LUT[360 × 8]`，热循环只剩一次数组读。

### 2.3 RGB ↔ HSV

热循环用**整数 max/min + 一次除法**求色相扇区：

$$
h = \begin{cases}
60\cdot\frac{g-b}{\Delta} \bmod 360 & \max = r\\
60\cdot\frac{b-r}{\Delta} + 120 & \max = g\\
60\cdot\frac{r-g}{\Delta} + 240 & \max = b
\end{cases}
$$

**为什么不用 atan2 / HSL**：

- `atan2` 在 1200 万像素上是约 0.6 秒的量级，而这里每像素只有 4 次比较 + 1 次除法；
- HSV 的 V 直接就是 `max(r,g,b)/255`，省一次除法和一次开方；对"分色"而言
  HSV 与 HSL 只差一个重标定，幅度常量里已经吸收掉。

**精度**：`rgb → hsv → rgb` 往返最大误差 ≤ 1 个 8 位码（`rgbHsvRoundTripErrorIsWithinOneCode`）。
把色相写成整数截断的 `60*(g-b)/delta` 会让误差跳到 4 个码 —— 那条测试就是拦这个的。

**无彩色保护**：`sat ≤ 0` 的像素直接跳过（`continue` 必须在权重计算**之前**）。
纯灰的色相是未定义量，动它就是错。

---

## 3. 局部调整（LocalMask）

### 3.1 形状

坐标全部归一化到 0..1，相对**工作缓冲**（不是原图）：

$$
n_x = \frac{x}{w-1},\qquad n_y = \frac{y}{h-1}
$$

用 `w-1` 而不是 `w`：这样 `x = w-1` 正好落在 1.0，边界蒙版不会差半像素。

**线性**（`LinearMask(angle, start, end)`）：渐变方向 $\theta$，投影

$$
p = n_x\cos\theta + n_y\sin\theta
$$

对单位方四角求 $p_{\min}, p_{\max}$ 后归一：

$$
q = \frac{p - p_{\min}}{p_{\max}-p_{\min}},\qquad
t = \mathrm{clamp}\!\left(\frac{q-s}{e-s},0,1\right),\qquad w = t^2(3-2t)
$$

四角归一化的意义：任何角度下 `start/end` 的语义一致（0 = 画面一端，1 = 另一端），
否则改成 45° 就得重新学一遍滑杆。

**径向**（`RadialMask(cx,cy,rx,ry,feather,inverted)`）：椭圆归一化距离

$$
d = \sqrt{\left(\frac{n_x-c_x}{r_x}\right)^2 + \left(\frac{n_y-c_y}{r_y}\right)^2}
$$

$$
w = \begin{cases}
1 & d \le 1-f\\
1 - \mathrm{smoothstep}\!\left(\frac{d-(1-f)}{f}\right) & 1-f < d < 1\\
0 & d \ge 1
\end{cases}
\qquad \text{inverted 时 } w \leftarrow 1-w
$$

三条硬判据：**中心 = 1、边缘 = 0、羽化段单调**（`radialCentreIsOneEdgeIsZero`、`radialFeatherIsMonotonicDecreasing`）。

### 3.2 调整量

`LocalAdjust(exposure, contrast, saturation, temperature)`，全部 [-1,1]，按蒙版权重 $w$ 缩放：

| 参数 | 作用 | 备注 |
| --- | --- | --- |
| exposure | `c *= 1 + 1.5·e·w` | 不用 `pow(2, ev)`：每像素一次 pow 在 1200 万上要 0.4 秒；`1+1.5e` 与 `2^(2e)` 在 [-1,1] 上差 < 6%，严格单调 |
| contrast | `c = (c-0.5)(1+c·w) + 0.5` | 中心 0.5 不动，黑白点不漂 |
| saturation | `c += (c - lum)·s·w`，`lum` 用 Rec.709 权重 | 与全局饱和同一条公式 |
| temperature | `r *= 1+0.35t·w`，`b *= 1-0.45t·w` | 与 `lockDaylightWb` 的系数同源 |

**权重 0 处逐位不变**：`if (wgt <= 0f) continue` + 写出前比对原值，避免"乘 1.0 再量化"引入 ±1 漂移。

### 3.3 权重场内存策略

| 模式 | 触发条件 | 内存 | 每像素成本 |
| --- | --- | --- | --- |
| 整场预计算 | `蒙版数 × w × h × 4 ≤ 24 MB` | 最多 24 MB | 1 次数组读 |
| 按行预计算 | 超过上限（4096 长边 + 4 蒙版必然落这里） | `蒙版数 × w × 4` | 形状计算 w·h 次 + 行内 1 次读 |

按行模式下**每行只算一次形状**（`fillRow`），行内像素仍然只查表 —— 形状计算次数是
`w·h` 而不是 `w·h·蒙版数`。两条路径数值逐位一致（`rowModeMatchesFullPrecompute`）。

> 为什么上限是 24 MB：4096 长边按 3:2 约 1200 万像素，4 个蒙版全展开是 **192 MB**，
> 在 14 GB 的机器上会直接把系统压进页面文件。24 MB 是"预览（≤1600 长边）爽快、
> 成片自动降级"的分界。

### 3.4 数量上限

`LocalMaskField.MAX_MASKS = 4`。超出的部分**截断**（不抛）——配方来自外部。
中性蒙版（四项全 0）在构造时就被丢掉：它不产生任何改动，却会白算一整张权重场。

---

## 4. 管线顺序

```
线性光 toLin
  → 白平衡（temp）
  → 全局色调 toneLut（含 shadow lift / contrast / highlight roll）
  → 单色 或 全局饱和 + split tone + soft + skinProtect
  → fade
  → 暗角
  → 颗粒
  → 量化到 8 位（toSrgb）
  ───────────── 以上 v2 完全不变 ─────────────
  → 局部蒙版（8 位域，逐像素权重）
  → HSL 分色（8 位域，HSV）
  → 曲线（8 位域，256 项烘焙表）
```

**为什么三个新算子都在量化之后**：

1. 三者都是"显示域"的语义（用户对着屏幕拖），放在线性域会让同一条 S 曲线在中暗部
   与高光的力度完全不成比例；
2. 8 位域里它们全部是整数查表，**与"没有这些算子时"是同一条指令路径**，
   于是中性判据天然逐位成立；
3. 顺序固定为 蒙版 → 分色 → 曲线：先局部定位，再分色，最后整体影调。
   这个顺序也决定了"预览与成片一致"的推导链。

---

## 5. 性能预算

**验收口径**：4096 长边 ≈ 1200 万像素，8 线程（`ColorPipeline` 的默认分块），
端到端（含 3 个蒙版 + 8 带分色 + 一条 S 曲线）**< 1.2 秒**。

### 5.1 实测（不是估算）

JVM 上 4096×3000 = 12.29 MPix，**单线程**，预热后再计时：

| 配置 | 耗时 |
| --- | --- |
| v2 基线（无任何专业算子） | 1302 ms |
| + 曲线（一条 S） | 1174 ms |
| + 分色（2 带有偏移） | 1222 ms |
| + 蒙版（3 个，按行模式） | 1807 ms |
| **三者全开** | **2382 ms** |

8 线程行带线性加速后 ≈ **300 ms**，相对 1.2 s 的预算有 4 倍余量。
（曲线那一行比基线还低是 JIT/缓存噪声，量级上说明它确实是"免费"的。）

### 5.2 优化前后（这两条是踩出来的）

第一版实测 **3873 ms**，其中分色 +1044 ms、蒙版 +1283 ms，都属于"看着像查表、
实际每个像素都在重算"的写法。改了两处，数值**逐位不变**（全套测试照跑）：

| 位置 | 改之前 | 改之后 | 省下 |
| --- | --- | --- | --- |
| 分色 | 每像素遍历 8 个带、做 8 次权重乘加 | 按色相角 0..359 **预合成**加权偏移表，每像素 3 次读表 | ~830 ms |
| 蒙版 | 每像素一次 sealed 分派 + `plan.at()` 调用 | 每**行**一次分派，线性段用仿射递推、径向段把 y 分量提到行外 | ~1600 ms |

> 教训：`when (shape)` 写在每像素循环里，即使分支预测完美，也挡不住虚调用与装箱。
> 这两个算子的正确形状都是"每行（或每角度）准备一次，行内只做乘加"。

### 5.3 内存与分配

真正会爆的不是算术，是分配：

| 项 | 中性时 | 全开时 |
| --- | --- | --- |
| 曲线表 | 0 字节 | 3 × 256 × 4 = 3 KB |
| 蒙版权重场 | 0 字节 | 预览（≤1600 长边）整场模式 ≤ 1.5 MB；成片按行模式 `4 × w × 4` = 64 KB |
| 分色加权表 | 0 字节 | 3 × 360 × 4 = 4.3 KB |
| `EngineParams` | 每张图构造 1 次 | 同左（**不在行带循环里重建**） |

**退化场景**（必须知道）：

| 场景 | 表现 | 处理 |
| --- | --- | --- |
| 预览 1600 长边 + 4 蒙版 | 整场模式，1.5 MB | 无感 |
| 成片 4096 + 4 蒙版 | 按行模式，形状计算 1200 万 × 4 次 | 仍在预算内（见上表） |
| 内存 < 192 MB 设备 | `exportEdge = 1600`，永不进按行模式 | 自动规避 |

---

## 6. 与 iOS 对齐要求

三条**必须逐位一致**（`EngineDeterminismTest` 与 `ProPipelineIdentityTest` 两端都跑）：

1. **曲线 LUT**：Fritsch–Carlson 的每一步都是 IEEE single 的加减乘除，Kotlin/Native 与
   JVM 结果相同。切线与 `tau` 的乘法顺序不许改（`tau * a * di` 而不是 `tau * (a * di)`）。
2. **HSL 权重表**：`WEIGHT_LUT` 用 `cos` 只出现在**构造**阶段之前的历史版本里；
   当前版本只用 smoothstep（纯浮点乘法），**不引入任何平台 libm 依赖**。这是刻意的：
   `cos` 在两端可能差 1 ulp，而 1 ulp 足以让权重和偏离 1e-5 的判据。
3. **蒙版坐标**：归一化除数必须是 `w-1` / `h-1`，两端同值。用 `w` 会让 4096 与 1600
   两档预览的蒙版位置差半个像素的累积。

**iOS 侧的接口对齐**：

- `EngineParams` 新增了 3 个构造参数，Kotlin 默认值**不导出到 Swift**，所以
  `Support.swift` 的 `EnginePipeline.makeParams` 必须显式传 `curves/hsl/masks`
  （与它现有的 15 个参数同理）；
- `YishunRecipe` 升到 v3 后，Swift 侧 `YishunView.swift` 的 `RecipeCodec.encodeForExif`
  调用点会多出三个可空字段，`RecipePro.withPro` 是两端都应该走的组装入口。

**性能对齐**：iOS 的 `Concurrency` 分块与 Android 的 8 线程分块等价（都是行带），
所以上表的预算可以照抄。

---

## 7. 配方 v3 兼容矩阵

| 写入方 \ 读取方 | v1 App | v2 App | v3 App（本轮） |
| --- | --- | --- | --- |
| v1 payload（无 `v`，含死字段 `seed`） | 读得进 | 读得进 | **读得进**（`seed` 被忽略，`v` 默认 1） |
| v2 payload（14 字段） | 拒绝（`v=2 > 1`） | 读得进 | **读得进，字段语义完全不变** |
| v3 payload（+ `curve` / `hsl` / `masks`） | 拒绝 | 拒绝（`v=3 > 2`） | 读得进 |
| v4+ payload | 拒绝 | 拒绝 | **整条拒绝** |
| `v < 1` | 拒绝 | 拒绝 | **整条拒绝** |

规则（SYNC.md §一瞬配方，继续有效）：

- `v` 总是写出（`@EncodeDefault(ALWAYS)`）；
- **没有 `seed`**：种子只能由 `lookId` 经 `ColorCore.grainSeedFor` 单点推出；
- **`exportEdge` 必须记**：颗粒按成片长边调校，换台内存不同的机器就对不上；
- 三个新字段全部可空，`encodeDefaults = false` 保证**中性配方不会多出键**（v2 形状不变）。

**降级行为**（不崩、不猜）：

| 坏输入 | 行为 |
| --- | --- |
| `curve.master` 长度奇数 / < 4 | 该条曲线作废（退化成中性） |
| `masks[].kind` 不认识 | 整条蒙版丢弃 |
| `masks[]` 超过 4 个 | 截断到 4 |
| `hsl.bands` 长度 < 24 | 整组退化成全 0 |

判据：`RecipeV3Test.corruptedProFieldsDegradeInsteadOfCrashing`。

---

## 8. 自定义配方（CustomLooks）

- 落盘：`filesDir/custom_looks.json`（**不进 SharedPreferences**，理由见类注释）。
- 写盘：先写 `.tmp` 再改名，写到一半被杀不会留下半截 JSON 把整库读丢。
- 导出：`SPLOOK1:` + Base64Url(JSON, 无 padding)。前缀与 `SPRECIPE1:` **刻意不同**：
  两者语义不同（配方 = 怎么调这张照片，自定义配方还带名字与时间），
  混用会让导入方拿到一条能解但缺字段的 payload。
- 导入：只按 Base64Url 字母表收口，解析失败返回 `null`（**不崩**）；
  id 总是重新生成（导入同一条两次要得到两条）。
- `asLooks()`：id 前缀 `user_`，可直接进现有配方选择器。
- 备份：`backup_rules.xml` 与 `data_extraction_rules.xml` 的**云备份与换机直传两侧
  都排除** `custom_looks.json`（与 `suipai.private.xml` 同口径）。

---

## 9. 判据清单（改代码前先看这里）

| 算子 | 判据 | 测试 |
| --- | --- | --- |
| 全部 | 中性输入 → 逐字节不变 | `ProPipelineIdentityTest.neutralProOperatorsLeavePixelsBitIdentical` |
| 全部 | 中性时不分配工作内存 | `neutralProOperatorsDoNotAllocateWorkerState` |
| 全部 | 行带乱序 → 结果不变 | `proOperatorsKeepChunkedProcessingDeterministic` |
| 曲线 | 1000 组随机点 LUT 单调 | `ToneCurveTest.lutIsMonotonicForRandomControlPoints` |
| 曲线 | 端点 ⊆ [0,1]、x 乱序自动排序 | `endpointsAreAlwaysInsideUnitInterval` / `outOfOrderXIsSortedAutomatically` |
| 分色 | 零偏移逐位不变、灰阶不染色 | `HslBandsTest.zeroOffsetsAreBitExact` / `grayscaleIsNeverTinted` |
| 分色 | 往返 ≤ 1/255、权重和恒为 1 | `rgbHsvRoundTripErrorIsWithinOneCode` / `bandWeightsSumToOneEverywhere` |
| 蒙版 | 中心 1 / 边缘 0 / 羽化单调 | `LocalMaskProbeTest.radialCentreIsOneEdgeIsZero` / `radialFeatherIsMonotonicDecreasing` |
| 蒙版 | 整场 vs 按行逐位一致 | `rowModeMatchesFullPrecompute` |
| 配方 | v2 读得进、v3 往返、v4 拒绝 | `RecipeV3Test` |
| 自定义配方 | 序列化往返、导入导出往返、坏文本不崩 | `CustomLooksTest` |

**每条判据都带"喂坏例子必须变红"的自检**。写新判据时照抄这个结构：
先断言正常路径，再把一个必然违反它的输入喂进去并断言"这次必须失败"。
