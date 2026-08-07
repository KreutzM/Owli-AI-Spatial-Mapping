$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Target = Join-Path $Root "gradle\wrapper\gradle-wrapper.jar"
$Expected = "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"
$Url = "https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"

function Assert-Checksum([string] $Path) {
    $Stream = [System.IO.File]::OpenRead($Path)
    $Hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $Actual = ([System.BitConverter]::ToString($Hasher.ComputeHash($Stream))).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $Hasher.Dispose()
        $Stream.Dispose()
    }
    if ($Actual -ne $Expected) {
        throw "Gradle wrapper checksum mismatch. Expected $Expected but got $Actual."
    }
}

if (Test-Path $Target) {
    Assert-Checksum $Target
    exit 0
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Target) | Out-Null
$Temp = "$Target.tmp"
try {
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Temp
    Assert-Checksum $Temp
    Move-Item -Force $Temp $Target
    Write-Host "Verified Gradle wrapper installed at $Target"
}
finally {
    if (Test-Path $Temp) { Remove-Item -Force $Temp }
}
