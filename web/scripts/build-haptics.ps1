<# Build a release-like, side-by-side test APK using the local debug key.
   Does not need the production signing identity or replace its download.
   ./scripts/build-haptics.ps1 [-Server https://your.domain]
#>
param([string]$Server = "")
$ErrorActionPreference = "Stop"
$web = (Resolve-Path "$PSScriptRoot/..").Path
$root = (Resolve-Path "$web/..").Path
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = [Environment]::GetEnvironmentVariable("JAVA_HOME", "User") }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = [Environment]::GetEnvironmentVariable("ANDROID_HOME", "User") }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk" }
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME/bin/java.exe")) { throw "Install JDK 21 and set JAVA_HOME first" }
if (-not (Test-Path "$env:ANDROID_HOME/platforms/android-36/android.jar")) { throw "Install Android SDK platform 36 first" }
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
if ($Server -and $Server -notmatch '^https://') { throw "-Server must start with https://" }

$previousTarget = $env:STU_TARGET
$previousBase = $env:VITE_API_BASE
Push-Location $web
try {
    & npm.cmd run typecheck
    if ($LASTEXITCODE -ne 0) { throw "typecheck failed" }
    $env:STU_TARGET = "android"
    $env:VITE_API_BASE = $Server
    & npx.cmd vite build --outDir dist-android --emptyOutDir
    if ($LASTEXITCODE -ne 0) { throw "vite build failed" }
    & npx.cmd cap sync android
    if ($LASTEXITCODE -ne 0) { throw "cap sync failed" }
    Set-Content -Path "android/local.properties" -Value ("sdk.dir=" + $env:ANDROID_HOME.Replace('\', '/')) -Encoding ascii
} finally {
    $env:STU_TARGET = $previousTarget
    $env:VITE_API_BASE = $previousBase
    Pop-Location
}

# ASCII path for Android tools; only map a currently unoccupied drive.
$letter = [char[]](70..90) | Where-Object { -not (Test-Path "$($_):\") } | Select-Object -First 1
if (-not $letter) { throw "no free drive letter for subst" }
subst "$($letter):" "$root"
if ($LASTEXITCODE -ne 0) { throw "subst failed" }
try {
    Push-Location "$($letter):\web\android"
    try {
        & .\gradlew.bat --no-daemon --console=plain --max-workers=2 assembleHaptics testHapticsUnitTest
        if ($LASTEXITCODE -ne 0) { throw "gradle failed ($LASTEXITCODE)" }
    } finally { Pop-Location }
} finally { subst "$($letter):" /d | Out-Null }

$apk = "$web/android/app/build/outputs/apk/haptics/app-haptics.apk"
if (-not (Test-Path $apk)) { throw "APK not found: $apk" }
$version = (Select-String -Path "$web/android/app/build.gradle" -Pattern 'versionName "([^"]+)"').Matches[0].Groups[1].Value
$suffix = (Select-String -Path "$web/android/app/build.gradle" -Pattern "versionNameSuffix '([^']+)'").Matches[0].Groups[1].Value
New-Item -ItemType Directory -Force "$root/release" | Out-Null
$output = "$root/release/STU-assistant-$version$suffix.apk"
Copy-Item -LiteralPath $apk -Destination $output -Force
Get-Item $output | Select-Object FullName, Length
Write-Output ("SHA256 " + (Get-FileHash $output -Algorithm SHA256).Hash.ToLowerInvariant())
if (-not $Server) { Write-Output "No server baked in. Preview works offline; sign-in asks for the server address." }
