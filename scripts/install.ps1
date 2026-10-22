# Builds the debug APK, installs it on the connected Meebook and sets the e-ink refresh row.
# -AdbPath / -SkipBuild exist so the argument quoting can be tested offline with a stand-in exe.
[CmdletBinding()]
param(
    [string]$AdbPath = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$pkg = 'app.meebook.launcher'
$uri = 'content://com.android.systemui.eink/einksettings'

# The device command is passed to `adb shell` as ONE argument containing no double quotes
# (those are mangled differently by Windows PowerShell 5.1 and PowerShell 7). The SQL quotes
# are backslash-escaped single quotes, which the device's sh turns into literal '.
$where = "--where package_name=\'$pkg\'"

# Runs `adb shell <cmd>`; throws if the exit code is non-zero or the output reports an error.
function Invoke-DeviceShell([string]$cmd) {
    $out = (& $AdbPath shell $cmd | Out-String)
    Write-Verbose $out
    if ($LASTEXITCODE -ne 0 -or $out -match 'Error|Exception') {
        throw "adb shell failed ($LASTEXITCODE): $cmd`n$out"
    }
    return $out
}

if (-not $SkipBuild) {
    Push-Location $root
    try {
        & "$root\gradlew.bat" :app:assembleDebug
        if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
    } finally { Pop-Location }

    $apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
    & $AdbPath install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }
}

# Only the launcher's own row in the firmware refresh table is touched.
$binds = '--bind refresh_mode:i:10 --bind refresh_frequency:i:0 --bind is_refresh_setting:i:1'
$existing = Invoke-DeviceShell "content query --uri $uri $where"
if ($existing -match 'package_name=') {
    Invoke-DeviceShell "content update --uri $uri $binds $where" | Out-Null
} else {
    Invoke-DeviceShell "content insert --uri $uri --bind package_name:s:$pkg $binds" | Out-Null
}
$final = Invoke-DeviceShell "content query --uri $uri $where"
Write-Output $final
if ($final -notmatch 'refresh_mode=10') { throw "e-ink row verification failed:`n$final" }
