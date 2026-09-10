#Requires -Version 5.1

[CmdletBinding()]
param(
    [string]$GameName = $env:BENCHMARK_GAME_NAME,
    [string]$TagLine = $env:BENCHMARK_TAG_LINE,
    [string]$BaseUrl = $(if ($env:BENCHMARK_BASE_URL) { $env:BENCHMARK_BASE_URL } else { "http://localhost:8080" }),
    [ValidateRange(1, 100)]
    [int]$Iterations = 2,
    [string]$OutputPath
)

if ([string]::IsNullOrWhiteSpace($GameName)) {
    throw "Provide -GameName or set BENCHMARK_GAME_NAME."
}

if ([string]::IsNullOrWhiteSpace($TagLine)) {
    throw "Provide -TagLine or set BENCHMARK_TAG_LINE."
}

$curl = Get-Command curl.exe -ErrorAction SilentlyContinue
if ($null -eq $curl) {
    throw "curl.exe was not found. Install a Windows version that includes curl.exe, then run this script again."
}

function Format-Milliseconds {
    param($Value)

    if ($null -eq $Value) {
        return "n/a"
    }

    return ("{0:N1}" -f [double]$Value)
}

$counts = @(1, 5, 10, 20)
$normalizedBaseUrl = $BaseUrl.TrimEnd("/")
$encodedGameName = [Uri]::EscapeDataString($GameName)
$encodedTagLine = [Uri]::EscapeDataString($TagLine)
$results = @()

foreach ($count in $counts) {
    for ($attempt = 1; $attempt -le $Iterations; $attempt += 1) {
        $url = "$normalizedBaseUrl/api/v1/players/$encodedGameName/$encodedTagLine/matches?start=0&count=$count"
        $curlOutput = & $curl.Source --silent --show-error --output NUL --write-out "`n__BENCHMARK__%{http_code};%{time_total}" $url 2>&1
        $curlExitCode = $LASTEXITCODE
        $rawOutput = $curlOutput | Out-String
        $measurement = [regex]::Match($rawOutput, "__BENCHMARK__(?<status>\d{3});(?<seconds>\d+(?:\.\d+)?)")

        $statusCode = $null
        $latencyMs = $null
        if ($measurement.Success) {
            $statusCode = [int]$measurement.Groups["status"].Value
            $latencySeconds = [double]::Parse(
                $measurement.Groups["seconds"].Value,
                [Globalization.CultureInfo]::InvariantCulture
            )
            $latencyMs = [math]::Round($latencySeconds * 1000, 1)
        }

        $success = $curlExitCode -eq 0 -and $null -ne $statusCode -and $statusCode -ge 200 -and $statusCode -lt 300
        $failureReason =
            if ($success) {
                $null
            } elseif ($curlExitCode -ne 0) {
                "curl.exe exited with code $curlExitCode."
            } elseif ($null -eq $statusCode) {
                "curl.exe did not report an HTTP status."
            } else {
                "HTTP status $statusCode."
            }

        $results +=
            [PSCustomObject]@{
                Count         = $count
                Attempt       = $attempt
                LatencyMs     = $latencyMs
                HttpStatus    = if ($null -eq $statusCode) { "n/a" } else { "{0:D3}" -f $statusCode }
                Success       = $success
                CurlExitCode  = $curlExitCode
                FailureReason = $failureReason
            }
    }
}

$summary =
    foreach ($count in $counts) {
        $attempts = @($results | Where-Object Count -eq $count)
        $successfulAttempts = @($attempts | Where-Object Success)
        $latencies = @($successfulAttempts | Select-Object -ExpandProperty LatencyMs)
        $statistics =
            if ($latencies.Count -gt 0) {
                $latencies | Measure-Object -Average -Minimum -Maximum
            } else {
                $null
            }

        [PSCustomObject]@{
            Count            = $count
            Attempts         = $attempts.Count
            Successful       = $successfulAttempts.Count
            Failed           = $attempts.Count - $successfulAttempts.Count
            AverageLatencyMs = Format-Milliseconds $statistics.Average
            MinimumLatencyMs = Format-Milliseconds $statistics.Minimum
            MaximumLatencyMs = Format-Milliseconds $statistics.Maximum
            HttpStatuses     = (($attempts | Group-Object HttpStatus | Sort-Object Name | ForEach-Object { "$($_.Name)x$($_.Count)" }) -join ", ")
        }
    }

Write-Host "Per-request results"
$results | Format-Table Count, Attempt, LatencyMs, HttpStatus, Success, CurlExitCode, FailureReason -AutoSize

Write-Host "Summary (latency statistics include successful HTTP 2xx responses only)"
$summary | Format-Table Count, Attempts, Successful, Failed, AverageLatencyMs, MinimumLatencyMs, MaximumLatencyMs, HttpStatuses -AutoSize

if ($OutputPath) {
    $outputDirectory = Split-Path -Parent $OutputPath
    if ($outputDirectory -and -not (Test-Path -LiteralPath $outputDirectory)) {
        New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    }

    $results | Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath $OutputPath
    Write-Host "Saved per-request results to $OutputPath"
}
