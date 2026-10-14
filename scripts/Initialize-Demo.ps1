$ErrorActionPreference = 'Stop'
$demoRoot = Split-Path $PSScriptRoot
$envPath = Join-Path $demoRoot '.env'
if (Test-Path -LiteralPath $envPath) { Write-Host 'Existing demo .env retained.'; exit 0 }
function New-DemoSecret([int]$length) {
    $bytes = [byte[]]::new($length)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    [Convert]::ToBase64String($bytes)
}
$values = @(
    ('DB_PASSWORD=' + (New-DemoSecret 32))
    ('JWT_SECRET=' + (New-DemoSecret 64))
    'DEMO_PORT=18090'
)
[IO.File]::WriteAllLines($envPath, $values)
Write-Host 'Created local demo settings. Run: docker compose up -d --build'
