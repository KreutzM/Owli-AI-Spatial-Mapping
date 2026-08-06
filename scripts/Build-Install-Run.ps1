<#
.SYNOPSIS
Builds, installs, and optionally launches the current debug APK on one explicitly selected online ADB device.

.DESCRIPTION
Resolves the repository root from this script's location, builds the current checkout with the checked-in
Gradle wrapper by default, verifies exact APK provenance, selects one safe ADB target, prints device and
build evidence, installs with replacement semantics, and optionally launches or captures bounded logcat.

.PARAMETER Serial
Selects one exact ADB serial. The script never falls back to another device.

.PARAMETER AdbPath
Uses one explicit adb executable before all environment and PATH discovery locations.

.PARAMETER NoBuild
Skips Gradle only when the exact APK and its generated provenance sidecar match the current checkout.

.PARAMETER NoLaunch
Builds and installs without launching the application.

.PARAMETER ClearAppData
Explicitly clears com.owlitech.spatial data after installation. Disabled by default.

.PARAMETER GrantCameraPermission
Explicitly grants android.permission.CAMERA after installation. Disabled by default.

.PARAMETER CaptureLogcat
Writes one bounded, app-PID-focused logcat snapshot after launch. No background process is left running.

.PARAMETER Help
Shows detailed help and exits without building or contacting ADB.

.EXAMPLE
./scripts/Build-Install-Run.ps1

.EXAMPLE
./scripts/Build-Install-Run.ps1 -Serial R58M123456A

.EXAMPLE
./scripts/Build-Install-Run.ps1 -Serial R58M123456A -CaptureLogcat ./artifacts/s23plus-logcat.txt
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$AdbPath,
    [switch]$NoBuild,
    [switch]$NoLaunch,
    [switch]$ClearAppData,
    [switch]$GrantCameraPermission,
    [string]$CaptureLogcat,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

if ($Help) {
    Get-Help -Name $PSCommandPath -Detailed
    exit 0
}

$ApplicationId = 'com.owlitech.spatial'
$LaunchComponent = 'com.owlitech.spatial/.MainActivity'
$CameraPermission = 'android.permission.CAMERA'
$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$GradleWrapper = Join-Path $RepoRoot 'gradlew.bat'
$ApkPath = Join-Path $RepoRoot 'app/build/outputs/apk/debug/app-debug.apk'
$ProvenancePath = "$ApkPath.provenance.json"

function Invoke-Native {
    param(
        [Parameter(Mandatory)][string]$File,
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$Echo,
        [switch]$AllowFailure
    )

    $raw = @(& $File @Arguments 2>&1)
    $code = $LASTEXITCODE
    $lines = @($raw | ForEach-Object { $_.ToString() })
    if ($Echo) {
        $lines | ForEach-Object { Write-Host $_ }
    }
    if (-not $AllowFailure -and $code -ne 0) {
        $details = if ($lines.Count -gt 0) { $lines -join [Environment]::NewLine } else { '<no output>' }
        throw "Command failed with exit code $code: $File $($Arguments -join ' ')`n$details"
    }
    [pscustomobject]@{ ExitCode = $code; Lines = $lines }
}

function Get-TextSha256 {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Text)

    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        ([System.BitConverter]::ToString($hasher.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $hasher.Dispose()
    }
}

function Get-GitEvidence {
    $git = (Get-Command git -CommandType Application -ErrorAction Stop).Source
    $headResult = Invoke-Native $git @('-C', $RepoRoot, 'rev-parse', 'HEAD')
    $head = (($headResult.Lines | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1) ?? '').Trim()
    if ($head -notmatch '^[0-9a-fA-F]{40}$') {
        throw "Git HEAD is unavailable or not a full 40-character SHA: '$head'"
    }

    $status = Invoke-Native $git @('-C', $RepoRoot, '-c', 'core.quotepath=false', 'status', '--porcelain=v1', '--untracked-files=all')
    $diff = Invoke-Native $git @('-C', $RepoRoot, 'diff', '--binary', 'HEAD', '--', '.')
    $statusText = $status.Lines -join "`n"
    $diffText = $diff.Lines -join "`n"
    [pscustomobject]@{
        Head = $head.ToLowerInvariant()
        IsDirty = $status.Lines.Count -gt 0
        WorktreeFingerprint = Get-TextSha256 "STATUS`n$statusText`nDIFF`n$diffText"
    }
}

