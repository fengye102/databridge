param(
    [Parameter(Mandatory = $true)][string]$InputCsv,
    [string]$OutputCsv = (Join-Path $PSScriptRoot 'cdc-latency-summary.csv')
)

$ErrorActionPreference = 'Stop'
$records = @(Import-Csv -LiteralPath $InputCsv)
if ($records.Count -ne 300) { throw "Expected 300 latency samples, found $($records.Count)." }
foreach ($repeat in 1..3) {
    $group = @($records | Where-Object { [int]$_.repetition -eq $repeat })
    $indices = @($group | ForEach-Object { [int]$_.sample } | Sort-Object)
    if ($group.Count -ne 100 -or ($indices -join ',') -ne ((1..100) -join ',')) {
        throw "Expected samples 1..100 exactly once for repetition $repeat."
    }
}
foreach ($record in $records) {
    if ([int]$record.repetition -notin 1..3 -or [int]$record.row_id -ne [int]$record.sample -or
            [double]$record.latency_ms -lt 0 -or [double]$record.commit_ms -lt 0 -or
            [double]$record.schedule_lag_ms -lt 0) {
        throw "Invalid latency sample in repetition $($record.repetition), sample $($record.sample)."
    }
}

function Percentile([double[]]$Sorted, [double]$Probability) {
    $index = [math]::Ceiling($Probability * $Sorted.Count) - 1
    return [math]::Round($Sorted[$index], 3)
}

$summary = @(
    foreach ($repeat in @(1, 2, 3, 'all')) {
        $group = if ($repeat -eq 'all') { $records }
            else { @($records | Where-Object { [int]$_.repetition -eq $repeat }) }
        [double[]]$latencies = @($group | ForEach-Object { [double]$_.latency_ms } | Sort-Object)
        $commit = @($group | ForEach-Object { [double]$_.commit_ms } | Measure-Object -Average -Maximum)
        $lag = @($group | ForEach-Object { [double]$_.schedule_lag_ms } | Measure-Object -Average -Maximum)
        [pscustomobject]@{
            repetition = $repeat
            samples = $latencies.Count
            min_ms = [math]::Round($latencies[0], 3)
            p50_ms = Percentile $latencies 0.50
            p95_ms = Percentile $latencies 0.95
            p99_ms = Percentile $latencies 0.99
            max_ms = [math]::Round($latencies[-1], 3)
            mean_commit_ms = [math]::Round($commit[0].Average, 3)
            max_commit_ms = [math]::Round($commit[0].Maximum, 3)
            mean_schedule_lag_ms = [math]::Round($lag[0].Average, 3)
            max_schedule_lag_ms = [math]::Round($lag[0].Maximum, 3)
        }
    }
)
$summary | Export-Csv -LiteralPath $OutputCsv -NoTypeInformation -Encoding UTF8
Write-Output "Latency summary: $OutputCsv"
