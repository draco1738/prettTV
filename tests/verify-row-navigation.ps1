param(
    [Parameter(Mandatory)][string]$Tv,
    [string]$Adb='adb',
    [int]$Port=5037,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
function Adb-Run([string[]]$Arguments) {
    $result=& $Adb -P $Port -s $Tv @Arguments 2>&1
    if($LASTEXITCODE -ne 0){throw 'ADB failed during row-navigation verification'}
    return $result
}
function Assert-Home {
    $state=(Adb-Run @('shell','dumpsys','activity','activities')) -join "`n"
    if($state -notmatch 'topResumedActivity=.*dev\.zane\.pretttv/'){throw 'Another app became active; stopping TV input.'}
}
function Focus {
    $state=(Adb-Run @('shell','dumpsys','activity','dev.zane.pretttv','--viewport-diagnostics')) -join "`n"
    $match=[regex]::Match($state,'(?m)^prettTV focus=(\S+) visible=true')
    if(-not $match.Success){throw 'Visible Home focus was not reported'}
    $script:latestState=$state
    return $match.Groups[1].Value
}
function Key([string]$Code,[switch]$Hold) {
    Assert-Home
    $arguments=@('shell','input','keyevent')
    if($Hold){$arguments+='--longpress'}
    Adb-Run ($arguments+@($Code)) | Out-Null
    Start-Sleep -Milliseconds 220
    Assert-Home
}
Assert-Home
Focus | Out-Null
if($script:latestState -notmatch 'prettTV layout=BALANCED'){throw 'This check requires C Balanced'}
Key '3'
Start-Sleep -Seconds 2
Key '20'
$first=Focus
if(-not $first.StartsWith('favorite-')){throw 'Your apps row is unavailable; no further input sent'}
$visited=New-Object System.Collections.Generic.List[string]
$visited.Add($first)
for($i=0;$i -lt 8;$i++) {
    Key '22'
    $next=Focus
    if(-not $next.StartsWith('favorite-')){throw 'Right left the app row'}
    if($next -eq $visited[$visited.Count-1]){break}
    $visited.Add($next)
}
if($visited.Count -lt 2){throw 'At least two app cards are needed for the reversal check'}
for($i=$visited.Count-2;$i -ge 0;$i--) {
    Key '21'
    if((Focus) -ne $visited[$i]){throw 'Left did not return to the preceding card; possible premature sidebar'}
}
Key '22'
Key '21' -Hold
if((Focus) -ne $first){throw 'Held Left left the first app'}
Key '21'
if((Focus) -ne $first){throw 'Fresh Left left the first app instead of stopping at the boundary'}
$remembered=$first
for($step=0;$step -lt 3;$step++) {
    $before=Focus
    if($before.StartsWith('nav-')){break}
    $remembered=$before
    Key '19'
}
if(-not (Focus).StartsWith('nav-')){throw 'Up did not reach persistent top navigation'}
Key '20'
if((Focus) -ne $remembered){throw 'Top navigation did not return to the remembered content card'}
$script:latestState | Set-Content -LiteralPath (Join-Path $OutputDirectory 'row-navigation-final.txt')
Key '4'
"Passed reversal across $($visited.Count) app cards, held/fresh Left boundaries, and top-navigation return."
