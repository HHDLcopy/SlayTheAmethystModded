[CmdletBinding()]
param(
    [string]$StoreFile,
    [string]$KeyAlias = 'upload',
    [switch]$SkipLintCheck
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\build-android-release-common.ps1"

Invoke-StsAndroidReleaseBuild `
    -StoreFile $StoreFile `
    -KeyAlias $KeyAlias `
    -SkipLintCheck:$SkipLintCheck `
    -GradleTasks @(':app:assembleRelease') `
    -OutputDirs @('app\build\outputs\apk\release') `
    -DisplayName 'Slim release build'
