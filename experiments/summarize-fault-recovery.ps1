param(
    [Parameter(Mandatory = $true)][string]$InputCsv,
    [string]$OutputCsv = (Join-Path $PSScriptRoot 'fault-recovery-summary.csv')
)

$ErrorActionPreference = 'Stop'
$rows = @(Import-Csv -LiteralPath $InputCsv)
if ($rows.Count -ne 9) { throw "Expected 9 fault trials, found $($rows.Count)." }
$scenarios = @('full_copy', 'cdc_persist', 'event_replay')
$seen = @{}
foreach ($row in $rows) {
    if ($row.scenario -notin $scenarios -or [int]$row.repetition -notin 1..3) {
        throw "Invalid scenario/repetition: $($row.scenario)/$($row.repetition)."
    }
    $key = "$($row.scenario)/$($row.repetition)"
    if ($seen.ContainsKey($key)) { throw "Duplicate fault trial: $key." }
    $seen[$key] = $true
    if ([double]$row.recovery_ms -lt 0 -or [int]$row.difference_rows -ne 0 -or
            [int]$row.source_rows -ne [int]$row.target_rows -or $row.final_status -ne 'STREAMING' -or
            $row.kill_status -notin @('COPYING', 'STREAMING')) {
        throw "Invalid or inconsistent fault trial: $key."
    }
    if ($row.scenario -eq 'full_copy' -and [long]$row.checkpoint_last_id -le 0) {
        throw "Full-copy checkpoint missing: $key."
    }
    if ($row.scenario -ne 'full_copy' -and
            ([long]$row.event_sequence -le [long]$row.cursor_before -or
             [long]$row.event_sequence -le 0)) {
        throw "Unproven persisted event window: $key."
    }
    if ($row.scenario -eq 'event_replay' -and [int]$row.blocked_backends -lt 1) {
        throw "Replay was not observed blocked: $key."
    }
}
foreach ($scenario in $scenarios) {
    foreach ($repetition in 1..3) {
        if (-not $seen.ContainsKey("$scenario/$repetition")) {
            throw "Missing fault trial: $scenario/$repetition."
        }
    }
}

$summary = foreach ($scenario in $scenarios) {
    [double[]]$times = @($rows | Where-Object scenario -eq $scenario |
            ForEach-Object { [double]$_.recovery_ms } | Sort-Object)
    [pscustomobject]@{
        scenario = $scenario
        trials = 3
        min_recovery_ms = [math]::Round($times[0], 3)
        median_recovery_ms = [math]::Round($times[1], 3)
        max_recovery_ms = [math]::Round($times[2], 3)
        max_difference_rows = 0
    }
}
$summary | Export-Csv -LiteralPath $OutputCsv -NoTypeInformation -Encoding UTF8
Write-Output "Fault recovery summary: $OutputCsv"
