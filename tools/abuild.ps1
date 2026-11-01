# 随拍 · 串行构建闸（多智能体共用一个工作区时唯一允许的 gradle 入口）
#
# 为什么需要它：本机 14.3 GB 内存、一个 Gradle 守护进程常驻约 2 GB；多个 agent 同时
# 在同一工程上跑 gradle，会互相抢 ./build 的输出锁（Gradle 自己会等，但等待期间各起
# 一个 daemon / wrapper JVM，内存直接翻倍），而且失败信息会串到别人的日志里。
# 所以：所有 agent 一律经由本脚本构建，它用文件锁把构建串成一队。
#
# 用法（在工程根目录或任意目录）：
#   pwsh tools/abuild.ps1 :app:compileDebugKotlin
#   pwsh tools/abuild.ps1 :app:testDebugUnitTest --tests "com.suipai.app.data.*"
#
# 约定：
#   * 锁文件 C:\tmp\suipai-build.lock；持锁者写自己的 pid 与开始时间。
#   * 锁超过 45 分钟视为僵尸锁（持有者已经死了），自动清掉。
#   * 退出码 = gradle 的退出码；stdout/stderr 同时落到 C:\tmp\build-<pid>.log，
#     末尾 120 行回显，方便直接看失败原因。
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$Lock = 'C:\tmp\suipai-build.lock'
$LogDir = 'C:\tmp'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

function New-LogPath {
    Join-Path $LogDir ("build-{0}-{1}.log" -f $PID, (Get-Date -Format 'HHmmss'))
}

$myLog = New-LogPath
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$fs = $null
$waitNoted = $false
try {
    while ($true) {
        try {
            $fs = [System.IO.File]::Open($Lock, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
            $info = "pid=$PID started=$(Get-Date -Format o)"
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($info)
            $fs.Write($bytes, 0, $bytes.Length)
            $fs.Flush($true)
            break
        } catch [System.IO.IOException] {
            $stale = $false
            try {
                $age = (Get-Date) - (Get-Item $Lock -ErrorAction Stop).LastWriteTime
                if ($age.TotalMinutes -gt 45) { $stale = $true }
            } catch { }
            if ($stale) {
                Write-Host "[abuild] 清掉僵尸锁（45 分钟未更新）" -ForegroundColor Yellow
                Remove-Item $Lock -Force -ErrorAction SilentlyContinue
                continue
            }
            if (-not $waitNoted) {
                Write-Host "[abuild] 另一条 lane 正在构建，排队等待…（锁：$Lock）" -ForegroundColor DarkYellow
                $waitNoted = $true
            }
            Start-Sleep -Seconds 4
        }
    }

    $env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
    if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
        $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    }

    Push-Location $Root
    try {
        # 别加 `--no-daemon-hint`：Gradle 8.11.1 没有这个选项（踩过，整条闸一次任务都跑不到）。
        $all = @('--console=plain') + $GradleArgs
        # --no-daemon 会让每次构建 40s+ 起步；这里保留守护进程（由主控在收尾时 --stop）。
        & .\gradlew.bat @all *>&1 | Tee-Object -FilePath $myLog | Out-Host
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    $sw.Stop()
    if ($code -eq 0) {
        Write-Host ("[abuild] OK  {0:N0}s  log={1}" -f $sw.Elapsed.TotalSeconds, $myLog) -ForegroundColor Green
    } else {
        Write-Host ("[abuild] FAIL(exit={0})  {1:N0}s  log={2}" -f $code, $sw.Elapsed.TotalSeconds, $myLog) -ForegroundColor Red
        Write-Host "[abuild] 末尾 120 行：" -ForegroundColor Red
        Get-Content $myLog -Tail 120 | Out-Host
    }
    exit $code
} finally {
    if ($fs) {
        try { $fs.Dispose() } catch { }
        Remove-Item $Lock -Force -ErrorAction SilentlyContinue
    }
}
