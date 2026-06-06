[CmdletBinding()]
param(
    [string]$StoreFile,
    [string]$KeyAlias = 'upload',
    [switch]$SkipLintCheck
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

function Invoke-GradleTask {
    param(
        [Parameter(Mandatory = $true)]
        [string]$GradleWrapper,
        [Parameter(Mandatory = $true)]
        [string[]]$Tasks,
        [Parameter(Mandatory = $true)]
        [string]$FailureMessage
    )

    & $GradleWrapper @Tasks --stacktrace --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw $FailureMessage
    }
}

function Get-VerifiedRepoChildPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot,
        [Parameter(Mandatory = $true)]
        [string]$ChildPath
    )

    $fullRepoRoot = [System.IO.Path]::GetFullPath($RepoRoot).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $fullChildPath = [System.IO.Path]::GetFullPath((Join-Path $fullRepoRoot $ChildPath))
    $repoRootPrefix = $fullRepoRoot + [System.IO.Path]::DirectorySeparatorChar

    if (-not $fullChildPath.StartsWith($repoRootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean path outside repository: $fullChildPath"
    }

    return $fullChildPath
}

function Clear-ReleaseNativeBuildCache {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot
    )

    $nativeCachePaths = @(
        'app\.cxx\RelWithDebInfo',
        'app\build\intermediates\cxx\RelWithDebInfo'
    )

    foreach ($nativeCachePath in $nativeCachePaths) {
        $fullNativeCachePath = Get-VerifiedRepoChildPath -RepoRoot $RepoRoot -ChildPath $nativeCachePath
        if (Test-Path -LiteralPath $fullNativeCachePath) {
            Write-Host "Removing release native build cache: $fullNativeCachePath"
            Remove-Item -LiteralPath $fullNativeCachePath -Recurse -Force
        }
    }
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

        if (-not $SkipLintCheck) {
            Invoke-GradleTask `
                -GradleWrapper $gradleWrapper `
                -Tasks @(':app:lintDebug') `
                -FailureMessage 'lintDebug failed.'
        }
        Clear-ReleaseNativeBuildCache -RepoRoot $repoRoot
        Invoke-GradleTask `
            -GradleWrapper $gradleWrapper `
            -Tasks @(':app:assembleRelease') `
            -FailureMessage 'assembleRelease failed.'

        $outputDir = Join-Path $repoRoot 'app\build\outputs\apk\release'
        Write-Host "Release APK directory: $outputDir"
    } finally {
        if ($didPushLocation) {
            Pop-Location
        }
        Set-OrRestoreEnv -Snapshot $envSnapshot
    }
}

Main
