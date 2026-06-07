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
    -GradleTasks @(':app:assembleFullRelease') `
    -OutputDirs @('app\build\outputs\apk\fullRelease') `
    -DisplayName 'Full release build'
