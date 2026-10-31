param(
    [ValidateSet('Haptics','Release','Liveprobe','Huaweiprobe','Vivoprobe','Vivoidentity','Vivomap')][string]$Variant = 'Haptics',
    [string]$Server = '',
    [switch]$Online,
    [switch]$IdentityPair
)
$ErrorActionPreference = 'Stop'
$taskVariants = if ($IdentityPair) {
    if ($PSBoundParameters.ContainsKey('Variant')) { throw 'Use IdentityPair without Variant.' }
    @('Vivoidentity', 'Vivomap')
} else { @($Variant) }
$taskRoot = (Resolve-Path "$PSScriptRoot/..").Path
$androidRoot = Join-Path $taskRoot 'web/android'
$configPath = Join-Path $androidRoot 'app/src/main/assets/native-config.json'
$savedConfig = [IO.File]::ReadAllBytes($configPath)
$taskSource = git -C $taskRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Source commit is unavailable.' }
$sourceChanges = git -C $taskRoot status --porcelain
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify the source working tree.' }
if ($sourceChanges) { throw 'Commit the source changes before creating a delivery APK. Use Gradle directly for development builds.' }
& python (Join-Path $taskRoot 'deploy/sync-version.py')
if ($LASTEXITCODE -ne 0) { throw 'Version metadata is inconsistent.' }
$taskBuild = [IO.File]::ReadAllText((Join-Path $androidRoot 'app/build.gradle'))
$taskVersion = [regex]::Match($taskBuild, 'versionName\s+"([^"]+)"').Groups[1].Value
$taskCode = [regex]::Match($taskBuild, 'versionCode\s+(\d+)').Groups[1].Value
if (-not $taskVersion -or -not $taskCode) { throw 'Native version was not found in app/build.gradle' }

