param(
    [string]$DatabaseHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { 'localhost' }),
    [int]$DatabasePort = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 5432 }),
    [string]$DatabaseName = $(if ($env:DB_NAME) { $env:DB_NAME } else { 'ledger_db' }),
    [string]$DatabaseUser = $(if ($env:DB_USER) { $env:DB_USER } else { 'postgres' })
)

$ErrorActionPreference = 'Stop'
$psql = Get-Command psql -ErrorAction Stop
$sqlFile = Join-Path $PSScriptRoot 'prepare-load-test.sql'
$accountPoolFile = Join-Path ([System.IO.Path]::GetTempPath()) 'zerodrift-load-test-accounts.json'
$previousPassword = $env:PGPASSWORD

if ($env:DB_PASSWORD) {
    $env:PGPASSWORD = $env:DB_PASSWORD
} elseif (-not $env:PGPASSWORD) {
    $env:PGPASSWORD = 'postgres'
}

try {
    $output = & $psql.Source `
        --no-psqlrc `
        --quiet `
        --no-align `
        --tuples-only `
        --set 'ON_ERROR_STOP=1' `
        --host $DatabaseHost `
        --port $DatabasePort `
        --username $DatabaseUser `
        --dbname $DatabaseName `
        --file $sqlFile

    if ($LASTEXITCODE -ne 0) {
        throw "Load-test account preparation failed (psql exit code $LASTEXITCODE)."
    }

    $json = ($output -join "`n").Trim()
    $accountPool = $json | ConvertFrom-Json
    if ($accountPool.retailAccountIds.Count -ne 10000 -or -not $accountPool.hotspotMerchantId) {
        throw 'The database did not return the expected 10,000 retail accounts and hotspot merchant.'
    }

    [System.IO.File]::WriteAllText($accountPoolFile, $json, [System.Text.Encoding]::ASCII)
    Write-Output $accountPoolFile
} finally {
    if ($null -eq $previousPassword) {
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    } else {
        $env:PGPASSWORD = $previousPassword
    }
}
