param(
    [string]$DotnetPath = 'dotnet',
    [string]$InnoSetup = 'ISCC.exe',
    [string]$BuildDirectory = (Join-Path $PSScriptRoot '../build'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../artifacts')
)

$ErrorActionPreference = 'Stop'
$project = Join-Path $PSScriptRoot '../desktop/ClmStudio.csproj'
$version = ([xml](Get-Content -Raw -LiteralPath $project)).Project.PropertyGroup.Version
$BuildDirectory = [IO.Path]::GetFullPath($BuildDirectory)
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$publish = Join-Path $BuildDirectory 'publish'
& $DotnetPath publish $project -c Release -r win-x64 --self-contained true -o $publish
if ($LASTEXITCODE -ne 0) { throw 'CLM Studio 发布失败' }
$runtime = Get-Content -Raw -LiteralPath (Join-Path $publish 'CLMStudio.deps.json') | ConvertFrom-Json
$assets = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '../desktop/obj/project.assets.json') | ConvertFrom-Json
$packageRoots = $assets.packageFolders.PSObject.Properties.Name
$noticeDirectory = Join-Path $publish 'licenses'
New-Item -ItemType Directory -Force -Path $noticeDirectory | Out-Null
foreach ($library in $runtime.libraries.PSObject.Properties.Name) {
    if ($library -notmatch '^runtimepack\.(microsoft\.(?:netcore|windowsdesktop)\.app\.runtime\.win-x64)/(.+)$') { continue }
    $packageName = $Matches[1].ToLowerInvariant()
    $relativePath = "$packageName/$($Matches[2])"
    $package = $packageRoots | ForEach-Object { Join-Path $_ $relativePath } |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (!$package) { throw "缺少运行时包的许可材料：$relativePath" }
    $notices = Get-ChildItem -LiteralPath $package -File |
        Where-Object { $_.Name -match '^(license(\.txt)?|third-party-notices\.txt)$' }
    if (!$notices) { throw "缺少运行时许可：$relativePath" }
    foreach ($notice in $notices) {
        $destination = Join-Path $noticeDirectory "$packageName-$($notice.Name)"
        Copy-Item -LiteralPath $notice.FullName -Destination $destination -Force
    }
}
& $InnoSetup (Join-Path $PSScriptRoot 'CLMStudio.iss') "/DAppVersion=$version" "/DPublishDir=$publish" "/DOutputDir=$OutputDirectory"
if ($LASTEXITCODE -ne 0) { throw 'CLM Studio 安装包编译失败' }
Get-FileHash -LiteralPath (Join-Path $OutputDirectory "CLM-Studio-Setup-$version.exe") -Algorithm SHA256
