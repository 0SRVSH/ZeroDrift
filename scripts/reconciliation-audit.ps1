param(
    [string]$DatabaseHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { 'localhost' }),
    [int]$DatabasePort = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 5432 }),
    [string]$DatabaseName = $(if ($env:DB_NAME) { $env:DB_NAME } else { 'ledger_db' }),
    [string]$DatabaseUser = $(if ($env:DB_USER) { $env:DB_USER } else { 'postgres' })
)

$ErrorActionPreference = 'Stop'
$psql = Get-Command psql -ErrorAction SilentlyContinue
$docker = $null
if (-not $psql) {
    $docker = Get-Command docker -ErrorAction Stop
}
$sqlFile = Join-Path $PSScriptRoot 'reconciliation-audit.sql'
$previousPassword = $env:PGPASSWORD

try {
    if ($psql) {
        if ($env:DB_PASSWORD) {
            $env:PGPASSWORD = $env:DB_PASSWORD
        } elseif (-not $env:PGPASSWORD) {
            $env:PGPASSWORD = 'postgres'
        }

        & $psql.Source `
            --no-psqlrc `
            --set 'ON_ERROR_STOP=1' `
            --host $DatabaseHost `
            --port $DatabasePort `
            --username $DatabaseUser `
            --dbname $DatabaseName `
            --file $sqlFile
    } else {
        Get-Content -Raw -LiteralPath $sqlFile | & $docker.Source compose exec -T postgres psql `
            --no-psqlrc `
            --set 'ON_ERROR_STOP=1' `
            --username $DatabaseUser `
            --dbname $DatabaseName
    }

    if ($LASTEXITCODE -ne 0) {
        throw "Reconciliation audit failed (psql exit code $LASTEXITCODE)."
    }
} finally {
    if ($null -eq $previousPassword) {
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    } else {
        $env:PGPASSWORD = $previousPassword
    }
}
