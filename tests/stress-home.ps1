param(
    [Parameter(Mandatory)][string]$Tv,
    [string]$Adb = 'adb',
    [int]$Port = 5037,
    [ValidateRange(1,50)][int]$Cycles = 15,
    [ValidateRange(0,10)][int]$RestartCycles = 5,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$package='dev.zane.pretttv'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
function Adb-Run([string[]]$Arguments) {
    $result=& $Adb -P $Port -s $Tv @Arguments 2>&1
    if($LASTEXITCODE -ne 0){throw "ADB failed: $($Arguments -join ' ')"}
    return $result
}
function Assert-Home {
    $state=(Adb-Run @('shell','dumpsys','activity','activities')) -join "`n"
    if($state -notmatch 'topResumedActivity=.*dev\.zane\.pretttv/'){throw 'Another app became active; stopping TV inputs.'}
}
function Summary {
    return @(Adb-Run @('shell','dumpsys','activity',$package,'--viewport-diagnostics') | Where-Object {$_ -match '^prettTV|^VIEWPORT'})
}
function Wait-Home {
    $deadline=[DateTime]::UtcNow.AddSeconds(15)
    do {
        $state=Summary
        if(($state -join "`n") -match 'prettTV focus=feature visible=true'){Assert-Home;return $state}
        Start-Sleep -Milliseconds 200
    } while([DateTime]::UtcNow -lt $deadline)
    throw 'Home did not become ready within 15 seconds.'
}
$resolver=(Adb-Run @('shell','cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')) -join "`n"
if($resolver -notmatch 'dev.zane.pretttv/fi.jommi.rivi.MainActivity'){throw 'prettTV must already be the working default Home.'}
Assert-Home
$results=New-Object System.Collections.Generic.List[object]
try {
    for($cycle=1;$cycle -le $Cycles;$cycle++) {
        Assert-Home
        Adb-Run @('shell','input','keyevent','3') | Out-Null
        Wait-Home | Out-Null
        $before=((Adb-Run @('shell','pidof',$package)) -join '').Trim()
        # D-pad only: traverse long rows without opening media, changing settings, or choosing a menu action.
        $keys=@('20')*9+@('22')*16+@('21')*7+@('19')*4+@('22')*4+@('20')*3
        Adb-Run (@('shell','input','keyevent')+$keys) | Out-Null
        Assert-Home
        $state=Summary
        if(($state -join "`n") -match 'prettTV focus=null'){throw "Lost focus in navigation cycle $cycle"}
        Adb-Run @('shell','input','keyevent','4') | Out-Null
        Wait-Home | Out-Null
        Adb-Run @('shell','input','keyevent','21','22') | Out-Null
        Wait-Home | Out-Null
        $after=((Adb-Run @('shell','pidof',$package)) -join '').Trim()
        if($before -ne $after){throw "Process restarted unexpectedly in cycle $cycle"}
        $memory=(Adb-Run @('shell','dumpsys','meminfo',$package) | Select-String 'TOTAL PSS:').Line
        $results.Add([pscustomobject]@{test='navigation';cycle=$cycle;pid=$after;memory=$memory;state=$state})
        "Navigation cycle $cycle/$Cycles passed"
    }
    for($cycle=1;$cycle -le $RestartCycles;$cycle++) {
        Assert-Home
        Adb-Run @('shell','am','force-stop',$package) | Out-Null
        $timer=[Diagnostics.Stopwatch]::StartNew()
        Adb-Run @('shell','input','keyevent','3') | Out-Null
        $state=Wait-Home
        $results.Add([pscustomobject]@{test='cold-start';cycle=$cycle;readyMilliseconds=$timer.ElapsedMilliseconds;state=$state})
        "Cold start $cycle/$RestartCycles passed"
    }
} finally {
    $results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'stress-results.json')
}
"Passed $Cycles navigation cycles and $RestartCycles cold starts. No streaming app was launched."
