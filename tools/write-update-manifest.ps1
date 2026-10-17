# Generates public release metadata from the already-signed APK; never reads signing keys.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [Parameter(Mandatory=$true)][string]$BuildTools,
    [string]$Output
)
$ErrorActionPreference = 'Stop'
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$toolsPath = (Resolve-Path -LiteralPath $BuildTools).Path
$aapt = Join-Path $toolsPath 'aapt2.exe'
$apksigner = Join-Path $toolsPath 'apksigner.bat'
if (-not (Test-Path -LiteralPath $aapt) -or -not (Test-Path -LiteralPath $apksigner)) { throw 'BuildTools must contain aapt2.exe and apksigner.bat.' }
$badging = & $aapt dump badging $apkPath 2>&1
if ($LASTEXITCODE -ne 0) { throw 'aapt2 could not read the signed APK.' }
$packageLine = $badging | Where-Object { $_ -match "^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'" } | Select-Object -First 1
if (-not $packageLine -or $packageLine -notmatch "^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'") { throw 'APK package metadata was not found.' }
$packageName = $Matches[1]
$versionCode = [long]$Matches[2]
$versionName = $Matches[3]
if ($packageName -ne 'dev.zane.pretttv') { throw 'Only the production prettTV package can be published.' }
if ($badging -match '^application-debuggable') { throw 'Debuggable APKs cannot be published through the updater.' }
$verification = & $apksigner verify --verbose --print-certs $apkPath 2>&1
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
if ($verification -match 'certificate DN:.*CN=Android Debug') { throw 'Android debug certificates cannot be published through the updater.' }
$certificateLines = @($verification | Where-Object { $_ -match '^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$' })
if ($certificateLines.Count -ne 1 -or $certificateLines[0] -notmatch ': ([a-fA-F0-9]{64})$') { throw 'Exactly one APK signing certificate is required.' }
$certificate = $Matches[1].ToLowerInvariant()
if (-not $Output) { $Output = Join-Path (Split-Path -Parent $apkPath) 'update-manifest.json' }
$manifest = [ordered]@{
    schemaVersion = 1
    packageName = $packageName
    versionCode = $versionCode
    versionName = $versionName
    apkAsset = [System.IO.Path]::GetFileName($apkPath)
    apkSha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    signingCertificateSha256 = $certificate
}
$json = $manifest | ConvertTo-Json
[System.IO.File]::WriteAllText([System.IO.Path]::GetFullPath($Output), $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
Write-Output "Wrote updater metadata: $Output"
