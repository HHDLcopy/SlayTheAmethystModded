[CmdletBinding()]
param(
    [ValidateSet('doctor', 'install', 'start', 'stop', 'logs', 'screenshot', 'status', 'smoke')]
    [string]$Command = 'doctor',
    [ValidateSet('mts_basemod', 'mts', 'vanilla')]
    [string]$LaunchMode = 'mts_basemod',
    [string]$DeviceSerial = '',
    [string]$OutDir = '',
    [int]$TimeoutSeconds = 120,
    [int]$PollIntervalSeconds = 2,
    [switch]$ForceJvmCrash,
    [switch]$ForceRuntimeCrash,
    [switch]$SkipInstall,
    [switch]$NoStopAfterSmoke
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

$script:RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:GradleWrapper = $null
$script:AdbPath = $null
$script:ApplicationId = $null
$script:ResolvedDeviceSerial = $DeviceSerial.Trim()
$script:Operations = New-Object System.Collections.Generic.List[object]
$script:StartedAt = Get-Date
$script:Result = $null

function Get-IsoTimestamp {
    param([datetime]$Value = (Get-Date))
    return $Value.ToUniversalTime().ToString('o')
}

function New-DefaultOutDir {
    $harnessRoot = Join-Path (Join-Path $script:RepoRoot 'debug-artifacts') 'harness'
    return Join-Path $harnessRoot ("{0}-{1}" -f $Command, (Get-Date -Format 'yyyyMMdd-HHmmss'))
}

function Resolve-RepoPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $script:RepoRoot $Path))
}

function Get-ResolvedOutDir {
    if ([string]::IsNullOrWhiteSpace($OutDir)) {
        return New-DefaultOutDir
    }
    return Resolve-RepoPath $OutDir
}

function Test-IsWindows {
    $isWindowsVariable = Get-Variable -Name IsWindows -ErrorAction SilentlyContinue
    if ($null -ne $isWindowsVariable) {
        return [bool]$isWindowsVariable.Value
    }
    return $env:OS -eq 'Windows_NT' -or
        [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
}

function Limit-Text {
    param(
        [AllowNull()][string]$Text,
        [int]$MaxLength = 6000
    )
    if ($null -eq $Text) {
        return ''
    }
    if ($Text.Length -le $MaxLength) {
        return $Text
    }
    return $Text.Substring($Text.Length - $MaxLength, $MaxLength)
}

function Format-CommandForLog {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @()
    )
    $parts = New-Object System.Collections.Generic.List[string]
    $allParts = @($FilePath) + $Arguments
    foreach ($part in $allParts) {
        if ($part -match '[\s"]') {
            $parts.Add('"' + $part.Replace('"', '\"') + '"')
        } else {
            $parts.Add($part)
        }
    }
    return ($parts -join ' ')
}

