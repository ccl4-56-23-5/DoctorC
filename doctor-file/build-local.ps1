param(
    [string]$BuildDirectory = (Join-Path $env:TEMP 'FileDoctorC-build-1.1.7+32'),
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$GradleCache = $env:GRADLE_USER_HOME,
    [switch]$Test
)
$ErrorActionPreference = 'Stop'
if (!$Jdk -or !$Sdk) { throw 'Specify a JDK 21 and Android SDK with Platform 37 and Build Tools 37.0.0.' }
$sourceDirectory = $PSScriptRoot
if ([IO.Path]::GetFullPath($BuildDirectory).TrimEnd('\') -eq [IO.Path]::GetFullPath($sourceDirectory).TrimEnd('\')) {
    throw 'BuildDirectory must be separate from the archived source.'
}
New-Item -ItemType Directory -Path $BuildDirectory -Force | Out-Null
& robocopy $sourceDirectory $BuildDirectory /E /XD .git .gradle build third_party qa art history /XF '*-source.zip' /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -gt 7) { throw 'Source copy failed' }
$env:JAVA_HOME = $Jdk
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
if ($GradleCache) { $env:GRADLE_USER_HOME = $GradleCache }
Push-Location -LiteralPath $BuildDirectory
try {
    $buildTasks = @(':app:assembleFossRelease', ':app:lintFossRelease')
    if ($Test) { $buildTasks += @(':app:testFossDebugUnitTest', ':app:assembleFossDebug', ':app:assembleFossDebugAndroidTest') }
    & '.\gradlew.bat' @buildTasks --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
} finally { Pop-Location }
