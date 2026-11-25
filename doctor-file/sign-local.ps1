param(
    [Parameter(Mandatory)][string]$UnsignedApk,
    [Parameter(Mandatory)][string]$OutputApk,
    [string]$Sdk = $env:ANDROID_HOME,
    [Parameter(Mandatory)][string]$KeyStore,
    [string]$PasswordFile
)
$ErrorActionPreference = 'Stop'
foreach ($item in @($UnsignedApk,$KeyStore)) {
    if (!(Test-Path -LiteralPath $item -PathType Leaf)) { throw 'A required APK or private signing file is missing.' }
}
if ($PasswordFile) {
    if (!(Test-Path -LiteralPath $PasswordFile -PathType Leaf)) { throw 'Password file is missing.' }
    $pass = 'file:' + $PasswordFile
} elseif ($env:ZIPDOCTORC_KEYSTORE_PASSWORD) {
    $pass = 'env:ZIPDOCTORC_KEYSTORE_PASSWORD'
} else {
    throw 'Specify a password file or set ZIPDOCTORC_KEYSTORE_PASSWORD for this process.'
}
New-Item -ItemType Directory -Path (Split-Path $OutputApk) -Force | Out-Null
$signer = Join-Path $Sdk 'build-tools\37.0.0\apksigner.bat'
& $signer sign --ks $KeyStore --ks-key-alias sevenzip-android --ks-pass $pass --v4-signing-enabled false --out $OutputApk $UnsignedApk
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
& $signer verify --verbose --print-certs $OutputApk
if ($LASTEXITCODE -ne 0) { throw 'APK signature validation failed.' }
& (Join-Path $Sdk 'build-tools\37.0.0\zipalign.exe') -c -P 16 4 $OutputApk
if ($LASTEXITCODE -ne 0) { throw 'APK alignment validation failed.' }
Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256
