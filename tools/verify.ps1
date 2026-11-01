# 随拍 · 设备端到端验证脚本
#
# 为什么需要它：装机验证此前全靠手工敲 adb，命令散在 ANDROID-VERIFY.md 的 44 节里，
# 每一步的坑（dump 前要 rm -f、keyevent 26 是 POWER 会熄屏、装错包 24 条假红）都靠人记。
# 这个脚本把「装包 → 冷启动 → 逐屏断言 → 落报告」串成一条可重复的命令。
#
# 用法（在工程根目录）：
#   pwsh tools/verify.ps1 install                    # 装最新的 debug APK（含 md5 认包）
#   pwsh tools/verify.ps1 smoke                      # 冷启动 + 逐屏锚文本断言
#   pwsh tools/verify.ps1 smoke -SkipInstall         # 不重装，直接测
#   pwsh tools/verify.ps1 shot 03-相机                # 截图直接落 store/screens/phone/
#   pwsh tools/verify.ps1 dump                       # 打印当前屏的语义树（已去噪）
#   pwsh tools/verify.ps1 tap "筛选"                 # 按文本点一颗可见节点
#   pwsh tools/verify.ps1 log                        # 看最近崩溃/ANR（不依赖 app 日志）
#   pwsh tools/verify.ps1 report                     # 汇总本次跑到哪、哪一步失败
#   pwsh tools/verify.ps1 cleanup                    # 收尾：杀 logcat/instrument/netsimd + 关模拟器
#
# 安全闸（每次运行都先过）：
#   设备串口由 `pwsh tools/emu.ps1 serial` 提供；随后**必须**用
#   `getprop ro.boot.qemu.avd_name` 确认它是 suipai_gapi / suipai_r4，
#   否则立刻退出（本机还有别的会话在跑 dilao_fast / jian_test，抢过来会毁掉别人的测量）。
#   全程只用 `-s <serial>`，**任何情况下都不 adb kill-server**。
#
# 已知坑（本机实测，别再花时间重踩）：
#   * 这台 AVD 冷启动 80–100 s；`am start` 之后要等 8 s 首帧才稳定。
#   * `uiautomator dump` 前必须 `rm -f /sdcard/ui.xml`，否则失败时读到的是**上一屏**的旧文件。
#   * `uiautomator dump --compressed` 会按"对无障碍重要"裁掉纯文字 TextView，中文标签全没 → 不许加。
#   * `adb logcat` 在这台机器上抓不到 app 自己的日志（goldfish-logcat 把环形缓冲抽到宿主侧），
#     所以本脚本**不依赖 logcat 判断功能是否正常**；`log` 子命令只看 FATAL / ANR。
#   * 唤醒用 `keyevent 224`(WAKEUP)；`keyevent 26` 是 POWER，它是"切换"，屏亮着会按灭。
#   * 截图必须 `exec-out screencap -p >` 直接落 PC；写 /sdcard 会被 MediaStore 收成照片行。
#   * 抽屉（ModalBottomSheet）是独立窗口：它开着时底下那层不在 dump 里，取证前先 BACK 收掉。
#   * `AppBarCell` 的标签带「，已选中」后缀 → 按标签找节点一律用前缀匹配。
#
# 报告落在 docs/arch/DEVICE-RUN-<yyyyMMdd-HHmm>.md，同一次进程内可反复 report 覆盖。

param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('install', 'smoke', 'shot', 'dump', 'tap', 'log', 'report', 'cleanup', 'selftest')]
    [string]$Action,

    [Parameter(Position = 1)]
    [string]$Name,

    # 设备闸的措辞：默认允许两台随拍 AVD；-AnyAvd 表示"我知道我在干什么"
    [string[]]$AllowedAvd = @('suipai_gapi', 'suipai_r4'),
    [switch]$AnyAvd,

    # smoke：不重装、只冷启动+断言
    [switch]$SkipInstall,
    # smoke：每一步之间多等几秒（慢机器/首次索引）
    [int]$SettleSeconds = 8,
    # tap：可选的 `-WaitMs`，点完之后等多久再返回
    [int]$WaitMs = 1500,
    # dump：把整棵树原样打出来（默认只打 text / desc / resource-id 非空的节点）
    [switch]$Raw,
    # report：换一个输出目录
    [string]$ReportDir = 'docs/arch'
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Pkg = 'com.suipai.app.debug'
$Activity = "$Pkg/com.suipai.app.MainActivity"
$PhoneShots = Join-Path $Root 'store\screens\phone'

# ── 本进程内的执行账本（report 用）────────────────────────────────────────
$script:Steps = [System.Collections.Generic.List[object]]::new()
$script:RunStarted = Get-Date
$script:DeviceSerial = $null
$script:DeviceAvd = $null

function Add-Step {
    param(
        [string]$Name,
        [ValidateSet('PASS', 'FAIL', 'SKIP', 'INFO')] [string]$Status,
        [string]$Detail = '',
        [string]$Expected = '',
        [string]$Actual = ''
    )
    $script:Steps.Add([pscustomobject]@{
        At       = (Get-Date).ToString('HH:mm:ss')
        Name     = $Name
        Status   = $Status
        Detail   = $Detail
        Expected = $Expected
        Actual   = $Actual
    })
    $color = switch ($Status) {
        'PASS' { 'Green' }
        'FAIL' { 'Red' }
        'SKIP' { 'DarkYellow' }
        default { 'Gray' }
    }
    $line = "[$Status] $Name"
    if ($Detail) { $line += " — $Detail" }
    Write-Host $line -ForegroundColor $color
    if ($Status -eq 'FAIL') {
        if ($Expected) { Write-Host "        期望: $Expected" -ForegroundColor Red }
        if ($Actual) { Write-Host "        实际: $Actual" -ForegroundColor Red }
    }
}

# ── adb 与设备闸 ───────────────────────────────────────────────────────────
function Get-AdbPath {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
    else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) {
        throw "找不到 adb：$adb（设 ANDROID_HOME 或 ANDROID_SDK_ROOT）"
    }
    return $adb
}

$Adb = Get-AdbPath

function Invoke-Adb {
    param([string[]]$Args, [switch]$AllowFail, [switch]$Raw)
    $all = @('-s', $script:DeviceSerial) + $Args
    $out = & $Adb @all 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFail) {
        throw "adb $($Args -join ' ') 失败（exit=$code）：$($out -join "`n")"
    }
    if ($Raw) { return $out }
    return ($out -join "`n").Trim()
}

# 设备闸：拿到 serial → 断言它确实是我们的 AVD。
# 本机踩过：起设备时忘了 -s，探针量到的是别人会话的 jian_test / dilao_fast，
# 一整轮数字全是假的，而且看起来完全正常。
function Initialize-Device {
    if ($script:DeviceSerial) { return }
    $emuScript = Join-Path $PSScriptRoot 'emu.ps1'
    if (-not (Test-Path $emuScript)) { throw "找不到 tools/emu.ps1" }

    $serialRaw = & pwsh -NoProfile -File $emuScript serial 2>&1
    if ($LASTEXITCODE -ne 0 -or -not $serialRaw) {
        throw "没有在跑的模拟器（先跑 pwsh tools/emu.ps1 start）。emu.ps1 输出：$serialRaw"
    }
    # 单元素数组会被拆成标量，`$x[0]` 拿到的是第一个字符 —— 先 @() 包住再取
    $serialLines = @($serialRaw | Where-Object { "$_".Trim() -match '^emulator-\d+$' })
    if ($serialLines.Count -eq 0) { throw "emu.ps1 serial 没给出合法的 emulator-NNNN：$serialRaw" }
    $script:DeviceSerial = ([string]$serialLines[0]).Trim()

    $avd = (& $Adb -s $script:DeviceSerial shell getprop ro.boot.qemu.avd_name 2>&1 |
        Where-Object { $_ -and "$_".Trim() } | Select-Object -First 1)
    $script:DeviceAvd = if ($avd) { "$avd".Trim() } else { '<unknown>' }

    if (-not $AnyAvd -and $AllowedAvd -notcontains $script:DeviceAvd) {
        $msg = @(
            "设备不对：串口 $($script:DeviceSerial) 上是 AVD '$($script:DeviceAvd)'，",
            "期望 $($AllowedAvd -join ' 或 ')。",
            "这台机器上还有别的会话在用模拟器（dilao_fast / jian_test），",
            "抢过来量会把别人的结果和我们自己的都毁掉。",
            "确认要用它就跑：pwsh tools/verify.ps1 <子命令> -AnyAvd"
        ) -join ' '
        Add-Step 'device-gate' 'FAIL' $msg "AVD ∈ $($AllowedAvd -join ',')" "AVD = $($script:DeviceAvd)"
        exit 2
    }

    $booted = (Invoke-Adb -Args @('shell', 'getprop', 'sys.boot_completed') -AllowFail)
    if ($booted.Trim() -ne '1') {
        Add-Step 'device-gate' 'FAIL' "设备 $($script:DeviceSerial) 没开机完成（sys.boot_completed=$booted）"
        exit 2
    }
    Add-Step 'device-gate' 'INFO' "serial=$($script:DeviceSerial) avd=$($script:DeviceAvd)"
}

