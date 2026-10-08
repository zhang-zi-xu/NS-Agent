Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')
. (Join-Path $PSScriptRoot 'Install.ps1')
. (Join-Path $PSScriptRoot 'Share.ps1')
Initialize-NxJobType
$projectRoot = Get-NxRoot
$suffix = [char]0x961F + [string][char]0x53CB + ' copy ' + [guid]::NewGuid().ToString('N')
$fixtureRoot = Join-Path $projectRoot ('artifacts\launcher-tests\' + $suffix)
[IO.Directory]::CreateDirectory($fixtureRoot) | Out-Null
Initialize-NxRuntime $fixtureRoot
$script:passed = 0

function Assert-True([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw "Test failed: $Message" }
    $script:passed++
}
function Assert-Rejected([scriptblock] $Operation, [string] $Message) {
    $rejected = $false
    try { & $Operation | Out-Null } catch { $rejected = $true }
    Assert-True $rejected $Message
}

Assert-Rejected { Assert-NxRuntimePath $fixtureRoot (Join-Path $fixtureRoot 'data\outside') } 'runtime path escape'
Assert-Rejected { Assert-NxRuntimePath $fixtureRoot (Join-Path $fixtureRoot '.runtime\..\outside') } 'parent path escape'
Assert-True ((Get-NxRoot) -eq [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))) 'root independent of cwd'

$envFile = Join-Path $fixtureRoot '.env.local'
$originalKey = [Environment]::GetEnvironmentVariable('NONGXIN_CHAT_KEY')
try {
    [Environment]::SetEnvironmentVariable('NONGXIN_CHAT_KEY','process-example-key')
    [IO.File]::WriteAllText($envFile, "NONGXIN_CHAT_KEY=file-example-key`nNONGXIN_CHAT_MODEL=`$(Do-Not-Execute)`n", [Text.Encoding]::UTF8)
    $settings = Read-NxEnvironment $fixtureRoot
    Assert-True ($settings['NONGXIN_CHAT_KEY'] -eq 'process-example-key') 'process environment wins'
    Assert-True ($settings['NONGXIN_CHAT_MODEL'] -eq '$(Do-Not-Execute)') 'literal config, never eval'
    $buildSettings = Copy-NxBuildEnvironment $settings
    Assert-True (-not $buildSettings.ContainsKey('NONGXIN_CHAT_KEY')) 'build tools do not receive model secrets'
    Assert-True ($settings['NONGXIN_CHAT_KEY'] -eq 'process-example-key') 'service credentials remain in separate environment'
    [IO.File]::WriteAllText($envFile, 'not valid secret-value', [Text.Encoding]::UTF8)
    $errorText = ''
    try { Read-NxEnvironment $fixtureRoot | Out-Null } catch { $errorText = $_.Exception.Message }
    Assert-True ($errorText -and -not $errorText.Contains('secret-value')) 'config errors redact contents'
    [IO.File]::WriteAllText($envFile, 'UNKNOWN_SETTING=value', [Text.Encoding]::UTF8)
    Assert-Rejected { Read-NxEnvironment $fixtureRoot } 'unknown config refused'
} finally { [Environment]::SetEnvironmentVariable('NONGXIN_CHAT_KEY',$originalKey) }

