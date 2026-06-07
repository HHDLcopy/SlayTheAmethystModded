[CmdletBinding()]
param(
    [string]$StoreFile,
    [string]$KeyAlias = 'upload',
    [switch]$RunLintCheck
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\build-android-release-common.ps1"

Invoke-StsAndroidReleaseBuild `
    -StoreFile $StoreFile `
    -KeyAlias $KeyAlias `
    -SkipLintCheck:(-not $RunLintCheck) `
    -SkipNativeCacheCleanup `
    -GradleTasks @(':app:assembleFastFullRelease') `
    -OutputDirs @('app\build\outputs\apk\fastFullRelease') `
    -DisplayName 'Fast full release build' `
    -Fast
