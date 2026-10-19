$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repo 'infra\.env'
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw 'Missing infra/.env. Copy infra/.env.example and set the three passwords.'
}

$secrets = @{}
foreach ($line in [System.IO.File]::ReadAllLines($envFile)) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
    $pair = $trimmed.Split('=', 2)
    if ($pair.Length -eq 2) { $secrets[$pair[0].Trim()] = $pair[1].Trim() }
}
foreach ($key in @('MYSQL_PASSWORD', 'POSTGRES_PASSWORD')) {
    if (-not $secrets.ContainsKey($key) -or $secrets[$key] -like 'replace-with-*') {
        throw "Missing configured $key in infra/.env"
    }
}

$env:DATABRIDGE_SOURCE_JDBC_URL = 'jdbc:mysql://127.0.0.1:3307/databridge_source?connectionTimeZone=UTC'
$env:DATABRIDGE_SOURCE_USERNAME = 'databridge'
$env:DATABRIDGE_SOURCE_PASSWORD = $secrets['MYSQL_PASSWORD']
$env:DATABRIDGE_TARGET_JDBC_URL = 'jdbc:postgresql://127.0.0.1:15433/databridge_target'
$env:DATABRIDGE_TARGET_USERNAME = 'databridge'
$env:DATABRIDGE_TARGET_PASSWORD = $secrets['POSTGRES_PASSWORD']

Push-Location $PSScriptRoot
try {
    & mvn --batch-mode spring-boot:run
    $runExitCode = $LASTEXITCODE
} finally {
    Pop-Location
    foreach ($key in @(
        'DATABRIDGE_SOURCE_JDBC_URL', 'DATABRIDGE_SOURCE_USERNAME', 'DATABRIDGE_SOURCE_PASSWORD',
        'DATABRIDGE_TARGET_JDBC_URL', 'DATABRIDGE_TARGET_USERNAME', 'DATABRIDGE_TARGET_PASSWORD')) {
        Remove-Item -Path "Env:$key" -ErrorAction SilentlyContinue
    }
}
exit $runExitCode