$zipInput = Join-Path $fixtureRoot 'zip-input'
[IO.Directory]::CreateDirectory((Join-Path $zipInput 'test-tool')) | Out-Null
[IO.File]::WriteAllText((Join-Path $zipInput 'test-tool\tool.exe'),'fixture-not-executable')
$archivePath = Join-Path $fixtureRoot '.runtime\downloads\fixture.zip'
[IO.Compression.ZipFile]::CreateFromDirectory($zipInput,$archivePath)
$checksum = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
$archive = [pscustomobject]@{ filename='fixture.zip'; root='test-tool'; checksum=$checksum; urls=@('https://nodejs.org/fixture.zip') }
$tool = [pscustomobject]@{ version='1.0'; executable='tool.exe'; hashAlgorithm='SHA256'; archives=[pscustomobject]@{ x64=$archive } }
$installed = Install-NxTool $fixtureRoot 'fixture' $tool 'x64'
Assert-True (Test-Path -LiteralPath (Join-Path $installed 'nongxin-install.json')) 'verified archive installed'
$firstWrite = (Get-Item -LiteralPath (Join-Path $installed 'tool.exe')).LastWriteTimeUtc
$again = Install-NxTool $fixtureRoot 'fixture' $tool 'x64'
Assert-True ($again -eq $installed -and (Get-Item -LiteralPath (Join-Path $installed 'tool.exe')).LastWriteTimeUtc -eq $firstWrite) 'repeat installation reuses cache'
[IO.File]::WriteAllText((Join-Path $installed 'tool.exe'),'damaged')
Install-NxTool $fixtureRoot 'fixture' $tool 'x64' | Out-Null
Assert-True ([IO.File]::ReadAllText((Join-Path $installed 'tool.exe')) -eq 'fixture-not-executable') 'damaged installation repaired'
Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $fixtureRoot '.runtime\stale')).Count -eq 1) 'old damaged installation preserved'
Assert-Rejected { Install-NxTool $fixtureRoot 'fixture' $tool 'arm64' } 'unsupported package architecture'
$badArchive = [pscustomobject]@{ filename='fixture.zip'; root='test-tool'; checksum=('0' * 64); urls=@('https://nodejs.org/fixture.zip') }
Assert-Rejected { Receive-NxArchive $fixtureRoot $badArchive 'SHA256' 'fixture' } 'manual wrong checksum rejected'
$downloadArchive = [pscustomobject]@{ filename='network-fixture.zip'; root='test-tool'; checksum=$checksum; urls=@('https://nodejs.org/fixture.zip') }
$script:fetchAttempts = 0
Assert-Rejected { Receive-NxArchive $fixtureRoot $downloadArchive 'SHA256' 'fixture' { param($Uri,$Path); $script:fetchAttempts++; throw 'simulated-download-interruption' } } 'download interruption rejected'
Assert-True ($script:fetchAttempts -eq 2) 'bounded retry count'
Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $fixtureRoot '.runtime\downloads') -Filter '*.part-*').Count -eq 0) 'partial downloads removed'
Assert-Rejected { Receive-NxArchive $fixtureRoot $downloadArchive 'SHA256' 'fixture' { param($Uri,$Path); [IO.File]::WriteAllText($Path,'corrupt-download') } } 'download wrong checksum rejected'
$received = Receive-NxArchive $fixtureRoot $downloadArchive 'SHA256' 'fixture' { param($Uri,$Path); [IO.File]::Copy($archivePath,$Path) }
Assert-True (Test-NxArchiveHash $received 'SHA256' $checksum) 'test transfer uses same production checksum gate'
$unsafeArchive = Join-Path $fixtureRoot 'unsafe.zip'
$zip = [IO.Compression.ZipFile]::Open($unsafeArchive,[IO.Compression.ZipArchiveMode]::Create)
try { $zip.CreateEntry('../outside.txt') | Out-Null } finally { $zip.Dispose() }
Assert-Rejected { Expand-NxArchive $unsafeArchive (Join-Path $fixtureRoot 'unsafe-extracted') } 'zip traversal rejected'

$lockPath = Join-Path $fixtureRoot '.runtime\lock-test'
$lock = [IO.File]::Open($lockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try { Assert-Rejected { [IO.File]::Open($lockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None).Dispose() } 'concurrent launcher refused' }
finally { $lock.Dispose() }
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
$listener.Start()
try { Assert-Rejected { Assert-NxPortsAvailable @($listener.LocalEndpoint.Port) } 'occupied port refused' }
finally { $listener.Stop() }

$spaceFunction = ${function:Get-NxAvailableSpace}
try {
    function Get-NxAvailableSpace { return 1024 }
    Assert-Rejected { Install-NxToolchain $fixtureRoot 'x64' } 'insufficient space fails before downloading'
} finally { Set-Item -LiteralPath function:Get-NxAvailableSpace -Value $spaceFunction }

$statePath = Join-Path $fixtureRoot '.runtime\running.json'
$state = @{ root=$fixtureRoot; jobName=(New-NxJobName $fixtureRoot); launcherProcessId=$PID; launcherStartTicks='0' }
[IO.File]::WriteAllText($statePath,($state | ConvertTo-Json))
Assert-Rejected { Stop-NxRecordedJob $fixtureRoot } 'PID reuse refused'
$state.root = 'another-project'
[IO.File]::WriteAllText($statePath,($state | ConvertTo-Json))
Assert-Rejected { Stop-NxRecordedJob $fixtureRoot } 'other project state refused'

$environment = Read-NxEnvironment $projectRoot
$jobA = [Nongxin.Development.WindowsJob]::new((New-NxJobName $fixtureRoot))
$jobB = [Nongxin.Development.WindowsJob]::new((New-NxJobName $fixtureRoot))
$childA = $null; $childB = $null
try {
    $childA = $jobA.Start((Join-Path $env:SystemRoot 'System32\ping.exe'),[string[]]@('-n','60','127.0.0.1'),$fixtureRoot,$environment,(Join-Path $fixtureRoot 'child-a.out.log'),(Join-Path $fixtureRoot 'child-a.err.log'))
    $childB = $jobB.Start((Join-Path $env:SystemRoot 'System32\ping.exe'),[string[]]@('-n','60','127.0.0.1'),$fixtureRoot,$environment,(Join-Path $fixtureRoot 'child-b.out.log'),(Join-Path $fixtureRoot 'child-b.err.log'))
    $jobA.Dispose()
    Assert-True ($childA.WaitForExit(5000)) 'job disposal kills owned child'
    Assert-True (-not $childB.HasExited) 'unrelated job remains alive'
} finally {
    $jobA.Dispose(); $jobB.Dispose()
    if ($childA) { $childA.Dispose() }; if ($childB) { $childB.Dispose() }
}
$ownerJob = [Nongxin.Development.WindowsJob]::new((New-NxJobName $fixtureRoot))
$ownerProcess = $null
try {
    $ownerState = Join-Path $fixtureRoot 'owner-child-id.txt'
    $ownerProcess = $ownerJob.Start((Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'),[string[]]@('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'fixtures\job-owner.ps1'),'-JobSource',(Join-Path $PSScriptRoot 'WindowsJob.cs'),'-StatePath',$ownerState,'-LogDirectory',$fixtureRoot),$fixtureRoot,$environment,(Join-Path $fixtureRoot 'owner.out.log'),(Join-Path $fixtureRoot 'owner.err.log'))
    Assert-True ($ownerProcess.WaitForExit(15000) -and $ownerProcess.ExitCode -eq 0) 'fixture owner exited abruptly'
    $childId = [int][IO.File]::ReadAllText($ownerState)
    Start-Sleep -Milliseconds 500
    Assert-True (-not (Get-Process -Id $childId -ErrorAction SilentlyContinue)) 'OS closes job on launcher exit'
} finally { $ownerJob.Dispose(); if ($ownerProcess) { $ownerProcess.Dispose() } }

