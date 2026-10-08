Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-NxRoot {
    return [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
}

function Assert-NxRuntimePath([string] $Root, [string] $Path) {
    $base = [IO.Path]::GetFullPath((Join-Path $Root '.runtime'))
    $target = [IO.Path]::GetFullPath($Path)
    if (-not $target.StartsWith($base + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing a runtime operation outside the project .runtime directory.'
    }
    $current = $target
    while ($current -and $current.Length -ge $base.Length) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'Runtime paths must not contain junctions or symbolic links.'
            }
        }
        $current = [IO.Path]::GetDirectoryName($current)
    }
    return $target
}

function Initialize-NxRuntime([string] $Root) {
    foreach ($name in @('downloads', 'tools', 'staging', 'stale', 'logs', 'cache')) {
        $path = Assert-NxRuntimePath $Root (Join-Path $Root ('.runtime\' + $name))
        [IO.Directory]::CreateDirectory($path) | Out-Null
    }
}

function Get-NxArchitecture {
    $os = Get-CimInstance Win32_OperatingSystem
    if ([version]$os.Version -lt [version]'10.0') { throw 'Windows 10 or 11 is required.' }
    $processor = Get-CimInstance Win32_Processor | Select-Object -First 1
    switch ([int]$processor.Architecture) {
        9 { return 'x64' }
        12 { return 'arm64' }
        default { throw 'Only Windows x64 and ARM64 are supported. 32-bit Windows is not supported.' }
    }
}

function Initialize-NxJobType {
    if (-not ('Nongxin.Development.WindowsJob' -as [type])) {
        Add-Type -Path (Join-Path $PSScriptRoot 'WindowsJob.cs')
    }
}

function Read-NxEnvironment([string] $Root) {
    $environment = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in [Environment]::GetEnvironmentVariables().GetEnumerator()) {
        $environment[[string]$entry.Key] = [string]$entry.Value
    }
    $allowed = @('NONGXIN_CHAT_KEY','NONGXIN_CHAT_PROVIDER','NONGXIN_CHAT_MODEL',
        'NONGXIN_EMBEDDING_KEY','NONGXIN_EMBEDDING_ENDPOINT','NONGXIN_EMBEDDING_MODEL',
        'NONGXIN_EMBEDDING_MODE','NONGXIN_KB_ADMIN_TOKEN','NONGXIN_UPLOAD_DIR',
        'NONGXIN_UPLOAD_MAX_BYTES','NONGXIN_UPLOAD_RETENTION_DAYS',
        'NONGXIN_USAGE_LIMIT','NONGXIN_USAGE_PER_IP','NONGXIN_USAGE_GLOBAL')
    $file = Join-Path $Root '.env.local'
    if (Test-Path -LiteralPath $file) {
        $lineNumber = 0
        foreach ($line in [IO.File]::ReadAllLines($file, [Text.Encoding]::UTF8)) {
            $lineNumber++
            $trimmed = $line.Trim()
            if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
            if ($trimmed -notmatch '^([A-Z][A-Z0-9_]*)\s*=(.*)$') {
                throw ".env.local has invalid syntax at line $lineNumber (values are not displayed)."
            }
            $name = $Matches[1]
            $value = $Matches[2].Trim()
            if ($allowed -notcontains $name) { throw ".env.local has an unsupported variable at line $lineNumber." }
            if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[$value.Length-1] -eq '"') -or
                    ($value[0] -eq "'" -and $value[$value.Length-1] -eq "'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            if ($value.IndexOf([char]0) -ge 0) { throw ".env.local has an invalid value at line $lineNumber." }
            if (-not $environment.ContainsKey($name) -and $value.Length -gt 0) { $environment[$name] = $value }
        }
    }
    # Do not inherit JVM option injection: Java prints these options, possibly including secrets.
    foreach ($name in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_OPTS','MAVEN_ARGS','NODE_OPTIONS','NODE_PATH')) {
        $environment.Remove($name) | Out-Null
    }
    return ,$environment
}

function Copy-NxBuildEnvironment($Environment) {
    $copy = [Collections.Generic.Dictionary[string,string]]::new($Environment, [StringComparer]::OrdinalIgnoreCase)
    foreach ($name in @('NONGXIN_CHAT_KEY','NONGXIN_EMBEDDING_KEY','NONGXIN_KB_ADMIN_TOKEN')) {
        $copy.Remove($name) | Out-Null
    }
    return ,$copy
}

function Set-NxToolEnvironment($Environment, $Tools, [string] $Root) {
    $Environment['JAVA_HOME'] = $Tools.jdk
    $Environment['MAVEN_HOME'] = $Tools.maven
    $Environment['PATH'] = $Tools.node + ';' + (Join-Path $Tools.jdk 'bin') + ';' +
        (Join-Path $Tools.maven 'bin') + ';' + $Environment['PATH']
    $Environment['NPM_CONFIG_CACHE'] = Join-Path $Root '.runtime\cache\npm'
    $Environment['NPM_CONFIG_UPDATE_NOTIFIER'] = 'false'
    $Environment['NPM_CONFIG_AUDIT'] = 'false'
    $Environment['NPM_CONFIG_FUND'] = 'false'
}

function Assert-NxPortsAvailable([int[]] $Ports = @(3000,8080)) {
    $connections = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -in $Ports })
    if ($connections.Count -gt 0) {
        $description = ($connections | ForEach-Object { 'port ' + $_.LocalPort + ' (PID ' + $_.OwningProcess + ')' } |
            Select-Object -Unique) -join ', '
        throw "Address already in use: $description. Stop the known service yourself; no unrelated process was stopped."
    }
}

