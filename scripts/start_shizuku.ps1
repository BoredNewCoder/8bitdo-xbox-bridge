# Re-starts Shizuku on the Shield Pro after a reboot (non-root Shizuku doesn't survive
# reboot — this is normal per Shizuku's own docs, has to be re-run via adb every time).
# Resolves the versioned libshizuku.so path fresh each run via `pm path`, so it keeps
# working even after a Shizuku app update changes the install hash.

$adb = "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$shieldIp = "192.168.1.162:5555"

Write-Host "Connecting to Shield ($shieldIp)..."
& $adb connect $shieldIp

$pathLine = & $adb shell pm path moe.shizuku.privileged.api
if (-not $pathLine -or $pathLine -notmatch "^package:(.+)/base\.apk$") {
    Write-Host "ERROR: could not resolve Shizuku's installed apk path. Is it still installed?"
    exit 1
}
$appDir = $Matches[1]
$starterPath = "$appDir/lib/arm64/libshizuku.so"

Write-Host "Starting Shizuku via $starterPath ..."
& $adb shell $starterPath

Write-Host ""
Write-Host "Verifying shizuku_server is running under shell uid..."
$psOutput = & $adb shell ps -A
$psOutput | Out-String | Select-String "shizuku_server"
