[CmdletBinding()]
param(
    [string]$ApiBaseUrl = 'http://localhost:8080',
    [string]$FrontendPath = '..\nav-map-sys-front'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    Write-Host '== Backend unit/component tests =='
    & mvn -pl nav-server -am test
    if ($LASTEXITCODE -ne 0) {
        throw "Backend tests failed with exit code $LASTEXITCODE"
    }

    Write-Host '== Real PostGIS/pgRouting evidence test =='
    & mvn -pl nav-server -am `
        '-Dtest=ResumeEvidenceIntegrationTest' `
        '-Dresume.evidence=true' `
        '-Dsurefire.failIfNoSpecifiedTests=false' `
        test
    if ($LASTEXITCODE -ne 0) {
        throw "Evidence test failed with exit code $LASTEXITCODE"
    }

    Write-Host '== Local API smoke tests =='
    $openApi = Invoke-WebRequest -UseBasicParsing -Uri "$ApiBaseUrl/v3/api-docs" -TimeoutSec 15
    if ($openApi.StatusCode -ne 200 -or $openApi.Content.Length -le 0) {
        throw 'OpenAPI endpoint did not return a non-empty 200 response'
    }

    $poiResponse = Invoke-RestMethod `
        -Uri "$ApiBaseUrl/api/v1/pois?keyword=%E5%A4%A7%E5%AD%A6&limit=10" `
        -TimeoutSec 15
    if ($poiResponse.code -ne 1 -or $poiResponse.data.count -le 0) {
        throw 'POI keyword query did not return data'
    }

    $routeBody = @{
        origin = @{ poiId = 97 }
        destination = @{ poiId = 26200 }
        travelMode = 'WALKING'
        strategy = 'FASTEST'
        maxSnapDistanceLocal = 1000.0
    } | ConvertTo-Json -Depth 5
    $route = Invoke-RestMethod `
        -Method Post `
        -Uri "$ApiBaseUrl/api/v1/routing/routes" `
        -ContentType 'application/json' `
        -Body $routeBody `
        -TimeoutSec 60
    if ($route.code -ne 1 -or $route.data.distanceLocal -le 0 -or -not $route.data.geometryWkt) {
        throw 'Two-point route did not return positive distance and geometry'
    }

    # Project-local tile pyramid: origin=(12550000, 2800000), span=262144.
    $zoom = 14
    $tileCount = [math]::Pow(2, $zoom)
    $sampleX = 12622000.0
    $sampleY = 2639000.0
    $tileX = [math]::Floor((($sampleX - 12550000.0) / 262144.0) * $tileCount)
    $tileY = [math]::Floor(((2800000.0 - $sampleY) / 262144.0) * $tileCount)
    $tileUri = "$ApiBaseUrl/api/v1/map/tiles/base/$zoom/$tileX/$tileY.mvt"

    $timer = [Diagnostics.Stopwatch]::StartNew()
    $firstTile = Invoke-WebRequest -UseBasicParsing -Uri $tileUri -TimeoutSec 60
    $timer.Stop()
    $firstMs = $timer.Elapsed.TotalMilliseconds

    $timer.Restart()
    $repeatTile = Invoke-WebRequest -UseBasicParsing -Uri $tileUri -TimeoutSec 60
    $timer.Stop()
    $repeatMs = $timer.Elapsed.TotalMilliseconds
    if ($repeatTile.StatusCode -ne 200 -or $repeatTile.RawContentLength -le 0) {
        throw 'Vector-tile endpoint did not return a non-empty tile'
    }

    [pscustomobject]@{
        OpenApiStatus = $openApi.StatusCode
        PoiMatches = $poiResponse.data.count
        RouteDistanceLocal = [math]::Round($route.data.distanceLocal, 3)
        RouteDurationSeconds = [math]::Round($route.data.durationSeconds, 3)
        TileBytes = $repeatTile.RawContentLength
        TileFirstObservedMs = [math]::Round($firstMs, 3)
        TileRepeatMs = [math]::Round($repeatMs, 3)
        TileObservedSpeedup = [math]::Round($firstMs / $repeatMs, 2)
    } | Format-List
}
finally {
    Pop-Location
}

$resolvedFrontend = Resolve-Path -LiteralPath $FrontendPath -ErrorAction SilentlyContinue
if ($resolvedFrontend) {
    Push-Location $resolvedFrontend.Path
    try {
        Write-Host '== Frontend type-check and production build =='
        & npm run build
        if ($LASTEXITCODE -ne 0) {
            throw "Frontend build failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}
else {
    Write-Warning "Frontend path not found: $FrontendPath"
}