foreach ($directory in @('frontend\src','frontend\node_modules','server\src','server\data','tools','docs','.idea')) {
    [IO.Directory]::CreateDirectory((Join-Path $fixtureRoot $directory)) | Out-Null
}
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'frontend\src\new-untracked.ts'),'export const added = true;')
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'frontend\node_modules\secret.txt'),'excluded')
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'server\data\secret.txt'),'excluded')
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'README.md'),'fixture')
[IO.File]::WriteAllText((Join-Path $fixtureRoot '.env.example'),'NONGXIN_CHAT_KEY=')
$sourceFiles = @(Get-NxSourceFiles $fixtureRoot)
Assert-True ($sourceFiles -contains (Join-Path $fixtureRoot 'frontend\src\new-untracked.ts')) 'untracked source included'
Assert-True (-not ($sourceFiles -contains $envFile)) 'local configuration excluded'
Assert-True (-not ($sourceFiles | Where-Object { $_ -match '\\node_modules\\|\\server\\data\\|\\\.runtime\\' })) 'runtime and data excluded'
[IO.Directory]::CreateDirectory((Join-Path $fixtureRoot 'tools\development')) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $fixtureRoot 'tools\knowledge')) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $fixtureRoot 'server\src\main\resources\knowledge')) | Out-Null
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'tools\development\README.md'),'development guide')
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'tools\knowledge\harvest.txt'),'historical materials')
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'docs\competition.pdf'),'fixture')
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'frontend\src\AGENTS.md'),'AI instructions')
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'server\src\main\resources\knowledge\sources.json'),'{}')
$programFiles = @(Get-NxSourceFiles $fixtureRoot -ProgramOnly)
Assert-True ($programFiles -contains (Join-Path $fixtureRoot 'tools\development\README.md')) 'program-only includes launcher guide'
Assert-True ($programFiles -contains (Join-Path $fixtureRoot 'server\src\main\resources\knowledge\sources.json')) 'program-only retains essential runtime knowledge'
Assert-True (-not ($programFiles | Where-Object { $_ -match '\\docs\\|\\tools\\knowledge\\|\\AGENTS\.md$|\.pdf$' })) 'program-only excludes archival materials, competition and AI memory'
$sourceZip = New-NxSourceArchive $fixtureRoot
$zip = [IO.Compression.ZipFile]::OpenRead($sourceZip)
try { Assert-True (@($zip.Entries | Where-Object { $_.FullName -eq '.env.example' }).Count -eq 1) 'safe sample included in ZIP' }
finally { $zip.Dispose() }
[IO.File]::WriteAllText((Join-Path $fixtureRoot '.env.example'),'NONGXIN_CHAT_KEY=nonempty-example')
Assert-Rejected { New-NxSourceArchive $fixtureRoot } 'sample credentials must remain empty'
[IO.File]::WriteAllText((Join-Path $fixtureRoot '.env.example'),'NONGXIN_CHAT_KEY=')
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'frontend\src\credential.ts'),("const key = 'sk-" + ('a' * 32) + "';"))
Assert-Rejected { New-NxSourceArchive $fixtureRoot } 'credential-shaped source blocks sharing'
Write-Host "[passed] $script:passed launcher assertions; fixtures preserved in artifacts/launcher-tests"
