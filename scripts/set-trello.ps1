<#
.SYNOPSIS
  Safely stores your Trello settings in the git-ignored root .env (prompts locally; nothing is printed or sent anywhere).

.DESCRIPTION
  Asks for TRELLO_API_KEY, TRELLO_TOKEN (both hidden) and TRELLO_LIST_ID. Creates .env from .env.example if it does not
  exist, updates only the three TRELLO_* lines and preserves every other line. Press Enter to keep an existing value.
  Afterwards restart the backend with .\scripts\start-backend.ps1 (it loads .env). No Trello request is made here.
#>
$root = Join-Path $PSScriptRoot '..'
$envFile = Join-Path $root '.env'
if (-not (Test-Path $envFile)) { Copy-Item (Join-Path $root '.env.example') $envFile; Write-Host 'Created .env from .env.example' }

function Plain($secure) { [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)) }
$lines = Get-Content $envFile
function Current($name) { ($lines | Where-Object { $_ -like "$name=*" } | Select-Object -First 1) -replace "^$name=", '' }

$new = @{}
$k = Plain (Read-Host 'Trello API key (hidden, Enter keeps current)' -AsSecureString)
$t = Plain (Read-Host 'Trello token (hidden, Enter keeps current)' -AsSecureString)
$l = Read-Host 'Trello list ID of a TEST list (Enter keeps current)'
if ($k) { $new.TRELLO_API_KEY = $k }
if ($t) { $new.TRELLO_TOKEN = $t }
if ($l) { $new.TRELLO_LIST_ID = $l.Trim() }

foreach ($name in $new.Keys) {
    if ($lines -match "^$name=") { $lines = $lines | ForEach-Object { if ($_ -like "$name=*") { "$name=$($new[$name])" } else { $_ } } }
    else { $lines += "$name=$($new[$name])" }
}
Set-Content $envFile $lines
Remove-Variable k, t, l, new

$lines = Get-Content $envFile
foreach ($n in 'TRELLO_API_KEY', 'TRELLO_TOKEN', 'TRELLO_LIST_ID') {
    Write-Host ("{0}: {1}" -f $n, $(if (Current $n) { 'set' } else { 'EMPTY' }))
}
Write-Host 'Next: .\scripts\start-backend.ps1  (restart the backend), then check the Trello row in the dashboard status panel.'
