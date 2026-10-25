param(
    [ValidateSet('uniform', 'hotspot')]
    [string]$Scenario = 'uniform',
    [string]$Duration = '30s',
    [int]$Rate = 100,
    [int]$PreAllocatedVUs = 20,
    [int]$MaxVUs = 50,
    [int]$VirtualUsers = 50,
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$DatabaseHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { 'localhost' }),
    [int]$DatabasePort = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 5432 }),
    [string]$DatabaseName = $(if ($env:DB_NAME) { $env:DB_NAME } else { 'ledger_db' }),
    [string]$DatabaseUser = $(if ($env:DB_USER) { $env:DB_USER } else { 'postgres' })
)

$ErrorActionPreference = 'Stop'
$k6 = Get-Command k6 -ErrorAction Stop
$prepareScript = Join-Path $PSScriptRoot 'prepare-load-test.ps1'
$auditScript = Join-Path $PSScriptRoot 'reconciliation-audit.ps1'
$testScript = Join-Path (Split-Path $PSScriptRoot -Parent) "k6\$(
    if ($Scenario -eq 'uniform') { 'load-test-uniform.js' } else { 'stress-test-hotspot.js' }
)"
$previousEnvironment = @{
    ACCOUNT_POOL_FILE = $env:ACCOUNT_POOL_FILE
    BASE_URL = $env:BASE_URL
    DURATION = $env:DURATION
    RATE = $env:RATE
    PREALLOCATED_VUS = $env:PREALLOCATED_VUS
    MAX_VUS = $env:MAX_VUS
    VUS = $env:VUS
    PGPASSWORD = $env:PGPASSWORD
}

try {
    $prepareArguments = @{
        DatabaseHost = $DatabaseHost
        DatabasePort = $DatabasePort
        DatabaseName = $DatabaseName
        DatabaseUser = $DatabaseUser
    }
    $accountPoolFile = & $prepareScript @prepareArguments
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) {
        throw "Load-test account preparation failed (exit code $LASTEXITCODE)."
    }

    $env:ACCOUNT_POOL_FILE = $accountPoolFile
    $env:BASE_URL = $BaseUrl
    $env:DURATION = $Duration
    $k6Arguments = @(
        'run',
        '--env', "ACCOUNT_POOL_FILE=$accountPoolFile",
        '--env', "BASE_URL=$BaseUrl",
        '--env', "DURATION=$Duration"
    )
    if ($Scenario -eq 'uniform') {
        $env:RATE = "$Rate"
        $env:PREALLOCATED_VUS = "$PreAllocatedVUs"
        $env:MAX_VUS = "$MaxVUs"
        $k6Arguments += @(
            '--env', "RATE=$Rate",
            '--env', "PREALLOCATED_VUS=$PreAllocatedVUs",
            '--env', "MAX_VUS=$MaxVUs"
        )
    } else {
        $env:VUS = "$VirtualUsers"
        $k6Arguments += @('--env', "VUS=$VirtualUsers")
    }

    Write-Host "Running $Scenario benchmark against $BaseUrl."
    & $k6.Source @k6Arguments $testScript
    $loadTestExitCode = $LASTEXITCODE

    Write-Host 'Running post-load reconciliation audit.'
    $auditArguments = @{
        DatabaseHost = $DatabaseHost
        DatabasePort = $DatabasePort
        DatabaseName = $DatabaseName
        DatabaseUser = $DatabaseUser
    }
    & $auditScript @auditArguments
    $auditExitCode = $LASTEXITCODE

    if ($loadTestExitCode -ne 0) {
        throw "k6 benchmark failed (exit code $loadTestExitCode); reconciliation audit was still executed."
    }
    if ($auditExitCode -and $auditExitCode -ne 0) {
        throw "Post-load reconciliation audit failed (exit code $auditExitCode)."
    }
} finally {
    foreach ($name in $previousEnvironment.Keys) {
        $value = $previousEnvironment[$name]
        if ($null -eq $value) {
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
        } else {
            Set-Item "Env:$name" $value
        }
    }
}
