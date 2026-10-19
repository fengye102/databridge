$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$docker = 'D:\Dev Tools Manager\Docker Desktop\resources\bin\docker.exe'
$compose = Join-Path $repo 'infra\compose.yaml'
$envFile = Join-Path $repo 'infra\.env'
if (-not (Test-Path -LiteralPath $docker -PathType Leaf)) { throw 'Docker CLI not found' }
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) { throw 'infra/.env not found' }

Get-Content -LiteralPath (Join-Path $PSScriptRoot 'demo-source.sql') -Raw |
    & $docker compose --env-file $envFile -f $compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot databridge_source'
if ($LASTEXITCODE -ne 0) { throw 'MySQL demo fixture failed' }
Get-Content -LiteralPath (Join-Path $PSScriptRoot 'demo-target.sql') -Raw |
    & $docker compose --env-file $envFile -f $compose exec -T postgres sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -U databridge -d databridge_target -v ON_ERROR_STOP=1'
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL demo fixture failed' }
Write-Output 'Demo tables prepared. Target table is only valid for a new task while empty.'
