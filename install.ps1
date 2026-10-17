# Installs prettTV and its included RBTV+ companion. Keeps existing RBTV installations.
[CmdletBinding()]
param(
    [switch]$NoBuild,
    [switch]$LauncherOnly,
    [string]$Tv = $env:TV,
    [string]$Adb = 'adb',
    [int]$AdbPort = 5037,
    [string]$CompanionApk = $env:RBTV_APK
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
function Invoke-Adb([string[]]$Arguments) {
    $output = & $Adb -P $AdbPort -s $Tv @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($output -join [Environment]::NewLine)" }
    return $output
}
if (-not $Tv) {
    $devices = @(& $Adb -P $AdbPort devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($devices.Count -ne 1) { throw 'Use -Tv with one serial from adb devices.' }
    $Tv = $devices[0]
}
if ((Invoke-Adb -Arguments @('shell','pm','list','features') | Out-String) -notmatch 'android.software.leanback') { throw 'The selected device is not Android TV.' }
$apk = Join-Path $PSScriptRoot 'prettTV-0.1.7.apk'
if (-not (Test-Path -LiteralPath $apk)) {
    $apk = Join-Path $PSScriptRoot 'launcher/app/build/outputs/apk/release/app-release.apk'
    if (-not $NoBuild) {
        & ./launcher/gradlew.bat -p launcher :app:assembleRelease
        if ($LASTEXITCODE -ne 0) { throw 'Build failed. JDK 17 and Android SDK 34 are required.' }
    }
}
if (-not (Test-Path -LiteralPath $apk)) { throw "Launcher APK missing: $apk" }
$rbtv = $null
if (-not $LauncherOnly) {
    if ((Invoke-Adb -Arguments @('shell','pm','list','packages','com.rblive.app') | Out-String) -match 'package:') {
        Write-Output 'RBTV+ is already installed; keeping its version and data.'
    } else {
        if ($CompanionApk) {
            $rbtv = $CompanionApk
        } else {
            $rbtv = Join-Path $PSScriptRoot 'RBTV-3.0.320.apk'
            if (-not (Test-Path -LiteralPath $rbtv)) {
                $cache = Join-Path $PSScriptRoot 'results/companions'
                New-Item -ItemType Directory -Path $cache -Force | Out-Null
                $rbtv = Join-Path $cache 'RBTV-3.0.320.apk'
                if (-not (Test-Path -LiteralPath $rbtv)) {
                    Invoke-WebRequest -Uri 'https://github.com/draco1738/prettTV/releases/download/v0.1.7/RBTV-3.0.320.apk' -OutFile "$rbtv.download" -TimeoutSec 180
                    Move-Item -LiteralPath "$rbtv.download" -Destination $rbtv -Force
                }
            }
        }
        $expected = '5e7f0b4eb571d4c662678cad8cd18254ec8abd55ead230d2f8e7ab684ff8f279'
        if ((Get-FileHash -LiteralPath $rbtv -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'RBTV+ checksum mismatch. No APK was installed.' }
    }
}
$output = Invoke-Adb -Arguments @('install','-r',$apk)
if (($output -join '') -notmatch 'Success') { throw "prettTV install failed: $output" }
Write-Output 'prettTV installed.'
if ($rbtv) {
    $output = Invoke-Adb -Arguments @('install','-r',$rbtv)
    if (($output -join '') -notmatch 'Success') { throw "prettTV installed, but RBTV+ installation failed: $output" }
    Write-Output 'Included RBTV+ installed.'
}
Invoke-Adb -Arguments @('shell','am','start','-n','dev.zane.pretttv/fi.jommi.rivi.MainActivity')
Write-Output 'Choose prettTV under Settings > Choose default Home app. Existing Home apps and Android special-access permissions are unchanged.'
