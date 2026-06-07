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
    -GradleTasks @(':app:assembleFastSlimRelease') `
    -OutputDirs @('app\build\outputs\apk\fastSlimRelease') `
    -DisplayName 'Fast slim release build' `
    -Fast