function Escape-ProcessArgument {
    param([AllowNull()][string]$Argument)
    if ($null -eq $Argument) {
        return '""'
    }
    if ($Argument.Length -gt 0 -and $Argument -notmatch '[\s"]') {
        return $Argument
    }

    $builder = New-Object System.Text.StringBuilder
    [void]$builder.Append('"')
    $backslashes = 0
    foreach ($char in $Argument.ToCharArray()) {
        if ($char -eq '\') {
            $backslashes += 1
            continue
        }
        if ($char -eq '"') {
            [void]$builder.Append(('\' * (($backslashes * 2) + 1)))
            [void]$builder.Append('"')
            $backslashes = 0
            continue
        }
        if ($backslashes -gt 0) {
            [void]$builder.Append(('\' * $backslashes))
            $backslashes = 0
        }
        [void]$builder.Append($char)
    }
    if ($backslashes -gt 0) {
        [void]$builder.Append(('\' * ($backslashes * 2)))
    }
    [void]$builder.Append('"')
    return $builder.ToString()
}

function Add-ProcessArguments {
    param(
        [Parameter(Mandatory = $true)][System.Diagnostics.ProcessStartInfo]$StartInfo,
        [string[]]$Arguments = @()
    )
    $argumentListProperty = [System.Diagnostics.ProcessStartInfo].GetProperty('ArgumentList')
    if ($null -ne $argumentListProperty) {
        foreach ($argument in $Arguments) {
            [void]$StartInfo.ArgumentList.Add($argument)
        }
        return
    }

    $StartInfo.Arguments = (($Arguments | ForEach-Object { Escape-ProcessArgument $_ }) -join ' ')
}

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory = $script:RepoRoot,
        [int]$TimeoutSeconds = 0,
        [switch]$AllowFailure
    )

    $started = Get-Date
    $output = ''
    $exitCode = 0
    $timedOut = $false
    $process = New-Object System.Diagnostics.Process
    try {
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        $startInfo.FileName = $FilePath
        $startInfo.WorkingDirectory = $WorkingDirectory
        $startInfo.UseShellExecute = $false
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        Add-ProcessArguments -StartInfo $startInfo -Arguments $Arguments
        $process.StartInfo = $startInfo

        if (-not $process.Start()) {
            throw "Failed to start command: $FilePath"
        }

        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if ($TimeoutSeconds -gt 0) {
            $finished = $process.WaitForExit($TimeoutSeconds * 1000)
            if (-not $finished) {
                $timedOut = $true
                try {
                    $process.Kill()
                } catch {
                    # The process may have exited between WaitForExit and Kill.
                }
                $process.WaitForExit()
            }
        } else {
            $process.WaitForExit()
        }
        $stdout = $stdoutTask.Result
        $stderr = $stderrTask.Result
        $outputParts = @($stdout, $stderr) | Where-Object { -not [string]::IsNullOrEmpty($_) }
        $output = $outputParts -join [Environment]::NewLine
        $exitCode = if ($timedOut) { -1 } else { $process.ExitCode }
    } finally {
        $process.Dispose()
    }

    $ended = Get-Date
    $operation = [ordered]@{
        command = Format-CommandForLog -FilePath $FilePath -Arguments $Arguments
        exitCode = $exitCode
        startedAt = Get-IsoTimestamp $started
        endedAt = Get-IsoTimestamp $ended
        durationMs = [int64]($ended - $started).TotalMilliseconds
        timedOut = $timedOut
        outputTail = Limit-Text $output
    }
    $script:Operations.Add($operation)

    if ($timedOut -and -not $AllowFailure) {
        throw ("Command timed out after {0}s: {1}`n{2}" -f $TimeoutSeconds, $operation.command, (Limit-Text $output 2000))
    }
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw ("Command failed with exit code {0}: {1}`n{2}" -f $exitCode, $operation.command, (Limit-Text $output 2000))
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $output
    }
}

function Read-GradleProperty {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [string]$DefaultValue = ''
    )
    $propertiesFile = Join-Path $script:RepoRoot 'gradle.properties'
    if (-not (Test-Path -LiteralPath $propertiesFile)) {
        return $DefaultValue
    }
    foreach ($line in Get-Content -LiteralPath $propertiesFile) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -le 0) {
            continue
        }
        $key = $trimmed.Substring(0, $separator).Trim()
        if ($key -eq $Name) {
            return $trimmed.Substring($separator + 1).Trim()
        }
    }
    return $DefaultValue
}

function Read-LocalProperty {
    param([Parameter(Mandatory = $true)][string]$Name)
    $propertiesFile = Join-Path $script:RepoRoot 'local.properties'
    if (-not (Test-Path -LiteralPath $propertiesFile)) {
        return ''
    }
    foreach ($line in Get-Content -LiteralPath $propertiesFile) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -le 0) {
            continue
        }
        $key = $trimmed.Substring(0, $separator).Trim()
        if ($key -eq $Name) {
            $value = $trimmed.Substring($separator + 1).Trim()
            $value = $value -replace '\\:', ':'
            $value = $value -replace '\\\\', '\'
            return Resolve-RepoPath $value
        }
    }
    return ''
}

