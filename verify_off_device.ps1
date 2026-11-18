$ErrorActionPreference = "Stop"

$scriptDir = $PSScriptRoot
Set-Location $scriptDir

Write-Host "=== Running AirLyrics Off-Device Verification Suite ==="
$env:JAVA_HOME = "C:\Users\user.20\Documents\ChatGPT\lo\.toolchain\jdk\jdk-21.0.13+9"
$env:ANDROID_HOME = "C:\Users\user.20\Documents\ChatGPT\lo\.toolchain\android-sdk"

& "$scriptDir\gradlew.bat" testDebugUnitTest `
    --tests "com.andsi.airlyrics.widget.*" `
    --tests "com.andsi.airlyrics.debug.*" `
    --tests "com.andsi.airlyrics.aod.*" `
    --tests "com.andsi.airlyrics.lockscreen.*" `
    --tests "com.andsi.airlyrics.floating.StationaryHoldGestureTest" `
    "-Pairlyrics.sourceDateEpoch=1731859200" `
    "-Pairlyrics.skipRustBuild=true"

if ($LASTEXITCODE -ne 0) {
    throw "Gradle testDebugUnitTest failed with exit code $LASTEXITCODE"
}

$xmlFiles = @(
    "TEST-com.andsi.airlyrics.debug.LockScreenAodSimulationActivityTest.xml",
    "TEST-com.andsi.airlyrics.widget.SamsungLockScreenAodEndToEndTest.xml",
    "TEST-com.andsi.airlyrics.widget.FixedLyricsPanelProviderTest.xml",
    "TEST-com.andsi.airlyrics.aod.AodLyricsMediaPublisherTest.xml",
    "TEST-com.andsi.airlyrics.lockscreen.LockScreenComponentsTest.xml",
    "TEST-com.andsi.airlyrics.floating.StationaryHoldGestureTest.xml"
)

$totalTests = 0
$totalFailures = 0
$totalErrors = 0

foreach ($fileName in $xmlFiles) {
    $filePath = Join-Path "$scriptDir\app\build\test-results\testDebugUnitTest" $fileName
    if (Test-Path $filePath) {
        [xml]$doc = Get-Content $filePath
        $suite = $doc.testsuite
        $totalTests += [int]$suite.tests
        $totalFailures += [int]$suite.failures
        $totalErrors += [int]$suite.errors
        Write-Host "Suite $($suite.name): tests=$($suite.tests), failures=$($suite.failures), errors=$($suite.errors)"
    }
}

if ($totalTests -eq 0 -or $totalFailures -gt 0 -or $totalErrors -gt 0) {
    throw "Verification FAILED: tests=$totalTests failures=$totalFailures errors=$totalErrors"
}

Write-Host "=== VERIFICATION GREEN: $totalTests/$totalTests tests passed (0 failures, 0 errors) ==="
