param(
    [ValidateSet('Start','Prepare','Check','Share','Stop')][string] $Action = 'Start',
    [switch] $Mobile,
    [switch] $NoBrowser,
    [switch] $ProgramOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
# A batch launched from PowerShell 7 can inherit its incompatible module path.
# Resolve Windows built-ins from this host, without changing user/system settings.
$env:PSModulePath = Join-Path $PSHOME 'Modules'
. (Join-Path $PSScriptRoot 'Common.ps1')
. (Join-Path $PSScriptRoot 'Install.ps1')
. (Join-Path $PSScriptRoot 'Share.ps1')

$projectRoot = Get-NxRoot
$lockStream = $null; $job = $null; $backend = $null; $frontendProcess = $null
$registered = $false; $exitCode = 0
try {
    Initialize-NxRuntime $projectRoot
    if ($Action -eq 'Share') { New-NxSourceArchive $projectRoot -ProgramOnly:$ProgramOnly | Out-Null; exit 0 }
    Initialize-NxJobType
    if ($Action -eq 'Stop') { Stop-NxRecordedJob $projectRoot; exit 0 }
    $architecture = Get-NxArchitecture
    try {
        $lockStream = [IO.File]::Open((Join-Path $projectRoot '.runtime\launcher.lock'),
            [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    } catch { throw 'This project already has a launcher or check in progress. Use the Stop action if needed.' }
    $tools = Install-NxToolchain $projectRoot $architecture
    $environment = Read-NxEnvironment $projectRoot
    Set-NxToolEnvironment $environment $tools $projectRoot
    $buildEnvironment = Copy-NxBuildEnvironment $environment
    $jobName = New-NxJobName $projectRoot
    $job = [Nongxin.Development.WindowsJob]::new($jobName)
    $java = Join-Path $tools.jdk 'bin\java.exe'
    $node = Join-Path $tools.node 'node.exe'
    Invoke-NxProcess $job $node @('--version') $projectRoot $buildEnvironment $projectRoot 'node-version'
    Invoke-NxProcess $job $java @('-version') $projectRoot $buildEnvironment $projectRoot 'java-version'
    Invoke-NxProcess $job $java (Get-NxMavenArguments $tools $projectRoot @('--version')) $projectRoot $buildEnvironment $projectRoot 'maven-version'
    if ($Action -eq 'Prepare') { Write-Host '[ready] private toolchain installed; no system settings changed'; exit 0 }
    if ($Action -eq 'Start') { Assert-NxPortsAvailable }
    Install-NxFrontend $job $tools $projectRoot $buildEnvironment

    if ($Action -eq 'Check') {
        # Tests must not inherit real model credentials or start paid embedding jobs.
        $buildEnvironment['NONGXIN_CHAT_KEY'] = ''
        $buildEnvironment['NONGXIN_EMBEDDING_KEY'] = ''
        $buildEnvironment['NONGXIN_EMBEDDING_MODE'] = 'remote'
        Invoke-NxProcess $job $java (Get-NxMavenArguments $tools $projectRoot @('-q','-Dnongxin.embedding.api-key=',
            '-Dnongxin.chat.default-api-key=','-Dnongxin.embedding.mode=remote','package')) (Join-Path $projectRoot 'server') $buildEnvironment $projectRoot 'backend-check'
        foreach ($command in @('typecheck','typecheck:test','lint','test','build')) {
            Invoke-NxProcess $job $node @((Join-Path $tools.node 'node_modules\npm\bin\npm-cli.js'),'run',$command) `
                (Join-Path $projectRoot 'frontend') $buildEnvironment $projectRoot ('frontend-' + $command.Replace(':','-'))
        }
        Invoke-NxProcess $job (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') `
            @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'tests.ps1')) `
            $projectRoot $buildEnvironment $projectRoot 'launcher-tests'
        Write-Host '[passed] backend, frontend and launcher checks'; exit 0
    }

    Invoke-NxProcess $job $java (Get-NxMavenArguments $tools $projectRoot @('-q','-DskipTests','package')) `
        (Join-Path $projectRoot 'server') $buildEnvironment $projectRoot 'backend-build'
    Assert-NxPortsAvailable
    $statePath = Join-Path $projectRoot '.runtime\running.json'
    $stopPath = Join-Path $projectRoot '.runtime\stop-request.txt'
    if (Test-Path -LiteralPath $stopPath) { Remove-Item -LiteralPath (Assert-NxRuntimePath $projectRoot $stopPath) }
    $owner = Get-Process -Id $PID
    try {
        $state = @{ root = $projectRoot; jobName = $jobName; launcherProcessId = $PID;
            launcherStartTicks = $owner.StartTime.ToUniversalTime().Ticks.ToString() }
    } finally { $owner.Dispose() }
    [IO.File]::WriteAllText($statePath, ($state | ConvertTo-Json), [Text.Encoding]::UTF8)
    $registered = $true
    $backend = $job.Start($java, [string[]]@('-Dfile.encoding=UTF-8','-jar',
        (Join-Path $projectRoot 'server\target\nongxin-agent.jar'),'--server.address=127.0.0.1','--server.port=8080'),
        (Join-Path $projectRoot 'server'), $environment,
        (Join-Path $projectRoot '.runtime\logs\backend.out.log'), (Join-Path $projectRoot '.runtime\logs\backend.err.log'))
    $listenHost = '127.0.0.1'
    if ($Mobile) { $listenHost = '0.0.0.0' }
    $frontendProcess = $job.Start($node, [string[]]@((Join-Path $projectRoot 'frontend\node_modules\vite\bin\vite.js'),
        '--host',$listenHost,'--port','3000','--strictPort'), (Join-Path $projectRoot 'frontend'), $buildEnvironment,
        (Join-Path $projectRoot '.runtime\logs\frontend.out.log'), (Join-Path $projectRoot '.runtime\logs\frontend.err.log'))
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($backend.HasExited -or $frontendProcess.HasExited) { throw 'A service exited. See .runtime/logs/backend.* and frontend.*.' }
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 2
            $page = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:3000/' -TimeoutSec 2
            if ($health.status -eq 'ok' -and $health.service -eq 'nongxin-api' -and $page.StatusCode -eq 200) {
                $backendListener = @(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue)
                $frontendListener = @(Get-NetTCPConnection -State Listen -LocalPort 3000 -ErrorAction SilentlyContinue)
                if ($backendListener.Count -gt 0 -and $frontendListener.Count -gt 0 -and
                    @($backendListener | Where-Object { $_.OwningProcess -ne $backend.Id }).Count -eq 0 -and
                    @($frontendListener | Where-Object { $_.OwningProcess -ne $frontendProcess.Id }).Count -eq 0) {
                    $ready = $true; break
                }
            }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $ready) { throw 'Services did not become ready. See .runtime/logs.' }
    Write-Host '[ready] http://localhost:3000/ - keep this launcher open; Ctrl+C or closing it stops its services.'
    Write-Host '[logs] .runtime/logs (startup/services); server/data/nongxin.log (application)'
    if ($Mobile) {
        Write-Host '[mobile] Trusted same-Wi-Fi network only. Windows Firewall may need PRIVATE-network permission.'
        Write-Host '[mobile] Use Phone access in the page for the LAN QR. WeChat QR remains local-only; GPS/microphone require HTTPS.'
    }
    if (-not $NoBrowser) { Start-Process 'http://localhost:3000/' -WindowStyle Hidden | Out-Null }
    while ($true) {
        Start-Sleep -Milliseconds 500
        if (Test-Path -LiteralPath $stopPath) {
            if ([IO.File]::ReadAllText($stopPath) -eq $jobName) { Write-Host '[stop] Project services stopped.'; break }
        }
        if ($backend.HasExited -or $frontendProcess.HasExited) { throw 'A service stopped unexpectedly; shutting down this launcher job.' }
    }
} catch {
    $exitCode = 1
    $safeMessage = 'Launcher operation failed. Review .runtime/logs and the teammate quickstart.'
    $message = $_.Exception.Message
    if ($message -notmatch '(?i)sk-|api.?key|password|token|https?://.*@') { $safeMessage = $message }
    Write-Host ('[error] ' + $safeMessage) -ForegroundColor Red
} finally {
    if ($job) { $job.Dispose() }
    if ($backend) { $backend.Dispose() }
    if ($frontendProcess) { $frontendProcess.Dispose() }
    if ($registered) {
        foreach ($path in @((Join-Path $projectRoot '.runtime\running.json'),(Join-Path $projectRoot '.runtime\stop-request.txt'))) {
            if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath (Assert-NxRuntimePath $projectRoot $path) }
        }
    }
    if ($lockStream) { $lockStream.Dispose() }
}
exit $exitCode
