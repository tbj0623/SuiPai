# 随拍 · 模拟器串行闸
#
# 本机 14.3 GB 内存，一台无头模拟器常驻 4.5–5.5 GB。多智能体并行时最容易把机器压死的就是
# 「两条 lane 各起一台 emulator」。所以设备侧工作一律经由本脚本：
#   * 用文件锁 C:\tmp\suipai-emu.lock 串行化「起设备」这一段；
#   * 起之前先看有没有已在跑的 emulator：有就复用并打印它的 serial（不重启，省 60-90 秒）；
#   * 等待 sys.boot_completed=1，并把 sys.boot_completed 之后还要暖的几件事一起做完
#     （放行装包、屏幕常亮、动画、导航模式）。
#
# 用法：
#   pwsh tools/emu.ps1 start [avd]     # 起/复用，stdout 最后一行是 serial
#   pwsh tools/emu.ps1 serial          # 只打印当前 serial（没有则 exit 1）
#   pwsh tools/emu.ps1 stop            # 关掉串口对应的那台（精确 -s，绝不 adb kill-server）
#   pwsh tools/emu.ps1 status
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('start', 'stop', 'serial', 'status')]
    [string]$Action,
    [Parameter(Position = 1)]
    [string]$Avd = 'suipai_gapi'
)
$ErrorActionPreference = 'Stop'

$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$Adb = Join-Path $Sdk 'platform-tools\adb.exe'
$Emu = Join-Path $Sdk 'emulator\emulator.exe'
$Lock = 'C:\tmp\suipai-emu.lock'
New-Item -ItemType Directory -Force -Path 'C:\tmp' | Out-Null

function Get-AvdName([string]$serial) {
    try {
        return ((& $Adb -s $serial shell getprop ro.boot.qemu.avd_name 2>$null) -join '').Trim()
    } catch { return '' }
}

# 只认**我们自己那几台** AVD 的串口。这台机器上还有别的会话（jian 的 jian_test、
# 上一轮验证留下的 dilao_fast / dilao_qa），`adb devices` 会把它们一起列出来。
# 第一版直接取第一条 `emulator-\d+ device`，于是 `start` 会"复用"别人的设备、
# `stop` 会把别人的设备关掉 —— 实测踩到：`status` 报出 `serial=emulator-5554 avd=dilao_qa`。
# 所以每个会读写设备的子命令都必须先过这道闸。
$OwnAvds = @('suipai_gapi', 'suipai_r4')

function Get-EmuSerial {
    try {
        # 注意 `@(...)`：PowerShell 会把单元素数组拆成标量，`$lines[0]` 于是成了第一个**字符**
        # （踩过：serial 变成了 "e"，随后 wait-for-boot 一直等一台叫 e 的设备）。
        $lines = @(& $Adb devices 2>$null | Where-Object { $_ -match '^emulator-\d+\s+device' })
        foreach ($line in $lines) {
            $s = ([string]$line).Split("`t")[0].Trim()
            if ((Get-AvdName $s) -in $OwnAvds) { return $s }
        }
    } catch { }
    return $null
}

