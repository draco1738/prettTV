param(
    [Parameter(Mandatory)][string]$Tv,
    [string]$Adb='adb',
    [int]$Port=5037,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$target='dev.zane.pretttv.stability'
$runner="$target.test"
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
function Adb-Run([string[]]$Arguments) {
    $result=& $Adb -P $Port -s $Tv @Arguments 2>&1
    if($LASTEXITCODE -ne 0){throw "ADB failed: $($Arguments -join ' ')"}
    return $result
}
foreach($package in @($target,$runner)) {
    if(((Adb-Run @('shell','pm','list','packages',$package)) -join '') -match [regex]::Escape($package)) {
        throw "Test package already exists; refusing to replace or delete it: $package"
    }
}
$installed=New-Object System.Collections.Generic.List[string]
try {
    Adb-Run @('install',(Join-Path $repo 'launcher/app/build/outputs/apk/stability/app-stability.apk')) | Out-Null
    $installed.Add($target)
    Adb-Run @('install',(Join-Path $repo 'launcher/app/build/outputs/apk/androidTest/stability/app-stability-androidTest.apk')) | Out-Null
    $installed.Add($runner)
    $result=Adb-Run @('shell','am','instrument','-w','-r',"$runner/fi.jommi.rivi.StabilityInstrumentation")
    $result | Set-Content -LiteralPath (Join-Path $OutputDirectory 'android-fault-tests.txt')
    $summary=[regex]::Match(($result -join "`n"),'STABILITY PASS: (\d+) Android fault tests')
    if(-not $summary.Success -or [int]$summary.Groups[1].Value -lt 25){throw 'Android fault tests failed or incomplete; inspect android-fault-tests.txt'}
    "All $($summary.Groups[1].Value) Android fault tests passed."
} finally {
    for($i=$installed.Count-1;$i -ge 0;$i--){Adb-Run @('uninstall',$installed[$i]) | Out-Null}
}
