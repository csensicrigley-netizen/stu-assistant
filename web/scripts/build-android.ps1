<# Build the signed native app. Self-hosters set -Server at build time; no URL picker ships. #>
param([string]$Server = '', [switch]$Online)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path "$PSScriptRoot/../..").Path
& (Join-Path $taskRoot 'deploy/make-native-release.ps1') -Variant Release -Server $Server -Online:$Online
if (-not $?) { throw 'Native release build failed' }
$taskBuild = [IO.File]::ReadAllText((Join-Path $taskRoot 'web/android/app/build.gradle'))
$taskVersion = [regex]::Match($taskBuild, 'versionName\s+"([^"]+)"').Groups[1].Value
$taskDownload = Join-Path $taskRoot 'web/dist/download'
New-Item -ItemType Directory -Path $taskDownload -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $taskRoot "release/STU-assistant-$taskVersion.apk") -Destination (Join-Path $taskDownload 'stu-assistant.apk')
