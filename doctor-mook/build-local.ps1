param(
    [string]$BuildDirectory,
    [string]$SignedApk
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
if (!$BuildDirectory) { $BuildDirectory = Join-Path $workspace '.cache/MookDoctorC-build-1.0.24+33' }
$BuildDirectory = [IO.Path]::GetFullPath($BuildDirectory)
if ($SignedApk) { $SignedApk = [IO.Path]::GetFullPath($SignedApk) }
& (Join-Path $workspace 'tool/启用.ps1')

Push-Location -LiteralPath $PSScriptRoot
try {
    & .\gradlew.bat :app:assembleRelease --project-cache-dir "$BuildDirectory/gradle" "-PbookBuildRoot=$BuildDirectory/app" --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
} finally {
    Pop-Location
}
$unsigned = Join-Path $BuildDirectory 'app/outputs/apk/release/app-release-unsigned.apk'
if (!$SignedApk) {
    Write-Output $unsigned
    return
}

$keyRoot = Join-Path $workspace '密钥'
$secret = Get-Content -LiteralPath "$keyRoot/密钥.md" -Encoding UTF8 |
    Where-Object { $_ -match '^\s*-\s*ANDROID_KEYSTORE_PASSWORD\s*[:：]' } | Select-Object -First 1
if (!$secret) { throw 'Signing passphrase entry missing' }
$signer = Join-Path $env:ANDROID_HOME 'build-tools/37.0.0/apksigner.bat'
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($SignedApk)) | Out-Null
$env:BOOKDOCTORC_P12_PASSWORD = ($secret -replace '^\s*-\s*ANDROID_KEYSTORE_PASSWORD\s*[:：]\s*','').Trim().Trim([char]96)
try {
    & $signer sign --ks "$keyRoot/release.p12" --ks-type PKCS12 --ks-key-alias bookdoctorc --ks-pass env:BOOKDOCTORC_P12_PASSWORD --key-pass env:BOOKDOCTORC_P12_PASSWORD --out $SignedApk $unsigned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
    & $signer verify --verbose --print-certs $SignedApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
    Write-Output $SignedApk
} finally {
    Remove-Item Env:BOOKDOCTORC_P12_PASSWORD -ErrorAction SilentlyContinue
}