function Get-NxJobPrefix([string] $Root) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hash = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Root.ToLowerInvariant()))).Replace('-','').Substring(0,16) }
    finally { $sha.Dispose() }
    return 'Local\NongxinDev-' + $hash + '-'
}

function New-NxJobName([string] $Root) {
    return (Get-NxJobPrefix $Root) + [guid]::NewGuid().ToString('N')
}

function Invoke-NxProcess($Job, [string] $Executable, [string[]] $Arguments, [string] $Directory,
    $Environment, [string] $Root, [string] $Label) {
    $tag = $Label + '-' + [guid]::NewGuid().ToString('N')
    $stdout = Join-Path $Root ('.runtime\logs\' + $tag + '.out.log')
    $stderr = Join-Path $Root ('.runtime\logs\' + $tag + '.err.log')
    Write-Host "[run] $Label"
    $process = $Job.Start($Executable, $Arguments, $Directory, $Environment, $stdout, $stderr)
    try {
        $started = [DateTime]::UtcNow
        $lastProgress = $started
        while (-not $process.WaitForExit(500)) {
            if (([DateTime]::UtcNow - $lastProgress).TotalSeconds -ge 15) {
                $seconds = [int]([DateTime]::UtcNow - $started).TotalSeconds
                Write-Host "[run] $Label still running (${seconds}s); logs: .runtime/logs"
                $lastProgress = [DateTime]::UtcNow
            }
        }
        if ($process.ExitCode -ne 0) {
            throw "$Label failed (exit $($process.ExitCode)). See $stdout and $stderr. Log contents are not echoed."
        }
    } finally { $process.Dispose() }
}

function Get-NxMavenArguments($Tools, [string] $Root, [string[]] $Goals) {
    $boot = @(Get-ChildItem -LiteralPath (Join-Path $Tools.maven 'boot') -Filter 'plexus-classworlds-*.jar')
    if ($boot.Count -ne 1) { throw 'The private Maven installation is incomplete.' }
    $settings = Join-Path $Root '.runtime\maven-settings.xml'
    if (-not (Test-Path -LiteralPath $settings)) { $settings = Join-Path $Tools.maven 'conf\settings.xml' }
    # Maven's official classworlds launcher, without cmd.exe command-string interpolation.
    return @('-Dfile.encoding=UTF-8', '-classpath', $boot[0].FullName,
        ('-Dclassworlds.conf=' + (Join-Path $Tools.maven 'bin\m2.conf')),
        ('-Dmaven.home=' + $Tools.maven),
        ('-Dmaven.multiModuleProjectDirectory=' + (Join-Path $Root 'server')),
        'org.codehaus.plexus.classworlds.launcher.Launcher',
        '--settings', $settings,
        ('-Dmaven.repo.local=' + (Join-Path $Root '.runtime\cache\maven-repository'))) + $Goals
}

function Install-NxFrontend($Job, $Tools, [string] $Root, $Environment) {
    $frontend = Join-Path $Root 'frontend'
    $lock = Join-Path $frontend 'package-lock.json'
    $package = Join-Path $frontend 'package.json'
    $digest = (Get-FileHash -LiteralPath $lock -Algorithm SHA256).Hash + ':' +
        (Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash + ':' + $Tools.architecture + ':' + $Tools.nodeVersion
    $marker = Join-Path $Root '.runtime\frontend-install.txt'
    $valid = (Test-Path -LiteralPath $marker) -and
        ([IO.File]::ReadAllText($marker).Trim() -eq $digest) -and
        (Test-Path -LiteralPath (Join-Path $frontend 'node_modules\vite\bin\vite.js')) -and
        (Test-Path -LiteralPath (Join-Path $frontend 'node_modules\typescript\bin\tsc')) -and
        (Test-Path -LiteralPath (Join-Path $frontend 'node_modules\.package-lock.json'))
    if ($valid) { Write-Host '[cache] frontend dependencies unchanged'; return }
    Invoke-NxProcess $Job (Join-Path $Tools.node 'node.exe') @(
        (Join-Path $Tools.node 'node_modules\npm\bin\npm-cli.js'), 'ci', '--no-audit', '--no-fund'
    ) $frontend $Environment $Root 'npm-ci'
    [IO.File]::WriteAllText($marker, $digest, [Text.Encoding]::UTF8)
}

function Stop-NxRecordedJob([string] $Root) {
    $statePath = Assert-NxRuntimePath $Root (Join-Path $Root '.runtime\running.json')
    if (-not (Test-Path -LiteralPath $statePath)) { Write-Host '[stop] No recorded project launcher.'; return }
    $state = [IO.File]::ReadAllText($statePath) | ConvertFrom-Json
    if ($state.root -ne $Root -or $state.jobName -notmatch '^Local\\NongxinDev-[A-F0-9]{16}-[a-f0-9]{32}$' -or
        -not $state.jobName.StartsWith((Get-NxJobPrefix $Root), [StringComparison]::Ordinal)) {
        throw 'Invalid launcher state; refusing to stop any process.'
    }
    $owner = Get-Process -Id ([int]$state.launcherProcessId) -ErrorAction SilentlyContinue
    if (-not $owner) { Write-Host '[stop] Recorded launcher is no longer running.'; return }
    try {
        if ($owner.StartTime.ToUniversalTime().Ticks.ToString() -ne $state.launcherStartTicks) {
            throw 'Recorded PID has been reused; refusing to stop any process.'
        }
        [IO.File]::WriteAllText((Join-Path $Root '.runtime\stop-request.txt'), $state.jobName)
        [Nongxin.Development.WindowsJob]::Stop($state.jobName)
        Write-Host '[stop] Stopped only this project launcher job.'
    } finally { $owner.Dispose() }
}
