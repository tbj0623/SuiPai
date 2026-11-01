# 截图归一化：1080×2400 → 1080×1920
#
# 为什么要这一步：本机 AVD 是 1080×2400（20:9），而 Play 商店的手机截图硬要求
# 1080×1920（16:9）—— `ListingCountsGuardTest.phoneScreenshotsFitPlaysFrame` 会用
# `store/screens/phone/` 里每张 PNG 的 IHDR 逐个核对，比例或尺寸不对就直接红。
# 直接把 2400 高的图塞进去，上传那一步才会发现，代价是一次重拍。
#
# 做法是**居中裁到 16:9 再缩**，不是拉伸：拉伸会让等宽数字与 2dp 直角发虚。
# 裁掉的是顶部状态栏与底部导航条那 480 px —— 那两段本来也不是产品界面。
#
# 用法：
#   pwsh tools/shots.ps1 -In store/screens/phone2 -Out store/screens/phone
#   pwsh tools/shots.ps1 -In store/screens/phone2 -Out store/screens/phone -WhatIf
param(
    [Parameter(Mandatory = $true)][string]$In,
    [Parameter(Mandatory = $true)][string]$Out,
    [int]$Width = 1080,
    [int]$Height = 1920,
    [string]$Prefix = '',
    [switch]$WhatIf
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$srcDir = Resolve-Path $In
if (-not (Test-Path $Out)) { New-Item -ItemType Directory -Force -Path $Out | Out-Null }
$outDir = (Resolve-Path $Out).Path

$files = Get-ChildItem $srcDir -Filter *.png | Where-Object { $_.BaseName -match '^\d' } | Sort-Object Name
if ($files.Count -eq 0) {
    Write-Error "在 $srcDir 里没找到以数字开头的 PNG（截图命名要像 01-timeline.png）"
    exit 1
}

$targetAspect = $Width / $Height
$done = 0
foreach ($f in $files) {
    $name = if ($Prefix) { "$Prefix$($f.Name)" } else { $f.Name }
    $dest = Join-Path $outDir $name
    $img = [System.Drawing.Image]::FromFile($f.FullName)
    try {
        $w = $img.Width
        $h = $img.Height
        $aspect = $w / $h
        # 居中裁到目标比例：先定能保留的最大矩形，再等比缩到目标尺寸。
        if ($aspect -gt $targetAspect) {
            $cropH = $h
            $cropW = [int][math]::Round($h * $targetAspect)
        } else {
            $cropW = $w
            $cropH = [int][math]::Round($w / $targetAspect)
        }
        $cropX = [int](($w - $cropW) / 2)
        $cropY = [int](($h - $cropH) / 2)

        Write-Output ("{0}: {1}x{2} → 裁 ({3},{4}) {5}x{6} → {7}x{8}" -f `
                $f.Name, $w, $h, $cropX, $cropY, $cropW, $cropH, $Width, $Height)
        if ($WhatIf) { $done++; continue }

        $bmp = New-Object System.Drawing.Bitmap($Width, $Height)
        try {
            $g = [System.Drawing.Graphics]::FromImage($bmp)
            try {
                # HighQualityBicubic 而不是默认的双线性：缩小时等宽字会糊成一团。
                $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
                $g.DrawImage(
                    $img,
                    (New-Object System.Drawing.Rectangle(0, 0, $Width, $Height)),
                    (New-Object System.Drawing.Rectangle($cropX, $cropY, $cropW, $cropH)),
                    [System.Drawing.GraphicsUnit]::Pixel
                )
            } finally { $g.Dispose() }
            $bmp.Save($dest, [System.Drawing.Imaging.ImageFormat]::Png)
        } finally { $bmp.Dispose() }
    } finally { $img.Dispose() }
    $done++
}

Write-Output "写出 $done 张到 $outDir"
# 自检：写完必须逐张回读尺寸，别信"应该是 1080×1920"。
if (-not $WhatIf) {
    $bad = @()
    foreach ($p in (Get-ChildItem $outDir -Filter *.png)) {
        $b = [System.IO.File]::ReadAllBytes($p.FullName)
        $pw = ([int]$b[16] -shl 24) -bor ([int]$b[17] -shl 16) -bor ([int]$b[18] -shl 8) -bor [int]$b[19]
        $ph = ([int]$b[20] -shl 24) -bor ([int]$b[21] -shl 16) -bor ([int]$b[22] -shl 8) -bor [int]$b[23]
        if ($pw -ne $Width -or $ph -ne $Height) { $bad += "$($p.Name)=${pw}x${ph}" }
    }
    if ($bad.Count -gt 0) {
        Write-Error ("这些不是 {0}x{1}：" -f $Width, $Height) + ($bad -join ', ')
        exit 1
    }
    Write-Output "尺寸自检通过：全部 ${Width}x${Height}"
}
