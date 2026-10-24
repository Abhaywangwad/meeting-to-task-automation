<#
.SYNOPSIS
  Repeatable smoke test for a RUNNING Meet2Action stack (MySQL, agent service, backend).

.DESCRIPTION
  Default mode (safe, free): health checks, configuration status, validation and error paths.
      No LLM request is made and nothing is written to the database.

  -Full : submits a sample transcript through the backend, which calls the agent and therefore the REAL LLM
          (this may cost money and requires LLM_* to be configured). It then exercises evidence display,
          unverified/stale approval refusal, approval, edit-resets-approval and reload against MySQL.
          It creates one meeting plus tasks in the database; there is no delete API, so remove them manually if wanted.

  -SyncOne : (with -Full) after the checks, asks you to type YES and then requests a Trello sync of ONE approved
          task through the backend. That creates exactly one card in the list configured by TRELLO_LIST_ID.
          Without -SyncOne no Trello request is ever made.

  The script never reads or prints credentials; it only talks to the backend and agent over HTTP.

.EXAMPLE
  .\scripts\smoke-test.ps1
  .\scripts\smoke-test.ps1 -Full
  .\scripts\smoke-test.ps1 -Full -SyncOne
#>
param(
    [string]$ApiBase = 'http://localhost:8080',
    [string]$AgentBase = 'http://localhost:8000',
    [switch]$Full,
    [switch]$SyncOne
)

$ErrorActionPreference = 'Stop'
$script:fail = 0
$script:warn = 0

function Pass($m) { Write-Host "[PASS] $m" -ForegroundColor Green }
function Fail($m) { $script:fail++; Write-Host "[FAIL] $m" -ForegroundColor Red }
function Warn($m) { $script:warn++; Write-Host "[WARN] $m" -ForegroundColor Yellow }
function Info($m) { Write-Host "[INFO] $m" -ForegroundColor Cyan }

# Returns @{ Status; Body } and never throws on HTTP error codes.
function Call($Method, $Url, $Body = $null, $TimeoutSec = 30) {
    $params = @{ Method = $Method; Uri = $Url; UseBasicParsing = $true; TimeoutSec = $TimeoutSec }
    if ($null -ne $Body) {
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 6 -Compress))
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try {
        $r = Invoke-WebRequest @params
        $status = [int]$r.StatusCode; $text = $r.Content
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        if ($null -eq $resp) { return @{ Status = 0; Body = $null; Error = $_.Exception.Message } }
        $status = [int]$resp.StatusCode
        $text = (New-Object System.IO.StreamReader($resp.GetResponseStream())).ReadToEnd()
    }
    $json = $null
    try { if ($text) { $json = $text | ConvertFrom-Json } } catch { }
    return @{ Status = $status; Body = $json; Raw = $text }
}

function Expect($Name, $Result, $Status, $Code = $null) {
    $okCode = ($null -eq $Code) -or ($Result.Body -and $Result.Body.code -eq $Code)
    $want = "HTTP $Status"
    if ($Code) { $want = "$want $Code" }
    if ($Result.Status -eq $Status -and $okCode) { Pass "${Name} ($want)" }
    else { Fail "${Name}: expected $want but got HTTP $($Result.Status) $($Result.Body.code) $($Result.Error)" }
}

Write-Host "Meet2Action smoke test  (backend $ApiBase, agent $AgentBase)" -ForegroundColor White
Write-Host "Mode: $(if($Full){'FULL (real extraction through the configured LLM)'}else{'basic (no LLM calls, no writes)'})"
Write-Host ''

# ---------- 1. Services and configuration (basic) ----------
$r = Call GET "$ApiBase/api/health"
if ($r.Status -eq 200 -and $r.Body.status -eq 'UP') { Pass 'Backend process is up' } else { Fail "Backend not reachable at $ApiBase (start it: cd backend; .\mvnw.cmd spring-boot:run)"; exit 1 }

$r = Call GET "$ApiBase/api/health/db"
if ($r.Status -eq 200) { Pass 'MySQL is connected (backend /api/health/db)' }
else { Fail 'MySQL is NOT connected: check MYSQL_* variables in the shell that started the backend, then restart it' }

$r = Call GET "$AgentBase/health"
if ($r.Status -eq 200 -and $r.Body.status -eq 'UP') { Pass 'Agent service is up' } else { Fail "Agent service not reachable at $AgentBase (cd agent-service; uvicorn app.main:app --port 8000)" }

