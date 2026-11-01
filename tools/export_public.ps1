param(
    [Parameter(Mandatory = $true)]
    [string]$Destination
)

$ErrorActionPreference = "Stop"
$sourceRoot = Split-Path -Parent $PSScriptRoot
if (Test-Path -LiteralPath $Destination) {
    throw "目标路径已存在，拒绝覆盖：$Destination"
}
New-Item -ItemType Directory -Path $Destination | Out-Null

function Copy-PublicPath([string]$relative) {
    $from = Join-Path $sourceRoot $relative
    if (-not (Test-Path -LiteralPath $from)) { throw "缺文件：$relative" }
    $to = Join-Path $Destination $relative
    $parent = Split-Path -Parent $to
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    Copy-Item -LiteralPath $from -Destination $to -Recurse -Force
}

# 只允许显式列出的源码/发布资料进入公开快照；绝不复制 .git 历史、
# 本地签名配置、.qoder 交接、build 输出、个人验证截图或缓存。
$files = @(
    ".gitignore", "LICENSE", "CONTRIBUTING.md", "SECURITY.md",
    "README.md", "CHANGELOG.md", "GOAL.md", "PRODUCT.md", "SYNC.md",
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    "gradlew", "gradlew.bat",
    "app/build.gradle.kts", "app/proguard-rules.pro",
    "shared/build.gradle.kts",
    "ios/BUILD.md", "ios/FEATURE-GAP.md", "ios/project.yml", "ios/VERIFY-ON-MAC.md",
    "docs/ROADMAP.md", "docs/feature-matrix.md", "docs/press-kit.md", "docs/QA_STATUS.md",
    "docs/arch/APP-ARCH.md",
    "store/feature-graphic-1024x500.png", "store/icon-512.png",
    "store/release-notes-zh.md", "store/privacy-policy-zh.md", "store/privacy-policy.html"
)
$dirs = @(
    ".github/workflows", "app/src", "shared/src", "gradle/wrapper",
    "ios/SuiPaiIOS", "docs/design", "docs/engine", "docs/product",
    "store/screens/phone"
)
foreach ($item in $files) { Copy-PublicPath $item }
foreach ($item in $dirs) { Copy-PublicPath $item }
# 商店长稿含内部路径；首次公开时用精简草稿。公开仓库再次导出时就复用它。
$publicListing = Join-Path $sourceRoot "store/listing-public.md"
if (Test-Path -LiteralPath $publicListing) {
    Copy-Item -LiteralPath $publicListing -Destination (Join-Path $Destination "store/listing-zh.md")
} else {
    Copy-PublicPath "store/listing-zh.md"
}
Get-ChildItem -LiteralPath (Join-Path $sourceRoot "store/screens/phone2") -File |
    Where-Object { $_.Extension -eq ".png" } |
    ForEach-Object { Copy-PublicPath ("store/screens/phone2/" + $_.Name) }
Get-ChildItem -LiteralPath (Join-Path $sourceRoot "tools") -File |
    Where-Object { $_.Extension -in @(".ps1", ".py", ".sh") } |
    ForEach-Object { Copy-PublicPath ("tools/" + $_.Name) }
Write-Host "Public snapshot ready: $Destination"
