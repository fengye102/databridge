param(
    [switch]$Pilot,
    [switch]$Smoke
)

$ErrorActionPreference = 'Stop'
if ($Pilot -and $Smoke) { throw 'Choose at most one of -Pilot and -Smoke.' }
$repo = Split-Path $PSScriptRoot -Parent
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
$docker = if ($dockerCommand) { $dockerCommand.Source }
    else { 'D:\Dev Tools Manager\Docker Desktop\resources\bin\docker.exe' }
if (-not (Test-Path -LiteralPath $docker)) { throw "Docker CLI not found: $docker" }
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Maven is not on PATH.' }
& $docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker Engine is not available.' }

$mode = if ($Smoke) { 'smoke' } elseif ($Pilot) { 'pilot' } else { 'formal' }
$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$raw = Join-Path $repo "experiments\raw\full-copy-$mode-$stamp.csv"
$log = Join-Path $repo "work\full-copy-$mode-$stamp.log"
New-Item -ItemType Directory -Force -Path (Split-Path $raw -Parent), (Split-Path $log -Parent) | Out-Null
if (Test-Path -LiteralPath $raw) { throw "Output already exists: $raw" }
$oldJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = '-Xmx512m'
    Push-Location (Join-Path $repo 'backend')
    try {
        $args = @('--batch-mode', '-q', '-Dit.test=FullCopyBenchmarkIT',
            '-Ddatabridge.benchmark=true', "-Ddatabridge.benchmark.csv=$raw")
        if ($Pilot) { $args += '-Ddatabridge.benchmark.pilot=true' }
        if ($Smoke) { $args += '-Ddatabridge.benchmark.smoke=true' }
        $args += @('verify', '-Pintegration')
        & mvn @args *> $log
        if ($LASTEXITCODE -ne 0) {
            Write-Output "Benchmark failed; inspect $log and partial CSV $raw"
            exit $LASTEXITCODE
        }
    } finally { Pop-Location }
} finally { $env:JAVA_TOOL_OPTIONS = $oldJavaOptions }
Write-Output "Raw CSV: $raw"
Write-Output "Run log: $log"
