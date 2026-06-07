[CmdletBinding()]
param(
    [string]$StoreFile,
    [string]$KeyAlias = 'upload',
    [switch]$SkipLintCheck
)

$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\build-release-fast.ps1" `
    -StoreFile $StoreFile `
    -KeyAlias $KeyAlias `
    -SkipLintCheck:$SkipLintCheck
exit 0
