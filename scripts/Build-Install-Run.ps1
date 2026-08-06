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
Skips Gradle only when the exact APK and its script-generated provenance sidecar match the current checkout.

.PARAMETER NoLaunch
Builds and installs without launching the application.

.PARAMETER ClearAppData
Explicitly clears com.owlitech.spatial data after installation. Disabled by default.

.PARAMETER GrantCameraPermission
Explicitly grants android.permission.CAMERA after installation. Disabled by default.

.PARAMETER CaptureLogcat
Writes one bounded, app-PID-focused `adb logcat -d` snapshot after launch. No background process is left running.

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
$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path -Path $PSScriptRoot -ChildPath '..'))
$GradleWrapper = Join-Path -Path $RepoRoot -ChildPath 'gradlew.bat'
$ApkPath = Join-Path -Path $RepoRoot -ChildPath 'app/build/outputs/apk/debug/app-debug.apk'
$ProvenancePath = "$ApkPath.provenance.json"

function Invoke-NativeProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$EchoOutput,
        [switch]$AllowFailure
    )

    $rawOutput = @(& $FilePath @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $lines = @($rawOutput | ForEach-Object { $_.ToString() })

    if ($EchoOutput) {
        foreach ($line in $lines) {
            Write-Host $line
        }
    }

    if (-not $AllowFailure -and $exitCode -ne 0) {
        $rendered = if ($lines.Count -gt 0) { $lines -join [Environment]::NewLine } else { '<no output>' }
        throw "Command failed with exit code $exitCode: $FilePath $($Arguments -join ' ')`n$rendered"
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Lines = $lines
    }
}

function Get-Sha256ForText {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

function Get-GitEvidence {
    param([Parameter(Mandatory = $true)][string]$Root)

    $gitCommand = Get-Command -Name git -CommandType Application -ErrorAction Stop
    $git = $gitCommand.Source

    $headResult = Invoke-NativeProcess -FilePath $git -Arguments @('-C', $Root, 'rev-parse', 'HEAD')
    $head = (($headResult.Lines | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1) ?? '').Trim()
    if ($head -notmatch '^[0-9a-fA-F]{40}$') {
        throw "Git HEAD is unavailable or not a full 40-character SHA: '$head'"
    }

    $statusResult = Invoke-NativeProcess -FilePath $git -Arguments @('-C', $Root, '-c', 'core.quotepath=false', 'status', '--porcelain=v1', '--untracked-files=all')
    $diffResult = Invoke-NativeProcess -FilePath $git -Arguments @('-C', $Root, 'diff', '--binary', 'HEAD', '--', '.')
    $statusText = $statusResult.Lines -join "`n"
    $diffText = $diffResult.Lines -join "`n"
    $fingerprint = Get-Sha256ForText -Text ("STATUS`n$statusText`nDIFF`n$diffText")

    return [pscustomobject]@{
        Head = $head.ToLowerInvariant()
        IsDirty = $statusResult.Lines.Count -gt 0
        WorktreeFingerprint = $fingerprint
    }
}

function Write-Provenance {
    param(
        [Parameter(Mandatory = $true)]$GitEvidence,
        [Parameter(Mandatory = $true)][string]$CurrentApkSha
    )

    $record = [ordered]@{
        SchemaVersion = 1
        GitHead = $GitEvidence.Head
        WorktreeFingerprint = $GitEvidence.WorktreeFingerprint
        ApkSha256 = $CurrentApkSha
    }
    $record | ConvertTo-Json | Set-Content -LiteralPath $ProvenancePath -Encoding utf8NoBOM
}

function Assert-CurrentProvenance {
    param(
        [Parameter(Mandatory = $true)]$GitEvidence,
        [Parameter(Mandatory = $true)][string]$CurrentApkSha
    )

    if (-not (Test-Path -LiteralPath $ProvenancePath -PathType Leaf)) {
        throw "-NoBuild refused: provenance sidecar is missing. Run this script once without -NoBuild for the current checkout."
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
        $record.ApkSha256 -ne $CurrentApkSha) {
        throw "-NoBuild refused: the APK is stale or does not match the current Git/worktree state."
    }
}

function Resolve-AdbExecutable {
    param([string]$ExplicitPath)

    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        if (-not (Test-Path -LiteralPath $ExplicitPath -PathType Leaf)) {
            throw "Explicit -AdbPath does not exist: $ExplicitPath"
        }
        return (Resolve-Path -LiteralPath $ExplicitPath).Path
    }

    foreach ($sdkRoot in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
            continue
        }
        $candidate = Join-Path -Path $sdkRoot -ChildPath 'platform-tools/adb.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $pathCommand = Get-Command -Name adb.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($null -ne $pathCommand) {
        return $pathCommand.Source
    }

    throw 'ADB was not found. Use -AdbPath, ANDROID_SDK_ROOT, ANDROID_HOME, or place adb.exe on PATH.'
}

