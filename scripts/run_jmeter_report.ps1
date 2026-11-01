[CmdletBinding()]
param(
    [string]$HostName = 'localhost',
    [int]$Port = 8080,
    [int]$ReadThreads = 10,
    [int]$ReadLoops = 20,
    [int]$RouteThreads = 2,
    [int]$RouteLoops = 3,
    [int]$TourThreads = 1,
    [int]$TourLoops = 1
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$testPlan = Join-Path $repoRoot 'performance\jmeter\nav-api-load-test.jmx'
$reportRoot = Join-Path $repoRoot 'reports\jmeter\latest'
$htmlRoot = Join-Path $reportRoot 'html'
$resultFile = Join-Path $reportRoot 'results.jtl'
$logFile = Join-Path $reportRoot 'jmeter.log'
$summaryFile = Join-Path $reportRoot 'summary.csv'

if (-not $reportRoot.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to clean report directory outside repository: $reportRoot"
}

$jmeter = Get-Command jmeter -ErrorAction Stop

try {
    $health = Invoke-WebRequest -UseBasicParsing `
        -Uri "http://${HostName}:$Port/v3/api-docs" -TimeoutSec 10
    if ($health.StatusCode -ne 200) {
        throw "Backend health check returned HTTP $($health.StatusCode)"
    }
}
catch {
    throw "Backend is not ready at http://${HostName}:$Port : $($_.Exception.Message)"
}

if (Test-Path -LiteralPath $reportRoot) {
    Remove-Item -LiteralPath $reportRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $reportRoot | Out-Null

& $jmeter.Source `
    -n `
    -t $testPlan `
    "-Jhost=$HostName" `
    "-Jport=$Port" `
    "-Jread_threads=$ReadThreads" `
    "-Jread_loops=$ReadLoops" `
    "-Jroute_threads=$RouteThreads" `
    "-Jroute_loops=$RouteLoops" `
    "-Jtour_threads=$TourThreads" `
    "-Jtour_loops=$TourLoops" `
    -l $resultFile `
    -e `
    -o $htmlRoot `
    -j $logFile

if ($LASTEXITCODE -ne 0) {
    throw "JMeter failed with exit code $LASTEXITCODE; inspect $logFile"
}

$rows = @(Import-Csv -LiteralPath $resultFile)
if ($rows.Count -eq 0) {
    throw 'JMeter result file contains no samples'
}

function Get-Percentile([double[]]$Values, [double]$Percentile) {
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Min(
        $sorted.Count - 1,
        [Math]::Max(0, [Math]::Ceiling($Percentile * $sorted.Count) - 1)
    )
    return [double]$sorted[$index]
}

$summary = foreach ($group in ($rows | Group-Object label)) {
    $samples = @($group.Group)
    $elapsed = [double[]]@($samples | ForEach-Object { [double]$_.elapsed })
    $errors = @($samples | Where-Object { $_.success -ne 'true' }).Count
    [pscustomobject]@{
        Label = $group.Name
        Samples = $samples.Count
        Errors = $errors
        ErrorPercent = [Math]::Round(100.0 * $errors / $samples.Count, 3)
        AverageMs = [Math]::Round(($elapsed | Measure-Object -Average).Average, 3)
        P50Ms = [Math]::Round((Get-Percentile $elapsed 0.50), 3)
        P90Ms = [Math]::Round((Get-Percentile $elapsed 0.90), 3)
        P95Ms = [Math]::Round((Get-Percentile $elapsed 0.95), 3)
        P99Ms = [Math]::Round((Get-Percentile $elapsed 0.99), 3)
        MinMs = [Math]::Round(($elapsed | Measure-Object -Minimum).Minimum, 3)
        MaxMs = [Math]::Round(($elapsed | Measure-Object -Maximum).Maximum, 3)
    }
}

$summary | Sort-Object Label | Export-Csv -LiteralPath $summaryFile -NoTypeInformation -Encoding UTF8
$summary | Sort-Object Label | Format-Table -AutoSize

$totalErrors = @($rows | Where-Object { $_.success -ne 'true' }).Count
Write-Host "Samples: $($rows.Count); errors: $totalErrors"
Write-Host "HTML dashboard: $htmlRoot\index.html"
Write-Host "CSV summary: $summaryFile"
