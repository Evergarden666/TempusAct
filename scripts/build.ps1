param(
    [string[]]$Tasks = @('testDebugUnitTest', 'assembleRelease'),
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$clockProject = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $clockProject
try {
    # Java 17's worker argument files need an ASCII cache path on Windows.
    $env:GRADLE_USER_HOME = Join-Path $env:TEMP 'jianshen-clock-gradle-home'
    $clockBuildOutput = Join-Path $env:TEMP 'jianshen-clock-app-build'
    $clockCache = Join-Path $env:USERPROFILE '.gradle\caches'
    if (Test-Path -LiteralPath (Join-Path $clockCache 'modules-2')) { $env:GRADLE_RO_DEP_CACHE = $clockCache }
    if (-not (Test-Path -LiteralPath 'local.properties')) {
        $clockSdk = $env:ANDROID_HOME
        if (-not $clockSdk) { $clockSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
        if (-not (Test-Path -LiteralPath $clockSdk)) { throw 'Install Android SDK 36 or set ANDROID_HOME first.' }
        Set-Content -LiteralPath 'local.properties' -Value ('sdk.dir=' + $clockSdk.Replace('\','/').Replace(':','\:')) -Encoding ascii
    }
    $clockSigning = Join-Path $clockProject 'signing.properties'
    if (-not (Test-Path -LiteralPath $clockSigning)) {
        $clockKeys = Join-Path $clockProject '.private'
        $clockKeystore = Join-Path $clockKeys 'clock-release.p12'
        if (Test-Path -LiteralPath $clockKeystore) { throw 'Signing key exists. Restore its signing.properties; do not replace the key.' }
        New-Item -ItemType Directory -Force -Path $clockKeys | Out-Null
        $clockPasswordBytes = New-Object byte[] 32
        $clockRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $clockRng.GetBytes($clockPasswordBytes)
        $clockRng.Dispose()
        $clockPassword = -join ($clockPasswordBytes | ForEach-Object { $_.ToString('x2') })
        $env:CLOCK_SIGNING_PASSWORD = $clockPassword
        try {
            & keytool -genkeypair -keystore $clockKeystore -storetype PKCS12 -alias clock -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=JianshenClock,OU=Local,O=Personal,C=CN' -storepass:env CLOCK_SIGNING_PASSWORD -keypass:env CLOCK_SIGNING_PASSWORD
            if ($LASTEXITCODE -ne 0) { throw 'Could not generate local signing key.' }
            @('storeFile=.private/clock-release.p12', 'keyAlias=clock', "storePassword=$clockPassword", "keyPassword=$clockPassword") |
                Set-Content -LiteralPath $clockSigning -Encoding ascii
        } finally { Remove-Item Env:CLOCK_SIGNING_PASSWORD -ErrorAction SilentlyContinue }
    }
    $clockCachedGradle = Get-ChildItem -Path (Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-9.1.0-bin\*\gradle-9.1.0\bin\gradle.bat') -ErrorAction SilentlyContinue | Select-Object -First 1
    $clockLauncher = if ($clockCachedGradle) { $clockCachedGradle.FullName } else { Join-Path $clockProject 'gradlew.bat' }
    $clockArgs = @('--no-daemon', '--console=plain', "-Pclock.buildDir=$clockBuildOutput") + $Tasks
    if ($Offline) { $clockArgs += '--offline' }
    & $clockLauncher @clockArgs
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed. Review the output above.' }
    $clockArtifacts = Join-Path $clockProject 'artifacts'
    New-Item -ItemType Directory -Force -Path $clockArtifacts | Out-Null
    if (($Tasks -contains 'testDebugUnitTest') -and (Test-Path -LiteralPath (Join-Path $clockBuildOutput 'test-results\testDebugUnitTest'))) {
        $clockReportPairs = @(
            @{ Source = 'test-results\testDebugUnitTest'; Target = 'test-results' },
            @{ Source = 'reports\tests\testDebugUnitTest'; Target = 'test-report' }
        )
        foreach ($clockReportPair in $clockReportPairs) {
            $clockReportTarget = Join-Path $clockArtifacts $clockReportPair.Target
            New-Item -ItemType Directory -Force -Path $clockReportTarget | Out-Null
            Get-ChildItem -LiteralPath (Join-Path $clockBuildOutput $clockReportPair.Source) |
                Copy-Item -Destination $clockReportTarget -Recurse -Force
        }
    }
    if (Test-Path -LiteralPath (Join-Path $clockBuildOutput 'reports\lint-results-debug.html')) {
        Copy-Item -LiteralPath (Join-Path $clockBuildOutput 'reports\lint-results-debug.html') -Destination $clockArtifacts -Force
    }
    $clockApk = Join-Path $clockBuildOutput 'outputs\apk\release\app-release.apk'
    if ((Test-Path -LiteralPath $clockApk) -and ($Tasks -contains 'assembleRelease')) {
        $clockDeliverable = Join-Path $clockArtifacts 'TempusAct-1.1.0.apk'
        Copy-Item -LiteralPath $clockApk -Destination $clockDeliverable -Force
        (Get-FileHash -LiteralPath $clockDeliverable -Algorithm SHA256).Hash.ToLowerInvariant() |
            Set-Content -LiteralPath ($clockDeliverable + '.sha256') -Encoding ascii
        Write-Output "APK: $clockDeliverable"
    }
} finally { Pop-Location }
