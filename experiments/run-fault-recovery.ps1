param([switch]$Smoke)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
$docker = if ($dockerCommand) { $dockerCommand.Source }
    else { 'D:\Dev Tools Manager\Docker Desktop\resources\bin\docker.exe' }
if (-not (Test-Path -LiteralPath $docker)) { throw "Docker CLI not found: $docker" }
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Maven is not on PATH.' }
& $docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker Engine is not available.' }

$mode = if ($Smoke) { 'smoke' } else { 'formal' }
$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$raw = Join-Path $repo "experiments\raw\fault-recovery-$mode-$stamp.csv"
$log = Join-Path $repo "work\fault-recovery-$mode-$stamp.log"
New-Item -ItemType Directory -Force -Path (Split-Path $raw -Parent), (Split-Path $log -Parent) | Out-Null
if (Test-Path -LiteralPath $raw) { throw "Output already exists: $raw" }
$oldJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = '-Xmx512m'
    Push-Location (Join-Path $repo 'backend')
    try {
        $args = @('--batch-mode', '-q', '-Dit.test=FaultRecoveryBenchmarkIT',
            '-Ddatabridge.fault=true', "-Ddatabridge.fault.csv=$raw")
        if ($Smoke) { $args += '-Ddatabridge.fault.smoke=true' }
        $args += @('verify', '-Pintegration')
        & mvn @args *> $log
        if ($LASTEXITCODE -ne 0) {
            Write-Output "Fault recovery benchmark failed; inspect $log and partial CSV $raw"
            exit $LASTEXITCODE
        }
    } finally { Pop-Location }
} finally { $env:JAVA_TOOL_OPTIONS = $oldJavaOptions }
Write-Output "Raw CSV: $raw"
Write-Output "Run log: $log"
