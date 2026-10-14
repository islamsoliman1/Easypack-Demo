$ErrorActionPreference = 'Stop'
$demoRoot = Split-Path $PSScriptRoot
$files = @('README.md','railway.json','Dockerfile.Railway','compose.yaml','.gitignore','.dockerignore','.gitattributes',
    'frontend/package.json','frontend/package-lock.json','frontend/index.html','frontend/tsconfig.json','frontend/vite.config.ts',
    'backendjava/pom.xml','backendjava/Dockerfile')
foreach ($folder in @('frontend/src','frontend/public','backendjava/src/main','backendjava/src/test','scripts')) {
    $files += Get-ChildItem -LiteralPath (Join-Path $demoRoot $folder) -File -Recurse | ForEach-Object {
        if ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked files cannot be exported.' }
        [IO.Path]::GetRelativePath($demoRoot, $_.FullName)
    }
}
if (Test-Path -LiteralPath (Join-Path $demoRoot 'VERIFICATION.md')) { $files += 'VERIFICATION.md' }
$outputDir = Join-Path $demoRoot 'output'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$archivePath = Join-Path $outputDir ('easypack-demo-github-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.zip')
Add-Type -AssemblyName System.IO.Compression
$archive = [IO.Compression.ZipFile]::Open($archivePath, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($relative in $files) {
        $inputPath = Join-Path $demoRoot $relative
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $inputPath, $relative.Replace('\','/'), [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose() }
Write-Host "GitHub source archive: $archivePath"
Write-Host 'Contains source only; excludes .env, dependencies, builds and local database data.'
