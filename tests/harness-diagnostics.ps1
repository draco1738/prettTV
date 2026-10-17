# Offline regression tests. Only named pure/parser functions are loaded; no ADB process runs.
$ErrorActionPreference='Stop'
$script:checks=0
function Assert-That([bool]$Condition,[string]$Message) {
    if(-not $Condition){throw $Message}
    $script:checks++
}
function Assert-Throws([scriptblock]$Operation,[string]$Message) {
    $threw=$false
    try { & $Operation | Out-Null } catch {$threw=$true}
    Assert-That $threw $Message
}
foreach($name in @('stress-home.ps1','lifecycle-home.ps1','soak-home.ps1')) {
    & {
        param($Name)
        $path=Join-Path $PSScriptRoot $Name
        $tokens=$null; $errors=$null
        $ast=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
        Assert-That ($errors.Count -eq 0) "$Name must parse without errors."
        $needed=if($Name -eq 'stress-home.ps1'){@('Select-HomeBlock','Summary','Assert-Focus')}else{@('Select-HomeBlock','State')}
        foreach($functionName in $needed) {
            $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName},$true)
            Assert-That ($null -ne $definition) "$Name is missing $functionName."
            . ([scriptblock]::Create($definition.Extent.Text))
        }
        $package='dev.zane.pretttv'; $homePackage=$package; $Layout='CINEMA'
        function Adb-Run([string[]]$Arguments) {return $diagnosticDump -split '\r?\n'}
        $stopped=@'
ACTIVITY dev.zane.pretttv/fi.jommi.rivi.MainActivity old pid=123
  prettTV layout=BALANCED apps=6
  prettTV focus=feature visible=false
  prettTV watchlist=2 episodes=0
  VIEWPORT x=0 y=0
  VIEWPORT_CONTENT signature=old-content
'@
        $active=@'
ACTIVITY dev.zane.pretttv/fi.jommi.rivi.MainActivity new pid=123
  prettTV layout=CINEMA apps=9
  prettTV focus=watchlist-provider:series:the night agent visible=true
  prettTV watchlist=3 episodes=1
  VIEWPORT x=42 y=900
  VIEWPORT_CONTENT signature=active-content
