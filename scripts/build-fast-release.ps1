[CmdletBinding()]
param(
    [string]$StoreFile,
    [string]$KeyAlias = 'upload',
    [switch]$RunLintCheck
)

$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\build-fast-release-slim.ps1" `
    -StoreFile $StoreFile `
    -KeyAlias $KeyAlias `
    -RunLintCheck:$RunLintCheck
exit 0