$ready = Call GET "$AgentBase/ready"
$llmConfigured = $false
if ($ready.Status -eq 200) {
    $llmConfigured = [bool]$ready.Body.llm_configured
    if ($llmConfigured) { Pass 'LLM is configured on the agent service (not verified until a real extraction runs)' }
    else { Warn 'LLM is NOT configured: set LLM_API_KEY and LLM_MODEL in agent-service/.env and restart the agent. Analysis will fail with llm_not_configured.' }
}

$t = Call GET "$ApiBase/api/integrations/trello"
$trelloConfigured = $false
if ($t.Status -eq 200) {
    $trelloConfigured = [bool]$t.Body.configured
    if ($trelloConfigured) { Pass 'Trello credentials are configured on the backend (not verified until a sync runs)' }
    else { Warn 'Trello is NOT configured: set TRELLO_API_KEY, TRELLO_TOKEN, TRELLO_LIST_ID for the backend and restart it. Syncing will answer trello_not_configured.' }
}

# ---------- 2. Validation and error paths (basic, no writes) ----------
Expect 'Blank transcript is rejected' (Call POST "$ApiBase/api/meetings" @{ transcript = '   ' }) 400 'validation_error'
Expect 'Unknown meeting returns 404' (Call GET "$ApiBase/api/meetings/does-not-exist") 404 'not_found'
Expect 'Client cannot set evidence_verified' (Call PATCH "$ApiBase/api/tasks/does-not-exist" @{ evidence_verified = $true }) 400 'unknown_field'
Expect 'Sync of an unknown task returns 404' (Call POST "$ApiBase/api/tasks/does-not-exist/sync" @{ version = 0 }) 404 'not_found'
$list = Call GET "$ApiBase/api/tasks"
if ($list.Status -eq 200) { Pass "Task list readable ($(@($list.Body).Count) stored task(s))" } else { Fail 'GET /api/tasks failed' }

if (-not $Full) {
    Write-Host ''
    Write-Host "Basic checks done: $script:fail failure(s), $script:warn warning(s). Use -Full for the real extraction workflow."
    exit ([int]($script:fail -gt 0))
}

# ---------- 3. Full workflow (real extraction) ----------
Write-Host ''
Write-Host '--- FULL workflow ---' -ForegroundColor White
if (-not $llmConfigured) { Fail 'Cannot run the full workflow: LLM not configured on the agent service.'; exit 1 }

$transcript = @'
Alex: Thanks everyone for joining the sprint sync. Let's go through the release blockers.
Priya: The payment API tests are still flaky on staging.
Alex: Priya, please prepare the test report by Friday.
Priya: Sure, I'll have it ready before the Friday standup.
Sam: We should probably look into caching at some point, but that's not for this release.
Alex: Okay. We decided to ship version 2.1 next Tuesday if the report looks good.
Sam: I finished the login page redesign yesterday, it's merged.
Alex: Great. Who is going to update the onboarding docs?
Priya: Not sure yet, we'll figure that out later.
'@ -replace "`r`n", "`n"

Info 'Submitting the sample transcript (this calls the real LLM through the agent)...'
$m = Call POST "$ApiBase/api/meetings" @{ transcript = $transcript } 120
if ($m.Status -notin 200, 201) {
    Fail "Analysis failed: HTTP $($m.Status) $($m.Body.code) - $($m.Body.message)"
    exit 1
}
$meeting = $m.Body
Pass "Meeting stored in MySQL: $($meeting.id) (extraction_status $($meeting.extraction_status))"
Info "Proposed tasks: $(@($meeting.tasks).Count); decisions: $(@($meeting.decisions).Count); questions: $(@($meeting.unresolved_questions).Count); completed updates: $(@($meeting.completed_updates).Count)"

# Evidence must be consistent with the stored transcript, independent of what the model claimed.
$badEvidence = 0
foreach ($task in @($meeting.tasks)) {
    $verifiedFlag = ($task.evidence_status -eq 'VERIFIED')
    if ($verifiedFlag -ne [bool]$task.evidence_verified) { $badEvidence++ }
    if ($verifiedFlag -and $null -ne $task.evidence_start -and $task.evidence_match -eq 'exact') {
        $slice = $meeting.transcript.Substring($task.evidence_start, $task.evidence_end - $task.evidence_start)
        if ($slice -ne $task.evidence_quote) { $badEvidence++ }
    }
    if ($verifiedFlag -eq $false -and $null -ne $task.evidence_start) { $badEvidence++ }
}
if ($badEvidence -eq 0) { Pass 'Every task quote is consistent with the stored transcript and its evidence flags' } else { Fail "$badEvidence task(s) have inconsistent evidence data" }

