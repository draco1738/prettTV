param(
    [Parameter(Mandatory)][string]$Tv,
    [string]$Adb='adb',
    [int]$Port=5037,
    [Parameter(Mandatory)][string]$FixtureApk,
    [ValidateRange(1,100)][int]$Cycles=50,
    [ValidateSet('CINEMA','QUIET','BALANCED')][string]$Layout,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$homePackage='dev.zane.pretttv'; $fixture='fi.jommi.rivi.testapp'
$outputPath=[IO.Path]::GetFullPath($OutputDirectory)
if(-not [IO.Path]::IsPathRooted($OutputDirectory) -or $outputPath -notmatch '^E:\\' -or -not (Test-Path -LiteralPath 'E:\')){throw 'An absolute output directory on the connected E: drive is required.'}
if(-not (Test-Path -LiteralPath $FixtureApk -PathType Leaf)){throw 'The isolated lifecycle fixture APK is missing.'}
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$installed=$false; $status='RUNNING'; $failure=$null; $cleanupError=$null
$results=New-Object System.Collections.Generic.List[object]
function Adb-Run([string[]]$Arguments) {
    $result=& $Adb -P $Port -s $Tv @Arguments 2>&1
    if($LASTEXITCODE -ne 0){throw "ADB failed: $($Arguments -join ' ')"}
    return $result
}
function Assert-Foreground([string]$Package) {
    $state=(Adb-Run @('shell','dumpsys','activity','activities')) -join "`n"
    if($state -notmatch ('(?:topResumedActivity|mResumedActivity)=.* '+[regex]::Escape($Package)+'/')){throw 'Unexpected foreground app; stopping TV inputs.'}
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
    $raw=(Adb-Run @('shell','dumpsys','activity',$homePackage,'--viewport-diagnostics')) -join "`n"
    $dump=Select-HomeBlock $raw -AllowStopped
    if(-not $dump){return [pscustomobject]@{focus='';visible='false';layout='';viewport='';signature='';catalog='';rows=''}}
    $focus=[regex]::Match($dump,'prettTV focus=([^\r\n]+) visible=(\w+)')
    $layoutNow=[regex]::Match($dump,'prettTV layout=(\w+)').Groups[1].Value
    if($Layout -and $focus.Groups[2].Value -eq 'true' -and $layoutNow -and $layoutNow -ne $Layout){throw "Expected $Layout but Home reports $layoutNow."}
    return [pscustomobject]@{focus=$focus.Groups[1].Value;visible=$focus.Groups[2].Value;layout=$layoutNow;viewport=[regex]::Match($dump,'VIEWPORT [^\r\n]+').Value;signature=[regex]::Match($dump,'VIEWPORT_CONTENT signature=([^\r\n]+)').Groups[1].Value;catalog=[regex]::Match($dump,'prettTV layout=[^\r\n]+').Value;rows=[regex]::Match($dump,'prettTV watchlist=[^\r\n]+').Value}
}
function Wait-Visible([string]$Visible) {
    $deadline=[DateTime]::UtcNow.AddSeconds(10)
    do { $state=State; if($state.visible -eq $Visible){return $state};Start-Sleep -Milliseconds 100 } while([DateTime]::UtcNow -lt $deadline)
    throw "Home did not reach visible=$Visible"
}
function Settle-Home([scriptblock]$NowMilliseconds={ [Environment]::TickCount64 }) {
    $deadline=(& $NowMilliseconds)+8000; $prior=$null; $missingFocus=$false
    do {
        $state=State
        if($state.visible -eq 'true'){Assert-Foreground $homePackage}
        $missingFocus=[string]::IsNullOrWhiteSpace($state.focus) -or $state.focus -eq 'null'
        if($state.visible -ne 'true' -or $missingFocus){
            # Package broadcasts can rebuild Home between layout and pre-draw. Require
            # two fresh valid observations after that transition; never accept null focus.
            $prior=$null
        } else {
            if($prior -and $state.focus -eq $prior.focus -and $state.viewport -eq $prior.viewport -and $state.signature -eq $prior.signature -and $state.catalog -eq $prior.catalog -and $state.rows -eq $prior.rows){return $state}
            $prior=$state
        }
        Start-Sleep -Milliseconds 350
    } while((& $NowMilliseconds) -lt $deadline)
    if($missingFocus){throw 'Home did not regain a valid focused view within the eight-second settling window.'}
    throw 'Home did not settle after a lifecycle transition.'
}
try {
    Assert-Foreground $homePackage
    if(((Adb-Run @('shell','pm','list','packages',$fixture)) -join '') -match [regex]::Escape($fixture)){throw 'The fixture app already exists; refusing to replace or remove it.'}
    Adb-Run @('install',$FixtureApk) | Out-Null; $installed=$true
    for($cycle=1;$cycle -le $Cycles;$cycle++) {
        $before=Settle-Home
        $pidBefore=((Adb-Run @('shell','pidof',$homePackage)) -join '').Trim()
        if(-not $pidBefore){throw 'Home process is missing.'}
        Assert-Foreground $homePackage
        Adb-Run @('shell','am','start','-n',"$fixture/.MainActivity") | Out-Null
        Wait-Visible 'false' | Out-Null
        Assert-Foreground $fixture
        Adb-Run @('shell','input','keyevent','4') | Out-Null
        $after=Settle-Home
        $unchanged=if($before.signature -and $after.signature){$before.signature -eq $after.signature}else{$before.catalog -eq $after.catalog -and $before.rows -eq $after.rows}
        # Feed changes can remove or resize cards while away; unchanged content must preserve position.
        if($unchanged -and ($after.focus -ne $before.focus -or $after.viewport -ne $before.viewport)){throw "Return position changed with unchanged content in cycle $cycle"}
        $pidAfter=((Adb-Run @('shell','pidof',$homePackage)) -join '').Trim()
        if($pidBefore -ne $pidAfter){throw 'Home process restarted during an ordinary app return.'}
        $results.Add([pscustomobject]@{cycle=$cycle;layout=$after.layout;pid=$pidAfter;contentUnchanged=$unchanged;positionStrictlyVerified=$unchanged;before=$before;after=$after})
        "Stop/return cycle $cycle/$Cycles passed"
    }
    $status='PASS'
} catch { $failure=$_.Exception.Message; $status='FAILED'; throw }
finally {
    if($installed){try { Adb-Run @('uninstall',$fixture) | Out-Null } catch { $cleanupError=$_.Exception.Message; $status='FAILED'; Write-Warning 'Fixture cleanup failed; its package is fi.jommi.rivi.testapp.' }}
    [pscustomobject]@{status=$status;failure=$failure;cleanupError=$cleanupError;requestedCycles=$Cycles;results=@($results.ToArray())} |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outputPath 'lifecycle-results.json') -Encoding UTF8
}
if($cleanupError){throw "Lifecycle fixture cleanup failed: $cleanupError"}
"PASS: $Cycles stop/return cycles; temporary lifecycle app removed."
