param(
    [Parameter(Mandatory)][string]$Tv,
    [string]$Adb='adb',
    [int]$Port=5037,
    [ValidateRange(1,50)][int]$Cycles=12,
    [ValidateRange(0,50)][int]$RestartCycles=9,
    [ValidateSet('CINEMA','QUIET','BALANCED')][string]$Layout,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$package='dev.zane.pretttv'
$outputPath=[IO.Path]::GetFullPath($OutputDirectory)
if(-not [IO.Path]::IsPathRooted($OutputDirectory) -or $outputPath -notmatch '^E:\\' -or -not (Test-Path -LiteralPath 'E:\')){throw 'An absolute output directory on the connected E: drive is required.'}
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$results=New-Object System.Collections.Generic.List[object]
$status='RUNNING'; $failure=$null; $actions=0; $observedLayout=''; $watch=[Diagnostics.Stopwatch]::StartNew()
function Adb-Run([string[]]$Arguments) {
    $result=& $Adb -P $Port -s $Tv @Arguments 2>&1
    if($LASTEXITCODE -ne 0){throw "ADB failed: $($Arguments -join ' ')"}
    return $result
}
function Foreground {
    $state=(Adb-Run @('shell','dumpsys','activity','activities')) -join "`n"
    return [regex]::Match($state,'(?:topResumedActivity|mResumedActivity)=.*? ([A-Za-z0-9_.]+)/').Groups[1].Value
}
function Assert-Home { if((Foreground) -ne $package){throw 'Another app became active; stopping TV inputs.'} }
function Select-HomeBlock([string]$Dump,[switch]$AllowStopped) {
    $blocks=@([regex]::Matches($Dump,'(?ms)^[ \t]*prettTV layout=.*?(?=^[ \t]*prettTV layout=|^[ \t]*ACTIVITY\s|\z)') | ForEach-Object {($_.Value -split '\r?\n' | ForEach-Object {$_.TrimStart()}) -join "`n"})
    $active=@($blocks | Where-Object {$_ -match '(?m)^prettTV focus=[^\r\n]+ visible=true(?:[ \t\r]|$)'})
    # Android can retain stopped MainActivity instances in the same dumpsys response.
    # Keep layout, focus and viewport together from the visible instance.
    if($active.Count){return $active[-1]}
    if($AllowStopped -and $blocks.Count){return $blocks[-1]}
    return $null
}
function Summary {
    $dump=(Adb-Run @('shell','dumpsys','activity',$package,'--viewport-diagnostics')) -join "`n"
    $block=Select-HomeBlock $dump
    if(-not $block){return @()}
    return @($block -split '\r?\n' | Where-Object {$_ -match '^prettTV|^VIEWPORT'})
}
function Assert-Focus($State) {
    $focus=[regex]::Match(($State -join "`n"),'prettTV focus=([^\r\n]+) visible=true').Groups[1].Value
    if([string]::IsNullOrWhiteSpace($focus) -or $focus -eq 'null'){throw 'Visible Home has no valid focused view.'}
    return $focus
}
function Wait-Home([switch]$AtStart) {
    $deadline=[DateTime]::UtcNow.AddSeconds(20)
    do {
        $state=Summary; $text=$state -join "`n"
        $focus=[regex]::Match($text,'prettTV focus=([^\r\n]+) visible=true').Groups[1].Value
        $layoutNow=[regex]::Match($text,'prettTV layout=(\w+)').Groups[1].Value
        if($Layout -and $layoutNow -and $layoutNow -ne $Layout){throw "Expected $Layout but Home reports $layoutNow. Select the layout before running this check."}
        $anchor=if($layoutNow -eq 'QUIET'){$focus -like 'favorite-*'}else{$focus -eq 'feature'}
        if($focus -and $focus -ne 'null' -and (-not $AtStart -or $anchor)){Assert-Home;return $state}
        Start-Sleep -Milliseconds 200
    } while([DateTime]::UtcNow -lt $deadline)
    throw 'Home did not become ready with the expected layout anchor within 20 seconds.'
}
function Key([string]$Code) {
    Assert-Home
    Adb-Run @('shell','input','keyevent',$Code) | Out-Null
    $script:actions++
    Assert-Home
}
try {
    $resolver=(Adb-Run @('shell','cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')) -join "`n"
    if($resolver -notmatch 'dev.zane.pretttv/fi.jommi.rivi.MainActivity'){throw 'prettTV must already be the working default Home.'}
    Assert-Home
    $initial=Wait-Home
    $observedLayout=[regex]::Match(($initial -join "`n"),'prettTV layout=(\w+)').Groups[1].Value
    for($cycle=1;$cycle -le $Cycles;$cycle++) {
        Key '3'; Wait-Home -AtStart | Out-Null
        $before=((Adb-Run @('shell','pidof',$package)) -join '').Trim()
        if(-not $before){throw 'Home process is missing.'}
        # 43 fixed direction actions per cycle. No OK/media/settings actions.
        $keys=@('20')*9+@('22')*16+@('21')*7+@('19')*4+@('22')*4+@('20')*3
        foreach($code in $keys){Key $code}
        $state=Summary; Assert-Focus $state | Out-Null
        Key '4'; Wait-Home -AtStart | Out-Null
        Key '21'; Key '22'; Wait-Home -AtStart | Out-Null
        $after=((Adb-Run @('shell','pidof',$package)) -join '').Trim()
        if($before -ne $after){throw "Process restarted unexpectedly in navigation cycle $cycle"}
        $memory=(Adb-Run @('shell','dumpsys','meminfo',$package) | Select-String 'TOTAL PSS:').Line
        $results.Add([pscustomobject]@{test='navigation';cycle=$cycle;layout=$observedLayout;pid=$after;actions=$keys.Count+4;memory=$memory;state=$state})
        "Navigation cycle $cycle/$Cycles passed ($observedLayout)"
    }
    for($cycle=1;$cycle -le $RestartCycles;$cycle++) {
        Assert-Home
        Adb-Run @('shell','am','force-stop',$package) | Out-Null
        $timer=[Diagnostics.Stopwatch]::StartNew()
        $foregroundNow=Foreground
        if($foregroundNow -and $foregroundNow -ne $package){throw "Another app ($foregroundNow) became active during cold start; stopping."}
        # Explicit launcher restart; no disabling Home and no unguarded key event.
        Adb-Run @('shell','am','start','-n',"$package/fi.jommi.rivi.MainActivity",'-a','android.intent.action.MAIN','-c','android.intent.category.HOME') | Out-Null
        $state=Wait-Home
        $results.Add([pscustomobject]@{test='cold-start';cycle=$cycle;layout=$observedLayout;readyMilliseconds=$timer.ElapsedMilliseconds;state=$state})
        "Cold start $cycle/$RestartCycles passed"
    }
    $status='PASS'
} catch {
    $failure=$_.Exception.Message; $status='FAILED'; throw
} finally {
    $watch.Stop()
    [pscustomobject]@{status=$status;failure=$failure;layout=$observedLayout;requestedCycles=$Cycles;requestedColdStarts=$RestartCycles;inputActions=$actions;elapsedSeconds=$watch.Elapsed.TotalSeconds;results=@($results.ToArray())} |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outputPath 'stress-results.json') -Encoding UTF8
}
"PASS: $actions guarded inputs, $Cycles navigation cycles, and $RestartCycles cold starts in $observedLayout."
