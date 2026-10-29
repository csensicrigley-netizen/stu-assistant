<#
  Build everything that goes to the server and to students, on this PC.

    powershell -ExecutionPolicy Bypass -File deploy\make-release.ps1 -Server https://your.domain

  release\stu-assistant-server.tar.gz   upload to the server, then: sudo bash deploy/install.sh your.domain
  release\STU事项助手-<version>.apk      the Android App (the website also offers it at /download/)

  -Server bakes the address into the App so students only sign in. Without it
  the App asks for the address once.
#>
param([string]$Server = "")

$ErrorActionPreference = "Stop"
$root = (Resolve-Path "$PSScriptRoot\..").Path
$web = "$root\web"

Write-Output "1/4 browser version"
Push-Location $web
cmd /c "npm run build > `"%TEMP%\stu-release-web.log`" 2>&1"
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { Get-Content "$env:TEMP\stu-release-web.log" -Tail 30; throw "web build failed" }

# 版本号只有一个真源：android/app/build.gradle。
# web 里的常量必须跟它一致，否则会出现「页面说 1.0.20、装上的包还是 1.0.19」这种假更新。
Write-Output "1.5/4 version sync"
$gradleText = Get-Content "$web\android\app\build.gradle" -Raw
if ($gradleText -notmatch 'versionCode\s+(\d+)') { throw "build.gradle 里没有 versionCode" }
$appCode = [int]$Matches[1]
if ($gradleText -notmatch 'versionName\s+"([^"]+)"') { throw "build.gradle 里没有 versionName" }
$appName = $Matches[1]
$tsText = Get-Content "$web\src\lib\version.ts" -Raw
if ($tsText -notmatch 'APP_VERSION_NAME\s*=\s*"([^"]+)"') { throw "version.ts 里没有 APP_VERSION_NAME" }
$webName = $Matches[1]
if ($tsText -notmatch 'APP_VERSION_CODE\s*=\s*(\d+)') { throw "version.ts 里没有 APP_VERSION_CODE" }
$webCode = [int]$Matches[1]
if ($webName -ne $appName -or $webCode -ne $appCode) {
  throw "版本号不一致：web=$webName/$webCode，gradle=$appName/$appCode。先改其中一个再打包。"
}
Write-Output ("    version " + $appName + " (" + $appCode + ") ok")

# ---- 更新说明 + 网页版本探针 -------------------------------------------------
# notes 从仓库里的 latest_version.json 继承（版本号一致时），
# 否则发出去的提示会变成一句空话——用户只看到「有新版本」却不知道为什么。
$repoLatest = "$root\backend\app\latest_version.json"
$notes = @()
if (Test-Path $repoLatest) {
  try {
    # 必须按 UTF-8 显式读：这个脚本跑在 Windows PowerShell 5.1 下，
    # 它对「无 BOM 的 UTF-8」按 GBK 解，中文会变乱码并让 JSON 直接解析失败，
    # 结果 notes 每次都悄悄变成空数组（发出去的「有新版本」没一句更新说明）。
    $rj = [IO.File]::ReadAllText($repoLatest, [Text.Encoding]::UTF8) | ConvertFrom-Json
    if ($rj.versionName -eq $appName) { $notes = @($rj.notes) }
  } catch {
    Write-Output ("    note: latest_version.json 解析失败，本次不带更新说明（" + $_.Exception.Message + "）")
    $notes = @()
  }
}
if ($notes.Count -eq 0) { Write-Output "    note: 没有更新说明（notes 为空）" }

# dist\version.json 是网页版自己的版本探针：PWA 会把旧 js 缓存住，
# 前端拿它和页面里的版本比一比，发现旧了就弹「刷新页面」。
# 必须写在 dist 里（会被一起发到 your-host），且不能被 service worker 缓存。
$probe = [ordered]@{
  versionName = $appName
  versionCode = $appCode
  notes       = $notes
  releasedAt  = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
}
New-Item -ItemType Directory -Force "$web\dist" | Out-Null
[IO.File]::WriteAllText("$web\dist\version.json", ($probe | ConvertTo-Json -Depth 4),
  (New-Object System.Text.UTF8Encoding($false)))
Write-Output ("    dist\version.json written (" + $appName + "/" + $appCode + ")")

Write-Output "2/4 Android App"
& "$web\scripts\build-android.ps1" -Server $Server
if (-not (Test-Path "$web\dist\download\stu-assistant.apk")) { throw "APK missing from dist\download" }

Write-Output "3/4 server bundle"
$stage = Join-Path $env:TEMP "stu-assistant-server"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force "$stage\backend\wheels", "$stage\deploy" | Out-Null
Copy-Item -Recurse "$root\backend\app" "$stage\backend\app"
Get-ChildItem "$stage\backend\app" -Recurse -Directory -Filter "__pycache__" | Remove-Item -Recurse -Force
Copy-Item "$root\backend\requirements.txt", "$root\backend\constraints.txt" "$stage\backend\"
Copy-Item "$root\backend\wheels\stu_mcp-0.7.3-py3-none-any.whl", "$root\backend\wheels\SHA256SUMS" "$stage\backend\wheels\"
Copy-Item -Recurse "$web\dist" "$stage\web"
Copy-Item "$root\deploy\install.sh", "$root\deploy\stu-assistant.service", "$root\deploy\README.md" "$stage\deploy\"

# 服务器端版本登记：跟这次打的包严格一致，旧客户端靠它知道自己该更新了
# （$notes 已经在 1.5/4 那一步读好，这里直接复用，别再读一遍文件。）
$latest = [ordered]@{
  versionName  = $appName
  versionCode  = $appCode
  apk          = "/download/stu-assistant.apk"
  notes        = $notes
  mandatory    = $false
  releasedAt   = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
  minSupported = "1.0.0"
}
# 必须写 UTF-8 无 BOM：带 BOM 时 json.loads 会失败，版本号会悄悄退回旧的
$latestJson = ($latest | ConvertTo-Json -Depth 4)
[IO.File]::WriteAllText("$stage\backend\app\latest_version.json", $latestJson, (New-Object System.Text.UTF8Encoding($false)))
if (-not (Test-Path "$stage\backend\app\latest_version.json")) { throw "latest_version.json 没写进包里" }
Write-Output "    server registers version $appName ($appCode)"

# shell scripts must reach Linux with LF line endings
foreach ($f in @("$stage\deploy\install.sh", "$stage\deploy\stu-assistant.service")) {
  $bytes = [IO.File]::ReadAllBytes($f)
  if ($bytes -contains 13) { throw "CRLF found in $f" }
}

Write-Output "4/4 archive"
$tgz = Join-Path $env:TEMP "stu-assistant-server.tar.gz"
if (Test-Path $tgz) { Remove-Item $tgz -Force }
tar -C $env:TEMP -czf $tgz "stu-assistant-server"
if ($LASTEXITCODE -ne 0) { throw "tar failed" }
New-Item -ItemType Directory -Force "$root\release" | Out-Null
Move-Item $tgz "$root\release\stu-assistant-server.tar.gz" -Force
Copy-Item "$root\deploy\README.md" "$root\release\部署说明.md" -Force
Remove-Item -Recurse -Force $stage

Get-ChildItem "$root\release" -File | ForEach-Object { "{0,-34} {1,8:N1} MB" -f $_.Name, ($_.Length / 1MB) }