$unknown = @($meeting.tasks | Where-Object { $null -eq $_.owner -or $null -eq $_.deadline })
if ($unknown.Count -gt 0) { Pass "$($unknown.Count) task(s) keep an unknown owner/deadline as null (nothing guessed)" } else { Info 'All proposed tasks had an owner and a deadline in the transcript' }

$verified = @($meeting.tasks | Where-Object { $_.evidence_status -eq 'VERIFIED' })
$unverified = @($meeting.tasks | Where-Object { $_.evidence_status -ne 'VERIFIED' })
if ($verified.Count -eq 0) { Fail 'No verified task was produced; cannot continue the review checks'; exit 1 }

if ($unverified.Count -gt 0) {
    $u = $unverified[0]
    Expect 'Approving an UNVERIFIED task is refused' (Call POST "$ApiBase/api/tasks/$($u.id)/approve" @{ version = $u.version }) 422 'evidence_not_verified'
} else { Info 'The model produced no unverified quote this time, so the unverified-approval refusal was not demonstrated (it is covered by automated tests).' }

$task = $verified[0]
Expect 'Approving with a STALE version is refused' (Call POST "$ApiBase/api/tasks/$($task.id)/approve" @{ version = $task.version + 99 }) 409 'version_conflict'
$a = Call POST "$ApiBase/api/tasks/$($task.id)/approve" @{ version = $task.version }
if ($a.Status -eq 200 -and $a.Body.review_status -eq 'APPROVED') { Pass 'Verified task approved with the current version' } else { Fail "Approve failed: HTTP $($a.Status) $($a.Body.code)" }

$e = Call PATCH "$ApiBase/api/tasks/$($task.id)" @{ owner = 'Smoke Tester' }
if ($e.Status -eq 200 -and $e.Body.review_status -eq 'PENDING_REVIEW') { Pass 'Editing an approved task reset it to PENDING_REVIEW' } else { Fail "Edit did not reset approval: HTTP $($e.Status) $($e.Body.review_status)" }
Expect 'Syncing the edited-but-not-reapproved task is refused' (Call POST "$ApiBase/api/tasks/$($task.id)/sync" @{ version = $e.Body.version }) 409 'task_not_approved'

$reload = Call GET "$ApiBase/api/tasks/$($task.id)"
if ($reload.Status -eq 200 -and $reload.Body.owner -eq 'Smoke Tester' -and $reload.Body.review_status -eq 'PENDING_REVIEW') { Pass 'Reloading reads the same state back from the backend/MySQL' } else { Fail 'Reload did not return the stored state' }

$a2 = Call POST "$ApiBase/api/tasks/$($task.id)/approve" @{ version = $reload.Body.version }
if ($a2.Status -eq 200) { Pass 'Task re-approved after the edit' } else { Fail "Re-approval failed: HTTP $($a2.Status)" }

# ---------- 4. Optional, deliberate Trello sync ----------
if (-not $SyncOne) {
    Info "Trello was NOT contacted. To create exactly ONE card for task $($task.id), re-run with -Full -SyncOne, or click 'Sync to Trello' in the UI."
} elseif (-not $trelloConfigured) {
    Fail 'Cannot sync: Trello is not configured on the backend.'
} else {
    Write-Host ''
    Write-Host "About to create ONE real Trello card for task '$($a2.Body.title)' in the list configured on the backend." -ForegroundColor Yellow
    $answer = Read-Host 'Type YES to continue'
    if ($answer -ceq 'YES') {
        $s = Call POST "$ApiBase/api/tasks/$($task.id)/sync" @{ version = $a2.Body.version } 60
        if ($s.Status -eq 200 -and $s.Body.sync_status -eq 'SYNCED') { Pass "Synced. Card URL: $($s.Body.trello_card_url)" }
        elseif ($s.Body.sync_status -eq 'UNCERTAIN' -or $s.Body.code -eq 'sync_uncertain') { Warn "Outcome UNKNOWN (a card may exist). In the UI click 'Check Trello', or POST $ApiBase/api/tasks/$($task.id)/sync/reconcile" }
        else { Fail "Sync failed truthfully: HTTP $($s.Status) $($s.Body.code) - $($s.Body.message)" }
    } else { Info 'Sync cancelled; nothing was sent to Trello.' }
}

Write-Host ''
Write-Host "Full workflow done: $script:fail failure(s), $script:warn warning(s)."
exit ([int]($script:fail -gt 0))