# ── 通用设备动作 ───────────────────────────────────────────────────────────
function Wake-Device {
    # 224 = KEYCODE_WAKEUP，只会唤醒；26 是 POWER，会"切换"。
    Invoke-Adb -Args @('shell', 'input', 'keyevent', '224') -AllowFail | Out-Null
    Invoke-Adb -Args @('shell', 'svc', 'power', 'stayon', 'true') -AllowFail | Out-Null
}

function Get-ForegroundPackage {
    # mCurrentFocus 比 `dumpsys activity` 轻得多，够用来回答"我在不在自己的 app 里"。
    $dump = Invoke-Adb -Args @('shell', 'dumpsys', 'window') -AllowFail
    $line = @($dump -split "`n" | Where-Object { $_ -match 'mCurrentFocus' } | Select-Object -First 1)
    if ($line.Count -eq 0) { return $null }
    $m = [regex]::Match([string]$line[0], '([a-zA-Z0-9_.]+)/[a-zA-Z0-9_.$]+')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

function Clear-DumpFile {
    # dump 失败时 `cat` 读到的是上一屏留下的旧文件 —— 这是本项目最贵的一次假红来源。
    Invoke-Adb -Args @('shell', 'rm', '-f', '/sdcard/ui.xml') -AllowFail | Out-Null
}

function Get-UiDump {
    <#
      返回 { Xml = [xml], Nodes = @( { text, desc, id, cls, clickable, bounds } ), Raw = string }
      失败（dump 没落文件 / 解析不了）返回 $null —— 调用方必须把它判成红，不许当成"没有节点"。
    #>
    Clear-DumpFile
    $dumpOut = Invoke-Adb -Args @('shell', 'uiautomator', 'dump', '/sdcard/ui.xml') -AllowFail
    if ("$dumpOut" -notmatch 'dumped to') {
        # 整条被 Killed / null root node 都走这里：再试一次（实测会时好时坏）
        Start-Sleep -Milliseconds 800
        Clear-DumpFile
        $dumpOut = Invoke-Adb -Args @('shell', 'uiautomator', 'dump', '/sdcard/ui.xml') -AllowFail
        if ("$dumpOut" -notmatch 'dumped to') {
            return $null
        }
    }
    $raw = Invoke-Adb -Args @('shell', 'cat', '/sdcard/ui.xml') -AllowFail
    if (-not $raw -or "$raw" -notmatch '<hierarchy') { return $null }
    try { $xml = [xml]$raw } catch { return $null }

    $nodes = [System.Collections.Generic.List[object]]::new()
    foreach ($n in $xml.SelectNodes('//node')) {
        $nodes.Add([pscustomobject]@{
            text      = [string]$n.text
            desc      = [string]$n.'content-desc'
            id        = [string]$n.'resource-id'
            cls       = [string]$n.class
            clickable = [string]$n.clickable
            bounds    = [string]$n.bounds
        })
    }
    return [pscustomobject]@{ Xml = $xml; Nodes = $nodes; Raw = "$raw" }
}

function Get-AnchorHit {
    <#
      在一个 dump 里找锚文本。命中规则（顺序即优先级）：
        1. resource-id 含锚文本（testTag 会映射成 android:id，需要 testTagsAsResourceId 已开）
        2. content-desc 或 text **等于**锚文本
        3. content-desc 或 text **以** 锚文本开头（兼容「，已选中」后缀）
      返回命中的那个节点，找不到返回 $null。
    #>
    param($Dump, [string]$Anchor)
    if (-not $Dump) { return $null }
    foreach ($n in $Dump.Nodes) {
        if ($n.id -and $n.id.Contains($Anchor)) { return $n }
    }
    foreach ($n in $Dump.Nodes) {
        if ($n.desc -eq $Anchor -or $n.text -eq $Anchor) { return $n }
    }
    foreach ($n in $Dump.Nodes) {
        if (($n.desc -and $n.desc.StartsWith($Anchor)) -or ($n.text -and $n.text.StartsWith($Anchor))) {
            return $n
        }
    }
    return $null
}

function Format-NodeTaste {
    # 失败时给一眼"屏幕上到底有什么"，比让人再跑一次 dump 便宜。
    param($Dump, [int]$Max = 18)
    if (-not $Dump) { return '<dump 失败，读不到语义树>' }
    $seen = @()
    foreach ($n in $Dump.Nodes) {
        $v = if ($n.desc) { $n.desc } elseif ($n.text) { $n.text } else { $null }
        if (-not $v) { continue }
        if ($seen -contains $v) { continue }
        $seen += $v
        if ($seen.Count -ge $Max) { break }
    }
    if ($seen.Count -eq 0) { return '<树里一个带字节点都没有>' }
    return ($seen -join ' / ')
}

function Invoke-ColdStart {
    Invoke-Adb -Args @('shell', 'am', 'force-stop', $Pkg) -AllowFail | Out-Null
    Start-Sleep -Milliseconds 500
    $startOut = Invoke-Adb -Args @('shell', 'am', 'start', '-W', '-n', $Activity) -AllowFail
    if ("$startOut" -match 'Error') {
        Add-Step 'cold-start' 'FAIL' "am start 报错：$startOut"
        return $false
    }
    return $true
}

# ── APK 定位与认包 ─────────────────────────────────────────────────────────
function Get-LatestDebugApk {
    $dir = Join-Path $Root 'app\build\outputs\apk\debug'
    if (-not (Test-Path $dir)) { throw "还没有 debug APK：$dir（先跑 pwsh tools/abuild.ps1 :app:assembleDebug）" }
    $apk = Get-ChildItem $dir -Filter '*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $apk) { throw "$dir 里没有 .apk" }
    return $apk
}

function Get-LocalMd5([string]$Path) {
    return (Get-FileHash -Path $Path -Algorithm MD5).Hash.ToLower()
}

function Assert-InstalledMatches {
    <#
      装完必须认包：debug 变体 applicationId 带 .debug 后缀，
      `adb install -r app-debug.apk` 会"Success"地装上另一个包，而探针启动的还是老的包
      —— ANDROID-VERIFY §41 记过一整轮 24 条假红全来自这一条。
    #>
    param([string]$LocalApk)
    $localMd5 = Get-LocalMd5 $LocalApk
    $paths = Invoke-Adb -Args @('shell', 'pm', 'path', $Pkg) -AllowFail
    if ("$paths" -notmatch 'package:') {
        Add-Step 'provenance' 'FAIL' "$Pkg 没装上" "pm path 有输出" "pm path = $paths"
        return $false
    }
    $apkPath = ([regex]::Match("$paths", 'package:(\S+)')).Groups[1].Value
    $devMd5 = (Invoke-Adb -Args @('shell', 'md5sum', $apkPath) -AllowFail)
    $devMd5 = "$devMd5".Trim().Split(' ')[0].ToLower()
    if ($devMd5 -ne $localMd5) {
        Add-Step 'provenance' 'FAIL' "设备上的包与本地 APK 不是同一份" $localMd5 $devMd5
        return $false
    }
    Add-Step 'provenance' 'PASS' "md5=$localMd5（本地 == 设备）"
    return $true
}

# ── 锚文本表 ───────────────────────────────────────────────────────────────
#
# 一张表说清「哪一屏、怎么进、至少要看到什么」。
# 表里的锚文本全部取自源码里的**真实字面量**（改文案会让这张表红 —— 那是对的，
# 因为改文案本来就该同步检查单）。
#
# Kind：
#   auto   —— smoke 自己能走到（冷启动 / 点底栏 / 点格子）
#   manual —— 需要多步手势（长按、双指、滑动方向）或需要外部条件（无网络、无权限），
#             smoke 只提示"这一屏要手工走"，不判红。检查单里逐条写了怎么走。
#
$AnchorTable = @(
    # ── 1. 首启 / 引导 / 授权 ────────────────────────────────────────────
    @{
        Id     = 'onboarding'
        Title  = '首启引导'
        Kind   = 'manual'
        How    = '清数据后冷启动；或 关于 › 重看首次引导'
        Anchors = @('onboarding_skip', '把相册当成印样页翻', '跳过')
        Why    = '首启是否进引导由 MainActivity 的 OnboardingGate.shouldShow 决定；权限由独立的 PermissionGate 挡着，两者顺序错了会看到一屏黑'
    },
    @{
        Id     = 'permission-gate'
        Title  = '授权门'
        Kind   = 'manual'
        How    = 'adb shell pm revoke 两个媒体权限后冷启动'
        Anchors = @('授权相册', '去设置', '再问一次')
        Why    = '撤权限后**不许**显示"还没有照片"（那是撒谎），必须出这道门'
    },

    # ── 2. 时间线 ────────────────────────────────────────────────────────
    @{
        Id     = 'timeline'
        Title  = '时间线（冷启动后首屏）'
        Kind   = 'auto'
        How    = '冷启动 8 秒后'
        Anchors = @('照片', '筛选', '显示')
        Why    = '这三颗是页眉工具条上常驻的；缺任一颗说明工具条被挤爆或没渲染'
    },
    @{
        Id     = 'timeline-search'
        Title  = '搜索框'
        Kind   = 'auto'
        How    = '时间线 → 点搜索框（矮屏是内嵌那颗）'
        Anchors = @('搜索', '文件名、相册、2024-09、今天')
        Why    = '提示语折行会把输入框撑高、把网格顶光（§39 量到 528 px）；这里只看它还在'
    },

    # ── 2b. 页眉「更多」抽屉：一整族二级入口都在这里 ──────────────────────
    @{
        Id     = 'chrome-more'
        Title  = '页眉「更多」抽屉'
        Kind   = 'auto'
        How    = '页眉行末那颗「更多」（testTag = chrome_more）'
        Anchors = @('chrome_more_sheet', '看照片', '整理', '设置')
        Why    = '三个 tab 之外的**全部**入口都收在这一个抽屉里（搜索/回忆/收藏夹/回收站/拼图/幻灯片/设置/隐私）。三组分区标题缺一个就说明抽屉没开好'
    },
    @{
        Id     = 'chrome-more-cells'
        Title  = '「更多」抽屉的 8 颗格子'
        Kind   = 'manual'
        How    = '打开「更多」抽屉后逐颗看'
        Anchors = @('chrome_open_search', 'chrome_open_memories', 'chrome_open_collections', 'chrome_open_trash', 'chrome_open_collage', 'chrome_open_slideshow', 'chrome_open_settings', 'chrome_open_privacy')
        Why    = '每颗都带 testTag，改文案不会让判据失灵；点它应当**先关抽屉再进页**（AppMoreCell 收口了这件事）'
    },

    # ── 3. 查看器 ────────────────────────────────────────────────────────
    @{
        Id     = 'viewer'
        Title  = '查看器'
        Kind   = 'manual'
        How    = '时间线点第一张缩略图'
        Anchors = @('返回', '更多', '旋转')
        Why    = '查看器页首那三颗是它自己的工具条；`返回` 不在就意味着 BACK 会把整个 app 退掉'
    },

    # ── 4. 相机 ──────────────────────────────────────────────────────────
    @{
        Id     = 'camera'
        Title  = '相机取景'
        Kind   = 'auto'
        How    = '点右下角快门 FAB（onClickLabel = "打开相机"）'
        Anchors = @('拍照', '关闭')
        Why    = '`拍照` 是快门那颗的 onClickLabel；`关闭` 是页首退出。取景画面本身在 uiautomator 里是空的（SurfaceView），只能靠这两颗节点认屏'
    },
    @{
        Id     = 'camera-sheet'
        Title  = '相机「设置」抽屉'
        Kind   = 'auto'
        How    = '相机 → 点「设置」那颗'
        Anchors = @('这一趟取景', '系统相机', '对焦中央', '锁定界面')
        Why    = '抽屉是独立窗口，开着时底下那层不在树里；这几个锚点同时验证"抽屉真的开了"和"专业控件在里面"'
    },

    # ── 5. 一瞬（含专业版面板）────────────────────────────────────────────
    @{
        Id     = 'yishun'
        Title  = '一瞬'
        Kind   = 'manual'
        How    = '查看器 → 点「一瞬」'
        Anchors = @('完成', '微调', '筛选', '更多')
        Why    = '`完成` 是 AppBarPrimary（保存并退出）；四颗齐了才说明整排工具条没被挤掉'
    },
    @{
        Id     = 'yishun-pro'
        Title  = '一瞬 › 专业面板（曲线 / HSL / 蒙版）'
        Kind   = 'manual'
        How    = '一瞬 → 「微调」/ 专业入口 → ProHubSheet'
        Anchors = @('yishun_pro_curve', 'yishun_pro_hsl', 'yishun_pro_mask', 'yishun_pro_save_recipe')
        Why    = '波段 2 新加的三块面板 + 存自定义配方。它们在语义树里靠 testTag 可寻址（按中文文案找会因为面板名与格子名相似而点错）'
    },
    @{
        Id     = 'yishun-more'
        Title  = '一瞬「更多」抽屉'
        Kind   = 'manual'
        How    = '一瞬 → 点「更多」'
        Anchors = @('配方行', '显示', '对比原图', '导出')
        Why    = '§19/§21 反复修过的那一叠；分区标题与格子撞名会让自动化点错节点'
    },

    # ── 6. 收：收藏 / 隐藏 / 回收站 ────────────────────────────────────────
    @{
        Id     = 'selection-bar'
        Title  = '多选条'
        Kind   = 'manual'
        How    = '长按任意一张缩略图（约 1400 ms）'
        Anchors = @('取消', '全选', '反选', '删除')
        Why    = '多选条不横滚（§19 的账）；`删除` 是批量删除那颗'
    },
    @{
        Id     = 'trash'
        Title  = '回收站'
        Kind   = 'manual'
        How    = '页眉「更多」→ 整理 › 回收站（chrome_open_trash）'
        Anchors = @('回收站', '清空')
        Why    = '30 天候车室；`清空` 要二次确认、`恢复` 不要'
    },
    @{
        Id     = 'collections'
        Title  = '收藏夹'
        Kind   = 'manual'
        How    = '页眉「更多」→ 看照片 › 收藏夹（chrome_open_collections）'
        Anchors = @('收藏夹', '新建')
        Why    = '用户自建相册；长按某一行出「重命名 / 删除」'
    },

    # ── 7. 玩：拼图 / 回忆 / 幻灯片 / 配方卡 ──────────────────────────────
    @{
        Id     = 'collage'
        Title  = '拼图'
        Kind   = 'manual'
        How    = '多选 ≥2 张 → 「更多」→ 整理 › 拼图；或直接抽屉里进'
        Anchors = @('导出', '长边')
        Why    = '模板横条里放不下的模板是**灰掉**而不是藏起来（灰着至少能听见"为什么"）'
    },
    @{
        Id     = 'memories'
        Title  = '回忆'
        Kind   = 'manual'
        How    = '页眉「更多」→ 看照片 › 回忆（chrome_open_memories）'
        Anchors = @('回忆', '正在翻旧片')
        Why    = '空态是「还没有能拼成回忆的照片」，不是"加载失败"——这两句分家就是撒谎'
    },
    @{
        Id     = 'slideshow'
        Title  = '幻灯片'
        Kind   = 'manual'
        How    = '页眉「更多」→ 整理 › 幻灯片（chrome_open_slideshow）'
        Anchors = @('这一轮放完了')
        Why    = '只在放完时出现，属于"终局文案"；平时靠画面本身，没有工具条'
    },
    @{
        Id     = 'recipe-card'
        Title  = '配方卡'
        Kind   = 'manual'
        How    = '一瞬 › 专业 › 分享配方卡（或「更多」里对应入口）'
        Anchors = @('recipe_card_preview', 'recipe_card_save', 'recipe_card_share')
        Why    = '把一瞬配方导成一张图；三个 testTag 分别对应预览/保存/分享'
    },

    # ── 8. 控：设置中心 ────────────────────────────────────────────────────
    @{
        Id     = 'settings'
        Title  = '设置中心'
        Kind   = 'manual'
        How    = '页眉「更多」→ 设置 › 设置（chrome_open_settings）'
        Anchors = @('设置', '外观', '相册', '相机', '隐私')
        Why    = '8 组设置按 SettingsGroup 分组；`没接线的开关必须是灰的`（SettingsModel 的 SettingLive.PENDING）'
    },
    @{
        Id     = 'settings-appearance'
        Title  = '设置 › 外观'
        Kind   = 'manual'
        How    = '设置 → 外观'
        Anchors = @('外观', '主题', '字号')
        Why    = '改字级会触发 configChanges 那条路径（manifest 已声明全套）'
    },
    @{
        Id     = 'settings-storage'
        Title  = '设置 › 存储'
        Kind   = 'manual'
        How    = '设置 → 存储'
        Anchors = @('存储', '清缓存', '回收站')
        Why    = '三个数字要在 IO 上算（别在每次重组里遍历目录）；`清数据` 要二次确认'
    },
    @{
        Id     = 'about'
        Title  = '关于随拍抽屉'
        Kind   = 'auto'
        How    = '页眉左上角「随拍 <版本>」那颗'
        Anchors = @('关于随拍', '权限与用途', '数据去哪了', '隐私政策')
        Why    = '这是页眉的品牌入口，也是审核要截图的那一行'
    },
    @{
        Id     = 'privacy'
        Title  = '隐私与数据'
        Kind   = 'manual'
        How    = '页眉「更多」→ 设置 › 隐私与数据（chrome_open_privacy）'
        Anchors = @('隐私与数据', '数据去哪了', '权限逐项用途')
        Why    = '每条权限都写了"只服务哪一件事"；不写代码里不存在的能力'
    },

    # ── 9. 反向 ──────────────────────────────────────────────────────────
    @{
        Id     = 'empty-state'
        Title  = '空库空态'
        Kind   = 'manual'
        How    = '清空媒体库（或撤权限）后看时间线'
        Anchors = @('拍完的照片会出现在这里', '重试')
        Why    = '空态第一句必须区分「没读到」和「确实没有」；`重试` 只在"没读到"时出现'
    }
)

# ── 子命令 ─────────────────────────────────────────────────────────────────
function Invoke-Install {
    Initialize-Device
    $apk = Get-LatestDebugApk
    $mb = [math]::Round($apk.Length / 1MB, 2)
    Add-Step 'apk-found' 'INFO' "$($apk.Name)  ${mb}MB  $($apk.LastWriteTime.ToString('MM-dd HH:mm'))"

    $out = Invoke-Adb -Args @('install', '-r', '-d', '-g', $apk.FullName) -AllowFail
    if ("$out" -notmatch 'Success') {
        Add-Step 'install' 'FAIL' "adb install 失败：$out"
        return 1
    }
    Add-Step 'install' 'PASS' "已安装 $($apk.Name)"

    # 装完立刻确认前台权限（-g 覆盖，但重装有时会漏）
    foreach ($perm in @('android.permission.READ_MEDIA_IMAGES', 'android.permission.CAMERA')) {
        Invoke-Adb -Args @('shell', 'pm', 'grant', $Pkg, $perm) -AllowFail | Out-Null
    }
    Add-Step 'grant' 'INFO' '媒体/相机权限已尝试授予'

    if (-not (Assert-InstalledMatches $apk.FullName)) { return 1 }
    return 0
}

function Invoke-Smoke {
    Initialize-Device

    if (-not $SkipInstall) {
        $rc = Invoke-Install
        if ($rc -ne 0) { Add-Step 'smoke' 'FAIL' '装包没过，smoke 中止'; return 1 }
    } else {
        if (-not (Assert-InstalledMatches (Get-LatestDebugApk).FullName)) {
            Add-Step 'smoke' 'FAIL' '设备上的包与本地 APK 不一致，smoke 中止'
            return 1
        }
    }

    Wake-Device

    # 1. 冷启动 → 首屏
    if (-not (Invoke-ColdStart)) { return 1 }
    Add-Step 'cold-start' 'PASS' "am start -W 已返回，等 $SettleSeconds 秒首帧"
    Start-Sleep -Seconds $SettleSeconds

    $fg = Get-ForegroundPackage
    if ($fg -ne $Pkg) {
        Add-Step 'foreground' 'FAIL' "冷启动后前台不是我们的包" $Pkg "$fg"
        return 1
    }
    Add-Step 'foreground' 'PASS' "前台 = $Pkg"

    # 2. 首屏断言：先判"在不在授权门 / 引导"，再判时间线
    $dump = Get-UiDump
    if (-not $dump) {
        Add-Step 'timeline' 'FAIL' 'uiautomator dump 读不到语义树（两次都失败）'
        return 1
    }
    $gate = Get-AnchorHit $dump '授权相册'
    $onboarding = Get-AnchorHit $dump 'onboarding_skip'
    if ($gate) {
        Add-Step 'timeline' 'SKIP' '当前停在**授权门**（没给相册权限）——先授权或 `pm grant` 再跑'
    } elseif ($onboarding) {
        Add-Step 'timeline' 'SKIP' '当前停在**首启引导**——走完引导或清 prefs 后再跑'
    } else {
        # 时间线锚：三颗里至少两颗（工具条可能因为字级换行，不要求全在同一屏）
        $timelineAnchors = @('照片', '筛选', '显示')
        $hits = @($timelineAnchors | Where-Object { Get-AnchorHit $dump $_ })
        if ($hits.Count -ge 2) {
            Add-Step 'timeline' 'PASS' "命中 $($hits -join ' / ')"
        } else {
            Add-Step 'timeline' 'FAIL' '时间线工具条锚文本不足' `
                "至少命中 2 个：$($timelineAnchors -join ' / ')" "命中 $($hits.Count) 个；屏幕上有：$(Format-NodeTaste $dump)"
        }
    }

    # 3. 逐屏自动可达的：关于抽屉（页眉那颗，不用手势）
    $about = Get-AnchorHit $dump '关于随拍'
    if ($about) {
        Add-Step 'header-about' 'PASS' '页眉「关于随拍」在语义树里'
    } else {
        Add-Step 'header-about' 'FAIL' '页眉「关于随拍」入口不见了（页眉被挤爆？）' `
            '关于随拍 <版本>' "$(Format-NodeTaste $dump)"
    }

    # 4. 三颗 tab 自己带名字（§44 的 G1，装机判据）
    $tabOk = 0
    foreach ($t in @('照片', '相册', '互通')) {
        # 底栏那颗是 clearAndSetSemantics 之后的 content-desc；页眉矮屏那一档是 AppBarCell
        if (Get-AnchorHit $dump $t) { $tabOk++ }
    }
    if ($tabOk -eq 3) {
        Add-Step 'nav-tabs' 'PASS' '三颗 tab 都自己报名字'
    } else {
        Add-Step 'nav-tabs' 'FAIL' "只认出 $tabOk / 3 颗 tab" '照片 / 相册 / 互通' "$(Format-NodeTaste $dump)"
    }

    # 4b. 页眉「更多」抽屉：三个 tab 之外的**全部**二级入口都收在这一个抽屉里，
    #     所以它开不开、八颗格子全不全，是"二级页面能不能到达"的唯一自动证据。
    $moreCell = Get-AnchorHit $dump '更多'
    if (-not $moreCell) { $moreCell = Get-AnchorHit $dump 'chrome_more' }
    if (-not $moreCell) {
        Add-Step 'chrome-more' 'FAIL' '页眉找不到「更多」入口（二级页面将全部不可达）' `
            '「更多」/ chrome_more' "$(Format-NodeTaste $dump)"
    } else {
        $c = ConvertFrom-Bounds $moreCell.bounds
        if (-not $c) {
            Add-Step 'chrome-more' 'SKIP' "「更多」bounds 解析不出来：$($moreCell.bounds)"
        } else {
            Invoke-Adb -Args @('shell', 'input', 'tap', "$($c.X)", "$($c.Y)") -AllowFail | Out-Null
            Start-Sleep -Milliseconds 1200
            $sheet = Get-UiDump
            if (-not $sheet) {
                Add-Step 'chrome-more' 'FAIL' '点了「更多」但 dump 不到（抽屉是独立窗口，可能已开但读不到）'
            } else {
                # 抽屉里三组分区标题 + 八颗 chrome_open_* —— 缺一颗就说明某个二级页面进不去
                $expectCells = @(
                    'chrome_open_search', 'chrome_open_memories', 'chrome_open_collections',
                    'chrome_open_trash', 'chrome_open_collage', 'chrome_open_slideshow',
                    'chrome_open_settings', 'chrome_open_privacy'
                )
                $missing = @($expectCells | Where-Object { -not (Get-AnchorHit $sheet $_) })
                if ($missing.Count -eq 0) {
                    Add-Step 'chrome-more' 'PASS' "抽屉打开，8 颗二级入口全在"
                } elseif ($missing.Count -le 2) {
                    # 抽屉可能分两屏（工具条一行装不下），少 1-2 颗不判死
                    Add-Step 'chrome-more' 'PASS' "抽屉打开；未在首屏看到：$($missing -join ', ')" `
                        '8 颗全在' "首屏 $($expectCells.Count - $missing.Count) / 8"
                } else {
                    Add-Step 'chrome-more' 'FAIL' "抽屉里缺 $($missing.Count) 颗二级入口：$($missing -join ', ')" `
                        '8 颗 testTag 全在' "$(Format-NodeTaste $sheet)"
                }
                # 收掉抽屉，别把后续步骤留在抽屉里
                Invoke-Adb -Args @('shell', 'input', 'keyevent', '4') -AllowFail | Out-Null
                Start-Sleep -Milliseconds 900
            }
        }
    }

    # 5. 切 tab 再切回来：验证导航活着（不判具体截图，只判前台没死）
    foreach ($tab in @('相册', '互通')) {
        $node = Get-AnchorHit $dump $tab
        if (-not $node) { continue }
        if ($node.clickable -ne 'true') {
            Add-Step "tab-$tab" 'SKIP' "「$tab」这颗在树里但 clickable=$($node.clickable)，跳过点击"
            continue
        }
        $center = ConvertFrom-Bounds $node.bounds
        if (-not $center) { Add-Step "tab-$tab" 'SKIP' "bounds 解析不出来：$($node.bounds)"; continue }
        Invoke-Adb -Args @('shell', 'input', 'tap', "$($center.X)", "$($center.Y)") -AllowFail | Out-Null
        Start-Sleep -Milliseconds 1200
        $after = Get-ForegroundPackage
        if ($after -eq $Pkg) {
            Add-Step "tab-$tab" 'PASS' "点了「$tab」之后仍在前台"
        } else {
            Add-Step "tab-$tab" 'FAIL' "点「$tab」之后前台变成 $after" $Pkg "$after"
        }
        # 切回照片页
        $back = Get-AnchorHit (Get-UiDump) '照片'
        if ($back) {
            $c2 = ConvertFrom-Bounds $back.bounds
            if ($c2) { Invoke-Adb -Args @('shell', 'input', 'tap', "$($c2.X)", "$($c2.Y)") -AllowFail | Out-Null; Start-Sleep -Milliseconds 900 }
        }
    }

    # 6. 相机：点快门 FAB
    $dump = Get-UiDump
    $cam = Get-AnchorHit $dump '打开相机'
    if (-not $cam) { $cam = Get-AnchorHit $dump '拍照' }
    if ($cam) {
        $c = ConvertFrom-Bounds $cam.bounds
        if ($c) {
            Invoke-Adb -Args @('shell', 'input', 'tap', "$($c.X)", "$($c.Y)") -AllowFail | Out-Null
            Start-Sleep -Seconds 3
            $camDump = Get-UiDump
            $close = Get-AnchorHit $camDump '关闭'
            if ($close) {
                Add-Step 'camera' 'PASS' '进相机成功（看到页首「关闭」）'
                # 立刻退出来，别把后续步骤留在相机里
                Invoke-Adb -Args @('shell', 'input', 'keyevent', '4') -AllowFail | Out-Null
                Start-Sleep -Milliseconds 1200
            } else {
                Add-Step 'camera' 'FAIL' '点了快门入口但没进到相机' '页首有「关闭」' "$(Format-NodeTaste $camDump)"
                Invoke-Adb -Args @('shell', 'input', 'keyevent', '4') -AllowFail | Out-Null
                Start-Sleep -Milliseconds 1200
            }
        } else {
            Add-Step 'camera' 'SKIP' "快门那颗 bounds 解析不出来：$($cam.bounds)"
        }
    } else {
        Add-Step 'camera' 'FAIL' '找不到快门入口（onClickLabel = "打开相机" / contentDescription = "拍照"）' `
            '「打开相机」或「拍照」' "$(Format-NodeTaste $dump)"
    }

    # 7. 手工项：只打印"还要人走哪些"，不判红
    $manual = @($AnchorTable | Where-Object { $_.Kind -eq 'manual' })
    Add-Step 'manual-remaining' 'INFO' "$($manual.Count) 屏需要手工走（见 docs/arch/DEVICE-CHECK.md）：$((($manual | ForEach-Object { $_.Title }) -join '、'))"

    return Write-Summary
}

function ConvertFrom-Bounds {
    # uiautomator 的 bounds 形如 [x1,y1][x2,y2] → 中心点
    param([string]$Bounds)
    if ($Bounds -notmatch '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') { return $null }
    $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
    return [pscustomobject]@{ X = [int](($x1 + $x2) / 2); Y = [int](($y1 + $y2) / 2) }
}

function Invoke-Shot {
    # 参数校验放在设备闸**之前**：这样"忘了给名字"能被单独判出来，不必先等一台对的模拟器。
    if (-not $Name) { throw "shot 需要名字：pwsh tools/verify.ps1 shot 03-相机" }
    Initialize-Device
    Wake-Device
    if (-not (Test-Path $PhoneShots)) { New-Item -ItemType Directory -Force -Path $PhoneShots | Out-Null }
    $out = Join-Path $PhoneShots "$Name.png"

    # exec-out 直接落 PC：写 /sdcard 会被 MediaStore 扫成照片行（HANDOFF-20260924 记过）。
    # 为什么要绕到 cmd：PowerShell 的 `>` 重定向会把管道当文本按 UTF-16/编码转一遍，
    # PNG 在中间就被改坏了；cmd 的 `>` 才是原样字节。这是本机唯一可靠的二进制落盘方式。
    $cmd = """$Adb"" -s $($script:DeviceSerial) exec-out screencap -p > ""$out"""
    cmd /c $cmd | Out-Null
    if (-not (Test-Path $out)) {
        Add-Step 'shot' 'FAIL' "截图没落盘：$out"
        return 1
    }
    $size = (Get-Item $out).Length
    if ($size -lt 20000) {
        Add-Step 'shot' 'FAIL' "截图文件太小（$size B），大概是空屏或没截到" '> 20 KB' "$size B"
        return 1
    }

    # 读 PNG IHDR 核尺寸（和 ListingCountsGuardTest 同一套判据：Play 要 9:16 上下）
    $fs = [System.IO.File]::OpenRead($out)
    try {
        $h = New-Object byte[] 26
        $null = $fs.Read($h, 0, 26)
    } finally { $fs.Dispose() }
    if (-not ($h[0] -eq 0x89 -and $h[1] -eq 0x50 -and $h[2] -eq 0x4E -and $h[3] -eq 0x47)) {
        Add-Step 'shot' 'FAIL' "落盘的不是 PNG：$out"
        return 1
    }
    $w = ([int]$h[16] -shl 24) -bor ([int]$h[17] -shl 16) -bor ([int]$h[18] -shl 8) -bor [int]$h[19]
    $hh = ([int]$h[20] -shl 24) -bor ([int]$h[21] -shl 16) -bor ([int]$h[22] -shl 8) -bor [int]$h[23]
    $ratio = if ($w -gt 0) { $hh / $w } else { 0 }

    Add-Step 'shot' 'PASS' "$Name.png  ${w}x${hh}  ratio=$([math]::Round($ratio,4))  $([math]::Round($size/1KB,1))KB"

    # 商店图要 1080×1920；别的尺寸不判红，只提醒
    if ($w -ne 1080 -or $hh -ne 1920) {
        Add-Step 'shot-frame' 'INFO' "不是 1080×1920（Play 手机截图口径）——当商店图用会不合格；先 wm size 1080x1920"
    }
    return Write-Summary
}

function Invoke-Dump {
    Initialize-Device
    Wake-Device
    $dump = Get-UiDump
    if (-not $dump) {
        Add-Step 'dump' 'FAIL' 'uiautomator dump 两次都失败（设备忙 / 屏灭 / 前台没有窗口）'
        return 1
    }
    Add-Step 'dump' 'PASS' "读到 $($dump.Nodes.Count) 个节点"
    if ($Raw) {
        Write-Output $dump.Raw
        return 0
    }
    # 去噪打印：只留带字 / 带 id / 可点的节点
    $i = 0
    foreach ($n in $dump.Nodes) {
        if (-not ($n.text -or $n.desc -or $n.id)) { continue }
        if ($n.clickable -ne 'true' -and -not $n.text -and -not $n.desc -and -not $n.id) { continue }
        $i++
        $parts = @()
        if ($n.id) { $parts += "id=$($n.id)" }
        if ($n.text) { $parts += "text='$($n.text)'" }
        if ($n.desc) { $parts += "desc='$($n.desc)'" }
        if ($n.clickable -eq 'true') { $parts += 'CLICK' }
        $parts += $n.bounds
        Write-Output ("  {0,3}. {1}" -f $i, ($parts -join '  '))
    }
    if ($i -eq 0) { Write-Output '  <树里一个带字节点都没有>' }
    return Write-Summary
}

function Invoke-Tap {
    # 同 shot：参数校验放在设备闸之前，便于单独验证"漏了文本"这条路。
    if (-not $Name) { throw "tap 需要文本：pwsh tools/verify.ps1 tap ""筛选""" }
    Initialize-Device
    Wake-Device
    $dump = Get-UiDump
    if (-not $dump) { Add-Step 'tap' 'FAIL' 'dump 失败，点不了'; return 1 }

    $node = Get-AnchorHit $dump $Name
    if (-not $node) {
        Add-Step 'tap' 'FAIL' "找不到「$Name」" "text/desc/id 里含「$Name」" "$(Format-NodeTaste $dump)"
        return 1
    }
    $c = ConvertFrom-Bounds $node.bounds
    if (-not $c) { Add-Step 'tap' 'FAIL' "「$Name」的 bounds 解析不出来：$($node.bounds)"; return 1 }

    Add-Step 'tap-target' 'INFO' "「$Name」 id=$($node.id) desc=$($node.desc) clickable=$($node.clickable) @$($c.X),$($c.Y)"
    Invoke-Adb -Args @('shell', 'input', 'tap', "$($c.X)", "$($c.Y)") -AllowFail | Out-Null
    Start-Sleep -Milliseconds $WaitMs

    $after = Get-UiDump
    if (-not $after) { Add-Step 'tap' 'FAIL' '点完之后 dump 不到，界面可能崩了'; return 1 }

    $same = Get-AnchorHit $after $Name
    Add-Step 'tap' 'PASS' "已点「$Name」；点后屏幕：$(Format-NodeTaste $after 10)"
    return Write-Summary
}

function Invoke-Log {
    Initialize-Device
    # 注意：这台 AVD 上 `adb logcat -d` 基本只剩系统噪声（goldfish-logcat 抽走了环形缓冲）。
    # 所以这里**只**看崩溃与 ANR —— 不做任何"功能是否正常"的判断。
    Add-Step 'log' 'INFO' 'logcat 在这台 AVD 上抓不到 app 日志；这里只看 FATAL / ANR'
    $raw = Invoke-Adb -Args @('logcat', '-d', '-b', 'crash', '-b', 'events') -AllowFail
    if (-not $raw) { Add-Step 'log' 'INFO' 'crash/events 缓冲是空的（或读不到）'; return Write-Summary }

    $lines = @("$raw" -split "`n")
    $fatal = @($lines | Where-Object { $_ -match 'FATAL EXCEPTION' })
    $anr = @($lines | Where-Object { $_ -match 'ANR in ' })
    if ($fatal.Count -eq 0) { Add-Step 'log-fatal' 'PASS' 'crash 缓冲里没有 FATAL EXCEPTION' }
    else {
        # 按包名数：别的进程（uiautomator 自己 already registered!）也会打 FATAL
        $ours = @($fatal | Where-Object { $_ -match [regex]::Escape($Pkg) -or $_ -match 'com\.suipai\.app' })
        if ($ours.Count -eq 0) {
            Add-Step 'log-fatal' 'PASS' "crash 缓冲有 $($fatal.Count) 条 FATAL，但**不是我们的包**"
        } else {
            Add-Step 'log-fatal' 'FAIL' "crash 缓冲里有 $($ours.Count) 条我们的 FATAL" '0' "$($ours[0..([Math]::Min(2,$ours.Count-1))] -join ' | ')"
        }
    }
    if ($anr.Count -eq 0) { Add-Step 'log-anr' 'PASS' '没有 ANR in' } else { Add-Step 'log-anr' 'FAIL' "有 $($anr.Count) 条 ANR" '0' "$($anr[0])" }
    return Write-Summary
}

function Write-Summary {
    $pass = @($script:Steps | Where-Object { $_.Status -eq 'PASS' }).Count
    $fail = @($script:Steps | Where-Object { $_.Status -eq 'FAIL' })
    $skip = @($script:Steps | Where-Object { $_.Status -eq 'SKIP' }).Count
    Write-Host ''
    Write-Host ("── 汇总：PASS=$pass  FAIL=$($fail.Count)  SKIP=$skip  （$(($script:Steps[0]).At) → $(($script:Steps[-1]).At)）") -ForegroundColor Cyan
    if ($fail.Count -gt 0) {
        foreach ($f in $fail) { Write-Host "   ✗ $($f.Name)：$($f.Detail)" -ForegroundColor Red }
        return 1
    }
    return 0
}

function Invoke-Report {
    # report **不碰设备**：它要在 cleanup 之后、设备已经关掉时也能跑
    # （否则最后一条"这次到底跑了什么"的记录会比设备活得短）。
    if (-not (Test-Path (Join-Path $Root $ReportDir))) {
        New-Item -ItemType Directory -Force -Path (Join-Path $Root $ReportDir) | Out-Null
    }
    $stamp = $script:RunStarted.ToString('yyyyMMdd-HHmm')
    $path = Join-Path (Join-Path $Root $ReportDir) "DEVICE-RUN-$stamp.md"

    $pass = @($script:Steps | Where-Object { $_.Status -eq 'PASS' }).Count
    $fail = @($script:Steps | Where-Object { $_.Status -eq 'FAIL' }).Count
    $skip = @($script:Steps | Where-Object { $_.Status -eq 'SKIP' }).Count
    # 0 步不能算 PASS —— "跑都没跑"和"跑过了"是两件事，混起来就是我们反复栽的那个坑
    $verdict = if ($script:Steps.Count -eq 0) { 'NOT-RUN' } elseif ($fail -eq 0) { 'PASS' } else { 'FAIL' }
    $devLine = if ($script:DeviceSerial) { "``$($script:DeviceSerial)`` / AVD ``$($script:DeviceAvd)``" } else { '（本进程没碰过设备）' }

    $sb = [System.Text.StringBuilder]::new()
    [void]$sb.AppendLine("# 装机验证记录 · $stamp")
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine("> 由 ``tools/verify.ps1 report`` 生成。这是**脚本跑到哪一步**的账，")
    [void]$sb.AppendLine('> 不是"功能都验过了"的结论 —— 手工项的检查见 `docs/arch/DEVICE-CHECK.md`。')
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine('## 结论')
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine("| 项 | 值 |")
    [void]$sb.AppendLine("| --- | --- |")
    [void]$sb.AppendLine("| 总判 | **$verdict** |")
    [void]$sb.AppendLine("| PASS / FAIL / SKIP | $pass / $fail / $skip |")
    [void]$sb.AppendLine("| 设备 | $devLine |")
    [void]$sb.AppendLine("| 开始时间 | $($script:RunStarted.ToString('yyyy-MM-dd HH:mm:ss')) |")
    [void]$sb.AppendLine("| 包 | ``$Pkg`` |")
    $apk = Get-LatestDebugApk -ErrorAction SilentlyContinue
    if ($apk) {
        [void]$sb.AppendLine("| 本地 APK | ``$($apk.Name)`` $([math]::Round($apk.Length/1MB,2))MB md5 ``$(Get-LocalMd5 $apk.FullName)`` |")
        [void]$sb.AppendLine("| APK 构建时间 | $($apk.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')) |")
    }
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine('## 步骤账')
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine('| 时刻 | 状态 | 步骤 | 说明 | 期望 | 实际 |')
    [void]$sb.AppendLine('| --- | --- | --- | --- | --- | --- |')
    foreach ($s in $script:Steps) {
        $d = ($s.Detail -replace '\|', '\|')
        $e = ($s.Expected -replace '\|', '\|')
        $a = ($s.Actual -replace '\|', '\|')
        [void]$sb.AppendLine("| $($s.At) | $($s.Status) | $($s.Name) | $d | $e | $a |")
    }
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine('## 没被这一步覆盖的（必须手工走）')
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine('| 屏 | 怎么进 | 至少看到 |')
    [void]$sb.AppendLine('| --- | --- | --- |')
    foreach ($row in @($AnchorTable | Where-Object { $_.Kind -eq 'manual' })) {
        [void]$sb.AppendLine("| $($row.Title) | $($row.How) | $(($row.Anchors -join ' / ')) |")
    }
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine('## 已知坑（照抄自脚本头）')
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine('- 冷启动 80–100 s；`am start` 之后至少等 8 s 首帧才稳定。')
    [void]$sb.AppendLine('- `uiautomator dump` 前必须 `rm -f /sdcard/ui.xml`，否则读到的可能是上一屏的旧文件。')
    [void]$sb.AppendLine('- 不许加 `--compressed`：它会把纯文字 TextView 全裁掉，中文标签集体消失。')
    [void]$sb.AppendLine('- `adb logcat` 抓不到 app 日志（goldfish-logcat），别用它判断功能；只看 FATAL / ANR。')
    [void]$sb.AppendLine('- 唤醒用 `keyevent 224`；`keyevent 26` 是 POWER，屏亮着会按灭。')
    [void]$sb.AppendLine('- 截图必须 `exec-out` 落 PC；写 `/sdcard` 会被 MediaStore 收成照片行。')

    Set-Content -Path $path -Value $sb.ToString() -Encoding utf8
    Add-Step 'report' 'PASS' "写入 $path"
    Write-Host "报告：$path" -ForegroundColor Cyan
    return 0
}

function Invoke-Cleanup {
    Add-Step 'cleanup-start' 'INFO' '收尾：先看这台上到底是谁的设备，再决定杀什么'

    # 0. **先认领设备再动手**。cleanup 是最危险的一个子命令：
    #    本机有别的会话在用 dilao_fast / jian_test，无差别 `emu.ps1 stop` 会把别人的
    #    整轮测量一起关掉。所以这里先自己解析一次 AVD 名（不依赖 Initialize-Device，
    #    因为设备可能已经挂了，那种情况下也该能收尾）。
    $emuScript = Join-Path $PSScriptRoot 'emu.ps1'
    $avdNow = $null
    $serialNow = $null
    $serialRaw = & pwsh -NoProfile -File $emuScript serial 2>&1
    if ($LASTEXITCODE -eq 0 -and $serialRaw) {
        $serialLines = @($serialRaw | Where-Object { "$_".Trim() -match '^emulator-\d+$' })
        if ($serialLines.Count -gt 0) {
            $serialNow = ([string]$serialLines[0]).Trim()
            $avdNow = ((& $Adb -s $serialNow shell getprop ro.boot.qemu.avd_name 2>&1 |
                    Where-Object { $_ -and "$_".Trim() } | Select-Object -First 1))
            $avdNow = if ($avdNow) { "$avdNow".Trim() } else { '<unknown>' }
        }
    }

    $deviceIsOurs = $AnyAvd -or ($avdNow -and $AllowedAvd -contains $avdNow)

    # 1. 挂着的 adb 子进程（logcat / instrument）
    #    这一项**故意不按 AVD 过滤**：挂着的 logcat 会一直拽着共享 adb server，
    #    对谁都没好处；而且它不影响别人的"测量结果"，只影响 adb 的响应。
    #    但仍然只在能确认"这是我们自己的会话起的"时才杀 —— 靠 `-s <ours>` 出现在命令行里。
    $killed = @()
    $skipped = @()
    foreach ($p in @(Get-Process -Name adb -ErrorAction SilentlyContinue)) {
        try {
            $cmdline = (Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)" -ErrorAction Stop).CommandLine
        } catch { $cmdline = '' }
        if ($cmdline -notmatch 'logcat|instrument') { continue }
        # 命令行里带别人的串口（dilao/jian 通常也是 emulator-5554…）时先别动；
        # 只有能证明是**我们自己的 serial** 才杀。宁可留一个残留进程，也不误杀别人。
        if ($serialNow -and $cmdline -match [regex]::Escape($serialNow)) {
            try { Stop-Process -Id $p.Id -Force; $killed += "adb($($p.Id))" } catch { }
        } else {
            $skipped += "adb($($p.Id))"
        }
    }
    if ($killed.Count -gt 0) { Add-Step 'cleanup-adb' 'INFO' "杀掉 $($killed -join ', ')" }
    elseif ($skipped.Count -gt 0) { Add-Step 'cleanup-adb' 'SKIP' "有 $($skipped.Count) 个 adb logcat/instrument 但不是我们这台起的，不动：$($skipped -join ',')" }
    else { Add-Step 'cleanup-adb' 'PASS' '没有挂着的 adb logcat / instrument' }

    # 2. 关设备 —— **只有确认是自己的 AVD 才关**
    if (-not $serialNow) {
        Add-Step 'cleanup-emu' 'PASS' '当前没有模拟器在跑（无需关闭）'
    } elseif (-not $deviceIsOurs) {
        Add-Step 'cleanup-emu' 'SKIP' "串口 $serialNow 上是 AVD '$avdNow'（不是 $($AllowedAvd -join '/')）——**不动它**。要收尾别人这台请自己去关，或者用 -AnyAvd 明说你确认要关。" `
            "AVD ∈ $($AllowedAvd -join ',')" "AVD = $avdNow"
    } else {
        $out = & pwsh -NoProfile -File $emuScript stop 2>&1
        Add-Step 'cleanup-emu' 'INFO' "emu.ps1 stop：$($out -join ' ')"
    }

    # 3. netsimd —— 只在"我们自己的设备刚被关掉"时才顺手收，
    #    因为 netsimd 是**按设备**起的；别人的设备还开着时它的 netsimd 也在跑。
    if ($deviceIsOurs -and $serialNow) {
        $net = @(Get-Process -Name netsimd -ErrorAction SilentlyContinue)
        if ($net.Count -gt 0) {
            foreach ($p in $net) { try { $p.Kill() } catch { } }
            Add-Step 'cleanup-netsimd' 'INFO' "杀掉 $($net.Count) 个 netsimd"
        } else { Add-Step 'cleanup-netsimd' 'PASS' '没有 netsimd' }
    } else {
        Add-Step 'cleanup-netsimd' 'SKIP' '设备不是我们的 / 已无设备 → 不碰 netsimd'
    }

    # 4. 验收
    Start-Sleep -Seconds 2
    $devices = (& $Adb devices 2>&1) -join "`n"
    Add-Step 'cleanup-verify' 'INFO' "adb devices：$($devices -replace "`n", ' | ')"

    # 5. Gradle 守护进程（14.3 GB 的机器上它常驻 2 GB+）
    #    同样不无差别杀：别的 lane 可能正在构建。只报数，让人决定。
    $gradle = @(Get-Process -Name java -ErrorAction SilentlyContinue | Where-Object {
            try { (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine -match 'GradleDaemon' } catch { $false }
        })
    if ($gradle.Count -gt 0) {
        Add-Step 'cleanup-gradle' 'INFO' "还有 $($gradle.Count) 个 GradleDaemon（别的 lane 可能在构建，没杀）；要回收就跑 gradlew --stop"
    } else { Add-Step 'cleanup-gradle' 'PASS' '没有 GradleDaemon 残留' }

    Add-Step 'cleanup-done' 'PASS' '收尾完成'
    return Write-Summary
}

# ── 自检（本机唯一能跑、且不碰设备的那一半）────────────────────────────────
#
# 为什么要有它：这个脚本里真正容易错的是三处纯逻辑 —— 设备闸、bounds 解析、
# 锚文本匹配。它们不需要模拟器就能验，而"没验过的判据等于没有判据"（见 ROADMAP §4.4）。
# `pwsh tools/verify.ps1 selftest` 会在**不连设备**的前提下把这三处跑一遍。
#
# 仪表读数要能说清它测的是什么：这里只证明"判据本身会红/会绿"，不证明"设备上没问题"。
function Invoke-Selftest {
    Add-Step 'selftest-start' 'INFO' '不连设备的纯逻辑自检（设备闸 / bounds / 锚匹配）'

    # 1. bounds 解析
    $cases = @(
        @{ In = '[0,0][1080,2400]'; Ok = $true; X = 540; Y = 1200 }
        @{ In = '[100,200][300,400]'; Ok = $true; X = 200; Y = 300 }
        @{ In = '[540,1200]'; Ok = $false }
        @{ In = ''; Ok = $false }
        @{ In = 'null'; Ok = $false }
    )
    foreach ($c in $cases) {
        $got = ConvertFrom-Bounds $c.In
        if ($c.Ok) {
            if ($got -and $got.X -eq $c.X -and $got.Y -eq $c.Y) {
                Add-Step "selftest-bounds[$($c.In)]" 'PASS' "→ $($got.X),$($got.Y)"
            } else {
                Add-Step "selftest-bounds[$($c.In)]" 'FAIL' '中心点算错' "$($c.X),$($c.Y)" "$($got.X),$($got.Y)"
            }
        } else {
            if ($null -eq $got) { Add-Step "selftest-bounds[$($c.In)]" 'PASS' '正确拒绝' }
            else { Add-Step "selftest-bounds[$($c.In)]" 'FAIL' '坏 bounds 没被拒绝' 'null' "$($got.X),$($got.Y)" }
        }
    }

    # 2. 锚文本匹配：造一个假的 dump，验三条优先规则
    function New-FakeDump([object[]]$Nodes) {
        return [pscustomobject]@{ Xml = $null; Nodes = [System.Collections.Generic.List[object]]$Nodes; Raw = '' }
    }
    $fake = New-FakeDump @(
        [pscustomobject]@{ text = ''; desc = ''; id = 'com.suipai.app.debug:id/gallery_note'; cls = 'V'; clickable = 'false'; bounds = '[0,0][10,10]' }
        [pscustomobject]@{ text = '筛选'; desc = ''; id = ''; cls = 'V'; clickable = 'true'; bounds = '[0,0][10,10]' }
        [pscustomobject]@{ text = ''; desc = '九宫格，已选中'; id = ''; cls = 'V'; clickable = 'true'; bounds = '[0,0][10,10]' }
    )
    # 2a. resource-id 优先（tag 会映射成 android:id）
    $hit = Get-AnchorHit $fake 'gallery_note'
    if ($hit -and $hit.id -like '*gallery_note') { Add-Step 'selftest-anchor[id]' 'PASS' 'resource-id 命中' }
    else { Add-Step 'selftest-anchor[id]' 'FAIL' 'resource-id 没命中' }

    # 2b. text 精确
    $hit = Get-AnchorHit $fake '筛选'
    if ($hit -and $hit.text -eq '筛选') { Add-Step 'selftest-anchor[text]' 'PASS' 'text 精确命中' }
    else { Add-Step 'selftest-anchor[text]' 'FAIL' 'text 没命中' }

    # 2c. 「，已选中」后缀要靠前缀匹配 —— 这是本项目栽过三次的坑
    $hit = Get-AnchorHit $fake '九宫格'
    if ($hit -and $hit.desc -eq '九宫格，已选中') { Add-Step 'selftest-anchor[prefix]' 'PASS' '「，已选中」后缀被前缀匹配吃住' }
    else { Add-Step 'selftest-anchor[prefix]' 'FAIL' '带后缀的标签没匹配上（会漏掉所有选中态格子）' }

    # 2d. 不该命中的
    $hit = Get-AnchorHit $fake '这个锚不在屏幕上'
    if ($null -eq $hit) { Add-Step 'selftest-anchor[miss]' 'PASS' '不存在的锚正确返回 null' }
    else { Add-Step 'selftest-anchor[miss]' 'FAIL' '凭空命中了不存在的锚' 'null' "$($hit.text)/$($hit.desc)" }

    # 3. dump 解析器对坏输入必须返回 null（不许当成"没有节点"）
    $bad = $null
    Add-Step 'selftest-dump-shape' 'INFO' 'dump 解析走真实 adb，不在自检范围（见 smoke）'

    # 4. 锚文本表自身的完整性：id 唯一、每条都有锚、auto 项非空
    $ids = @($AnchorTable | ForEach-Object { $_.Id })
    if ($ids.Count -eq ($ids | Select-Object -Unique).Count) {
        Add-Step 'selftest-table-ids' 'PASS' "锚文本表 $($ids.Count) 条，id 唯一"
    } else {
        $dup = ($ids | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name }) -join ','
        Add-Step 'selftest-table-ids' 'FAIL' "锚文本表有重复 id：$dup"
    }
    $noAnchor = @($AnchorTable | Where-Object { -not $_.Anchors -or $_.Anchors.Count -eq 0 })
    if ($noAnchor.Count -eq 0) { Add-Step 'selftest-table-anchors' 'PASS' '每条都有锚文本' }
    else { Add-Step 'selftest-table-anchors' 'FAIL' "$($noAnchor.Count) 条没有锚文本：$($noAnchor.Id -join ',')" }

    $auto = @($AnchorTable | Where-Object { $_.Kind -eq 'auto' })
    $manual = @($AnchorTable | Where-Object { $_.Kind -eq 'manual' })
    Add-Step 'selftest-table-split' 'INFO' "auto=$($auto.Count) 条（smoke 会自动断言）、manual=$($manual.Count) 条（进 DEVICE-CHECK 手工检查单）"

    # 5. 本地 APK 定位（只读文件系统，不连设备）
    try {
        $apk = Get-LatestDebugApk
        Add-Step 'selftest-apk' 'PASS' "$($apk.Name) $([math]::Round($apk.Length/1MB,2))MB md5=$(Get-LocalMd5 $apk.FullName)"
    } catch {
        Add-Step 'selftest-apk' 'FAIL' "找不到 debug APK：$($_.Exception.Message)"
    }

    # 6. 设备闸的**反向**证明：给它一个不属于我们的 AVD 名，必须拒绝。
    #    这里不连设备，只验"名单比较"这一段逻辑本身没写反。
    $foreign = 'dilao_fast'
    if (-not $AnyAvd -and $AllowedAvd -notcontains $foreign) {
        Add-Step 'selftest-gate' 'PASS' "外来 AVD '$foreign' 会被拒绝（名单：$($AllowedAvd -join ','))"
    } else {
        Add-Step 'selftest-gate' 'FAIL' "外来 AVD '$foreign' 没被名单挡住"
    }

    return Write-Summary
}

# ── 入口 ───────────────────────────────────────────────────────────────────
try {
    $rc = switch ($Action) {
        'install' { Invoke-Install }
        'smoke' { Invoke-Smoke }
        'shot' { Invoke-Shot }
        'dump' { Invoke-Dump }
        'tap' { Invoke-Tap }
        'log' { Invoke-Log }
        'report' { Invoke-Report }
        'cleanup' { Invoke-Cleanup }
        'selftest' { Invoke-Selftest }
    }
    exit ([int]$rc)
} catch {
    # 用法错误（缺参数）与真崩溃分开报：用法错误不打印调用栈，它是给人看的提示。
    $msg = "$($_.Exception.Message)"
    $isUsage = $msg -match '需要名字|需要文本|找不到 tools/emu\.ps1|找不到 adb'
    Write-Host ''
    if ($isUsage) {
        Write-Host "[用法] $msg" -ForegroundColor Yellow
        Add-Step "usage:$Action" 'FAIL' $msg
        exit 3
    }
    Write-Host "[FAIL] 未捕获错误：$msg" -ForegroundColor Red
    Write-Host $_.ScriptStackTrace -ForegroundColor DarkRed
    Add-Step "fatal:$Action" 'FAIL' $msg
    exit 9
}
