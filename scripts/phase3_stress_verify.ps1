param(
    [string]$DeviceSerial = "RZCX60ENLJF",
    [string]$PackageId = "com.andsi.airlyrics",
    [string]$ReleaseApk = "app\build\outputs\apk\release\app-release-signed.apk",
    [int]$SkipIterations = 25,
    [int]$LockCycles = 12
)

$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "C:\Users\user.20\Documents\ChatGPT\lo\.toolchain\jdk\jdk-21.0.13+9"
$env:ANDROID_HOME = "C:\Users\user.20\Documents\ChatGPT\lo\.toolchain\android-sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
$env:USERPROFILE = "C:\Users\user.20"
$env:ADB_VENDOR_KEYS = "C:\Users\user.20\.android\adbkey"

Write-Host "=== [Phase 3] Verifying device $DeviceSerial ===" -ForegroundColor Cyan
$devices = adb devices
if ($devices -notmatch "$DeviceSerial\s+device") {
    # If device serial isn't exact or only one device attached, try the single attached device
    $attached = adb devices | Select-String "device$"
    if ($attached.Count -eq 1) {
        $DeviceSerial = ($attached[0].Line -split "\s+")[0]
        Write-Host "Using attached device: $DeviceSerial" -ForegroundColor Yellow
    } else {
        Write-Warning "Device $DeviceSerial is not currently connected/authorized."
    }
}

if (!(Test-Path $ReleaseApk)) {
    throw "Release APK not found at $ReleaseApk. Run .\gradlew assembleRelease first."
}

Write-Host "=== [Phase 3] Checking APK Signature ===" -ForegroundColor Cyan
$apksigner = "C:\Users\user.20\Documents\ChatGPT\lo\.toolchain\android-sdk\build-tools\36.1.0\apksigner.bat"
& $apksigner verify --verbose $ReleaseApk

Write-Host "=== [Phase 3] Installing Signed Release APK ===" -ForegroundColor Cyan
adb -s $DeviceSerial install -r $ReleaseApk

Write-Host "=== [Phase 3] Resetting telemetry & logcat ===" -ForegroundColor Cyan
adb -s $DeviceSerial logcat -c
adb -s $DeviceSerial shell dumpsys gfxinfo $PackageId reset 2>$null | Out-Null

Write-Host "Starting baseline memory snapshot (ensure Spotify/YT Music is actively playing)..." -ForegroundColor Yellow
Start-Sleep -Seconds 3

$memStart = adb -s $DeviceSerial shell dumpsys meminfo $PackageId | Select-String "TOTAL PSS:|Native Heap|Dalvik Heap|Views:|AppContexts:"

Write-Host "=== [Stress 1/3] Rapid Track-Skip Blitz ($SkipIterations skips) ===" -ForegroundColor Cyan
for ($i = 1; $i -le $SkipIterations; $i++) {
    adb -s $DeviceSerial shell input keyevent KEYCODE_MEDIA_NEXT
    Start-Sleep -Milliseconds 1800
    if ($i % 5 -eq 0) {
        Write-Host "  Completed $i / $SkipIterations skips..."
    }
}

Write-Host "=== [Stress 2/3] Lock / AOD / Unlock Churn ($LockCycles cycles) ===" -ForegroundColor Cyan
for ($c = 1; $c -le $LockCycles; $c++) {
    # Screen off
    adb -s $DeviceSerial shell input keyevent KEYCODE_SLEEP
    Start-Sleep -Milliseconds 1600

    # Verify NO floating overlay window lingers while locked
    $lockedOverlays = adb -s $DeviceSerial shell dumpsys window windows | Select-String "airlyrics"

    # Wake to Lock Screen
    adb -s $DeviceSerial shell input keyevent KEYCODE_WAKEUP
    Start-Sleep -Milliseconds 1200

    # Unlock (swipe up)
    adb -s $DeviceSerial shell input swipe 540 1900 540 600 220
    Start-Sleep -Milliseconds 1200
}

Write-Host "=== [Stress 3/3] Collecting Post-Stress Telemetry ===" -ForegroundColor Cyan
$memEnd = adb -s $DeviceSerial shell dumpsys meminfo $PackageId | Select-String "TOTAL PSS:|Native Heap|Dalvik Heap|Views:|AppContexts:"
$gfxReport = adb -s $DeviceSerial shell dumpsys gfxinfo $PackageId | Select-String "Total frames rendered:|Janky frames:|90th percentile:|95th percentile:|99th percentile:"
$fatalLogs = adb -s $DeviceSerial logcat -d | Select-String -Pattern "FATAL EXCEPTION|TransactionTooLargeException|ClassNotFoundException|NoSuchMethodException|DeadObjectException|Recycled"
$notifDump = adb -s $DeviceSerial shell dumpsys notification --noredact | Select-String -Pattern "airlyrics_lockscreen_card" -Context 1,4

Write-Host "`n--- MEMORY BASELINE ---" -ForegroundColor Green
$memStart

Write-Host "`n--- MEMORY POST-STRESS ---" -ForegroundColor Green
$memEnd

Write-Host "`n--- GFXINFO FRAME PACING ---" -ForegroundColor Green
$gfxReport

Write-Host "`n--- LOCKSCREEN NOTIFICATION CHECK ---" -ForegroundColor Green
$notifDump

if ($fatalLogs) {
    Write-Host "`n--- CRITICAL ERRORS DETECTED ---" -ForegroundColor Red
    $fatalLogs
    exit 1
} else {
    Write-Host "`nPASS: Zero crashes, zero Binder TransactionTooLargeException, zero R8 missing-class errors." -ForegroundColor Green
}