function Resolve-GradleWrapper {
    $windowsWrapper = Join-Path $script:RepoRoot 'gradlew.bat'
    $unixWrapper = Join-Path $script:RepoRoot 'gradlew'
    if ((Test-IsWindows) -and (Test-Path -LiteralPath $windowsWrapper)) {
        return $windowsWrapper
    }
    if (Test-Path -LiteralPath $unixWrapper) {
        return $unixWrapper
    }
    if (Test-Path -LiteralPath $windowsWrapper) {
        return $windowsWrapper
    }
    throw "Missing Gradle wrapper under: $script:RepoRoot"
}

function Resolve-AdbPath {
    $adbFileName = if (Test-IsWindows) { 'adb.exe' } else { 'adb' }
    $sdkCandidates = New-Object System.Collections.Generic.List[string]
    foreach ($value in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, (Read-LocalProperty -Name 'sdk.dir'))) {
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $sdkCandidates.Add([System.IO.Path]::GetFullPath($value))
        }
    }
    foreach ($sdk in $sdkCandidates) {
        $candidate = Join-Path (Join-Path $sdk 'platform-tools') $adbFileName
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }
    $pathCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $pathCommand) {
        return $pathCommand.Source
    }
    throw 'Could not resolve adb. Set sdk.dir, ANDROID_SDK_ROOT, ANDROID_HOME, or add adb to PATH.'
}

function Build-AdbArgs {
    param([string[]]$Arguments)
    $adbArgs = New-Object System.Collections.Generic.List[string]
    if (-not [string]::IsNullOrWhiteSpace($script:ResolvedDeviceSerial)) {
        $adbArgs.Add('-s')
        $adbArgs.Add($script:ResolvedDeviceSerial)
    }
    foreach ($arg in $Arguments) {
        $adbArgs.Add($arg)
    }
    return $adbArgs.ToArray()
}

function Invoke-Adb {
    param(
        [string[]]$Arguments,
        [int]$TimeoutSeconds = 10,
        [switch]$AllowFailure
    )
    return Invoke-NativeCommand `
        -FilePath $script:AdbPath `
        -Arguments (Build-AdbArgs -Arguments $Arguments) `
        -TimeoutSeconds $TimeoutSeconds `
        -AllowFailure:$AllowFailure
}

function Invoke-Gradle {
    param([string[]]$Arguments)
    $gradleArgs = New-Object System.Collections.Generic.List[string]
    foreach ($arg in $Arguments) {
        $gradleArgs.Add($arg)
    }
    $gradleArgs.Add('--stacktrace')
    $gradleArgs.Add('--console=plain')
    if (Test-IsWindows) {
        $commandProcessor = if ([string]::IsNullOrWhiteSpace($env:COMSPEC)) { 'cmd.exe' } else { $env:COMSPEC }
        return Invoke-NativeCommand `
            -FilePath $commandProcessor `
            -Arguments (@('/c', $script:GradleWrapper) + $gradleArgs.ToArray())
    }
    return Invoke-NativeCommand -FilePath $script:GradleWrapper -Arguments $gradleArgs.ToArray()
}

