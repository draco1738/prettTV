#requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$Tv,
    [string]$Adb='adb',
    [ValidateRange(1,65535)][int]$Port=5037,
    [string]$Aapt,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$target='dev.zane.pretttv.stability'
$runner='dev.zane.pretttv.stability.test'
$fixture='fi.jommi.rivi.testapp'
$allowed=@($target,$runner,$fixture)
$outputPath=[IO.Path]::GetFullPath($OutputDirectory)
if(-not [IO.Path]::IsPathRooted($OutputDirectory) -or $outputPath -notmatch '^E:\\' -or
    -not (Test-Path -LiteralPath 'E:\')) {
    throw 'An absolute output directory on the connected E: drive is required.'
}
$ancestor=$outputPath
while($ancestor) {
    if(Test-Path -LiteralPath $ancestor) {
        if((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'The results directory must stay on E: without a junction or symbolic-link ancestor.'
        }
    }
    $parent=Split-Path -Path $ancestor -Parent
    if($parent -eq $ancestor){break}
    $ancestor=$parent
}
if(-not $Aapt) {
    if(-not $env:ANDROID_HOME){throw 'Set ANDROID_HOME or pass -Aapt with the SDK aapt2 executable path.'}
    $Aapt=Join-Path $env:ANDROID_HOME 'build-tools/35.0.0/aapt2.exe'
}
$adbExecutable=(Get-Command -Name $Adb -CommandType Application -ErrorAction Stop).Source
$aaptExecutable=(Get-Command -Name $Aapt -CommandType Application -ErrorAction Stop).Source
$artifacts=@(
    [pscustomobject]@{package=$fixture;apk=(Join-Path $repo 'launcher/testapp/build/outputs/apk/debug/testapp-debug.apk')},
    [pscustomobject]@{package=$target;apk=(Join-Path $repo 'launcher/app/build/outputs/apk/stability/app-stability.apk')},
    [pscustomobject]@{package=$runner;apk=(Join-Path $repo 'launcher/app/build/outputs/apk/androidTest/stability/app-stability-androidTest.apk')}
)

function Native-Run([string]$Executable,[string[]]$Arguments,[int]$TimeoutSeconds=30) {
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName=$Executable
    $start.UseShellExecute=$false
    $start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true
    $start.RedirectStandardError=$true
    foreach($argument in $Arguments){$start.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new()
    $process.StartInfo=$start
    try {
        if(-not $process.Start()){throw 'The validation command did not start.'}
        $stdout=$process.StandardOutput.ReadToEndAsync()
        $stderr=$process.StandardError.ReadToEndAsync()
        if(-not $process.WaitForExit($TimeoutSeconds*1000)) {
            $process.Kill($true)
            $process.WaitForExit()
            throw "Validation command timed out after $TimeoutSeconds seconds."
        }
        $text=$stdout.GetAwaiter().GetResult()+$stderr.GetAwaiter().GetResult()
        if($process.ExitCode -ne 0){throw "Validation command failed (exit $($process.ExitCode)): $text"}
        return $text
    } finally { $process.Dispose() }
}
function Adb-Run([string[]]$Arguments,[int]$TimeoutSeconds=30) {
    return Native-Run $adbExecutable (@('-P',[string]$Port,'-s',$Tv)+$Arguments) $TimeoutSeconds
}
function Test-TestPackage([string]$Package) {
    if($Package -notin $allowed){throw "Refusing a package outside the isolated test allowlist: $Package"}
    $dump=Adb-Run @('shell','dumpsys','package',$Package)
    if($dump -match ('(?m)^[ \t]*Package \['+[regex]::Escape($Package)+'\] \(')){return $true}
    if($dump.Trim() -eq "Unable to find package: $Package"){return $false}
    throw "Cannot establish whether test package exists; no install or removal is safe: $Package"
}

# Verify the APK manifests, not merely filenames, before any device mutation.
foreach($artifact in $artifacts) {
    if(-not (Test-Path -LiteralPath $artifact.apk -PathType Leaf)) {
        throw "Missing test APK. Run the documented build first: $($artifact.apk)"
    }
    $badging=Native-Run $aaptExecutable @('dump','badging',$artifact.apk)
    $identities=[regex]::Matches($badging,"(?m)^package: name='([^']+)'")
    if($identities.Count -ne 1 -or $identities[0].Groups[1].Value -ne $artifact.package) {
        throw "The APK does not have its expected isolated package ID: $($artifact.apk)"
    }
}
foreach($package in $allowed) {
    if(Test-TestPackage $package) {
        throw "Test package already exists; refusing to replace or later remove it: $package"
    }
}

New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$installed=[Collections.Generic.List[string]]::new()
$cleanupErrors=[Collections.Generic.List[string]]::new()
$failure=$null
$passed=0
$status='RUNNING'
try {
    foreach($artifact in $artifacts) {
        # No -r: a concurrent/pre-existing installation must never be overwritten.
        $reply=Adb-Run @('install',$artifact.apk) 90
        if($reply -notmatch '(?m)^Success\s*$'){throw "Android did not confirm installation of $($artifact.package): $reply"}
        $installed.Add($artifact.package)
        if(-not (Test-TestPackage $artifact.package)){throw "The installed test package could not be verified: $($artifact.package)"}
    }
    $result=Adb-Run @('shell','am','instrument','-w','-r','-e','visualPolishOnly','true',
        "$runner/fi.jommi.rivi.StabilityInstrumentation") 180
    $result | Set-Content -LiteralPath (Join-Path $outputPath 'android-visual-tests.txt') -Encoding UTF8
    $passed=[regex]::Matches($result,'(?m)^INSTRUMENTATION_STATUS: stream=PASS ').Count
    # Keep captures private: translucent test dialogs can include the current TV background.
    # Pull before removing the owned test app; never publish this raw evidence directory.
    $captureRoot='/sdcard/Android/data/dev.zane.pretttv.stability/files/visual-review'
    try { $captureListing=Adb-Run @('shell','ls','-d',$captureRoot) 15 } catch { $captureListing='' }
    if($captureListing.Trim() -eq $captureRoot) {
        Adb-Run @('pull',$captureRoot,(Join-Path $outputPath 'visual-review')) 30 | Out-Null
    }
    $summary=[regex]::Matches($result,'VISUAL POLISH PASS: (\d+) Android tests')
    if($summary.Count -ne 1 -or [int]$summary[0].Groups[1].Value -lt 51 -or
        $result -notmatch '(?m)^INSTRUMENTATION_CODE:\s*-1\s*$' -or
        $result -match '(?im)^\s*(?:INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|FAILURE|FAILED)(?:\b|:)') {
        throw 'Visual polish Android tests failed or were incomplete; inspect android-visual-tests.txt.'
    }
    $passed=[int]$summary[0].Groups[1].Value
    $status='PASS'
} catch {
    $failure=$_.Exception.Message
    $status='FAILED'
    $failure | Set-Content -LiteralPath (Join-Path $outputPath 'android-visual-error.txt') -Encoding UTF8
} finally {
    for($i=$installed.Count-1;$i -ge 0;$i--) {
        $package=$installed[$i]
        try {
            # Both checks are required: fixed test identity AND successful installation by this invocation.
            if($package -notin $allowed -or $package -notin @($artifacts.package)) {
                throw "Refusing unexpected cleanup package: $package"
            }
            if(Test-TestPackage $package) {
                $reply=Adb-Run @('uninstall',$package) 60
                if($reply.Trim() -ne 'Success'){throw "Android did not confirm removal of $package`: $reply"}
            }
            if(Test-TestPackage $package){throw "Temporary test package remains installed: $package"}
        } catch {
            $cleanupErrors.Add($_.Exception.Message)
            $status='FAILED'
        }
    }
    [pscustomobject]@{
        status=$status;passed=$passed;minimumRequired=51;failure=$failure
        installedByThisRun=@($installed.ToArray());cleanupErrors=@($cleanupErrors.ToArray())
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $outputPath 'android-visual-results.json') -Encoding UTF8
}
if($failure){throw $failure}
if($cleanupErrors.Count){throw "Test-package cleanup was incomplete: $($cleanupErrors -join ' | ')"}
"VISUAL POLISH PASS: $passed isolated Android tests; all three temporary packages removed."
