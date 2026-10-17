param(
    [Parameter(Mandatory)][string]$Tv,
    [string]$Adb='adb',
    [int]$Port=5037,
    [Parameter(Mandatory)][string]$FixtureApk,
    [ValidateRange(1,30)][int]$Cycles=10,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$homePackage='dev.zane.pretttv'
$fixture='fi.jommi.rivi.testapp'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
function Adb-Run([string[]]$Arguments) {
    $result=& $Adb -P $Port -s $Tv @Arguments 2>&1
    if($LASTEXITCODE -ne 0){throw "ADB failed: $($Arguments -join ' ')"}
    return $result
}
function Assert-Foreground([string]$Package) {
    $state=(Adb-Run @('shell','dumpsys','activity','activities')) -join "`n"
    if($state -notmatch ('topResumedActivity=.*'+[regex]::Escape($Package)+'/')){throw 'Unexpected foreground app; stopping TV inputs.'}
}
function State {
    $dump=(Adb-Run @('shell','dumpsys','activity',$homePackage,'--viewport-diagnostics')) -join "`n"
    $focus=[regex]::Match($dump,'prettTV focus=(\S+) visible=(\w+)')
    return [pscustomobject]@{focus=$focus.Groups[1].Value;visible=$focus.Groups[2].Value;viewport=[regex]::Match($dump,'VIEWPORT[^\r\n]+').Value}
}
function Wait-Visible([string]$Visible) {
    $deadline=[DateTime]::UtcNow.AddSeconds(5)
    do { $state=State; if($state.visible -eq $Visible){return $state};Start-Sleep -Milliseconds 100 } while([DateTime]::UtcNow -lt $deadline)
    throw "Home did not reach visible=$Visible"
}
Assert-Foreground $homePackage
if(((Adb-Run @('shell','pm','list','packages',$fixture)) -join '') -match [regex]::Escape($fixture)) {throw 'The fixture app already exists; refusing to replace or remove it.'}
$installed=$false
$results=New-Object System.Collections.Generic.List[object]
try {
    Adb-Run @('install',$FixtureApk) | Out-Null
    $installed=$true
    Start-Sleep -Seconds 1
    for($cycle=1;$cycle -le $Cycles;$cycle++) {
        Assert-Foreground $homePackage
        $before=State
        $pidBefore=((Adb-Run @('shell','pidof',$homePackage)) -join '').Trim()
        Adb-Run @('shell','am','start','-n',"$fixture/.MainActivity") | Out-Null
        Wait-Visible 'false' | Out-Null
        Assert-Foreground $fixture
        Adb-Run @('shell','input','keyevent','4') | Out-Null
        $after=Wait-Visible 'true'
        Assert-Foreground $homePackage
        if($after.focus -ne $before.focus -or $after.viewport -ne $before.viewport){throw "Return position changed in cycle $cycle"}
        $pidAfter=((Adb-Run @('shell','pidof',$homePackage)) -join '').Trim()
        if($pidBefore -ne $pidAfter){throw 'Home process restarted during an ordinary app return'}
        $results.Add([pscustomobject]@{cycle=$cycle;pid=$pidAfter;focus=$after.focus;viewport=$after.viewport})
        "Stop/return cycle $cycle/$Cycles passed"
    }
} finally {
    $results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'lifecycle-results.json')
    if($installed){Adb-Run @('uninstall',$fixture) | Out-Null}
}
"Passed $Cycles real stop/return cycles; temporary lifecycle app removed."