function Select-HarnessDevice {
    $devicesResult = Invoke-NativeCommand -FilePath $script:AdbPath -Arguments @('devices') -TimeoutSeconds 15 -AllowFailure
    if ($devicesResult.ExitCode -ne 0) {
        throw 'adb devices failed.'
    }

    $onlineDevices = New-Object System.Collections.Generic.List[string]
    foreach ($line in ([regex]::Split($devicesResult.Output, '\r?\n'))) {
        if ($line -match '^([^\s]+)\s+device$') {
            $onlineDevices.Add($matches[1])
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($script:ResolvedDeviceSerial)) {
        if (-not $onlineDevices.Contains($script:ResolvedDeviceSerial)) {
            throw "Requested device is not connected and online: $script:ResolvedDeviceSerial"
        }
        return
    }

    if ($onlineDevices.Count -eq 0) {
        throw 'No connected Android device or emulator is online.'
    }
    if ($onlineDevices.Count -gt 1) {
        throw "Multiple Android devices are online. Pass -DeviceSerial. Devices: $($onlineDevices -join ', ')"
    }
    $script:ResolvedDeviceSerial = $onlineDevices[0]
}

function Initialize-Harness {
    $script:GradleWrapper = Resolve-GradleWrapper
    $script:AdbPath = Resolve-AdbPath
    $script:ApplicationId = Read-GradleProperty -Name 'application.id' -DefaultValue 'io.stamethyst'
    if ([string]::IsNullOrWhiteSpace($script:ApplicationId)) {
        throw 'application.id cannot be empty.'
    }
    Select-HarnessDevice
}

function Get-GradleDeviceProperties {
    $properties = New-Object System.Collections.Generic.List[string]
    if (-not [string]::IsNullOrWhiteSpace($script:ResolvedDeviceSerial)) {
        $properties.Add("-PdeviceSerial=$script:ResolvedDeviceSerial")
    }
    return $properties.ToArray()
}

function Invoke-HarnessInstall {
    $assemblyArgs = @(':app:assembleDebug')
    Invoke-Gradle -Arguments $assemblyArgs | Out-Null

    $apkRoot = Join-Path (Join-Path (Join-Path (Join-Path $script:RepoRoot 'app') 'build') 'outputs') 'apk'
    $apkRoot = Join-Path $apkRoot 'debug'
    $apk = Get-ChildItem -LiteralPath $apkRoot -Filter '*.apk' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $apk) {
        throw "No debug APK found under: $apkRoot"
    }

    $script:Result.artifacts.debugApk = $apk.FullName
    Invoke-Adb -Arguments @('install', '-r', $apk.FullName) -TimeoutSeconds 180 | Out-Null
}

function Invoke-HarnessStart {
    $gradleArgs = New-Object System.Collections.Generic.List[string]
    $gradleArgs.Add(':app:stsStart')
    $gradleArgs.Add("-PlaunchMode=$LaunchMode")
    $gradleArgs.Add("-PforceJvmCrash=$($ForceJvmCrash.IsPresent.ToString().ToLowerInvariant())")
    $gradleArgs.Add("-PforceRuntimeCrash=$($ForceRuntimeCrash.IsPresent.ToString().ToLowerInvariant())")
    foreach ($property in (Get-GradleDeviceProperties)) {
        $gradleArgs.Add($property)
    }
    Invoke-Gradle -Arguments $gradleArgs.ToArray() | Out-Null
}

function Invoke-HarnessStop {
    $gradleArgs = New-Object System.Collections.Generic.List[string]
    $gradleArgs.Add(':app:stsStop')
    foreach ($property in (Get-GradleDeviceProperties)) {
        $gradleArgs.Add($property)
    }
    Invoke-Gradle -Arguments $gradleArgs.ToArray() | Out-Null
}

function Invoke-HarnessLogs {
    param([string]$OutputDirectory)
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

    $gradleArgs = New-Object System.Collections.Generic.List[string]
    $gradleArgs.Add(':app:stsPullLogs')
    $gradleArgs.Add("-PlogsDir=$OutputDirectory")
    foreach ($property in (Get-GradleDeviceProperties)) {
        $gradleArgs.Add($property)
    }
    Invoke-Gradle -Arguments $gradleArgs.ToArray() | Out-Null

    $latestArchive = Get-ChildItem -LiteralPath $OutputDirectory -Filter 'sts-jvm-logs-export-*.zip' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -ne $latestArchive) {
        $script:Result.artifacts.logsZip = $latestArchive.FullName
    }
}

function Invoke-HarnessScreenshot {
    param([string]$OutputDirectory)
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $remotePath = "/sdcard/sts_harness_$timestamp.png"
    $localPath = Join-Path $OutputDirectory "sts-screen-$timestamp.png"

    Invoke-Adb -Arguments @('shell', 'screencap', '-p', $remotePath) | Out-Null
    try {
    Invoke-Adb -Arguments @('pull', $remotePath, $localPath) -TimeoutSeconds 60 | Out-Null
    } finally {
        Invoke-Adb -Arguments @('shell', 'rm', $remotePath) -AllowFailure | Out-Null
    }

    $file = Get-Item -LiteralPath $localPath -ErrorAction SilentlyContinue
    if ($null -eq $file -or $file.Length -le 0) {
        throw "Screenshot was not created or is empty: $localPath"
    }
    $script:Result.artifacts.screenshot = $localPath
    return $localPath
}

