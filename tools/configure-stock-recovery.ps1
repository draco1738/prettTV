# Imports an existing private setup journal. This does not perform a stock restore.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$JournalPath,
    [Parameter(Mandatory)][string]$Tv,
    [string]$Adb = 'adb',
    [ValidateRange(1, 65535)][int]$AdbPort = 5037
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Read-LimitedText([string]$Path, [long]$MaximumBytes) {
    $file = Get-Item -LiteralPath $Path -ErrorAction Stop
    if ($file.PSIsContainer -or $file.Length -eq 0 -or $file.Length -gt $MaximumBytes) {
        throw 'A recovery source file is empty, too large, or not a regular file.'
    }
    return [System.IO.File]::ReadAllText($file.FullName)
}

function Get-RequiredProperty($Object, [string]$Name) {
    if ($null -eq $Object -or $null -eq $Object.PSObject.Properties[$Name]) {
        throw "Recovery source is missing required field: $Name."
    }
    return $Object.$Name
}

function Assert-UserZero($Object) {
    foreach ($field in @('user', 'userId')) {
        if ($null -ne $Object.PSObject.Properties[$field] -and [string]$Object.$field -cne '0') {
            throw 'Only the original user 0 setup journal is supported.'
        }
    }
}

function Get-DnsValue($Value) {
    # `settings get` prints "null" for a missing row. Never restore it as text.
    if ($null -eq $Value -or [string]$Value -ceq 'null') { return $null }
    if ($Value -isnot [string]) { throw 'DNS values must be strings or null.' }
    return $Value
}

function Assert-DnsMode($Value) {
    if ($null -ne $Value -and $Value -cnotin @('off', 'opportunistic', 'hostname')) {
        throw 'The journal contains an unsupported private DNS mode.'
    }
}

function Assert-DnsSpecifier($Value) {
    if ($null -eq $Value) { return }
    if ($Value.Length -eq 0 -or $Value.Length -gt 253) {
        throw 'The journal contains an invalid private DNS hostname.'
    }
    foreach ($label in ($Value -split '\.')) {
        if ($label.Length -eq 0 -or $label.Length -gt 63 -or
            $label -cnotmatch '^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$') {
            throw 'The journal contains an invalid private DNS hostname.'
        }
    }
}

function Invoke-Adb([string[]]$Arguments) {
    $output = @(& $Adb -P $AdbPort -s $Tv @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        # Avoid echoing arguments or device-specific output into logs.
        throw 'ADB failed. Check that the selected TV is connected and authorized.'
    }
    return ($output -join "`n").Trim()
}

if ($Tv -cnotmatch '^[A-Za-z0-9_\[][A-Za-z0-9_.:\[\]-]{0,255}$') {
    throw 'Use an explicit TV serial or network address from adb devices.'
}

$journalFile = Get-Item -LiteralPath $JournalPath -ErrorAction Stop
$journal = Read-LimitedText -Path $journalFile.FullName -MaximumBytes 65536 | ConvertFrom-Json
Assert-UserZero $journal
$snapshot = Get-RequiredProperty $journal 'snapshot'
if ($snapshot -isnot [string] -or [string]::IsNullOrWhiteSpace($snapshot)) {
    throw 'The journal must point to its original snapshot directory.'
}
if (-not [System.IO.Path]::IsPathRooted($snapshot)) {
    $snapshot = Join-Path $journalFile.DirectoryName $snapshot
}
$identity = Read-LimitedText -Path (Join-Path $snapshot 'device-identity.json') -MaximumBytes 65536 | ConvertFrom-Json
$deviceSerial = Get-RequiredProperty $identity 'ro.serialno'
$fingerprint = Get-RequiredProperty $identity 'ro.build.fingerprint'
foreach ($value in @($deviceSerial, $fingerprint)) {
    if ($value -isnot [string] -or [string]::IsNullOrWhiteSpace($value) -or
        $value.Length -gt 1024 -or $value -match '[\x00-\x1F\x7F]') {
        throw 'The original snapshot has an invalid device identity.'
    }
}
if ($deviceSerial.Length -gt 128) { throw 'The original snapshot has an invalid device serial.' }

