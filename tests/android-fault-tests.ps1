param(
    [Parameter(Mandatory)][string]$Tv,
    [string]$Adb='adb',
    [int]$Port=5037,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateSet('All','Lightweight')][string]$Suite='All'
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
$fixture='fi.jommi.rivi.testapp'
foreach($package in @($target,$runner,$fixture)) {
    if((Adb-Run @('shell','pm','list','packages',$package)) -contains ('package:'+$package)) {
        throw "Test package already exists; refusing to replace or delete it: $package"
    }
}
$installed=New-Object System.Collections.Generic.List[string]
try {
    Adb-Run @('install',(Join-Path $repo 'launcher/testapp/build/outputs/apk/debug/testapp-debug.apk')) | Out-Null
    $installed.Add($fixture)
    Adb-Run @('install',(Join-Path $repo 'launcher/app/build/outputs/apk/stability/app-stability.apk')) | Out-Null
    $installed.Add($target)
    Adb-Run @('install',(Join-Path $repo 'launcher/app/build/outputs/apk/androidTest/stability/app-stability-androidTest.apk')) | Out-Null
    $installed.Add($runner)
    $arguments=@('shell','am','instrument','-w','-r')
    if($Suite -eq 'Lightweight'){$arguments+=@('-e','lightweightOnly','true')}
    $arguments+="$runner/fi.jommi.rivi.StabilityInstrumentation"
    $result=Adb-Run $arguments
    $result | Set-Content -LiteralPath (Join-Path $OutputDirectory 'android-fault-tests.txt')
    $pattern=if($Suite -eq 'Lightweight'){'LIGHTWEIGHT PASS: (\d+) Android tests'}else{'STABILITY PASS: (\d+) Android fault tests'}
    $minimum=if($Suite -eq 'Lightweight'){23}else{25}
    $summary=[regex]::Match(($result -join "`n"),$pattern)
    if(-not $summary.Success -or [int]$summary.Groups[1].Value -lt $minimum -or ($result -join "`n") -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Android fault tests failed or incomplete; inspect android-fault-tests.txt'}
    "All $($summary.Groups[1].Value) Android fault tests passed."
} finally {
    for($i=$installed.Count-1;$i -ge 0;$i--){
        Adb-Run @('uninstall',$installed[$i]) | Out-Null
        if((Adb-Run @('shell','pm','list','packages',$installed[$i])) -contains ('package:'+$installed[$i])){throw 'Isolated test package remains after cleanup'}
    }
}