function Wait-Boot([string]$serial) {
    $deadline = (Get-Date).AddSeconds(300)
    while ((Get-Date) -lt $deadline) {
        $booted = (& $Adb -s $serial shell getprop sys.boot_completed 2>$null) -join ''
        if ($booted.Trim() -eq '1') { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

switch ($Action) {
    'status' {
        $s = Get-EmuSerial
        if ($s) {
            $avd = ((& $Adb -s $s shell getprop ro.boot.qemu.avd_name 2>$null) -join '').Trim()
            Write-Output "serial=$s avd=$avd"
            exit 0
        }
        Write-Output 'no emulator'
        exit 1
    }
    'serial' {
        $s = Get-EmuSerial
        if (-not $s) { Write-Error '没有在跑的模拟器'; exit 1 }
        Write-Output $s
        exit 0
    }
    'stop' {
        $s = Get-EmuSerial
        if (-not $s) { Write-Output '没有在跑的模拟器（无需关闭）'; exit 0 }
        $avd = Get-AvdName $s
        Write-Output "关闭 $s（avd=$avd）"
        & $Adb -s $s emu kill 2>&1 | Out-Null
        $deadline = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 2
            $still = & $Adb devices 2>$null | Where-Object { $_ -match "^$s\s" }
            if (-not $still) { break }
        }
        # 只杀**这台 AVD 对应**的 qemu，绝不按进程名一把梭：
        # 这台机器上同时可能有 jian_test / dilao_fast / dilao_qa 三台别人的设备，
        # 按名字 kill 会把它们一起打掉（第一版就是这么写的）。
        # 判据是命令行里带着 `-avd <本次的那台>`。
        Get-CimInstance Win32_Process -Filter "Name='qemu-system-x86_64-headless.exe' or Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -and $_.CommandLine -match "-avd\s+$([regex]::Escape($avd))\b" } |
            ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop } catch { } }
        Write-Output '已关闭'
        exit 0
    }
    'start' {
        $existing = Get-EmuSerial
        if ($existing) {
            if (Wait-Boot $existing) {
                Write-Output "[emu] 复用已有设备 $existing"
                Write-Output $existing
                exit 0
            }
            Write-Error "已有设备 $existing 但 300 秒内没起来"
            exit 1
        }

        $fs = $null
        while ($true) {
            try {
                $fs = [System.IO.File]::Open($Lock, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
                break
            } catch [System.IO.IOException] {
                try {
                    $age = (Get-Date) - (Get-Item $Lock -ErrorAction Stop).LastWriteTime
                    if ($age.TotalMinutes -gt 20) { Remove-Item $Lock -Force -ErrorAction SilentlyContinue; continue }
                } catch { }
                Write-Host '[emu] 另一条 lane 正在起设备，等待…'
                Start-Sleep -Seconds 3
            }
        }
        try {
            $again = Get-EmuSerial
            if ($again) {
                if (Wait-Boot $again) { Write-Output "[emu] 复用 $again"; Write-Output $again; exit 0 }
            }
            Write-Host "[emu] 启动 $Avd（冷启动约 80–100 秒）"
            Start-Process -FilePath $Emu -ArgumentList @(
                '-avd', $Avd, '-no-window', '-no-audio', '-no-boot-anim',
                '-no-snapshot-save', '-gpu', 'swiftshader_indirect', '-camera-back', 'virtualscene',
                '-camera-front', 'emulated', '-no-metrics'
            ) -WindowStyle Hidden | Out-Null
            $serial = $null
            $deadline = (Get-Date).AddSeconds(360)
            while ((Get-Date) -lt $deadline -and -not $serial) {
                Start-Sleep -Seconds 3
                $serial = Get-EmuSerial
            }
            if (-not $serial) { Write-Error '360 秒内没等到模拟器上线'; exit 1 }
            if (-not (Wait-Boot $serial)) { Write-Error "设备 $serial 上线但没开机完成"; exit 1 }

            # 开机后还要暖的几件（都是运行期设置，不写死到镜像里）
            & $Adb -s $serial shell input keyevent 224 2>&1 | Out-Null
            & $Adb -s $serial shell svc power stayon true 2>&1 | Out-Null
            & $Adb -s $serial shell settings put system screen_off_timeout 2147483647 2>&1 | Out-Null
            & $Adb -s $serial shell settings put global window_animation_scale 1 2>&1 | Out-Null
            & $Adb -s $serial shell settings put global transition_animation_scale 1 2>&1 | Out-Null
            & $Adb -s $serial shell settings put global animator_duration_scale 1 2>&1 | Out-Null
            & $Adb -s $serial shell dumpsys battery set ac 1 2>&1 | Out-Null
            Write-Output "[emu] 就绪 $serial"
            Write-Output $serial
        } finally {
            if ($fs) { try { $fs.Dispose() } catch { }; Remove-Item $Lock -Force -ErrorAction SilentlyContinue }
        }
        exit 0
    }
}