function Clear-HarnessRuntimeSignals {
    $stsRoot = Resolve-DeviceStsRoot
    foreach ($relativePath in @('boot_bridge_events.log', 'latest.log')) {
        $rootPath = [string]$stsRoot['root']
        $remotePath = "$rootPath/$relativePath"
        $quotedPath = Quote-AndroidShell $remotePath
        if ([string]$stsRoot['accessMode'] -eq 'run-as') {
            Invoke-Adb -Arguments @(
                'exec-out',
                'run-as',
                $script:ApplicationId,
                'sh',
                '-c',
                "rm -f $quotedPath"
            ) -AllowFailure | Out-Null
        } else {
            Invoke-AdbShellScript -Script "rm -f $quotedPath" -AllowFailure | Out-Null
        }
    }
}

function Quote-AndroidShell {
    param([Parameter(Mandatory = $true)][string]$Value)
    return "'" + $Value.Replace("'", "'""'""'") + "'"
}

function Invoke-AdbShellScript {
    param(
        [Parameter(Mandatory = $true)][string]$Script,
        [int]$TimeoutSeconds = 5,
        [switch]$AllowFailure
    )
    return Invoke-Adb -Arguments @('shell', $Script) -TimeoutSeconds $TimeoutSeconds -AllowFailure:$AllowFailure
}

function Resolve-DeviceStsRoot {
    $packageName = $script:ApplicationId
    $externalCandidates = @(
        "/sdcard/Android/data/$packageName/files/sts",
        "/storage/emulated/0/Android/data/$packageName/files/sts"
    )
    foreach ($candidate in $externalCandidates) {
        $quoted = Quote-AndroidShell $candidate
        $probe = Invoke-AdbShellScript -Script "ls $quoted >/dev/null 2>&1" -AllowFailure
        if ($probe.ExitCode -eq 0) {
            return [ordered]@{
                root = $candidate
                accessMode = 'shell'
            }
        }
    }

    $runAsProbe = Invoke-Adb -Arguments @('exec-out', 'run-as', $packageName, 'sh', '-c', "ls 'files/sts' >/dev/null 2>&1") -TimeoutSeconds 5 -AllowFailure
    if ($runAsProbe.ExitCode -eq 0) {
        return [ordered]@{
            root = 'files/sts'
            accessMode = 'run-as'
        }
    }

    return [ordered]@{
        root = $externalCandidates[0]
        accessMode = 'shell'
    }
}

function Read-RemoteStsText {
    param(
        [Parameter(Mandatory = $true)][System.Collections.IDictionary]$StsRoot,
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [int]$TailLines = 0
    )
    $trimmed = $RelativePath.TrimStart('/')
    $rootPath = [string]$StsRoot['root']
    $accessMode = [string]$StsRoot['accessMode']
    if ($accessMode -eq 'run-as') {
        $remotePath = if ($trimmed.Length -eq 0) { $rootPath } else { "$rootPath/$trimmed" }
        $quoted = Quote-AndroidShell $remotePath
        $readScript = if ($TailLines -gt 0) {
            "if [ -f $quoted ]; then tail -n $TailLines $quoted; fi"
        } else {
            "if [ -f $quoted ]; then cat $quoted; fi"
        }
        $result = Invoke-Adb -Arguments @('exec-out', 'run-as', $script:ApplicationId, 'sh', '-c', $readScript) -TimeoutSeconds 5 -AllowFailure
        return $result.Output
    }

    $remotePath = if ($trimmed.Length -eq 0) { $rootPath } else { "$rootPath/$trimmed" }
    $quotedPath = Quote-AndroidShell $remotePath
    $script = if ($TailLines -gt 0) {
        "if [ -f $quotedPath ]; then tail -n $TailLines $quotedPath; fi"
    } else {
        "if [ -f $quotedPath ]; then cat $quotedPath; fi"
    }
    $read = Invoke-AdbShellScript -Script $script -AllowFailure
    return $read.Output
}