function Write-Provenance {
    param([Parameter(Mandatory)]$GitEvidence, [Parameter(Mandatory)][string]$ApkSha)

    [ordered]@{
        SchemaVersion = 1
        GitHead = $GitEvidence.Head
        WorktreeFingerprint = $GitEvidence.WorktreeFingerprint
        ApkSha256 = $ApkSha
    } | ConvertTo-Json | Set-Content -LiteralPath $ProvenancePath -Encoding utf8NoBOM
}

function Assert-Provenance {
    param([Parameter(Mandatory)]$GitEvidence, [Parameter(Mandatory)][string]$ApkSha)

    if (-not (Test-Path -LiteralPath $ProvenancePath -PathType Leaf)) {
        throw '-NoBuild refused: provenance sidecar is missing. Run once without -NoBuild for this checkout.'
    }
    try {
        $record = Get-Content -LiteralPath $ProvenancePath -Raw | ConvertFrom-Json
    }
    catch {
        throw "-NoBuild refused: provenance sidecar is unreadable: $($_.Exception.Message)"
    }
    if ($record.SchemaVersion -ne 1 -or
        $record.GitHead -ne $GitEvidence.Head -or
        $record.WorktreeFingerprint -ne $GitEvidence.WorktreeFingerprint -or
        $record.ApkSha256 -ne $ApkSha) {
        throw '-NoBuild refused: the APK is stale or does not match the current Git/worktree state.'
    }
}

function Resolve-Adb {
    if (-not [string]::IsNullOrWhiteSpace($AdbPath)) {
        if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
            throw "Explicit -AdbPath does not exist: $AdbPath"
        }
        return (Resolve-Path -LiteralPath $AdbPath).Path
    }

    foreach ($sdkRoot in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ([string]::IsNullOrWhiteSpace($sdkRoot)) { continue }
        $candidate = Join-Path $sdkRoot 'platform-tools/adb.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $pathAdb = Get-Command adb.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($null -ne $pathAdb) { return $pathAdb.Source }
    throw 'ADB was not found. Use -AdbPath, ANDROID_SDK_ROOT, ANDROID_HOME, or place adb.exe on PATH.'
}

function Get-Devices {
    param([Parameter(Mandatory)][string]$Adb)

    $result = Invoke-Native $Adb @('devices', '-l')
    $devices = [System.Collections.Generic.List[object]]::new()
    foreach ($line in $result.Lines) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith('List of devices attached')) { continue }
        if ($trimmed -notmatch '^(?<serial>\S+)\s+(?<state>\S+)(?:\s+(?<details>.*))?$') { continue }

        $properties = @{}
        $details = if ($Matches.ContainsKey('details')) { $Matches.details } else { '' }
        foreach ($token in ($details -split '\s+')) {
            if ($token -match '^(?<key>[^:]+):(?<value>.*)$') { $properties[$Matches.key] = $Matches.value }
        }
        $devices.Add([pscustomobject]@{
            Serial = $Matches.serial
            State = $Matches.state
            Model = ($properties.model ?? '')
            Product = ($properties.product ?? '')
            Device = ($properties.device ?? '')
        })
    }
    $devices.ToArray()
}

function Write-Devices {
    param([Parameter(Mandatory)][object[]]$Devices)

    Write-Host 'ADB devices:'
    foreach ($device in $Devices) {
        Write-Host ("  serial={0} state={1} model={2} product={3} device={4}" -f $device.Serial, $device.State, $device.Model, $device.Product, $device.Device)
    }
}