function Get-AdbDevices {
    param([Parameter(Mandatory = $true)][string]$AdbExecutable)

    $result = Invoke-NativeProcess -FilePath $AdbExecutable -Arguments @('devices', '-l')
    $devices = @()
    foreach ($line in $result.Lines) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith('List of devices attached')) {
            continue
        }
        if ($trimmed -notmatch '^(?<serial>\S+)\s+(?<state>\S+)(?:\s+(?<details>.*))?$') {
            continue
        }

        $serialValue = $Matches.serial
        $stateValue = $Matches.state
        $detailsValue = if ($Matches.ContainsKey('details')) { $Matches['details'] } else { '' }
        $detailMap = @{}
        foreach ($token in ($detailsValue -split '\s+')) {
            if ($token -match '^(?<key>[^:]+):(?<value>.*)$') {
                $detailMap[$Matches.key] = $Matches.value
            }
        }

        $devices += [pscustomobject]@{
            Serial = $serialValue
            State = $stateValue
            Model = ($detailMap['model'] ?? '')
            Product = ($detailMap['product'] ?? '')
            Device = ($detailMap['device'] ?? '')
        }
    }
    return @($devices)
}

function Write-DeviceCandidates {
    param([Parameter(Mandatory = $true)][object[]]$Devices)

    if ($Devices.Count -eq 0) {
        Write-Host 'ADB devices: <none>'
        return
    }

    Write-Host 'ADB devices:'
    foreach ($device in $Devices) {
        Write-Host ("  serial={0} state={1} model={2} product={3} device={4}" -f $device.Serial, $device.State, $device.Model, $device.Product, $device.Device)
    }
}

function Select-AdbDevice {
    param(
        [Parameter(Mandatory = $true)][object[]]$Devices,
        [string]$RequestedSerial
    )

    if (-not [string]::IsNullOrWhiteSpace($RequestedSerial)) {
        $matches = @($Devices | Where-Object { $_.Serial -ceq $RequestedSerial })
        if ($matches.Count -ne 1) {
            Write-DeviceCandidates -Devices $Devices
            throw "Requested ADB serial was not found exactly once: $RequestedSerial"
        }
        if ($matches[0].State -ne 'device') {
            Write-DeviceCandidates -Devices $Devices
            throw "Requested ADB serial is not online in 'device' state: $RequestedSerial ($($matches[0].State))"
        }
        return $matches[0]
    }

    if ($Devices.Count -ne 1 -or $Devices[0].State -ne 'device') {
        Write-DeviceCandidates -Devices $Devices
        throw 'Exactly one attached online ADB device is required when -Serial is omitted.'
    }
    return $Devices[0]
}

function Invoke-SelectedAdb {
    param(
        [Parameter(Mandatory = $true)][string]$AdbExecutable,
        [Parameter(Mandatory = $true)][string]$SelectedSerial,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$EchoOutput,
        [switch]$AllowFailure
    )

    return Invoke-NativeProcess -FilePath $AdbExecutable -Arguments (@('-s', $SelectedSerial) + $Arguments) -EchoOutput:$EchoOutput -AllowFailure:$AllowFailure
}

function Get-SelectedAdbValue {
    param(
        [Parameter(Mandatory = $true)][string]$AdbExecutable,
        [Parameter(Mandatory = $true)][string]$SelectedSerial,
        [Parameter(Mandatory = $true)][string]$PropertyName
    )

    $result = Invoke-SelectedAdb -AdbExecutable $AdbExecutable -SelectedSerial $SelectedSerial -Arguments @('shell', 'getprop', $PropertyName)
    return ((($result.Lines | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1) ?? '')).Trim()
}

