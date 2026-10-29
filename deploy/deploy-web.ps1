# 只发网页版到静态站主机（不动 app 包、不动后端）
#
# 为什么要有这个脚本：`npm run build` 会把 dist 整个清空重建，
# 里面 download/stu-assistant.apk 也会被一起清掉。如果之后直接把 dist 传上去，
# 学生点「下载 App」就会拿到 404 或一张 HTML 页面（就是"APK 只有 500 字节"那个现象）。
# 所以这里在打包前，先把 release 里最新的 APK 放回 dist/download，并校验大小与 sha。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File deploy\deploy-web.ps1
#   powershell -ExecutionPolicy Bypass -File deploy\deploy-web.ps1 -NoBuild   # 跳过构建，只发

param(
  [switch]$NoBuild,
  [string]$Apk = "",
  # 你自己的静态站主机（ssh config 里的别名）与站点地址；仓库里不写死任何真实域名
  [string]$WebHost = "web-host",
  [string]$SiteUrl = "https://example.com"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path "$PSScriptRoot\..").Path
$web = "$root\web"
$release = "$root\release"

# ---------- 1. 构建 ----------
if (-not $NoBuild) {
  Write-Output "1/4 build web"
  Push-Location $web
  cmd /c "npm run build > `"%TEMP%\stu-web-build.log`" 2>&1"
  $code = $LASTEXITCODE
  Pop-Location
  if ($code -ne 0) { Get-Content "$env:TEMP\stu-web-build.log" -Tail 30; throw "web build failed" }
}

# ---------- 2. 找回 APK 并放回 dist/download ----------
Write-Output "2/4 restore apk"
if (-not $Apk) {
  $latest = Get-ChildItem "$release\*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $latest) { throw "release 目录里没有 APK；先跑 make-release.ps1" }
  $Apk = $latest.FullName
}
if (-not (Test-Path $Apk)) { throw "找不到 APK：$Apk" }

$apkSize = (Get-Item $Apk).Length
if ($apkSize -lt 1MB) { throw "APK 只有 $apkSize 字节，明显不对；先重新跑 make-release.ps1" }

New-Item -ItemType Directory -Force "$web\dist\download" | Out-Null
Copy-Item $Apk "$web\dist\download\stu-assistant.apk" -Force
$localSha = (Get-FileHash "$web\dist\download\stu-assistant.apk" -Algorithm SHA256).Hash.ToLower()
Write-Output ("    {0}  {1:N1} MB  sha {2}" -f (Split-Path $Apk -Leaf), ($apkSize / 1MB), $localSha.Substring(0, 16))

# 页面上的版本号必须和这个包一致，否则又是一次假更新
$versionTs = Get-Content "$web\src\lib\version.ts" -Raw
if ($versionTs -notmatch 'APP_VERSION_NAME\s*=\s*"([^"]+)"') { throw "version.ts 里没有 APP_VERSION_NAME" }
$webName = $Matches[1]
if ((Split-Path $Apk -Leaf) -notmatch [regex]::Escape($webName)) {
  throw "APK 文件名（$(Split-Path $Apk -Leaf)）和 version.ts（$webName）不一致"
}

# ---------- 3. 打包 ----------
Write-Output "3/4 pack"
$tgz = Join-Path $env:TEMP "stu-web-only.tgz"
if (Test-Path $tgz) { Remove-Item $tgz -Force }
tar -C "$web\dist" -czf $tgz .
if ($LASTEXITCODE -ne 0) { throw "tar failed" }

# ---------- 4. 上传并原子切换 ----------
Write-Output "4/4 deploy to web host"
scp -q $tgz "$WebHost:/tmp/stu-web-only.tgz"
$remote = @'
set -e
rm -rf /opt/stu-web.new && mkdir -p /opt/stu-web.new
tar -C /opt/stu-web.new -xzf /tmp/stu-web-only.tgz
test -s /opt/stu-web.new/download/stu-assistant.apk
rm -rf /opt/stu-web.old && mv /opt/stu-web /opt/stu-web.old && mv /opt/stu-web.new /opt/stu-web
chmod -R a+rX /opt/stu-web && chown -R root:root /opt/stu-web
ls -l /opt/stu-web/download/
grep -o "assets/index-[A-Za-z0-9_-]*\.\(js\|css\)" /opt/stu-web/index.html | sort -u
curl -s -o /dev/null -w "home %{http_code}\n" $SiteUrl/
curl -s -o /dev/null -w "APK  %{http_code}  %{size_download} bytes\n" $SiteUrl/download/stu-assistant.apk
sha256sum /opt/stu-web/download/stu-assistant.apk | cut -c1-16
'@
$remote -replace "`r`n", "`n" | Set-Content -Encoding ascii -NoNewline "$env:TEMP\stu-deploy-remote.sh"
scp -q "$env:TEMP\stu-deploy-remote.sh" "$WebHost:/tmp/stu-deploy.sh"
ssh "$WebHost" "bash /tmp/stu-deploy.sh"

Write-Output ""
Write-Output "done: $SiteUrl/"