function Select-Device {
    param([Parameter(Mandatory)][object[]]$Devices)

    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        $matches = @($Devices | Where-Object { $_.Serial -ceq $Serial })
        if ($matches.Count -ne 1) {
            Write-Devices $Devices
            throw "Requested ADB serial was not found exactly once: $Serial"
        }
        if ($matches[0].State -ne 'device') {
            Write-Devices $Devices
            throw "Requested ADB serial is not online in 'device' state: $Serial ($($matches[0].State))"
        }
        return $matches[0]
    }

    if ($Devices.Count -ne 1 -or $Devices[0].State -ne 'device') {
        Write-Devices $Devices
        throw 'Exactly one attached online ADB device is required when -Serial is omitted.'
    }
    $Devices[0]
}

function Invoke-DeviceAdb {
    param(
        [Parameter(Mandatory)][string]$Adb,
        [Parameter(Mandatory)][string]$SelectedSerial,
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$Echo,
        [switch]$AllowFailure
    )
    Invoke-Native $Adb (@('-s', $SelectedSerial) + $Arguments) -Echo:$Echo -AllowFailure:$AllowFailure
}

function Get-DeviceProperty {
    param([Parameter(Mandatory)][string]$Adb, [Parameter(Mandatory)][string]$SelectedSerial, [Parameter(Mandatory)][string]$Name)

    $result = Invoke-DeviceAdb $Adb $SelectedSerial @('shell', 'getprop', $Name)
    ((($result.Lines | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1) ?? '')).Trim()
}

function Get-Battery {
    param([Parameter(Mandatory)][string]$Adb, [Parameter(Mandatory)][string]$SelectedSerial)

    $result = Invoke-DeviceAdb $Adb $SelectedSerial @('shell', 'dumpsys', 'battery') -AllowFailure
    if ($result.ExitCode -ne 0) { return [pscustomobject]@{ Level = 'unavailable'; Status = 'unavailable' } }
    $text = $result.Lines -join "`n"
    $level = if ($text -match '(?m)^\s*level:\s*(?<v>\d+)\s*$') { $Matches.v } else { 'unavailable' }
    $code = if ($text -match '(?m)^\s*status:\s*(?<v>\d+)\s*$') { $Matches.v } else { 'unavailable' }
    $names = @{ '1' = 'unknown'; '2' = 'charging'; '3' = 'discharging'; '4' = 'not-charging'; '5' = 'full' }
    $status = if ($names.ContainsKey($code)) { "$code ($($names[$code]))" } else { $code }
    [pscustomobject]@{ Level = $level; Status = $status }
}

