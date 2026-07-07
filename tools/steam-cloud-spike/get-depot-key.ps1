[CmdletBinding()]
param(
    [uint32] $AppId = 646570,
    [uint32] $DepotId = 877621,
    [string] $Username,
    [string] $Password,
    [string] $Steam2FACode,
    [string] $EmailCode,
    [string] $AccountName,
    [string] $SteamId64,
    [string] $RefreshToken,
    [string] $GuardData,
    [string] $EnvFile,
    [string] $ProxyUrl,
    [int] $LoginTimeoutMinutes = 5,
    [switch] $DebugProtocol,
    [switch] $Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($Help) {
    Write-Host @"
Usage:
  .\tools\steam-cloud-spike\get-depot-key.ps1

Examples:
  .\tools\steam-cloud-spike\get-depot-key.ps1 -Username "account"
  .\tools\steam-cloud-spike\get-depot-key.ps1 -EnvFile "agent-tmp\steam-depot-key-646570-877621.env"
  .\tools\steam-cloud-spike\get-depot-key.ps1 -ProxyUrl "http://127.0.0.1:7897"

Defaults:
  -AppId 646570
  -DepotId 877621

Output:
  Prints depotKeyHex and depotKeyBase64 to the terminal. No key file is written.
"@
    exit 0
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptDir "..\..")
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"

if (-not (Test-Path -LiteralPath $gradleWrapper)) {
    throw "Gradle wrapper not found: $gradleWrapper"
}

function Set-TemporaryEnv {
    param(
        [hashtable] $OriginalValues,
        [string] $Name,
        [string] $Value
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }

    if (-not $OriginalValues.ContainsKey($Name)) {
        $OriginalValues[$Name] = [Environment]::GetEnvironmentVariable($Name, "Process")
    }

    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

function Restore-TemporaryEnv {
    param(
        [hashtable] $OriginalValues
    )

    foreach ($entry in $OriginalValues.GetEnumerator()) {
        if ($null -eq $entry.Value) {
            [Environment]::SetEnvironmentVariable([string] $entry.Key, $null, "Process")
        } else {
            [Environment]::SetEnvironmentVariable([string] $entry.Key, [string] $entry.Value, "Process")
        }
    }
}

function Read-PlainPassword {
    param(
        [string] $Prompt
    )

    $securePassword = Read-Host $Prompt -AsSecureString
    return [System.Net.NetworkCredential]::new("", $securePassword).Password
}

$originalEnv = @{}

try {
    $hasRefreshToken = -not [string]::IsNullOrWhiteSpace($RefreshToken) `
        -or -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("STEAM_REFRESH_TOKEN", "Process")) `
        -or -not [string]::IsNullOrWhiteSpace($EnvFile)

    if (-not $hasRefreshToken) {
        if ([string]::IsNullOrWhiteSpace($Username) -and [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("STEAM_USERNAME", "Process"))) {
            $Username = Read-Host "Steam username"
        }

        if ([string]::IsNullOrWhiteSpace($Password) -and [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("STEAM_PASSWORD", "Process"))) {
            $Password = Read-PlainPassword "Steam password"
        }
    }

    Set-TemporaryEnv $originalEnv "STEAM_USERNAME" $Username
    Set-TemporaryEnv $originalEnv "STEAM_PASSWORD" $Password
    Set-TemporaryEnv $originalEnv "STEAM_2FA_CODE" $Steam2FACode
    Set-TemporaryEnv $originalEnv "STEAM_EMAIL_CODE" $EmailCode
    Set-TemporaryEnv $originalEnv "STEAM_ACCOUNT_NAME" $AccountName
    Set-TemporaryEnv $originalEnv "STEAM_STEAM_ID64" $SteamId64
    Set-TemporaryEnv $originalEnv "STEAM_REFRESH_TOKEN" $RefreshToken
    Set-TemporaryEnv $originalEnv "STEAM_GUARD_DATA" $GuardData
    Set-TemporaryEnv $originalEnv "STS_DEPOT_KEY_ENV_FILE" $EnvFile
    Set-TemporaryEnv $originalEnv "STEAM_PROXY_URL" $ProxyUrl

    $toolArgs = @(
        "--app-id", [string] $AppId,
        "--depot-id", [string] $DepotId,
        "--login-timeout-minutes", [string] $LoginTimeoutMinutes,
        "--print-key",
        "--no-output"
    )

    if ($DebugProtocol) {
        $toolArgs += "--debug"
    }

    Push-Location $repoRoot
    try {
        & $gradleWrapper ":tools:steam-cloud-spike:depotKey" "--console=plain" "--no-parallel" "--args=$($toolArgs -join ' ')"
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    } finally {
        Pop-Location
    }
} finally {
    Restore-TemporaryEnv $originalEnv
}
