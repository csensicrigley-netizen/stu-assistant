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
$versionScript = Join-Path $taskRoot 'deploy/sync-version.py'
[string[]]$versionArgs = @($versionScript, '--json')
foreach ($taskVariant in $taskVariants) { $versionArgs += @('--variant', $taskVariant) }
$versionJson = & python @versionArgs
if ($LASTEXITCODE -ne 0) { throw 'Product or probe version metadata is inconsistent.' }
$versionMetadata = $versionJson | ConvertFrom-Json

# Android upgrade ordering is per applicationId. An unrelated OEM experiment
# must never force a product version increase. Read actual APK headers, not names.
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$aaptPath = $null
if ($sdkRoot -and (Test-Path -LiteralPath (Join-Path $sdkRoot 'build-tools'))) {
    $aaptPath = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory |
        Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'aapt.exe' } |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $aaptPath) { throw 'aapt is required to verify delivery package identities and version floors.' }
$shippedApks = @()
$probePrefix = 'stu-vc-probe-' + [Guid]::NewGuid().ToString('N')
$probeIndex = 0
foreach ($known in Get-ChildItem -LiteralPath (Join-Path $taskRoot 'release') -Filter '*.apk' -ErrorAction SilentlyContinue) {
    $probeCopy = Join-Path $env:TEMP ($probePrefix + "-" + $probeIndex + '.apk')
    $probeIndex++
    try {
        Copy-Item -LiteralPath $known.FullName -Destination $probeCopy -Force
        $badging = & $aaptPath dump badging $probeCopy 2>&1
        $aaptExitCode = $LASTEXITCODE
        $heading = ($badging | Select-String -Pattern '^package:' | Select-Object -First 1).Line
        if ($aaptExitCode -ne 0 -or $heading -notmatch "name='([^']+)' versionCode='(\d+)'") { throw "Cannot verify shipped APK: $($known.Name)" }
        $shippedApks += @{package=$Matches[1]; versionCode=[int]$Matches[2]; apk=$known.Name}
    } finally { Remove-Item -LiteralPath $probeCopy -Force -ErrorAction SilentlyContinue }
}
$inventoryPath = [IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllText($inventoryPath, (ConvertTo-Json -InputObject @($shippedApks)), [Text.UTF8Encoding]::new($false))
    $checkedVersions = & python @versionArgs --shipped-apks $inventoryPath
    if ($LASTEXITCODE -ne 0) { throw 'A delivery version does not exceed the previously shipped version for its package.' }
} finally { Remove-Item -LiteralPath $inventoryPath -Force -ErrorAction SilentlyContinue }
foreach ($build in $versionMetadata.builds) {
    Write-Output "    $($build.channel): $($build.applicationId) $($build.versionName) ($($build.versionCode)); product $($build.baseProductVersion.versionName)"
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
$logPath = Join-Path $releaseDirectory "native-$($versionMetadata.builds[0].versionName)-build.log"
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
        $buildMetadata = $versionMetadata.builds | Where-Object { $_.variant -eq $taskVariant } | Select-Object -First 1
        $artifactPath = Join-Path $releaseDirectory $buildMetadata.artifactName
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
        $badging = & $aaptPath dump badging $builtApk 2>&1
        $aaptExitCode = $LASTEXITCODE
        $heading = ($badging | Select-String -Pattern '^package:' | Select-Object -First 1).Line
        if ($aaptExitCode -ne 0 -or $heading -notmatch "name='([^']+)' versionCode='([^']+)' versionName='([^']+)'") { throw 'APK identity metadata could not be read.' }
        $packageName = $Matches[1]
        if ($packageName -cne $buildMetadata.applicationId -or [int]$Matches[2] -ne $buildMetadata.versionCode -or $Matches[3] -cne $buildMetadata.versionName) { throw 'APK does not match the selected channel package/version.' }
        $hash = (Get-FileHash -LiteralPath $builtApk -Algorithm SHA256).Hash
        $receipt = @{versionName=$buildMetadata.versionName;versionCode=$buildMetadata.versionCode;variant=$taskVariant;channel=$buildMetadata.channel;isProductBuild=$buildMetadata.isProductBuild;baseProductVersion=$buildMetadata.baseProductVersion;package=$packageName;apk=(Split-Path $artifactPath -Leaf);bytes=(Get-Item -LiteralPath $builtApk).Length;sha256=$hash;builtAt=[DateTime]::UtcNow.ToString('o');source=$taskSource;sourceClean=$true;server=$packagedServer;webBundleIncluded=$false}
        if (-not $buildMetadata.isProductBuild) { $receipt.functionalUpgrade = $false }
        $deliveries += @{builtApk=$builtApk;artifactPath=$artifactPath;receipt=$receipt}
    }
    $configHash = (Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash
    [IO.File]::WriteAllBytes($configPath,$savedConfig)
    $sourceAfterBuild = git -C $taskRoot rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $sourceAfterBuild -ne $taskSource) { throw 'Source commit changed during the build.' }
    $changesAfterBuild = git -C $taskRoot status --porcelain
    if ($LASTEXITCODE -ne 0 -or $changesAfterBuild) { throw 'Source changed during the build; commit and rebuild before delivery.' }
    # Validate every artifact before publishing either member of an identity pair.
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
