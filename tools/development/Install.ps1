Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.Net.Http

function Test-NxArchiveHash([string] $Path, [string] $Algorithm, [string] $Expected) {
    return (Test-Path -LiteralPath $Path -PathType Leaf) -and
        ((Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash -eq $Expected)
}

function Receive-NxArchive([string] $Root, $Archive, [string] $Algorithm, [string] $Label, [scriptblock] $Fetch = $null) {
    $destination = Assert-NxRuntimePath $Root (Join-Path $Root ('.runtime\downloads\' + $Archive.filename))
    if (Test-Path -LiteralPath $destination) {
        if (-not (Test-NxArchiveHash $destination $Algorithm $Archive.checksum)) {
            throw "$Label cached archive failed checksum verification. Move it aside and retry: $destination"
        }
        Write-Host "[cache] verified $Label archive"
        return $destination
    }
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    foreach ($address in $Archive.urls) {
        $uri = [uri]$address
        if ($uri.Scheme -ne 'https' -or $uri.Port -ne 443 -or $uri.UserInfo -or
            @('nodejs.org','github.com','dlcdn.apache.org','archive.apache.org') -notcontains $uri.Host) {
            throw 'A tool download must use an approved official HTTPS host.'
        }
        for ($attempt = 1; $attempt -le 2; $attempt++) {
            $partial = Assert-NxRuntimePath $Root ($destination + '.part-' + [guid]::NewGuid().ToString('N'))
            $client = [Net.Http.HttpClient]::new()
            $cancel = [Threading.CancellationTokenSource]::new()
            $cancel.CancelAfter([TimeSpan]::FromMinutes(15))
            $response = $null; $inputStream = $null; $outputStream = $null
            try {
                Write-Host "[download] $Label attempt $attempt; first setup needs Internet access"
                if ($Fetch) {
                    # Internal test seam; no user configuration is ever executed here.
                    & $Fetch $uri $partial
                } else {
                $response = $client.GetAsync($uri, [Net.Http.HttpCompletionOption]::ResponseHeadersRead, $cancel.Token).GetAwaiter().GetResult()
                $response.EnsureSuccessStatusCode() | Out-Null
                $inputStream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
                $outputStream = [IO.File]::Open($partial, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
                $buffer = New-Object byte[] 65536
                $total = 0L; $lastProgress = [DateTime]::UtcNow
                while (($read = $inputStream.ReadAsync($buffer, 0, $buffer.Length, $cancel.Token).GetAwaiter().GetResult()) -gt 0) {
                    $outputStream.Write($buffer, 0, $read); $total += $read
                    if (([DateTime]::UtcNow - $lastProgress).TotalSeconds -ge 15) {
                        Write-Host ('[download] ' + $Label + ': ' + [Math]::Round($total / 1MB,1) + ' MB')
                        $lastProgress = [DateTime]::UtcNow
                    }
                }
                $outputStream.Dispose(); $outputStream = $null
                }
                if (-not (Test-NxArchiveHash $partial $Algorithm $Archive.checksum)) {
                    throw [IO.InvalidDataException]::new('Tool archive checksum mismatch.')
                }
                Move-Item -LiteralPath $partial -Destination $destination
                return $destination
            } catch [IO.InvalidDataException] {
                throw "$Label download failed checksum verification; refusing to install."
            } catch {
                Write-Host "[download] $Label attempt failed; credentials and exception details are not displayed."
            } finally {
                if ($outputStream) { $outputStream.Dispose() }
                if ($inputStream) { $inputStream.Dispose() }
                if ($response) { $response.Dispose() }
                $cancel.Dispose(); $client.Dispose()
                if (Test-Path -LiteralPath $partial) {
                    Assert-NxRuntimePath $Root $partial | Out-Null
                    Remove-Item -LiteralPath $partial
                }
            }
        }
    }
    throw "Could not download $Label. Place the official ZIP in $destination and retry; its checksum will still be verified."
}

function Expand-NxArchive([string] $ArchivePath, [string] $Destination) {
    $base = [IO.Path]::GetFullPath($Destination) + [IO.Path]::DirectorySeparatorChar
    $zip = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        foreach ($entry in $zip.Entries) {
            $entryPath = [IO.Path]::GetFullPath((Join-Path $Destination $entry.FullName))
            if (-not $entryPath.StartsWith($base, [StringComparison]::OrdinalIgnoreCase) -or
                (($entry.ExternalAttributes -shr 16) -band 0xF000) -eq 0xA000) {
                throw 'Unsafe archive entry; refusing to extract.'
            }
        }
    } finally { $zip.Dispose() }
    [IO.Compression.ZipFile]::ExtractToDirectory($ArchivePath, $Destination)
}

function Install-NxTool([string] $Root, [string] $Name, $Tool, [string] $Architecture) {
    $variant = $Tool.archives.PSObject.Properties[$Architecture]
    if (-not $variant) { $variant = $Tool.archives.PSObject.Properties['any'] }
    if (-not $variant) { throw "No official package configured for $Name / $Architecture." }
    $archive = $variant.Value
    if ($archive.filename -notmatch '^[A-Za-z0-9._+-]+\.zip$' -or $archive.root -notmatch '^[A-Za-z0-9._+-]+$' -or
        $Tool.version -notmatch '^[A-Za-z0-9._+-]+$' -or
        $archive.checksum -notmatch '^[a-fA-F0-9]+$' -or
        @('SHA256','SHA512') -notcontains $Tool.hashAlgorithm -or
        $archive.checksum.Length -ne $(if ($Tool.hashAlgorithm -eq 'SHA256') { 64 } else { 128 })) {
        throw 'Invalid tool manifest; refusing installation.'
    }
    $install = Assert-NxRuntimePath $Root (Join-Path $Root ('.runtime\tools\' + $Name + '-' + $Tool.version + '-' + $Architecture))
    $executable = Join-Path $install $Tool.executable
    $marker = Join-Path $install 'nongxin-install.json'
    if ((Test-Path -LiteralPath $marker) -and (Test-Path -LiteralPath $executable)) {
        try {
            $state = [IO.File]::ReadAllText($marker) | ConvertFrom-Json
            if ($state.packageChecksum -eq $archive.checksum -and $state.version -eq $Tool.version -and
                $state.executableChecksum -eq (Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash) {
                Write-Host "[cache] $Name $($Tool.version) ready"
                return $install
            }
        } catch { Write-Host "[repair] incomplete $Name installation" }
    }
    $download = Receive-NxArchive $Root $archive $Tool.hashAlgorithm $Name
    $staging = Assert-NxRuntimePath $Root (Join-Path $Root ('.runtime\staging\' + [guid]::NewGuid().ToString('N')))
    try {
        [IO.Directory]::CreateDirectory($staging) | Out-Null
        Write-Host "[extract] $Name $($Tool.version)"
        Expand-NxArchive $download $staging
        $extracted = Join-Path $staging $archive.root
        $binary = Join-Path $extracted $Tool.executable
        if (-not (Test-Path -LiteralPath $binary -PathType Leaf)) { throw "$Name archive has an unexpected layout." }
        $state = @{ version = $Tool.version; packageChecksum = $archive.checksum;
            executableChecksum = (Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash }
        [IO.File]::WriteAllText((Join-Path $extracted 'nongxin-install.json'), ($state | ConvertTo-Json), [Text.Encoding]::UTF8)
        if (Test-Path -LiteralPath $install) {
            $old = Assert-NxRuntimePath $Root (Join-Path $Root ('.runtime\stale\' + $Name + '-' + [guid]::NewGuid().ToString('N')))
            Move-Item -LiteralPath $install -Destination $old
            Write-Host "[repair] preserved the old $Name environment in .runtime/stale"
        }
        Move-Item -LiteralPath $extracted -Destination $install
        return $install
    } finally {
        if (Test-Path -LiteralPath $staging) {
            Assert-NxRuntimePath $Root $staging | Out-Null
            Remove-Item -LiteralPath $staging -Recurse -Force
        }
    }
}

function Install-NxToolchain([string] $Root, [string] $Architecture) {
    $manifest = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'toolchain.json')) | ConvertFrom-Json
    if ($manifest.schemaVersion -ne 1) { throw 'Unsupported tool manifest version.' }
    if ((Get-NxAvailableSpace $Root) -lt 2GB) { throw 'At least 2 GB free disk space is required for development setup.' }
    $tools = @{ architecture = $Architecture; nodeVersion = $manifest.node.version }
    foreach ($name in @('node','jdk','maven')) { $tools[$name] = Install-NxTool $Root $name $manifest.$name $Architecture }
    return $tools
}

function Get-NxAvailableSpace([string] $Root) {
    return [IO.DriveInfo]::new([IO.Path]::GetPathRoot($Root)).AvailableFreeSpace
}