function Parse-BootBridgeEvents {
    param([AllowNull()][string]$Text)
    $latest = $null
    $terminal = $null
    $count = 0
    $safeText = ''
    if ($null -ne $Text) {
        $safeText = $Text
    }

    foreach ($line in ([regex]::Split($safeText, '\r?\n'))) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0) {
            continue
        }
        $parts = $trimmed.Split("`t", 3)
        $eventType = $parts[0].Trim().ToUpperInvariant()
        $progress = $null
        if ($parts.Count -ge 2) {
            [int]$parsed = 0
            if ([int]::TryParse($parts[1].Trim(), [ref]$parsed)) {
                $progress = $parsed
            }
        }
        $message = if ($parts.Count -ge 3) { $parts[2].Trim() } else { '' }
        $event = [ordered]@{
            type = $eventType
            progress = $progress
            message = $message
        }
        $latest = $event
        $count += 1
        if ($eventType -eq 'READY' -or $eventType -eq 'FAIL') {
            $terminal = $event
        }
    }

    return [ordered]@{
        eventCount = $count
        latestEvent = $latest
        terminalEvent = $terminal
    }
}

function Find-CrashMarker {
    param([AllowNull()][string]$Text)
    $markers = @(
        'Game crashed.',
        'Exception occurred in CardCrawlGame render method!',
        'Exception in thread "LWJGL Application"',
        'Forced runtime crash for expected-exit verification'
    )
    $safeText = ''
    if ($null -ne $Text) {
        $safeText = $Text
    }
    foreach ($marker in $markers) {
        if ($safeText.IndexOf($marker, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            return $marker
        }
    }
    return $null
}

function Get-LastNonBlankLine {
    param([AllowNull()][string]$Text)
    $last = $null
    $safeText = ''
    if ($null -ne $Text) {
        $safeText = $Text
    }
    foreach ($line in ([regex]::Split($safeText, '\r?\n'))) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -gt 0) {
            $last = $trimmed
        }
    }
    return $last
}

function Get-ProcessPidText {
    param([Parameter(Mandatory = $true)][string]$ProcessName)
    $quoted = Quote-AndroidShell $ProcessName
    $result = Invoke-AdbShellScript -Script "pidof $quoted 2>/dev/null || true" -AllowFailure
    return $result.Output.Trim()
}

function Get-PackageVersionInfo {
    $quoted = Quote-AndroidShell $script:ApplicationId
    $result = Invoke-AdbShellScript -Script "dumpsys package $quoted 2>/dev/null | grep -E 'version(Name|Code)=' || true" -TimeoutSeconds 5 -AllowFailure
    $versionName = $null
    $versionCode = $null
    foreach ($line in ([regex]::Split($result.Output, '\r?\n'))) {
        $trimmed = $line.Trim()
        if ($trimmed.StartsWith('versionName=')) {
            $versionName = $trimmed.Substring('versionName='.Length)
        } elseif ($trimmed.StartsWith('versionCode=')) {
            $versionCode = $trimmed.Substring('versionCode='.Length).Split(' ')[0]
        }
    }
    return [ordered]@{
        versionName = $versionName
        versionCode = $versionCode
    }
}