try {
    if ($NoLaunch -and -not [string]::IsNullOrWhiteSpace($CaptureLogcat)) {
        throw '-CaptureLogcat requires launch; remove -NoLaunch or omit -CaptureLogcat.'
    }
    if (-not (Test-Path -LiteralPath $GradleWrapper -PathType Leaf)) {
        throw "Checked-in Gradle wrapper was not found: $GradleWrapper"
    }

    $git = Get-GitEvidence
    if (-not $NoBuild) {
        Remove-Item -LiteralPath $ApkPath -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $ProvenancePath -Force -ErrorAction SilentlyContinue
        Write-Host "Building current checkout with: $GradleWrapper :app:assembleDebug"
        Invoke-Native $GradleWrapper @(':app:assembleDebug') -Echo | Out-Null
    }
    if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
        throw "Expected debug APK does not exist: $ApkPath"
    }

    $apkSha = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($NoBuild) { Assert-Provenance $git $apkSha } else { Write-Provenance $git $apkSha }

    Write-Host '--- Build provenance ---'
    Write-Host "Git HEAD: $($git.Head)"
    Write-Host ("Git worktree: {0}" -f $(if ($git.IsDirty) { 'dirty' } else { 'clean' }))
    Write-Host "APK path: $ApkPath"
    Write-Host "APK SHA-256: $apkSha"

    $adb = Resolve-Adb
    $devices = @(Get-Devices $adb)
    if ($devices.Count -eq 0) {
        Write-Host 'ADB devices: <none>'
        if (-not [string]::IsNullOrWhiteSpace($Serial)) {
            throw "Requested ADB serial was not found exactly once: $Serial"
        }
        throw 'Exactly one attached online ADB device is required when -Serial is omitted.'
    }
    $selected = Select-Device $devices
    $selectedSerial = $selected.Serial

    $manufacturer = Get-DeviceProperty $adb $selectedSerial 'ro.product.manufacturer'
    $model = Get-DeviceProperty $adb $selectedSerial 'ro.product.model'
    $product = Get-DeviceProperty $adb $selectedSerial 'ro.product.name'
    $deviceName = Get-DeviceProperty $adb $selectedSerial 'ro.product.device'
    $androidRelease = Get-DeviceProperty $adb $selectedSerial 'ro.build.version.release'
    $sdk = Get-DeviceProperty $adb $selectedSerial 'ro.build.version.sdk'
    $fingerprint = Get-DeviceProperty $adb $selectedSerial 'ro.build.fingerprint'
    $battery = Get-Battery $adb $selectedSerial

    Write-Host '--- Selected device ---'
    Write-Host "ADB path: $adb"
    Write-Host "Serial: $selectedSerial"
    Write-Host "Manufacturer: $manufacturer"
    Write-Host "Model: $model"
    Write-Host "Product: $product"
    Write-Host "Device: $deviceName"
    Write-Host "Android release: $androidRelease"
    Write-Host "SDK level: $sdk"
    Write-Host "Build fingerprint: $fingerprint"
    Write-Host "Battery level: $($battery.Level)"
    Write-Host "Battery status: $($battery.Status)"

    Write-Host 'Installing debug APK with replacement semantics...'
    $install = Invoke-DeviceAdb $adb $selectedSerial @('install', '-r', $ApkPath) -Echo
    if (($install.Lines -join "`n") -match '(?im)^\s*Failure\b') { throw 'ADB reported an installation failure.' }

    if ($ClearAppData) {
        Write-Host 'Clearing application data (explicit opt-in)...'
        $clear = Invoke-DeviceAdb $adb $selectedSerial @('shell', 'pm', 'clear', $ApplicationId) -Echo
        if (($clear.Lines -join "`n") -notmatch '(?im)^\s*Success\s*$') { throw 'Application data clear did not report Success.' }
    }
    if ($GrantCameraPermission) {
        Write-Host 'Granting camera permission (explicit opt-in)...'
        Invoke-DeviceAdb $adb $selectedSerial @('shell', 'pm', 'grant', $ApplicationId, $CameraPermission) -Echo | Out-Null
    }

    if (-not $NoLaunch) {
        if (-not [string]::IsNullOrWhiteSpace($CaptureLogcat)) {
            Invoke-DeviceAdb $adb $selectedSerial @('logcat', '-c') | Out-Null
        }
        Write-Host "Launching $LaunchComponent..."
        $launch = Invoke-DeviceAdb $adb $selectedSerial @('shell', 'am', 'start', '-W', '-n', $LaunchComponent) -Echo
        if (($launch.Lines -join "`n") -match '(?im)^\s*(Error:|Exception)') { throw 'ADB activity start reported an error.' }

        if (-not [string]::IsNullOrWhiteSpace($CaptureLogcat)) {
            $pidResult = Invoke-DeviceAdb $adb $selectedSerial @('shell', 'pidof', $ApplicationId)
            $pid = ((($pidResult.Lines | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1) ?? '') -split '\s+')[0]
            if ($pid -notmatch '^\d+$') { throw "Could not resolve a running PID for $ApplicationId after launch." }
            $log = Invoke-DeviceAdb $adb $selectedSerial @('logcat', '-d', '-v', 'threadtime', "--pid=$pid")
            $logPath = if ([System.IO.Path]::IsPathRooted($CaptureLogcat)) {
                [System.IO.Path]::GetFullPath($CaptureLogcat)
            } else {
                [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $CaptureLogcat))
            }
            $directory = Split-Path $logPath -Parent
            if (-not [string]::IsNullOrWhiteSpace($directory)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
            $log.Lines | Set-Content -LiteralPath $logPath -Encoding utf8NoBOM
            Write-Host "Bounded app logcat snapshot: $logPath"
        }
    }

    Write-Host 'Build/install workflow completed successfully.'
    exit 0
}
catch {
    [Console]::Error.WriteLine("ERROR: $($_.Exception.Message)")
    exit 1
}
