param(
    [Parameter(Mandatory)][string]$Tv,
    [string]$Adb='adb',
    [int]$Port=5037,
    [ValidateRange(3600,43200)][int]$DurationSeconds=3600,
    [ValidateRange(30,600)][int]$SampleIntervalSeconds=120,
    [ValidateSet('CINEMA','QUIET','BALANCED')][string]$Layout,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$package='dev.zane.pretttv'
$outputPath=[IO.Path]::GetFullPath($OutputDirectory)
if(-not [IO.Path]::IsPathRooted($OutputDirectory) -or $outputPath -notmatch '^E:\\' -or -not (Test-Path -LiteralPath 'E:\')){throw 'An absolute output directory on the connected E: drive is required.'}
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$rawPath=Join-Path $outputPath 'samples'
New-Item -ItemType Directory -Path $rawPath -Force | Out-Null
$samples=New-Object System.Collections.Generic.List[object]
$watch=[Diagnostics.Stopwatch]::StartNew()
$status='RUNNING'; $failure=$null; $actions=0; $cycles=0; $expectedProcess=''; $observedLayout=''
$startedUtc=[DateTime]::UtcNow.ToString('o'); $nextSample=0.0
$observationGapLimit=[math]::Max(2*$SampleIntervalSeconds,180)
$lastObservationSeconds=0.0; $maxObservationGapSeconds=0.0
$minimumSamples=[int][math]::Floor([math]::Floor($DurationSeconds/$SampleIntervalSeconds)*0.8)+2
function Test-SoakDuration([double]$ElapsedSeconds,[int]$RequestedSeconds) {
    return $RequestedSeconds -ge 3600 -and $ElapsedSeconds -ge $RequestedSeconds
}
function Assert-Coverage([double]$ElapsedSeconds) {
    $gap=[math]::Max(0,$ElapsedSeconds-$script:lastObservationSeconds)
    $script:maxObservationGapSeconds=[math]::Max($script:maxObservationGapSeconds,$gap)
    if($gap -gt $script:observationGapLimit){
        throw "INTERRUPTED: Observation gap of $([math]::Round($gap,1)) seconds exceeds $($script:observationGapLimit) seconds; the soak was not continuously observed."
    }
}
function Record-Observation([double]$ElapsedSeconds) {
    Assert-Coverage $ElapsedSeconds
    $script:lastObservationSeconds=$ElapsedSeconds
}
function Adb-Run([string[]]$Arguments) {
    # ArgumentList preserves exact arguments; stdout/stderr drain concurrently to prevent deadlock.
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$Adb; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    foreach($argument in (@('-P',[string]$Port,'-s',$Tv)+$Arguments)){$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new(); $process.StartInfo=$info
    $commandWatch=[Diagnostics.Stopwatch]::StartNew()
    try {
        if(-not $process.Start()){throw 'Could not start the ADB command.'}
        $standardOut=$process.StandardOutput.ReadToEndAsync(); $standardError=$process.StandardError.ReadToEndAsync()
        if(-not $process.WaitForExit(30000)){
            try {$process.Kill($true);$null=$process.WaitForExit(2000)}catch{ }
            Assert-Coverage $watch.Elapsed.TotalSeconds
            throw "ADB command exceeded 30 seconds: $($Arguments -join ' ')"
        }
        Assert-Coverage $watch.Elapsed.TotalSeconds
        $remaining=[int][math]::Max(1,30000-$commandWatch.ElapsedMilliseconds)
        if(-not [Threading.Tasks.Task]::WhenAll([Threading.Tasks.Task[]]@($standardOut,$standardError)).Wait($remaining)){
            throw "ADB output exceeded the 30-second command limit: $($Arguments -join ' ')"
        }
        $output=$standardOut.GetAwaiter().GetResult(); $errorText=$standardError.GetAwaiter().GetResult()
        if($process.ExitCode -ne 0){throw "ADB failed: $($Arguments -join ' '): $errorText"}
        return $output -split '\r?\n' | Where-Object {$_ -ne ''}
    } finally {
        try {if(-not $process.HasExited){$process.Kill($true)}}catch{ }
        $process.Dispose()
    }
}
function Assert-Home {
    $state=(Adb-Run @('shell','dumpsys','activity','activities')) -join "`n"
    if($state -notmatch '(?:topResumedActivity|mResumedActivity)=.* dev\.zane\.pretttv/'){
        throw 'INTERRUPTED: Another app became active; stopping immediately without further input.'
    }
    Record-Observation $watch.Elapsed.TotalSeconds
}
function Select-HomeBlock([string]$Dump,[switch]$AllowStopped) {
    $blocks=@([regex]::Matches($Dump,'(?ms)^[ \t]*prettTV layout=.*?(?=^[ \t]*prettTV layout=|^[ \t]*ACTIVITY\s|\z)') | ForEach-Object {($_.Value -split '\r?\n' | ForEach-Object {$_.TrimStart()}) -join "`n"})
    $active=@($blocks | Where-Object {$_ -match '(?m)^prettTV focus=[^\r\n]+ visible=true(?:[ \t\r]|$)'})
    # Android can retain stopped MainActivity instances in the same dumpsys response.
    # Keep layout, focus and viewport together from the visible instance.
    if($active.Count){return $active[-1]}
    if($AllowStopped -and $blocks.Count){return $blocks[-1]}
    return $null
}
function State {
    $raw=(Adb-Run @('shell','dumpsys','activity',$package,'--viewport-diagnostics','--feed-diagnostics')) -join "`n"
    $dump=Select-HomeBlock $raw
    if(-not $dump){throw 'No visible MainActivity diagnostic block was found.'}
    $focus=[regex]::Match($dump,'prettTV focus=([^\r\n]+) visible=true').Groups[1].Value
    if(-not $focus -or $focus -eq 'null'){throw 'Visible Home lost its focused view.'}
    $layoutNow=[regex]::Match($dump,'prettTV layout=(\w+)').Groups[1].Value
    if($Layout -and $layoutNow -ne $Layout){throw "Unexpected layout change: expected $Layout, found $layoutNow."}
    return [pscustomobject]@{focus=$focus;layout=$layoutNow;dump=$dump}
}
function Assert-Process {
    $homeProcess=((Adb-Run @('shell','pidof',$package)) -join '').Trim()
    if(-not $homeProcess -or ($script:expectedProcess -and $homeProcess -ne $script:expectedProcess)){
        throw 'Home process disappeared or restarted unexpectedly during soak.'
    }
    return $homeProcess
}
function Key([string]$Code) {
    Assert-Home
    Adb-Run @('shell','input','keyevent',$Code) | Out-Null
    $script:actions++
    Assert-Home
}
function Sample([string]$Label) {
    Assert-Home
    $homeProcess=Assert-Process
    $state=State
    $memory=(Adb-Run @('shell','dumpsys','meminfo',$package)) -join "`n"
    $gfx=(Adb-Run @('shell','dumpsys','gfxinfo',$package,'framestats')) -join "`n"
    $prefix=('{0:D4}-{1}' -f $samples.Count,$Label)
    $memory | Set-Content -LiteralPath (Join-Path $rawPath "$prefix-memory.txt") -Encoding UTF8
    $gfx | Set-Content -LiteralPath (Join-Path $rawPath "$prefix-frames.txt") -Encoding UTF8
    $state.dump | Set-Content -LiteralPath (Join-Path $rawPath "$prefix-state.txt") -Encoding UTF8
    $pssMatch=[regex]::Match($memory,'TOTAL PSS:\s*(\d+)')
    if(-not $pssMatch.Success){$pssMatch=[regex]::Match($memory,'(?m)^\s*TOTAL\s+(\d+)')}
    $totalPss=if($pssMatch.Success){[long]$pssMatch.Groups[1].Value}else{$null}
    $frameMatch=[regex]::Match($gfx,'Total frames rendered:\s*(\d+)')
    $jankMatch=[regex]::Match($gfx,'Janky frames:\s*(\d+)\s*\(([^)]+)\)')
    $tailMatch=[regex]::Match($gfx,'99th percentile:\s*([^\r\n]+)')
    $samples.Add([pscustomobject]@{
        elapsedSeconds=[math]::Round($watch.Elapsed.TotalSeconds,2);label=$Label;pid=$homeProcess;layout=$state.layout;focus=$state.focus
        totalPssKb=$totalPss;frames=$frameMatch.Groups[1].Value;jankyFrames=$jankMatch.Groups[1].Value;jankPercent=$jankMatch.Groups[2].Value;frame99=$tailMatch.Groups[1].Value
    })
    if($samples.Count -eq 1 -or $samples.Count % 5 -eq 0 -or $Label -eq 'final'){
        try { Adb-Run @('shell','dumpsys','activity','exit-info',$package) | Set-Content -LiteralPath (Join-Path $rawPath "$prefix-exit-info.txt") -Encoding UTF8 }
        catch { $_.Exception.Message | Set-Content -LiteralPath (Join-Path $rawPath "$prefix-exit-info-unavailable.txt") -Encoding UTF8 }
    }
    # Incremental checkpoints survive host termination. These logs remain private on E:.
    $samples.ToArray() | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputPath 'soak-samples.json') -Encoding UTF8
    Assert-Home
}
function Guarded-Idle([int]$Seconds) {
    $until=$watch.Elapsed.TotalSeconds+$Seconds
    while($watch.Elapsed.TotalSeconds -lt $until -and $watch.Elapsed.TotalSeconds -lt $DurationSeconds){
        Start-Sleep -Milliseconds 1000
        Assert-Home
        Maybe-Sample
    }
}
function Maybe-Sample {
    if($watch.Elapsed.TotalSeconds -ge $script:nextSample -and $watch.Elapsed.TotalSeconds -lt $DurationSeconds){
        Sample 'periodic'; $script:nextSample=$watch.Elapsed.TotalSeconds+$SampleIntervalSeconds
        "Soak: $([int]$watch.Elapsed.TotalSeconds)/$DurationSeconds seconds, $cycles bursts, $actions guarded inputs"
    }
}
function Memory-Trend {
    $valid=@($samples | Where-Object {$null -ne $_.totalPssKb})
    if($valid.Count -lt 2){return [pscustomobject]@{available=$false;reason='Fewer than two memory samples'}}
    $quarter=[math]::Max(1,[int][math]::Floor($valid.Count/4))
    $early=($valid | Select-Object -First $quarter | Measure-Object -Property totalPssKb -Average).Average
    $late=($valid | Select-Object -Last $quarter | Measure-Object -Property totalPssKb -Average).Average
    $meanX=($valid | Measure-Object -Property elapsedSeconds -Average).Average
    $meanY=($valid | Measure-Object -Property totalPssKb -Average).Average
    $numerator=0.0; $denominator=0.0
    foreach($s in $valid){$dx=$s.elapsedSeconds-$meanX;$numerator+=$dx*($s.totalPssKb-$meanY);$denominator+=$dx*$dx}
    $slope=if($denominator -gt 0){[math]::Round(60*$numerator/$denominator,2)}else{0}
    return [pscustomobject]@{available=$true;samples=$valid.Count;firstPssKb=$valid[0].totalPssKb;lastPssKb=$valid[-1].totalPssKb;earlyQuarterMeanKb=[math]::Round($early,2);lateQuarterMeanKb=[math]::Round($late,2);quarterDifferenceKb=[math]::Round($late-$early,2);linearTrendKbPerMinute=$slope;interpretation='Review sustained growth with the raw samples; no exact RSS/PSS threshold is asserted.'}
}
try {
    Assert-Home
    $initial=State; $observedLayout=$initial.layout
    $expectedProcess=Assert-Process
    Adb-Run @('shell','dumpsys','gfxinfo',$package,'reset') | Out-Null
    Sample 'initial'; $nextSample=$watch.Elapsed.TotalSeconds+$SampleIntervalSeconds
    while($watch.Elapsed.TotalSeconds -lt $DurationSeconds){
        Assert-Home; Assert-Process | Out-Null
        # Bounded fixed routes only: no OK, media app launches, menus, or settings writes.
        $route=if($cycles % 2 -eq 0){@('20','20','22','22','22','21','21','19','19','4')}else{@('20','22','22','21','21','20','19','4')}
        foreach($code in $route){
            if($watch.Elapsed.TotalSeconds -ge $DurationSeconds){break}
            Key $code
            Maybe-Sample
        }
        State | Out-Null
        $cycles++
        Guarded-Idle $(if($cycles % 2 -eq 0){30}else{15})
        Maybe-Sample
    }
    Assert-Home; Assert-Process | Out-Null
    Sample 'final'
    if(-not (Test-SoakDuration $watch.Elapsed.TotalSeconds $DurationSeconds)){throw 'The full requested monotonic duration (at least one hour) was not completed.'}
    if($samples.Count -lt $minimumSamples){throw "INTERRUPTED: Only $($samples.Count) diagnostic samples completed; at least $minimumSamples are required for coverage."}
    $status='PASS'
} catch {
    $failure=$_.Exception.Message
    $status=if($failure.StartsWith('INTERRUPTED:')){'INTERRUPTED'}else{'FAILED'}
    throw
} finally {
    $watch.Stop()
    if($status -eq 'RUNNING'){$status='INTERRUPTED';$failure='Execution ended before the full soak duration completed.'}
    $trend=Memory-Trend
    [pscustomobject]@{
        status=$status;failure=$failure;startedUtc=$startedUtc;finishedUtc=[DateTime]::UtcNow.ToString('o');requestedSeconds=$DurationSeconds
        elapsedSeconds=[math]::Round($watch.Elapsed.TotalSeconds,2);completedFullDuration=(Test-SoakDuration $watch.Elapsed.TotalSeconds $DurationSeconds)
        layout=$observedLayout;expectedPid=$expectedProcess;navigationBursts=$cycles;inputActions=$actions;memoryTrend=$trend;sampleCount=$samples.Count
        minimumSamples=$minimumSamples;observationGapLimitSeconds=$observationGapLimit;maxObservationGapSeconds=[math]::Round($maxObservationGapSeconds,2)
        releaseGate='PASS confirms only this uninterrupted soak; review memory/frame trends and complete the other release checks.'
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outputPath 'soak-results.json') -Encoding UTF8
    $samples.ToArray() | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputPath 'soak-samples.json') -Encoding UTF8
}
"PASS: completed $([math]::Round($watch.Elapsed.TotalSeconds)) monotonic seconds with $actions guarded inputs and $($samples.Count) diagnostic samples."