function Get-HarnessStatus {
    $stsRoot = Resolve-DeviceStsRoot
    $bootText = Read-RemoteStsText -StsRoot $stsRoot -RelativePath 'boot_bridge_events.log'
    $latestLogTail = Read-RemoteStsText -StsRoot $stsRoot -RelativePath 'latest.log' -TailLines 120
    $boot = Parse-BootBridgeEvents -Text $bootText
    $crashMarker = Find-CrashMarker -Text $latestLogTail

    $packageName = $script:ApplicationId
    $launcherPid = Get-ProcessPidText -ProcessName $packageName
    $gamePid = Get-ProcessPidText -ProcessName "$packageName`:game"
    $prepPid = Get-ProcessPidText -ProcessName "$packageName`:prep"
    $diagPid = Get-ProcessPidText -ProcessName "$packageName`:diag"
    $logcatPid = Get-ProcessPidText -ProcessName "$packageName`:logcat"

    $runtimeSignalState = $null
    if ($null -ne $boot.terminalEvent) {
        $runtimeSignalState = $boot.terminalEvent.type
    } elseif ($null -ne $crashMarker) {
        $runtimeSignalState = 'CRASH_MARKER'
    }

    $observedState = 'NOT_RUNNING'
    if ($null -ne $boot.terminalEvent -and $boot.terminalEvent.type -eq 'FAIL') {
        $observedState = 'FAIL'
    } elseif ($null -ne $crashMarker) {
        $observedState = 'CRASH_MARKER'
    } elseif ($null -ne $boot.terminalEvent -and
        $boot.terminalEvent.type -eq 'READY' -and
        -not [string]::IsNullOrWhiteSpace($gamePid)
    ) {
        $observedState = 'READY'
    } elseif (-not [string]::IsNullOrWhiteSpace($gamePid)) {
        $observedState = 'RUNNING_WITHOUT_TERMINAL_EVENT'
    } elseif (-not [string]::IsNullOrWhiteSpace($launcherPid)) {
        $observedState = 'LAUNCHER_RUNNING'
    }

    return [ordered]@{
        observedState = $observedState
        runtimeSignalState = $runtimeSignalState
        applicationId = $packageName
        deviceSerial = $script:ResolvedDeviceSerial
        package = Get-PackageVersionInfo
        processes = [ordered]@{
            launcher = $launcherPid
            game = $gamePid
            prep = $prepPid
            diag = $diagPid
            logcat = $logcatPid
        }
        storage = $stsRoot
        bootBridge = $boot
        latestLog = [ordered]@{
            lastNonBlankLine = Get-LastNonBlankLine -Text $latestLogTail
            crashMarker = $crashMarker
        }
    }
}

function Wait-HarnessStatus {
    $safeTimeoutSeconds = [Math]::Max(1, $TimeoutSeconds)
    $safePollMs = [Math]::Max(250, $PollIntervalSeconds * 1000)
    $deadline = (Get-Date).AddSeconds($safeTimeoutSeconds)
    $latestStatus = $null

    do {
        $latestStatus = Get-HarnessStatus
        if ($latestStatus.observedState -in @('READY', 'FAIL', 'CRASH_MARKER')) {
            return $latestStatus
        }
        if ((Get-Date) -ge $deadline) {
            return $latestStatus
        }
        Start-Sleep -Milliseconds $safePollMs
    } while ($true)
}

function Set-ResultSuccess {
    param(
        [bool]$Success,
        [string]$Status,
        [string]$Message
    )
    $script:Result.success = $Success
    $script:Result.status = $Status
    $script:Result.message = $Message
}

function Complete-Result {
    $endedAt = Get-Date
    $script:Result.endedAt = Get-IsoTimestamp $endedAt
    $script:Result.durationMs = [int64]($endedAt - $script:StartedAt).TotalMilliseconds
    $script:Result.operations = $script:Operations
}

function Write-HarnessResult {
    param([string]$ResultPath)
    Complete-Result
    $json = $script:Result | ConvertTo-Json -Depth 32
    Set-Content -LiteralPath $ResultPath -Value $json -Encoding UTF8
    Write-Host "Harness result: $ResultPath"
}

