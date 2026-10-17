param(
    [Parameter(Mandatory)][string]$Tv,
    [string]$Adb='adb',
    [int]$Port=5037,
    [ValidateRange(1,3)][int]$Cycles=2,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$package='dev.zane.pretttv'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
function Adb-Run([string[]]$Arguments) {
    $result=& $Adb -P $Port -s $Tv @Arguments 2>&1
    if($LASTEXITCODE -ne 0){throw 'ADB failed during motion measurement'}
    return $result
}
function Assert-Home {
    $state=(Adb-Run @('shell','dumpsys','activity','activities')) -join "`n"
    if($state -notmatch 'topResumedActivity=.*dev\.zane\.pretttv/'){throw 'Another app became active; stopping TV input.'}
}
function Traverse {
    # Direction/Back only. No media launches, menus, installs or settings changes.
    foreach($segment in @('20','22 22 22 22 22 22 22 22 22 22','21 21 21 21 21 21 21 21 21 21',
        '20 20 20 20 20 20','22 22 22 22 22 22 22 22','21 21 21 21 21 21 21 21','19 19 19 19 19 19','4 4')) {
        Assert-Home
        # The fixed integer key list is authored above, never taken from device/user text.
        $command='for key in '+$segment+'; do input keyevent "$key"; sleep 0.12; done'
        Adb-Run @('shell',$command) | Out-Null
    }
    Start-Sleep -Milliseconds 500
    Assert-Home
}
Assert-Home
$state=(Adb-Run @('shell','dumpsys','activity',$package)) -join "`n"
if($state -notmatch 'prettTV layout=BALANCED'){throw 'This comparable route requires C Balanced'}
$pidBefore=((Adb-Run @('shell','pidof',$package)) -join '').Trim()
Adb-Run @('shell','input','keyevent','3') | Out-Null
Start-Sleep -Seconds 2
Traverse # Warm artwork and glyph caches before recording.
Adb-Run @('shell','dumpsys','gfxinfo',$package,'reset') | Out-Null
$watch=[Diagnostics.Stopwatch]::StartNew()
for($i=0;$i -lt $Cycles;$i++){Traverse}
$watch.Stop()
$gfx=Adb-Run @('shell','dumpsys','gfxinfo',$package,'framestats')
$gfx | Set-Content -LiteralPath (Join-Path $OutputDirectory 'gfxinfo.txt')
$state=Adb-Run @('shell','dumpsys','activity',$package,'--viewport-diagnostics')
$state | Set-Content -LiteralPath (Join-Path $OutputDirectory 'final-state.txt')
$pidAfter=((Adb-Run @('shell','pidof',$package)) -join '').Trim()
if($pidBefore -ne $pidAfter){throw 'Home process restarted during measurement'}
if(($state -join "`n") -match 'prettTV focus=null'){throw 'Home lost focus'}
$gfx | Select-String '^Total frames rendered:|^Janky frames:|^50th percentile:|^90th percentile:|^95th percentile:|^99th percentile:|^Number Slow UI thread:|^Number Slow issue draw commands:'
"Completed $Cycles measured routes in $($watch.Elapsed.TotalSeconds.ToString('F1')) seconds; same Home process."
