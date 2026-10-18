$ErrorActionPreference = 'Stop'

$composeFile = Join-Path $PSScriptRoot 'compose.yaml'
$envFile = Join-Path $PSScriptRoot '.env'
$dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
$docker = if ($dockerCommand) { $dockerCommand.Source } else { 'D:\Dev Tools Manager\Docker Desktop\resources\bin\docker.exe' }
if (-not (Test-Path -LiteralPath $docker)) { throw "Docker CLI not found: $docker" }
if (-not (Test-Path -LiteralPath $envFile)) { throw "Local environment file not found: $envFile" }
$env:PATH = "$(Split-Path -Parent $docker);$env:PATH"
$compose = @('compose', '--env-file', $envFile, '-f', $composeFile)

function Invoke-Docker([string[]]$arguments) {
    $output = & $docker @arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "docker $($arguments -join ' ') failed (exit $LASTEXITCODE): $($output -join ' ')" }
    return $output
}

Invoke-Docker ($compose + @('config', '--quiet')) | Out-Null
$rows = @(Invoke-Docker ($compose + @('ps', '--format', 'json')))
$containers = @($rows | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json })
foreach ($service in 'mysql', 'postgres') {
    $match = @($containers | Where-Object { $_.Service -eq $service -and $_.State -eq 'running' -and $_.Health -eq 'healthy' })
    if ($match.Count -ne 1) { throw "$service is not running and healthy" }
}

$mysqlQuery = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h 127.0.0.1 -u "$MYSQL_USER" -N -e "SELECT VERSION(), @@log_bin, @@binlog_format, @@binlog_row_image, @@binlog_expire_logs_seconds;"'
$mysqlResult = @(Invoke-Docker ($compose + @('exec', '-T', 'mysql', 'sh', '-lc', $mysqlQuery)))
$mysqlFields = $mysqlResult[0] -split "`t"
if ($mysqlFields.Count -ne 5 -or $mysqlFields[0] -ne '8.4.11' -or $mysqlFields[1] -ne '1' -or $mysqlFields[2] -ne 'ROW' -or $mysqlFields[3] -ne 'FULL' -or $mysqlFields[4] -ne '604800') {
    throw "Unexpected MySQL version or Binlog settings: $($mysqlResult -join ' ')"
}
$grantQuery = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h 127.0.0.1 -u "$MYSQL_USER" -N -e "SHOW BINARY LOG STATUS; SHOW GRANTS FOR CURRENT_USER();"'
$grants = @(Invoke-Docker ($compose + @('exec', '-T', 'mysql', 'sh', '-lc', $grantQuery))) -join ' '
foreach ($permission in 'RELOAD', 'SHOW DATABASES', 'REPLICATION SLAVE', 'REPLICATION CLIENT') {
    if ($grants -notlike "*$permission*") { throw "MySQL grant missing: $permission" }
}
if ($grants -like '*GRANT ALL PRIVILEGES*' -or $grants -notlike '*GRANT SELECT ON*') {
    throw 'MySQL CDC account is not read-only on the source database'
}

$pgQuery = 'PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SHOW server_version;"'
$pgVersion = @(Invoke-Docker ($compose + @('exec', '-T', 'postgres', 'sh', '-lc', $pgQuery)))[0]
if (-not $pgVersion.StartsWith('17.11')) { throw "Unexpected PostgreSQL version: $pgVersion" }

foreach ($port in 3307, 15433) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try { $client.Connect('127.0.0.1', $port) }
    catch { throw "TCP 127.0.0.1:$port is unavailable: $($_.Exception.Message)" }
    finally { $client.Dispose() }
}

foreach ($volume in 'databridge-dev_mysql_data', 'databridge-dev_postgres_data') {
    Invoke-Docker @('volume', 'inspect', $volume, '--format', '{{.Name}}') | Out-Null
}
if (-not (Test-Path -LiteralPath 'D:\Dev Tools Manager\Docker Data\main\ext4.vhdx')) {
    throw 'Docker WSL data disk not found on D:'
}

"PASS: MySQL $($mysqlFields[0]) ROW/FULL Binlog, PostgreSQL $pgVersion, both healthy, loopback ports 3307/15433, named volumes on D:"