$resolvedOutDir = Get-ResolvedOutDir
New-Item -ItemType Directory -Force -Path $resolvedOutDir | Out-Null
$resultPath = Join-Path $resolvedOutDir 'result.json'
$script:Result = [ordered]@{
    schemaVersion = 1
    command = $Command
    startedAt = Get-IsoTimestamp $script:StartedAt
    endedAt = $null
    durationMs = $null
    success = $false
    status = 'NOT_RUN'
    message = ''
    repoRoot = $script:RepoRoot
    applicationId = $null
    deviceSerial = $script:ResolvedDeviceSerial
    launchMode = $LaunchMode
    forceJvmCrash = $ForceJvmCrash.IsPresent
    forceRuntimeCrash = $ForceRuntimeCrash.IsPresent
    timeoutSeconds = $TimeoutSeconds
    artifacts = [ordered]@{
        outDir = $resolvedOutDir
        resultJson = $resultPath
    }
    statusSnapshot = $null
    operations = @()
    error = $null
}

$exitCode = 0
try {
    Initialize-Harness
    $script:Result.applicationId = $script:ApplicationId
    $script:Result.deviceSerial = $script:ResolvedDeviceSerial

    switch ($Command) {
        'doctor' {
            $status = Get-HarnessStatus
            $script:Result.statusSnapshot = $status
            Set-ResultSuccess -Success $true -Status 'OK' -Message 'Harness prerequisites are available.'
        }
        'install' {
            Invoke-HarnessInstall
            Set-ResultSuccess -Success $true -Status 'INSTALLED' -Message 'Debug APK installed.'
        }
        'start' {
            Invoke-HarnessStart
            Set-ResultSuccess -Success $true -Status 'START_REQUESTED' -Message 'Launch request was sent through :app:stsStart.'
        }
        'stop' {
            Invoke-HarnessStop
            Set-ResultSuccess -Success $true -Status 'STOPPED' -Message 'Application force-stop completed.'
        }
        'logs' {
            Invoke-HarnessLogs -OutputDirectory $resolvedOutDir
            Set-ResultSuccess -Success $true -Status 'LOGS_EXPORTED' -Message 'Log export completed.'
        }
        'screenshot' {
            Invoke-HarnessScreenshot -OutputDirectory $resolvedOutDir | Out-Null
            Set-ResultSuccess -Success $true -Status 'SCREENSHOT_CAPTURED' -Message 'Screenshot captured.'
        }
        'status' {
            $status = Get-HarnessStatus
            $script:Result.statusSnapshot = $status
            Set-ResultSuccess -Success $true -Status $status.observedState -Message 'Status snapshot captured.'
        }
        'smoke' {
            if (-not $SkipInstall) {
                Invoke-HarnessInstall
            }
            Clear-HarnessRuntimeSignals
            Invoke-HarnessStart
            $status = Wait-HarnessStatus
            $script:Result.statusSnapshot = $status

            try {
                Invoke-HarnessScreenshot -OutputDirectory $resolvedOutDir | Out-Null
            } catch {
                $script:Result.artifacts.screenshotError = $_.Exception.Message
            }
            try {
                Invoke-HarnessLogs -OutputDirectory $resolvedOutDir
            } catch {
                $script:Result.artifacts.logsError = $_.Exception.Message
            }
            if (-not $NoStopAfterSmoke) {
                Invoke-HarnessStop
            }

            $expectedState = if ($ForceJvmCrash) {
                'FAIL'
            } elseif ($ForceRuntimeCrash) {
                'CRASH_MARKER'
            } else {
                'READY'
            }
            $success = if ($ForceRuntimeCrash) {
                $status.observedState -eq 'CRASH_MARKER' -or $null -ne $status.latestLog.crashMarker
            } else {
                $status.observedState -eq $expectedState
            }
            $message = if ($success) {
                "Smoke run reached expected state: $expectedState"
            } else {
                "Smoke run expected $expectedState but observed $($status.observedState)"
            }
            Set-ResultSuccess -Success $success -Status $status.observedState -Message $message
            if (-not $success) {
                $exitCode = 1
            }
        }
    }
} catch {
    $exitCode = 1
    $script:Result.error = [ordered]@{
        type = $_.Exception.GetType().FullName
        message = $_.Exception.Message
    }
    Set-ResultSuccess -Success $false -Status 'ERROR' -Message $_.Exception.Message
} finally {
    Write-HarnessResult -ResultPath $resultPath
}

exit $exitCode
