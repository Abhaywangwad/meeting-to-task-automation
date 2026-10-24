<#
.SYNOPSIS
  Starts Spring Boot with settings loaded from the git-ignored root .env file (the backend itself does not read .env).

.DESCRIPTION
  Copy .env.example to .env, fill in MYSQL_* (and optionally TRELLO_* / AGENT_SERVICE_URL), then run this script.
  Values are placed in this process's environment only. The script prints variable NAMES, never values.
  Variables already set in your shell win over the file. Empty values in the file are skipped.

.EXAMPLE
  .\scripts\start-backend.ps1            # load .env, then run the backend
  .\scripts\start-backend.ps1 -DryRun    # only show which variable names would be loaded
#>
param([switch]$DryRun, [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'))

$backendVars = 'MYSQL_HOST', 'MYSQL_PORT', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD', 'SERVER_PORT',
    'CORS_ALLOWED_ORIGINS', 'JPA_DDL_AUTO', 'SCHEMA_INIT', 'AGENT_SERVICE_URL', 'AGENT_CONNECT_TIMEOUT_SECONDS',
    'AGENT_READ_TIMEOUT_SECONDS', 'MEETING_DEDUPE_WINDOW_SECONDS', 'TRELLO_API_KEY', 'TRELLO_TOKEN', 'TRELLO_LIST_ID',
    'TRELLO_API_BASE_URL', 'TRELLO_CONNECT_TIMEOUT_SECONDS', 'TRELLO_READ_TIMEOUT_SECONDS', 'TRELLO_STALE_SYNC_SECONDS'

$loaded = @()
if (Test-Path $EnvFile) {
    foreach ($line in Get-Content $EnvFile) {
        if ($line -match '^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$') {
            $name = $Matches[1]
            $value = ($Matches[2] -replace '\s+#.*$', '').Trim().Trim('"')
            if ($backendVars -contains $name -and $value -ne '' -and -not (Test-Path "Env:$name")) {
                Set-Item "Env:$name" $value
                $loaded += $name
            }
        }
    }
    Write-Host "Loaded from $(Resolve-Path $EnvFile): $(if($loaded){$loaded -join ', '}else{'(nothing)'})"
} else {
    Write-Host "No .env file at $EnvFile; using the current shell environment only."
}
foreach ($required in 'MYSQL_USER', 'MYSQL_PASSWORD') {
    if (-not (Test-Path "Env:$required")) { Write-Warning "$required is not set: the backend will start, but MySQL will show as unavailable." }
}
if (-not (Test-Path 'Env:TRELLO_API_KEY')) { Write-Host 'Trello not configured: syncing will be disabled (optional).' }

if ($DryRun) { return }
Push-Location (Join-Path $PSScriptRoot '..\backend')
try { & .\mvnw.cmd spring-boot:run } finally { Pop-Location }
