<#
  Build the signed Android APK.

    powershell -ExecutionPolicy Bypass -File scripts\build-android.ps1 [-Server https://your.domain]

  -Server  bakes the backend address into the App, so students only sign in.
           Without it, the App asks for the address once on the sign-in screen.

  Output:  web\dist\download\stu-assistant.apk   (served by the website)
           ..\release\STU事项助手-<version>.apk   (to share directly)

  Gradle and aapt2 break on non-ASCII paths (this folder is 'STU事项助手'),
  so the project is built through a temporary drive letter (subst).
#>
param([string]$Server = "")

$ErrorActionPreference = "Stop"
$web = (Resolve-Path "$PSScriptRoot\..").Path
$root = (Resolve-Path "$web\..").Path
$env:JAVA_HOME = "C:\Users\Administrator\AppData\Local\REAStarter\tools\jdk-21.0.12.1+1"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

if (-not (Test-Path "$root\android-signing\keystore.properties")) { throw "missing android-signing\keystore.properties" }
if ($Server -and $Server -notmatch '^https://') { throw "-Server must start with https://" }

Push-Location $web
try {
  # 1. the App's own web bundle (no service worker), with the server address
  $env:STU_TARGET = "android"
  $env:VITE_API_BASE = $Server
  cmd /c "npx vite build --outDir dist-android --emptyOutDir > `"%TEMP%\stu-vite-android.log`" 2>&1"
  if ($LASTEXITCODE -ne 0) { Get-Content "$env:TEMP\stu-vite-android.log" -Tail 20; throw "vite build failed" }
  # an empty value already removed VITE_API_BASE; ignore whichever is gone
  Remove-Item Env:STU_TARGET, Env:VITE_API_BASE -ErrorAction SilentlyContinue

  # 2. copy it into the Android project
  cmd /c "npx cap sync android > `"%TEMP%\stu-cap-sync.log`" 2>&1"
  if ($LASTEXITCODE -ne 0) { Get-Content "$env:TEMP\stu-cap-sync.log" -Tail 20; throw "cap sync failed" }
  Set-Content -Path "android\local.properties" -Value ("sdk.dir=" + $env:ANDROID_HOME.Replace('\', '/')) -Encoding ascii
} finally {
  Pop-Location
}

# 3. gradle through an ASCII drive letter mapped to the project root, so both
#    web\android and android-signing are reachable without non-ASCII paths
$letter = [char[]](70..90) | Where-Object { -not (Test-Path "$($_):\") } | Select-Object -First 1
if (-not $letter) { throw "no free drive letter for subst" }
subst "$($letter):" "$root"
try {
  Push-Location "$($letter):\web\android"
  cmd /c "gradlew.bat --no-daemon assembleRelease > `"%TEMP%\stu-gradle.log`" 2>&1"
  $code = $LASTEXITCODE
  Pop-Location
  if ($code -ne 0) { Get-Content "$env:TEMP\stu-gradle.log" -Tail 40; throw "gradle failed ($code)" }
} finally {
  subst "$($letter):" /d | Out-Null
}

# 4. publish
$apk = "$web\android\app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { throw "APK not found: $apk" }
$version = (Select-String -Path "$web\android\app\build.gradle" -Pattern 'versionName "([^"]+)"').Matches[0].Groups[1].Value
New-Item -ItemType Directory -Force "$web\dist\download", "$root\release" | Out-Null
Copy-Item $apk "$web\dist\download\stu-assistant.apk" -Force
Copy-Item $apk "$root\release\STU事项助手-$version.apk" -Force
$size = [math]::Round((Get-Item $apk).Length / 1MB, 1)
Write-Output "APK $version ($size MB) -> dist\download\stu-assistant.apk and release\STU事项助手-$version.apk"
if ($Server) { Write-Output "server baked in: $Server" } else { Write-Output "no server baked in: the App asks for it on first sign-in" }