$homeText = Read-LimitedText -Path (Join-Path $snapshot 'home-activity.txt') -MaximumBytes 4096
$homeLines = @($homeText -split '\r?\n' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
$stockHome = 'com.google.android.apps.tv.launcherx/.home.HomeActivity'
$components = @($homeLines | Where-Object { $_ -match '^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$' })
if ($components.Count -ne 1 -or $components[0] -cne $stockHome) {
    throw 'The saved Home must be the supported Google TV Home activity.'
}

$allowedPackages = @(
    'com.tcl.tv.plus', 'com.tcl.browser', 'com.tcl.smartalexa', 'com.tcl.esticker',
    'com.tcl.interactive', 'com.tcl.logkit', 'com.google.android.feedback',
    'com.tcl.channelplus', 'com.google.android.tungsten.setupwraith',
    'com.google.android.apps.tv.launcherx'
)
$seenPackages = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$packages = @()
$entries = @(Get-RequiredProperty $journal 'packages')
if ($entries.Count -eq 0 -or $entries.Count -gt $allowedPackages.Count) {
    throw 'The journal must contain the recorded package changes.'
}
foreach ($entry in $entries) {
    Assert-UserZero $entry
    $name = Get-RequiredProperty $entry 'package'
    if ($name -isnot [string] -or $name -cnotin $allowedPackages -or -not $seenPackages.Add($name)) {
        throw 'The journal contains an unsupported or duplicate package.'
    }
    if ((Get-RequiredProperty $entry 'action') -cne 'disable-user' -or
        (Get-RequiredProperty $entry 'status') -cne 'applied') {
        throw 'Only completed disable-user package changes can be imported.'
    }
    $previous = Get-RequiredProperty $entry 'previousEnabled'
    if ($null -eq $previous -or [string]$previous -cnotmatch '^[0-4]$') {
        throw 'A recorded package enabled state is invalid.'
    }
    if ($name -ceq 'com.google.android.apps.tv.launcherx' -and [int]$previous -gt 1) {
        throw 'The saved Google TV Home state must be enabled or default.'
    }
    $packages += [ordered]@{ name = $name; previousState = [int]$previous; managedState = 3 }
}
if (-not $seenPackages.Contains('com.google.android.apps.tv.launcherx')) {
    throw 'The journal does not contain the original Google TV Home state.'
}

$dns = @{}
$settings = @(Get-RequiredProperty $journal 'settings')
if ($settings.Count -ne 2) { throw 'Exactly the two recorded private DNS settings are required.' }
foreach ($setting in $settings) {
    Assert-UserZero $setting
    $name = Get-RequiredProperty $setting 'name'
    if ($name -isnot [string] -or $name -cnotin @('private_dns_mode', 'private_dns_specifier') -or
        $dns.ContainsKey($name) -or (Get-RequiredProperty $setting 'namespace') -cne 'global' -or
        (Get-RequiredProperty $setting 'status') -cne 'applied') {
        throw 'The journal contains an unsupported, duplicate, or incomplete setting.'
    }
    $dns[$name] = @{
        previous = Get-DnsValue (Get-RequiredProperty $setting 'previous')
        managed = Get-DnsValue (Get-RequiredProperty $setting 'requested')
    }
}
Assert-DnsMode $dns['private_dns_mode'].previous
Assert-DnsMode $dns['private_dns_mode'].managed
Assert-DnsSpecifier $dns['private_dns_specifier'].previous
Assert-DnsSpecifier $dns['private_dns_specifier'].managed
if ($dns['private_dns_mode'].previous -ceq 'hostname' -and
    [string]::IsNullOrWhiteSpace($dns['private_dns_specifier'].previous)) {
    throw 'The saved hostname DNS mode requires its original hostname.'
}
if ($dns['private_dns_mode'].managed -cne 'hostname' -or
    $dns['private_dns_specifier'].managed -cne 'dns.adguard-dns.com') {
    throw 'This importer supports only the recorded AdGuard private DNS change.'
}

# All source validation happens before connecting. These queries do not change TV state.
if ((Invoke-Adb @('shell', 'getprop', 'ro.serialno')) -cne $deviceSerial -or
    (Invoke-Adb @('shell', 'getprop', 'ro.build.fingerprint')) -cne $fingerprint) {
    throw 'The connected TV does not match the serial and firmware of the original snapshot.'
}
if ((Invoke-Adb @('shell', 'am', 'get-current-user')) -cne '0') {
    throw 'Switch the TV to its original user 0 before importing recovery settings.'
}

$config = [ordered]@{
    version = 1
    deviceSerial = $deviceSerial
    fingerprint = $fingerprint
    stockHome = $stockHome
    packages = $packages
    dnsMode = $dns['private_dns_mode'].previous
    dnsSpecifier = $dns['private_dns_specifier'].previous
    managedDnsMode = $dns['private_dns_mode'].managed
    managedDnsSpecifier = $dns['private_dns_specifier'].managed
}
$json = $config | ConvertTo-Json -Depth 6 -Compress
$encoded = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($json))
# Imports only the private baseline. This command cannot execute the restoration.
$result = Invoke-Adb @('shell', 'dumpsys', 'activity', 'dev.zane.pretttv', '--configure-stock-recovery', $encoded)
if ($result -cnotmatch '(?m)^\s*PRETTTV_RECOVERY_CONFIGURED\s*$') {
    throw 'Recovery was not configured. Open an updated prettTV, then retry. No stock restore was run.'
}
Write-Output 'Stock recovery configured for this TV. Nothing has been restored; use prettTV Settings when needed.'