function Get-BatteryEvidence {
    param(
        [Parameter(Mandatory = $true)][string]$AdbExecutable,
        [Parameter(Mandatory = $true)][string]$SelectedSerial
    )

    $result = Invoke-SelectedAdb -AdbExecutable $AdbExecutable -SelectedSerial $SelectedSerial -Arguments @('shell', 'dumpsys', 'battery') -AllowFailure
    if ($result.ExitCode -ne 0) {
        return [pscustomobject]@{ Level = 'unavailable'; Status = 'unavailable' }
    }

    $text = $result.Lines -join "`n"
    $level = if ($text -match '(?m)^\s*level:\s*(?<value>\d+)\s*$') { $Matches.value } else { 'unavailable' }
    $statusCode = if ($text -match '(?m)^\s*status:\s*(?<value>\d+)\s*$') { $Matches.value } else { 'unavailable' }
    $statusNames = @{ '1' = 'unknown'; '2' = 'charging'; '3' = 'discharging'; '4' = 'not-charging'; '5' = 'full' }
    $status = if ($statusNames.ContainsKey($statusCode)) { "$statusCode ($($statusNames[$statusCode]))" } else { $statusCode }
    return [pscustomobject]@{ Level = $level; Status = $status }
}

try {
    if ($NoLaunch -and -not [string]::IsNullOrWhiteSpace($CaptureLogcat)) {
        throw '-CaptureLogcat requires launch; remove -NoLaunch or omit -CaptureLogcat.'
    }
    if (-not (Test-Path -LiteralPath $GradleWrapper -PathType Leaf)) {
        throw "Checked-in Gradle wrapper was not found: $GradleWrapper"
    }

    $gitEvidence = Get-GitEvidence -Root $RepoRoot

    if (-not $NoBuild) {
        Remove-Item -LiteralPath $ApkPath -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $ProvenancePath -Force -ErrorAction SilentlyContinue
        Write-Host "Building current checkout with: $GradleWrapper :app:assembleDebug"
        Invoke-NativeProcess -FilePath $GradleWrapper -Arguments @(':app:assembleDebug') -EchoOutput | Out-Null
    }

    if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
        throw "Expected debug APK does not exist: $ApkPath"
    }

    $apkSha = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($NoBuild) {
        Assert-CurrentProvenance -GitEvidence $gitEvidence -CurrentApkSha $apkSha
    }
    else {
        Write-Provenance -GitEvidence $gitEvidence -CurrentApkSha $apkSha
    }

    Write-Host '--- Build provenance ---'
    Write-Host "Git HEAD: $($gitEvidence.Head)"
    Write-Host ("Git worktree: {0}" -f $(if ($gitEvidence.IsDirty) { 'dirty' } else { 'clean' }))
    Write-Host "APK path: $ApkPath"
    Write-Host "APK SHA-256: $apkSha"

    $adb = Resolve-AdbExecutable -ExplicitPath $AdbPath
    $devices = Get-AdbDevices -AdbExecutable $adb
    $selected = Select-AdbDevice -Devices $devices -RequestedSerial $Serial
    $selectedSerial = $selected.Serial

    $manufacturer = Get-SelectedAdbValue -AdbExecutable $adb -SelectedSerial $selectedSerial -PropertyName 'ro.product.manufacturer'
    $model = Get-SelectedAdbValue -AdbExecutable $adb -SelectedSerial $selectedSerial -PropertyName 'ro.product.model'
    $product = Get-SelectedAdbValue -AdbExecutable $adb -SelectedSerial $selectedSerial -PropertyName 'ro.product.name'
    $deviceName = Get-SelectedAdbValue -AdbExecutable $adb -SelectedSerial $selectedSerial -PropertyName 'ro.product.device'
    $androidRelease = Get-SelectedAdbValue -AdbExecutable $adb -SelectedSerial $selectedSerial -PropertyName 'ro.build.version.release'
    $sdkLevel = Get-SelectedAdbValue -AdbExecutable $adb -SelectedSerial $selectedSerial -PropertyName 'ro.build.version.sdk'
    $fingerprint = Get-SelectedAdbValue -AdbExecutable $adb -SelectedSerial $selectedSerial -PropertyName 'ro.build.fingerprint'
    $battery = Get-BatteryEvidence -AdbExecutable $adb -SelectedSerial $selectedSerial

    Write-Host '--- Selected device ---'
    Write-Host "ADB path: $adb"
    Write-Host "Serial: $selectedSerial"
    Write-Host "Manufacturer: $manufacturer"
    Write-Host "Model: $model"
    Write-Host "Product: $product"
    Write-Host "Device: $deviceName"
    Write-Host "Android release: $androidRelease"
    Write-Host "SDK level: $sdkLevel"
    Write-Host "Build fingerprint: $fingerprint"
    Write-Host "Battery level: $($battery.Level)"
    Write-Host "Battery status: $($battery.Status)"

    Write-Host 'Installing debug APK with replacement semantics...'
    $installResult = Invoke-SelectedAdb -AdbExecutable $adb -SelectedSerial $selectedSerial -Arguments @('install', '-r', $ApkPath) -EchoOutput
    if (($installResult.Lines -join "`n") -match '(?im)^\s*Failure\b') {
        throw 'ADB reported an installation failure.'
    }

    if ($ClearAppData) {
        Write-Host 'Clearing application data (explicit opt-in)...'
        $clearResult = Invoke-SelectedAdb -AdbExecutable $adb -SelectedSerial $selectedSerial -Arguments @('shell', 'pm', 'clear', $ApplicationId) -EchoOutput
        if (($clearResult.Lines -join "`n") -notmatch '(?im)^\s*Success\s*$') {
            throw 'Application data clear did not report Success.'
        }
    }

    if ($GrantCameraPermission) {
        Write-Host 'Granting camera permission (explicit opt-in)...'
        Invoke-SelectedAdb -AdbExecutable $adb -SelectedSerial $selectedSerial -Arguments @('shell', 'pm', 'grant', $ApplicationId, $CameraPermission) -EchoOutput | Out-Null
    }

    if (-not $NoLaunch) {
        if (-not [string]::IsNullOrWhiteSpace($CaptureLogcat)) {
            Invoke-SelectedAdb -AdbExecutable $adb -SelectedSerial $selectedSerial -Arguments @('logcat', '-c') | Out-Null
        }

        Write-Host "Launching $LaunchComponent..."
        $launchResult = Invoke-SelectedAdb -AdbExecutable $adb -SelectedSerial $selectedSerial -Arguments @('shell', 'am', 'start', '-W', '-n', $LaunchComponent) -EchoOutput
        if (($launchResult.Lines -join "`n") -match '(?im)^\s*(Error:|Exception)') {
            throw 'ADB activity start reported an error.'
        }

        if (-not [string]::IsNullOrWhiteSpace($CaptureLogcat)) {
            $pidResult = Invoke-SelectedAdb -AdbExecutable $adb -SelectedSerial $selectedSerial -Arguments @('shell', 'pidof', $ApplicationId)
            $pid = ((($pidResult.Lines | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1) ?? '') -split '\s+')[0]
            if ($pid -notmatch '^\d+$') {
                throw "Could not resolve a running PID for $ApplicationId after launch."
            }

            $logResult = Invoke-SelectedAdb -AdbExecutable $adb -SelectedSerial $selectedSerial -Arguments @('logcat', '-d', '-v', 'threadtime', "--pid=$pid")
            $logPath = if ([System.IO.Path]::IsPathRooted($CaptureLogcat)) {
                [System.IO.Path]::GetFullPath($CaptureLogcat)
            }
            else {
                [System.IO.Path]::GetFullPath((Join-Path -Path (Get-Location).Path -ChildPath $CaptureLogcat))
            }
            $logDirectory = Split-Path -Path $logPath -Parent
            if (-not [string]::IsNullOrWhiteSpace($logDirectory)) {
                New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
            }
            $logResult.Lines | Set-Content -LiteralPath $logPath -Encoding utf8NoBOM
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
