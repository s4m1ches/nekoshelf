<#
.SYNOPSIS
  Pushes the Nekoshelf KOReader Kit to a USB-/adb-connected Android e-ink reader.

.DESCRIPTION
  Copies patches/, defaults.custom.lua, the Bookends settings and presets and the
  catgirl ornament pack into /sdcard/koreader/ with adb.

  It NEVER touches settings.reader.lua: KOReader rewrites that file on exit and
  it holds your own state. Instead the script prints the path of the partial
  settings file (settings.reader.partial.lua) so you can merge the keys you want
  by hand while KOReader is closed (see README.md).

  Existing files with the same names are overwritten (adb push). Back up
  /sdcard/koreader first if you have customised the same files.

.PARAMETER Adb
  Path to adb.exe. Default: adb from PATH, else the Android SDK platform-tools.

.PARAMETER Serial
  Device serial (adb -s). Needed only when several devices are connected.

.PARAMETER SkipOrnaments
  Do not push the 48 ornament PNGs (Bookshelf catgirl pack).
#>
[CmdletBinding()]
param(
    [string]$Adb,
    [string]$Serial,
    [switch]$SkipOrnaments
)

$ErrorActionPreference = 'Stop'
$kit = $PSScriptRoot

if (-not $Adb) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $Adb = $cmd.Source }
    else {
        $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
        $Adb = Join-Path $sdk 'platform-tools\adb.exe'
    }
}
if (-not (Test-Path $Adb)) { throw "adb not found. Pass -Adb <path to adb.exe>." }

$adbArgs = @()
if ($Serial) { $adbArgs += @('-s', $Serial) }

function Invoke-Adb {
    & $Adb @adbArgs @args
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($args -join ' ')" }
}

Invoke-Adb get-state | Out-Null
$root = '/sdcard/koreader'
& $Adb @adbArgs shell "test -d $root"
if ($LASTEXITCODE -ne 0) {
    throw "$root not found on the device. Start KOReader once first so it creates its folder, then run this script again."
}
Write-Host "Target: $root (KOReader must have been started once on the device)"

Write-Host "-> patches/"
Invoke-Adb shell "mkdir -p $root/patches"
Get-ChildItem (Join-Path $kit 'patches') -File | ForEach-Object {
    Invoke-Adb push $_.FullName "$root/patches/$($_.Name)"
}

Write-Host "-> defaults.custom.lua"
Invoke-Adb push (Join-Path $kit 'defaults.custom.lua') "$root/defaults.custom.lua"

Write-Host "-> Bookends settings and presets"
Invoke-Adb shell "mkdir -p $root/settings/bookends_presets"
Invoke-Adb push (Join-Path $kit 'settings\bookends.lua') "$root/settings/bookends.lua"
Get-ChildItem (Join-Path $kit 'settings\bookends_presets') -File | ForEach-Object {
    Invoke-Adb push $_.FullName "$root/settings/bookends_presets/$($_.Name)"
}

if (-not $SkipOrnaments) {
    Write-Host "-> catgirl ornament pack"
    $dest = "$root/settings/bookshelf/ornaments"
    Invoke-Adb shell "mkdir -p $dest"
    Invoke-Adb push (Join-Path $kit 'bookshelf-ornaments\Кошкодевочки') $dest
}

$partial = Join-Path $kit 'settings.reader.partial.lua'
Write-Host ""
Write-Host "Done. settings.reader.lua was NOT modified."
Write-Host "To apply the recommended KOReader settings, merge keys from:"
Write-Host "  $partial"
Write-Host "into /sdcard/koreader/settings.reader.lua with KOReader closed"
Write-Host "(pull it with adb, edit, push back; see koreader-kit/README.md)."
Write-Host "Restart KOReader to load the patch, defaults and Bookends presets."