# 版本号只能往前：release/ 里发过的包若比这次还高，装上去会被系统判成降级，
# 用户看到「应用未安装」，只能先卸载再装（2026-10-31 真实事故）。
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if ($sdkRoot -and (Test-Path -LiteralPath (Join-Path $sdkRoot 'build-tools'))) {
    $aaptPath = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory |
        Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'aapt.exe' } |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if ($aaptPath) {
        $highest = 0
        $highestName = ''
        $probePrefix = 'stu-vc-probe-' + [Guid]::NewGuid().ToString('N')
        $probeIndex = 0
        foreach ($known in Get-ChildItem -LiteralPath (Join-Path $taskRoot 'release') -Filter '*.apk' -ErrorAction SilentlyContinue) {
            $probeCopy = Join-Path $env:TEMP ($probePrefix + "-" + $probeIndex + '.apk')
            $probeIndex++
            try {
                Copy-Item -LiteralPath $known.FullName -Destination $probeCopy -Force
                $heading = (& $aaptPath dump badging $probeCopy 2>&1 | Select-String -Pattern '^package:' | Select-Object -First 1).Line
                if ($heading -match "versionCode='(\d+)'") {
                    $seen = [int]$Matches[1]
                    if ($seen -gt $highest) { $highest = $seen; $highestName = $known.Name }
                }
            } catch { } finally { Remove-Item -LiteralPath $probeCopy -Force -ErrorAction SilentlyContinue }
        }
        if ($highest -ge [int]$taskCode) {
            throw "版本号不能往回走：本次 versionCode=$taskCode，但 release/ 里 $highestName 已经是 $highest。先把 app/build.gradle 的 versionCode 提到 $($highest + 1) 以上。"
        }
        Write-Output "    versionCode $taskCode is newer than every shipped apk (highest $highest)"
    }
}
if ($Variant -eq 'Release' -and -not (Test-Path -LiteralPath (Join-Path $taskRoot 'android-signing/keystore.properties'))) {
    throw 'The release signing properties are missing.'
}
$effectiveServer = if ($Server) { $Server.Trim().TrimEnd('/') } else { ([Text.Encoding]::UTF8.GetString($savedConfig) | ConvertFrom-Json).server }
$taskUri = $null
if (-not $effectiveServer -or -not [Uri]::TryCreate($effectiveServer, [UriKind]::Absolute, [ref]$taskUri) -or
    $taskUri.Scheme -ne 'https' -or -not $taskUri.Host -or $taskUri.UserInfo -or $taskUri.Query -or $taskUri.Fragment -or
    $effectiveServer -match '[?#]' -or $effectiveServer -ne $effectiveServer.Trim() -or $effectiveServer.EndsWith('/')) {
    throw 'Configure an HTTPS backend base URL before packaging; users cannot supply one in the app.'
}
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME','User') }
$driveLetter = [char[]](70..90) | Where-Object { -not (Test-Path "$($_):\") } | Select-Object -First 1
if (-not $driveLetter) { throw 'No free ASCII drive is available for the Android tools.' }
$releaseDirectory = Join-Path $taskRoot 'release'
New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
$logPath = Join-Path $releaseDirectory "native-$taskVersion-build.log"
subst "$($driveLetter):" "$taskRoot"
if ($LASTEXITCODE -ne 0) { throw 'subst failed' }
try {
    if ($Server) {
        $deploymentConfig = [Text.Encoding]::UTF8.GetString($savedConfig) | ConvertFrom-Json
        $deploymentConfig.server = $effectiveServer
        [IO.File]::WriteAllText($configPath, ($deploymentConfig | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
    }
    Push-Location "$($driveLetter):\web\android"
    try {
        [string[]]$networkArgs = if ($Online) { @() } else { @('--offline') }
        [string[]]$buildTasks = $taskVariants | ForEach-Object { ":app:assemble$_" }
        & ./gradlew.bat @networkArgs --no-daemon --console=plain --max-workers=2 @buildTasks *> $logPath
        if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $logPath -Tail 35; throw 'Native APK build failed' }
    } finally { Pop-Location }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $deliveries = @()
    foreach ($taskVariant in $taskVariants) {
        $variantLower = $taskVariant.ToLowerInvariant()
        $suffix = switch ($taskVariant) { 'Haptics' { '-native.1' } 'Liveprobe' { '-liveprobe.1' } 'Huaweiprobe' { '-huaweiprobe.1' } 'Vivoprobe' { '-vivoprobe.1' } 'Vivoidentity' { '-vivoidentity.1' } 'Vivomap' { '-vivoidentity.1' } default { '' } }
        $fileSuffix = switch ($taskVariant) { 'Vivoidentity' { '-vivoidentity-control.1' } 'Vivomap' { '-vivoidentity-amap.1' } default { $suffix } }
        $artifactPath = Join-Path $releaseDirectory "STU-assistant-$taskVersion$fileSuffix.apk"
        $builtApk = Join-Path $androidRoot "app/build/outputs/apk/$variantLower/app-$variantLower.apk"
        $archive = [IO.Compression.ZipFile]::OpenRead($builtApk)
        try {
            if ($archive.Entries.FullName | Where-Object { $_ -like 'assets/public/*' -or $_ -like 'assets/*.js' }) { throw 'A web UI bundle unexpectedly entered the native APK' }
            $packagedConfig = $archive.GetEntry('assets/native-config.json')
            if (-not $packagedConfig) { throw 'Native configuration is missing from the APK' }
            $configReader = [IO.StreamReader]::new($packagedConfig.Open(), [Text.Encoding]::UTF8)
            try { $packagedServer = ($configReader.ReadToEnd() | ConvertFrom-Json).server } finally { $configReader.Dispose() }
            if ($packagedServer -cne $effectiveServer) { throw 'Packaged backend differs from the intended deployment configuration.' }
        } finally { $archive.Dispose() }
        $packageName = ''
        if ($taskVariant -in @('Vivoidentity', 'Vivomap')) {
            if (-not $aaptPath) { throw 'aapt is required to verify the identity experiment.' }
            $heading = (& $aaptPath dump badging $builtApk 2>&1 | Select-String -Pattern '^package:' | Select-Object -First 1).Line
            $expectedPackage = if ($taskVariant -eq 'Vivomap') { 'com.autonavi.minimap' } else { 'com.stuassistant.app.vivoprobe' }
            if ($LASTEXITCODE -ne 0 -or $heading -notmatch "name='([^']+)' versionCode='([^']+)' versionName='([^']+)'") { throw 'APK identity metadata could not be read.' }
            $packageName = $Matches[1]
            if ($packageName -cne $expectedPackage -or $Matches[2] -ne $taskCode -or $Matches[3] -cne "$taskVersion$suffix") { throw 'Identity APK does not match intended package/version.' }
        }
        $hash = (Get-FileHash -LiteralPath $builtApk -Algorithm SHA256).Hash
        $receipt = @{versionName="$taskVersion$suffix";versionCode=[int]$taskCode;variant=$taskVariant;package=$packageName;apk=(Split-Path $artifactPath -Leaf);bytes=(Get-Item -LiteralPath $builtApk).Length;sha256=$hash;builtAt=[DateTime]::UtcNow.ToString('o');source=$taskSource;sourceClean=$true;server=$packagedServer;webBundleIncluded=$false}
        $deliveries += @{builtApk=$builtApk;artifactPath=$artifactPath;receipt=$receipt}
    }
    $configHash = (Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash
    [IO.File]::WriteAllBytes($configPath,$savedConfig)
    $sourceAfterBuild = git -C $taskRoot rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $sourceAfterBuild -ne $taskSource) { throw 'Source commit changed during the build.' }
    $changesAfterBuild = git -C $taskRoot status --porcelain
    if ($LASTEXITCODE -ne 0 -or $changesAfterBuild) { throw 'Source changed during the build; commit and rebuild before delivery.' }
    # Validate the entire pair before publishing either artifact. Version-floor
    # checks happen once, so the two identities intentionally share one version.
    foreach ($delivery in $deliveries) {
        $artifactPath = $delivery.artifactPath
        $delivery.receipt.nativeConfigSha256 = $configHash
        [IO.File]::WriteAllText("$artifactPath.json.tmp",($delivery.receipt | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
        Copy-Item -LiteralPath $delivery.builtApk -Destination $artifactPath
        Move-Item -LiteralPath "$artifactPath.json.tmp" -Destination "$artifactPath.json" -Force
        Write-Output "$artifactPath`nSHA256 $($delivery.receipt.sha256)"
    }
} finally {
    [IO.File]::WriteAllBytes($configPath,$savedConfig)
    subst "$($driveLetter):" /d | Out-Null
}
