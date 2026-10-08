Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

function Get-NxSourceFiles([string] $Root, [switch] $ProgramOnly) {
    $files = [Collections.Generic.List[string]]::new()
    foreach ($name in @('README.md','.gitignore','.env.example',
        ([char]0x542F + [string][char]0x52A8 + [char]0x519C + [char]0x5FC3 + '.bat'),
        ([char]0x542F + [string][char]0x52A8 + [char]0x519C + [char]0x5FC3 + '-' + [char]0x624B + [char]0x673A + [char]0x6A21 + [char]0x5F0F + '.bat'),
        'server\pom.xml')) {
        $path = Join-Path $Root $name
        if (Test-Path -LiteralPath $path -PathType Leaf) { $files.Add($path) }
    }
    $queue = [Collections.Generic.Queue[string]]::new()
    $directories = @('frontend','server\src')
    if ($ProgramOnly) { $directories += 'tools\development' }
    else { $directories += @('tools','docs') }
    foreach ($directory in $directories) {
        $path = Join-Path $Root $directory
        if (Test-Path -LiteralPath $path -PathType Container) { $queue.Enqueue($path) }
    }
    $excluded = @('node_modules','target','dist','.vite','.git','.runtime','artifacts','.idea','.vscode',
        'data','uploads','backup','logs','static','.codex','.claude','.agents','.cursor','memory','memories')
    while ($queue.Count -gt 0) {
        foreach ($item in Get-ChildItem -LiteralPath $queue.Dequeue() -Force) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { continue }
            if ($item.PSIsContainer) {
                if ($excluded -notcontains $item.Name -and $item.Name -notmatch '\.tmpdir$') { $queue.Enqueue($item.FullName) }
            } elseif ($item.Name -notmatch '^\.env' -and $item.Name -notin @('.npmrc','settings.xml','id_rsa','id_ed25519','AGENTS.md','CLAUDE.md','GEMINI.md','MEMORY.md') -and
                $item.Name -notmatch '\.(log|db|sqlite|zip|bak|tmp|class|tsbuildinfo|pem|p12|pfx|key)(-.+)?$') {
                if ($ProgramOnly -and $item.Extension -in @('.pdf','.doc','.docx','.ppt','.pptx','.xlsx')) { continue }
                if ($ProgramOnly -and $item.Extension -eq '.md' -and
                    $item.FullName -ne (Join-Path $Root 'tools\development\README.md')) { continue }
                $files.Add($item.FullName)
            }
        }
    }
    return $files.ToArray()
}

function New-NxSourceArchive([string] $Root, [switch] $ProgramOnly) {
    $files = @(Get-NxSourceFiles $Root -ProgramOnly:$ProgramOnly)
    foreach ($file in $files) {
        if ([IO.Path]::GetFileName($file) -eq '.env.example') {
            foreach ($line in [IO.File]::ReadAllLines($file, [Text.Encoding]::UTF8)) {
                if ($line -match '^\s*[A-Z0-9_]*(?:KEY|TOKEN|SECRET|PASSWORD)\s*=(.*)$' -and
                    $Matches[1].Trim().Trim([char]34,[char]39).Length -gt 0) {
                    throw 'Source sharing stopped: .env.example must contain only empty credentials.'
                }
            }
        }
        if ([IO.Path]::GetFileName($file) -eq '.env.example' -or
            [IO.Path]::GetExtension($file) -in @('.ts','.tsx','.js','.mjs','.java','.json','.yml','.yaml','.xml','.properties','.html','.css','.csv','.md','.txt','.ps1','.cs','.bat')) {
            $content = [IO.File]::ReadAllText($file, [Text.Encoding]::UTF8)
            if ($content -match 'sk-[a-fA-F0-9]{24,}|sk-(?:(?:proj|svcacct)-)?[A-Za-z0-9_-]{48,}|npm_[A-Za-z0-9]{30,}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{60,}' -or
                $content -match '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----') {
                throw 'Source sharing stopped: a credential-shaped value was found. Review source files; values are not displayed.'
            }
        }
    }
    $release = Join-Path $Root 'artifacts\releases'
    [IO.Directory]::CreateDirectory($release) | Out-Null
    if (((Get-Item -LiteralPath (Join-Path $Root 'artifacts')).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        ((Get-Item -LiteralPath $release).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Source archive output must not be a junction or symbolic link.'
    }
    $output = Join-Path $release ('nongxin-source-' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8) + '.zip')
    $zip = [IO.Compression.ZipFile]::Open($output, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in $files) {
            $relative = $file.Substring($Root.TrimEnd('\').Length + 1).Replace('\','/')
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file, $relative, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $zip.Dispose() }
    Write-Host "[share] $($files.Count) source files: $output"
    Write-Host '[share] No Git operation was performed. Runtime, data, credentials and IDE files were excluded.'
    return $output
}
