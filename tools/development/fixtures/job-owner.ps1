param([string] $JobSource, [string] $StatePath, [string] $LogDirectory)
$ErrorActionPreference = 'Stop'
Add-Type -Path $JobSource
$job = [Nongxin.Development.WindowsJob]::new('Local\NongxinJobFixture-' + [guid]::NewGuid().ToString('N'))
$environment = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($entry in [Environment]::GetEnvironmentVariables().GetEnumerator()) { $environment[[string]$entry.Key] = [string]$entry.Value }
$child = $job.Start((Join-Path $env:SystemRoot 'System32\ping.exe'), [string[]]@('-n','60','127.0.0.1'),
    $LogDirectory, $environment, (Join-Path $LogDirectory 'owner-child.out.log'), (Join-Path $LogDirectory 'owner-child.err.log'))
[IO.File]::WriteAllText($StatePath, $child.Id.ToString())
# Deliberately exit without Dispose/finally. Windows must close the job handle.
[Environment]::Exit(0)
