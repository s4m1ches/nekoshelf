# Builds the debug APK, installs it on the connected Meebook and sets the e-ink refresh row.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$pkg = 'app.meebook.launcher'
$uri = 'content://com.android.systemui.eink/einksettings'

Push-Location $root
try {
    & "$root\gradlew.bat" :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
} finally { Pop-Location }

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }

# Only the launcher's own row in the firmware refresh table is touched.
$existing = & $adb shell "content query --uri $uri --where `"package_name='$pkg'`""
if ($existing -match 'package_name=') {
    & $adb shell "content update --uri $uri --bind refresh_mode:i:10 --bind refresh_frequency:i:0 --bind is_refresh_setting:i:1 --where `"package_name='$pkg'`""
} else {
    & $adb shell "content insert --uri $uri --bind package_name:s:$pkg --bind refresh_mode:i:10 --bind refresh_frequency:i:0 --bind is_refresh_setting:i:1"
}
& $adb shell "content query --uri $uri --where `"package_name='$pkg'`""