'@
        $diagnosticDump=$stopped+"`r`n"+$active
        $selected=Select-HomeBlock $diagnosticDump
        Assert-That ($selected -match 'layout=CINEMA' -and $selected -notmatch 'layout=BALANCED') "$Name selected a stopped layout."
        Assert-That ($selected -match 'signature=active-content' -and $selected -notmatch 'signature=old-content') "$Name mixed diagnostic instances."
        $diagnosticDump=$active+"`r`n"+$stopped
        Assert-That ((Select-HomeBlock $diagnosticDump) -match 'signature=active-content') "$Name must select the active instance even when listed first."
        $diagnosticDump=$stopped+"`r`n"+$active.Replace('visible=true','visible=false')
        Assert-That ($null -eq (Select-HomeBlock $diagnosticDump)) "$Name must not treat a stopped instance as active."
        Assert-That ((Select-HomeBlock $diagnosticDump -AllowStopped) -match 'signature=active-content') "$Name must select one complete last stopped block for lifecycle observations."
        Assert-That ($null -eq (Select-HomeBlock 'No activities found.')) "$Name must handle absent diagnostics."
        Assert-That ($null -eq (Select-HomeBlock $null)) "$Name must handle null diagnostic input."
        $diagnosticDump=($stopped+"`n"+$active) -replace '\r?\n',"`r`n"
        if($Name -eq 'stress-home.ps1') {
            $summary=Summary
            Assert-That ((Assert-Focus $summary) -eq 'watchlist-provider:series:the night agent') 'Stress must preserve spaces in focus keys.'
            Assert-That (($summary -join "`n") -notmatch 'BALANCED|old-content') 'Stress summary must contain only the active block.'
            $diagnosticDump=$stopped
            Assert-That (@(Summary).Count -eq 0) 'Stress must reject entirely stopped diagnostics.'
            Assert-Throws { Assert-Focus (Summary) } 'Stress must reject missing visible focus.'
        } else {
            $state=State
            Assert-That ($state.layout -eq 'CINEMA' -and $state.focus -eq 'watchlist-provider:series:the night agent') "$Name must extract layout and focus from the same active block."
            if($Name -eq 'lifecycle-home.ps1') {
                Assert-That ($state.signature -eq 'active-content' -and $state.viewport -eq 'VIEWPORT x=42 y=900') 'Lifecycle must retain the matching viewport and content signature.'
                $diagnosticDump=$stopped
                $state=State
                Assert-That ($state.visible -eq 'false' -and $state.layout -eq 'BALANCED') 'Lifecycle must allow stopped diagnostics from a previous layout.'
                $diagnosticDump='No activities found.'
                Assert-That ((State).visible -eq 'false') 'Lifecycle must handle absent stopped diagnostics without a null crash.'
                $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Settle-Home'},$true)
                Assert-That ($null -ne $definition) 'Lifecycle must expose its settling check.'
                . ([scriptblock]::Create($definition.Extent.Text))
                $clock=[pscustomobject]@{milliseconds=0;reads=0;foregroundChecks=0}
                $valid=[pscustomobject]@{focus='favorite-netflix';visible='true';layout='QUIET';viewport='VIEWPORT y=0';signature='same';catalog='same';rows='same'}
                $unfocused=[pscustomobject]@{focus='null';visible='true';layout='QUIET';viewport='VIEWPORT y=0';signature='same';catalog='same';rows='same'}
                $observations=@($unfocused,$valid,$valid)
                function State {
                    $result=$observations[[math]::Min($clock.reads,$observations.Count-1)]
                    $clock.reads++
                    return $result
                }
                function Assert-Foreground([string]$Package) {$clock.foregroundChecks++}
                function Start-Sleep([int]$Milliseconds) {$clock.milliseconds+=$Milliseconds}
                $settled=Settle-Home -NowMilliseconds {$clock.milliseconds}
                Assert-That ($settled.focus -eq 'favorite-netflix' -and $clock.reads -eq 3) 'Lifecycle must allow null -> valid -> valid focus to settle.'
                Assert-That ($clock.foregroundChecks -eq 3) 'Lifecycle must guard foreground for every visible settling observation.'
                $clock.milliseconds=0; $clock.reads=0
                $observations=@($valid,$unfocused,$valid,$valid)
                Settle-Home -NowMilliseconds {$clock.milliseconds} | Out-Null
                Assert-That ($clock.reads -eq 4) 'A missing-focus observation must reset the previous valid settling sample.'
                $clock.milliseconds=0; $clock.reads=0
                $observations=@($unfocused)
                Assert-Throws {Settle-Home -NowMilliseconds {$clock.milliseconds}} 'Persistent null focus must fail the settling deadline.'
                Assert-That ($clock.milliseconds -ge 8000 -and $clock.milliseconds -lt 8350) 'Persistent null focus must time out within one poll of eight seconds.'
            } else {
                $diagnosticDump=$stopped
                Assert-Throws {State} 'Soak must reject entirely stopped diagnostics.'
                $diagnosticDump=$active.Replace('watchlist-provider:series:the night agent','null')
                Assert-Throws {State} 'Soak must reject a null visible focus.'
                $parametersOnly=[scriptblock]::Create($ast.ParamBlock.Extent.Text+"`nreturn `$DurationSeconds")
                Assert-That ((& $parametersOnly -Tv 'offline-fixture' -OutputDirectory 'E:/offline-fixture') -eq 3600) 'Soak must default to one hour.'
                Assert-That ((& $parametersOnly -Tv 'offline-fixture' -OutputDirectory 'E:/offline-fixture' -DurationSeconds 3600) -eq 3600) 'Soak must accept the approved one-hour minimum.'
                Assert-Throws {& $parametersOnly -Tv 'offline-fixture' -OutputDirectory 'E:/offline-fixture' -DurationSeconds 3599} 'Soak must reject durations shorter than one hour.'
                Assert-That ((& $parametersOnly -Tv 'offline-fixture' -OutputDirectory 'E:/offline-fixture' -DurationSeconds 7200) -eq 7200) 'Soak must continue to accept longer requested runs.'
                $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Test-SoakDuration'},$true)
                Assert-That ($null -ne $definition) 'Soak must expose its exact duration completion check.'
                . ([scriptblock]::Create($definition.Extent.Text))
                Assert-That (-not (Test-SoakDuration 3599.99 3600)) 'Incomplete one-hour evidence must fail.'
                Assert-That (Test-SoakDuration 3600 3600) 'A completed requested one-hour duration must pass.'
                Assert-That (-not (Test-SoakDuration 3600 7200)) 'One hour must not pass a still-requested two-hour run.'
                Assert-That (Test-SoakDuration 7200 7200) 'A completed requested two-hour duration must pass.'
            }
        }
        "$Name diagnostic selection passed."
    } $name
}
"PASS: $script:checks offline harness assertions; no TV connection or input."
