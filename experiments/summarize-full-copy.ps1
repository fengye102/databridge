param(
    [Parameter(Mandatory = $true)][string]$InputCsv,
    [string]$OutputCsv = (Join-Path $PSScriptRoot 'full-copy-summary.csv')
)

$ErrorActionPreference = 'Stop'
$records = @(Import-Csv -LiteralPath $InputCsv)
if ($records.Count -ne 18) { throw "Expected 18 formal runs, found $($records.Count)." }
$summary = @(
    foreach ($rowCount in @(10000, 100000, 1000000)) {
        foreach ($batchSize in @(500, 1000)) {
            $group = @($records | Where-Object {
                [int]$_.rows -eq $rowCount -and [int]$_.batch_size -eq $batchSize
            })
            $repetitions = @($group | ForEach-Object { [int]$_.repetition } | Sort-Object)
            if ($group.Count -ne 3 -or ($repetitions -join ',') -ne '1,2,3') {
                throw "Expected repetitions 1,2,3 for rows=$rowCount batch=$batchSize."
            }
            $times = @($group | ForEach-Object { [long]$_.elapsed_ms } | Sort-Object)
            $throughput = @($group | ForEach-Object { [double]$_.rows_per_second })
            $peak = @($group | ForEach-Object { [long]$_.peak_heap_bytes } | Measure-Object -Maximum)
            [pscustomobject]@{
                rows = $rowCount
                batch_size = $batchSize
                runs = 3
                median_elapsed_ms = $times[1]
                min_elapsed_ms = $times[0]
                max_elapsed_ms = $times[2]
                mean_elapsed_ms = [math]::Round(($times | Measure-Object -Average).Average, 2)
                mean_rows_per_second = [math]::Round(($throughput | Measure-Object -Average).Average, 2)
                max_peak_heap_bytes = $peak[0].Maximum
            }
        }
    }
)
if ($summary.Count -ne 6) { throw "Expected six groups, found $($summary.Count)." }
$summary | Export-Csv -LiteralPath $OutputCsv -NoTypeInformation -Encoding UTF8
Write-Output "Summary: $OutputCsv"
