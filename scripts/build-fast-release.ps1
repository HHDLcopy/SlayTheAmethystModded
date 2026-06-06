[CmdletBinding()]
param(
    [string]$StoreFile,
    [string]$KeyAlias = 'upload'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

function Set-OrRestoreEnv {
    param(
        [Parameter(Mandatory = $true)]
        [hashtable]$Snapshot
    )

    foreach ($entry in $Snapshot.GetEnumerator()) {
        if ($entry.Value.Exists) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value.Value, 'Process')
        } else {
            Remove-Item "Env:$($entry.Key)" -ErrorAction SilentlyContinue
        }
    }
}

function Resolve-RequiredEnvValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    foreach ($target in @(
            [System.EnvironmentVariableTarget]::Process,
            [System.EnvironmentVariableTarget]::User,
            [System.EnvironmentVariableTarget]::Machine
        )) {
        $value = [Environment]::GetEnvironmentVariable($Name, $target)
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            return $value
        }
    }

    throw "Missing environment variable: $Name"
}

function Resolve-GradleUserHome {
    if (-not [string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
        return [System.IO.Path]::GetFullPath($env:GRADLE_USER_HOME)
    }

    if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
        return [System.IO.Path]::GetFullPath((Join-Path $env:USERPROFILE '.gradle'))
    }

    $profilePath = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
    if ([string]::IsNullOrWhiteSpace($profilePath)) {
        throw 'Could not resolve a Gradle user home.'
    }
    return [System.IO.Path]::GetFullPath((Join-Path $profilePath '.gradle'))
}

function Main {
    $scriptDir = $PSScriptRoot
    $repoRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptDir '..'))
    $gradleWrapper = Join-Path $repoRoot 'gradlew.bat'
    $resolvedStoreFile = if ([string]::IsNullOrWhiteSpace($StoreFile)) {
        Join-Path $repoRoot 'signing\stamethyst-upload.jks'
    } else {
        $StoreFile
    }

    if (-not (Test-Path -LiteralPath $gradleWrapper)) {
        throw "Missing gradle wrapper: $gradleWrapper"
    }
    if (-not (Test-Path -LiteralPath $resolvedStoreFile)) {
        throw "Missing release keystore: $resolvedStoreFile"
    }

    $resolvedStorePassword = Resolve-RequiredEnvValue -Name 'RELEASE_STORE_PASSWORD'
    $resolvedKeyPassword = Resolve-RequiredEnvValue -Name 'RELEASE_KEY_PASSWORD'

    $envSnapshot = @{
        GRADLE_USER_HOME = @{
            Exists = Test-Path Env:GRADLE_USER_HOME
            Value = $env:GRADLE_USER_HOME
        }
        RELEASE_STORE_FILE = @{
            Exists = Test-Path Env:RELEASE_STORE_FILE
            Value = $env:RELEASE_STORE_FILE
        }
        RELEASE_STORE_PASSWORD = @{
            Exists = Test-Path Env:RELEASE_STORE_PASSWORD
            Value = $env:RELEASE_STORE_PASSWORD
        }
        RELEASE_KEY_ALIAS = @{
            Exists = Test-Path Env:RELEASE_KEY_ALIAS
            Value = $env:RELEASE_KEY_ALIAS
        }
        RELEASE_KEY_PASSWORD = @{
            Exists = Test-Path Env:RELEASE_KEY_PASSWORD
            Value = $env:RELEASE_KEY_PASSWORD
        }
    }

    $didPushLocation = $false
    try {
        $resolvedGradleUserHome = Resolve-GradleUserHome
        [Environment]::SetEnvironmentVariable('GRADLE_USER_HOME', $resolvedGradleUserHome, 'Process')
        Write-Host "Gradle user home: $resolvedGradleUserHome"

        [Environment]::SetEnvironmentVariable('RELEASE_STORE_FILE', $resolvedStoreFile, 'Process')
        [Environment]::SetEnvironmentVariable('RELEASE_STORE_PASSWORD', $resolvedStorePassword, 'Process')
        [Environment]::SetEnvironmentVariable('RELEASE_KEY_ALIAS', $KeyAlias, 'Process')
        [Environment]::SetEnvironmentVariable('RELEASE_KEY_PASSWORD', $resolvedKeyPassword, 'Process')

        Push-Location $repoRoot
        $didPushLocation = $true

        & $gradleWrapper `
            ':app:assembleFastRelease' `
            --stacktrace `
            --console=plain
        if ($LASTEXITCODE -ne 0) {
            throw 'assembleFastRelease failed.'
        }

        $outputDir = Join-Path $repoRoot 'app\build\outputs\apk\fastRelease'
        Write-Host "Fast release APK directory: $outputDir"
        Write-Host 'Fast release skips release preflight lint, lintVital, R8 minification, resource shrinking, and release native cache cleanup.'
    } finally {
        if ($didPushLocation) {
            Pop-Location
        }
        Set-OrRestoreEnv -Snapshot $envSnapshot
    }
}

Main
