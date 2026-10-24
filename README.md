# Meet2Action

AI-powered meeting-to-execution agent. *Turn meeting conversations into evidence-backed commitments.*

Planned workflow: **Transcript → AI Extraction → Evidence Review → Human Approval → Trello Sync**

Current state: React dashboard, Spring Boot API with MySQL persistence and a human approval workflow, a FastAPI agent service for LLM extraction with independent evidence verification, and explicit, safely gated Trello sync.

**Demo principles:** AI proposes, humans approve · evidence is independently checked against the transcript · missing information stays unknown rather than guessed · only approved, verified, current task versions can be synced · integration failures are shown honestly.

## Technology stack

| Layer | Tech |
|---|---|
| Frontend | React + TypeScript + Vite |
| Backend | Java 21 + Spring Boot 3.5 + Maven (Spring Data JPA, MySQL) |
| AI service | Python + FastAPI |
| Database | MySQL 8+ |
| Integrations | Google Gemini via its OpenAI-compatible API (any OpenAI-compatible provider works; agent service), Trello REST API (backend) |

```
Meet2Action/
├── frontend/         React app (port 5173)
├── backend/          Spring Boot API (port 8080)
├── agent-service/    FastAPI service (port 8000)
├── scripts/          smoke-test.ps1, start-backend.ps1
├── tests/            placeholder (tests live in each service)
├── docker-compose.yml  optional MySQL
├── .env.example
└── README.md
```

## Prerequisites

- Node.js 20+ and npm
- JDK 21 (Maven is bundled via `./mvnw`; no separate install needed)
- Python 3.10+
- MySQL 8+ (local install **or** Docker)

## Demo runbook (Windows PowerShell; run each numbered block in its own terminal)

All local settings live in git-ignored files or your shell. Nothing below needs a file that is not described here.

**0. One-time setup**
```powershell
Copy-Item .env.example .env                 # then edit .env: set MYSQL_USER and MYSQL_PASSWORD (and optionally TRELLO_*)
Copy-Item agent-service\.env.example agent-service\.env   # then edit agent-service\.env: paste your own Gemini key after LLM_API_KEY= (see “Google Gemini setup”)
cd agent-service; python -m venv .venv; .\.venv\Scripts\Activate.ps1; pip install -r requirements.txt; cd ..
cd frontend; npm install; cd ..
```
Create the database once if needed: `CREATE DATABASE IF NOT EXISTS meet2action CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`

**1. MySQL** must be running. Windows: `Get-Service *mysql*` then `Start-Service <name>` (admin). Linux: `sudo systemctl start mysql`. Or `docker compose up -d` (optional).

**2. Agent service (FastAPI, port 8000)** starts even without LLM credentials; analysis then answers `llm_not_configured`.
```powershell
cd agent-service; .\.venv\Scripts\Activate.ps1; uvicorn app.main:app --port 8000
```
**3. Backend (Spring Boot, port 8080)**: `start-backend.ps1` loads the root `.env` into the process (printing only variable names) and runs Maven.
```powershell
.\scripts\start-backend.ps1
```
**4. Frontend (Vite, port 5173)**
```powershell
cd frontend; npm run dev
```
**5. Verify before presenting** (no LLM call, no database writes):
```powershell
.\scripts\smoke-test.ps1
```
It must show `[PASS]` for backend, MySQL, agent; a `[WARN]` means an optional piece (LLM or Trello) is not configured.

**6. Present** at <http://localhost:5173>:
1. *System status* panel: backend, MySQL, AI agent, Trello rows are real checks. Point out a warning if the LLM or Trello is unconfigured: nothing is faked.
2. Click **Insert sample transcript** then **Analyze Meeting**. This calls your real LLM. Review each task: quote, evidence badge, owner/deadline “Not specified” when the transcript does not say.
3. **Unverified evidence**: only appears if the model returns a quote that is not in the transcript. If one appears, show its warning and the disabled Approve button. To demonstrate it deterministically without an LLM, run `cd backend; .\mvnw.cmd test -Dtest=WorkflowTest` (mocked test data) — do not present mocked data as live output.
4. **Edit** an owner, **Approve**, then **Edit** again: the task returns to *Pending review* (approval is tied to one exact version). Approve again. **Reject** another task (final).
5. Refresh the page: the last meeting reloads from MySQL; **All tasks** lists everything stored.
6. **Trello** (optional, deliberate): see “Manual live Trello test” below. After syncing, show the card link and the *Synced to Trello* badge. If Trello fails or times out the UI says so (`FAILED` / `UNCERTAIN` + **Check Trello**), never “success”.

**Manual live LLM check without the UI** (spends tokens; run only when you want to):
```powershell
curl.exe -s -X POST http://localhost:8000/extract -H "Content-Type: application/json" -d "{\"transcript\": \"Alex: Priya, please prepare the test report by Friday.\"}"
```
Or the full workflow script: `.\scripts\smoke-test.ps1 -Full` (creates one meeting and its tasks in MySQL; there is no delete API).

### Manual live Trello test (creates exactly ONE card)

1. In Trello create a **scratch board and list** just for testing. Get an API key and token (Power-Up admin page, see “Trello sync” below) and the list ID.
2. Put `TRELLO_API_KEY`, `TRELLO_TOKEN`, `TRELLO_LIST_ID` in the git-ignored root `.env`, restart the backend with `.\scripts\start-backend.ps1`, and confirm the status panel shows *Trello … credentials configured*.
3. In the UI approve one verified task and click **Sync to Trello** once, or run `.\scripts\smoke-test.ps1 -Full -SyncOne` which asks you to type `YES` before sending anything.
4. Expected: the task shows *Synced to Trello* with a working card link; the card description holds the evidence quote and `[meet2action-task-id:…]`. Clicking Sync again returns the same card (no second card). Delete the test card yourself in Trello afterwards; Meet2Action never deletes or archives cards.

## Smoke test and verification status

`scripts/smoke-test.ps1` (see its header) is the repeatable check of a running stack: default mode is read-only and free; `-Full` runs the real extraction workflow; `-SyncOne` additionally syncs one task after you type `YES`.

What is verified how:

| Area | Evidence |
|---|---|
| Backend logic, approval/sync rules, concurrency | Maven tests on in-memory H2 with mocked agent and Trello (`WorkflowTest`, `TrelloSyncTest`, …) |
| MySQL schema and persistence | `MySqlIntegrationTest` against real MySQL (skipped unless `MYSQL_PASSWORD` is set); run with `JPA_DDL_AUTO=validate` to also check entities against the tables |
| Agent service | pytest with a mocked LLM; no live provider call is part of any automated test |
| Frontend | Vitest with mocked API; production build |
| Trello over real HTTP | verified against a throwaway local fake Trello server only (timeouts, 401/404/429, reconciliation, concurrency); **never against trello.com** |
| Live LLM, live Trello | not exercised by automated checks; use the manual procedures above |

## Environment variables

The backend reads real environment variables (it does not load `.env` files itself; `scripts/start-backend.ps1` loads the root `.env` for you); the agent service also reads `agent-service/.env`; Vite reads `frontend/.env`. See [.env.example](.env.example) for every variable. Never commit real `.env` files (they are git-ignored).

PowerShell (set for the current session):

```powershell
$env:MYSQL_HOST="localhost"; $env:MYSQL_PORT="3306"; $env:MYSQL_DATABASE="meet2action"
$env:MYSQL_USER="meet2action_user"; $env:MYSQL_PASSWORD="your_local_password"
```

Linux/macOS:

```bash
export MYSQL_HOST=localhost MYSQL_PORT=3306 MYSQL_DATABASE=meet2action
export MYSQL_USER=meet2action_user MYSQL_PASSWORD=your_local_password
```

## MySQL setup

Option A — local MySQL (run as an admin user, choose your own password):

```sql
CREATE DATABASE meet2action CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'meet2action_user'@'localhost' IDENTIFIED BY 'your_local_password';
GRANT ALL PRIVILEGES ON meet2action.* TO 'meet2action_user'@'localhost';
FLUSH PRIVILEGES;
```

```bash
mysql -u root -p      # then paste the SQL above
```

Option B — Docker (optional): copy `.env.example` to `.env`, set the passwords, then

```bash
docker compose up -d
```

## Run the services

Backend (port 8080):

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```
```bash
cd backend && ./mvnw spring-boot:run
```

AI service (port 8000):

```powershell
cd agent-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```
```bash
cd agent-service && python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

Frontend (port 5173):

```bash
cd frontend
npm install
npm run dev
```

## Health checks

```powershell
curl.exe http://localhost:8080/api/health      # backend process (does not need MySQL)
curl.exe http://localhost:8080/api/health/db   # MySQL connectivity (503 if DB unreachable)
curl.exe http://localhost:8000/health          # AI service
```
```bash
curl http://localhost:8080/api/health
curl http://localhost:8080/api/health/db
curl http://localhost:8000/health
```

The frontend at http://localhost:5173 performs the backend and AI-service checks live and shows loading / connected / error states.

## AI extraction (agent service)

`POST http://localhost:8000/extract` sends the transcript to an OpenAI-compatible LLM, validates the JSON it returns, and then **independently checks every quotation against the transcript in application code**. Extraction needs LLM credentials; the service itself (and `/health`) starts without them.

### Google Gemini setup (default)

The agent talks to Google's **OpenAI-compatible** Gemini endpoint through the existing provider adapter (`POST {LLM_BASE_URL}/chat/completions`, `Authorization: Bearer <key>`, system prompt and transcript as separate `system`/`user` messages, JSON mode on). **LLM credentials belong to the agent service only**: never in React/`frontend/.env`, never in Spring Boot, never in tracked files.

1. Create a key in [Google AI Studio](https://aistudio.google.com/apikey) (sign in, “Create API key”, pick or create a project). Never paste it into chat, source code, the README or `.env.example`.
2. Create the git-ignored local file and add the key yourself:
   ```powershell
   Copy-Item agent-service\.env.example agent-service\.env
   notepad agent-service\.env        # fill in LLM_API_KEY= only; leave the rest unless you want another model
   ```
   Contents (placeholders; the key line stays empty in tracked files):
   ```
   LLM_API_KEY=
   LLM_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai/
   LLM_MODEL=gemini-3.8-flash
   LLM_TIMEOUT_SECONDS=30
   LLM_JSON_MODE=true
   ```
3. **Change the model any time** with `LLM_MODEL`. The ID must be available to the Google AI Studio project that owns the key; if it is not, extraction fails with `llm_provider_error` (“model or endpoint not found: check LLM_MODEL”). Google changes model availability, so check the model list in AI Studio.
4. Start FastAPI **from the `agent-service` folder** (that is where `.env` is read) and confirm `llm_configured` is true:
   ```powershell
   cd agent-service; .\.venv\Scripts\Activate.ps1; uvicorn app.main:app --port 8000
   curl.exe http://localhost:8000/ready
   ```
5. One small live check (spends a few tokens; creates nothing in MySQL or Trello):
   ```powershell
   curl.exe -s -X POST http://localhost:8000/extract -H "Content-Type: application/json" -d "{\"transcript\": \"Alex: Priya, please prepare the test report by Friday.\"}"
   ```
   Expect one task with `owner` Priya, `deadline` Friday and `evidence_status` `verified`. If the endpoint rejects JSON mode (HTTP 400), set `LLM_JSON_MODE=false`; output is still validated by the same Pydantic schemas and evidence is still checked by our code, never by the model.

**Model availability and load.** Check which model IDs your key can use with `GET https://generativelanguage.googleapis.com/v1beta/openai/models` (Bearer key). Some IDs exist but are overloaded or unavailable to new users (HTTP 503 “high demand”, or 404 “no longer available to new users”); the agent retries transient 5xx twice, then reports `llm_provider_busy` / `llm_provider_error` honestly. In local testing `gemini-3.8-flash` was overloaded and `gemini-2.5-flash` returned 404 for a new key, while `gemini-3.6-flash` worked, so set `LLM_MODEL` accordingly in `agent-service/.env`.

`GET /ready` reports `llm_configured` (settings present), `llm_missing` (names only), `env_file_found` and `llm_connectivity` without exposing secrets. Configured is **not** verified: by default `llm_connectivity` is `not_checked`. `GET /ready?verify=true` makes one token-free request (retrieve the configured model) and reports `ok`, `auth_failed`, `model_not_found`, `rate_limited`, `timeout`, `unreachable` or `error`. Only a real extraction proves the full path including JSON mode.

Request:

```bash
curl -X POST http://localhost:8000/extract -H "Content-Type: application/json" \
  -d '{"transcript": "Alex: Priya, please prepare the test report by Friday."}'
```
```powershell
curl.exe -X POST http://localhost:8000/extract -H "Content-Type: application/json" -d '{\"transcript\": \"Alex: Priya, please prepare the test report by Friday.\"}'
```

Response shape (illustrative, not real model output):

```json
{
  "tasks": [{
    "id": "uuid", "title": "...", "description": "...",
    "owner": "Priya", "deadline": "Friday",
    "evidence_quote": "Priya, please prepare the test report by Friday.",
    "evidence_verified": true, "evidence_status": "verified",
    "evidence_start": 6, "evidence_end": 55, "evidence_match": "exact",
    "confidence": 0.9
  }],
  "decisions": [{"id": "uuid", "text": "...", "owner": null, "evidence_quote": "...", "evidence_verified": true, "evidence_status": "verified", "evidence_start": 0, "evidence_end": 10, "evidence_match": "exact"}],
  "unresolved_questions": [],
  "completed_updates": []
}
```

Evidence rules: a quote is `verified` if it is an exact substring of the transcript, or matches after collapsing whitespace runs (`evidence_match: "whitespace_normalized"`; case-sensitive). Empty, missing or non-matching quotes are `unverified`. Offsets refer to the original transcript. Verification flags sent by the model are ignored. **A verified quote proves only that those words appear in the transcript, not that the model read them correctly as a commitment**, and it is separate from `confidence` and from future human review.

Errors (body: `{"detail": {"code": "...", "message": "..."}}`):

| Status | `code` | Cause |
|---|---|---|
| 422 | (validation) | empty / whitespace-only / missing transcript |
| 413 | `transcript_too_large` | over `MAX_TRANSCRIPT_CHARS` (200000) |
| 503 | `llm_not_configured` | `LLM_API_KEY` / `LLM_MODEL` missing |
| 504 | `llm_timeout` | provider exceeded `LLM_TIMEOUT_SECONDS` |
| 429 | `llm_rate_limited` | provider rate limit |
| 502 | `llm_provider_error` | network failure, bad credentials, provider 4xx/5xx |
| 502 | `llm_malformed_output` | model output not valid JSON / schema |

No fake tasks are returned on failure.

## Dashboard (frontend)

Start order: MySQL, then the agent service, then the backend (see below), then:

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
```

Optional `frontend/.env` (public URLs only; `VITE_*` values are bundled into the browser, so never put keys here):

```
VITE_API_BASE_URL=http://localhost:8080
VITE_AGENT_BASE_URL=http://localhost:8000
```

Using it:
1. The **System status** panel makes real requests to `/api/health`, `/api/health/db` (backend, MySQL) and the agent `/health` + `/ready`. It shows a warning if the agent is up but has no LLM configured.
2. Paste a transcript (or click **Insert sample transcript**; the sample is only input text and goes through the real backend and LLM) and click **Analyze Meeting**. The result and its meeting ID come from the server.
3. Each proposed task shows owner/deadline (“Not specified” if null), the supporting quote, evidence status (verified/unverified), review status, sync status and the model’s self-reported confidence. “Show in transcript” appears only when the backend supplied offsets.
4. **Approve** (sends the task’s current `version`), **Edit** (title, description, owner, deadline; editing an approved task resets it to Pending review) and **Reject** (asks for confirmation; final). Tasks with unverified evidence cannot be approved; the backend enforces this, the UI just explains it. Buttons are disabled while a request runs, and the UI only changes after a successful server response.
5. **All tasks** lists tasks across meetings (`GET /api/tasks`, filterable by review status); **Reload from server** refetches the current meeting. The last meeting ID (only) is kept in `localStorage` and reloaded after a browser refresh.
6. The “Sync to Trello” button is intentionally disabled; sync arrives in a later step.

Known limitations: there is no endpoint to list meetings, so history is browsed through tasks; no authentication; meetings that were analyzed with zero tasks are reachable only via the remembered ID; the layout was checked in a desktop browser only.

## Backend: persistence and approval workflow

Flow: client → `POST /api/meetings` → Spring Boot saves the meeting → calls the agent service `POST /extract` → validates the result → stores tasks, evidence and insights in MySQL → client reviews (edit / approve / reject). LLM credentials live only in the agent service; Spring Boot never calls an LLM and the React app never sees a key.

### Start everything (PowerShell)

Credentials come from environment variables in the shell that launches the backend; do not commit them. Use your own local values:

```powershell
$env:MYSQL_HOST = "localhost"; $env:MYSQL_PORT = "3306"; $env:MYSQL_DATABASE = "meet2action"
$env:MYSQL_USER = "<user>"; $env:MYSQL_PASSWORD = "<password>"
$env:AGENT_SERVICE_URL = "http://localhost:8000"   # default

# terminal 1: agent service (set LLM_* in agent-service/.env or the shell for real extraction)
cd agent-service; .\.venv\Scripts\Activate.ps1; uvicorn app.main:app --port 8000
# terminal 2: backend
cd backend; .\mvnw.cmd spring-boot:run
```
```bash
export MYSQL_HOST=localhost MYSQL_PORT=3306 MYSQL_DATABASE=meet2action MYSQL_USER=<user> MYSQL_PASSWORD=<password>
(cd agent-service && source .venv/bin/activate && uvicorn app.main:app --port 8000) &
(cd backend && ./mvnw spring-boot:run)
```

### Database and schema

Create the database once (the backend never creates the database itself):

```sql
CREATE DATABASE IF NOT EXISTS meet2action CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Tables (`meetings`, `task_proposals`, `meeting_insights`) are created by the idempotent script `backend/src/main/resources/db/schema-mysql.sql`, which `SchemaInitializer` applies at startup (`CREATE TABLE IF NOT EXISTS`; it never drops or alters anything). If MySQL is unreachable the backend still boots (`/api/health` is UP, `/api/health/db` is 503) and logs that schema setup was skipped; restart after fixing MySQL. Set `JPA_DDL_AUTO=validate` to have Hibernate check entities against the schema. Disable the initializer with `SCHEMA_INIT=false`.

### Review rules (enforced server-side)

| From | Action | To | Conditions |
|---|---|---|---|
| `PENDING_REVIEW` | approve | `APPROVED` | evidence `VERIFIED`; request `version` equals the current version |
| `PENDING_REVIEW` / `APPROVED` | reject | `REJECTED` | optional `version` must match if given |
| `APPROVED` | edit title/description/owner/deadline | `PENDING_REVIEW` | approval invalidated; version incremented |
| `REJECTED` | anything | n/a | terminal: cannot be approved, edited or re-rejected (409) |

Evidence status (`VERIFIED`/`UNVERIFIED`, set by the agent service), review status and sync status (`NOT_SYNCED`, nothing syncs yet) are independent fields. Clients cannot set evidence or status fields: unknown JSON properties are rejected with 400. A verified quote only proves the words appear in the transcript, not that the proposal is correct, so approval is a human decision. Owners and deadlines are never guessed; missing values stay `null`.

### Endpoints (JSON is snake_case)

| Method | Path | Notes |
|---|---|---|
| POST | `/api/meetings` | body `{"transcript": "..."}`; 201 created, 200 if an identical transcript was submitted within 30s (returns the existing meeting, no second extraction) |
| GET | `/api/meetings/{id}` | meeting, `extraction_status` (`PENDING`/`COMPLETED`/`FAILED`), `error_code`, tasks, decisions, unresolved_questions, completed_updates |
| GET | `/api/tasks` | optional `meeting_id`, `review_status` filters |
| GET | `/api/tasks/{id}` | one task |
| PATCH | `/api/tasks/{id}` | any of `title`, `description`, `owner`, `deadline`, optional `version`; `""` clears owner/deadline |
| POST | `/api/tasks/{id}/approve` | body `{"version": <current version>}` (required) |
| POST | `/api/tasks/{id}/reject` | optional body `{"version": n}` |
| GET | `/api/health`, `/api/health/db` | process health / real MySQL connectivity |

Errors: `{"status", "code", "error", "message", "timestamp"}`. Notable codes: `validation_error`/`unknown_field` (400), `not_found` (404), `version_conflict`/`invalid_state`/`task_rejected` (409), `evidence_not_verified` (422), `database_error` (503). Failed extraction returns the agent's mapped status (`agent_unavailable` 503, `agent_timeout` 504, `agent_malformed_response` 502, `llm_not_configured` 503, `llm_rate_limited` 429, otherwise 502) plus `meeting_id`; the meeting is stored with `extraction_status: FAILED` and no tasks. A `COMPLETED` meeting with zero tasks is a genuine empty result. Transcript limit: 200000 characters (same as the agent service).

### Try the approval workflow (needs a configured LLM for a real extraction)

```powershell
$m = Invoke-RestMethod -Method Post http://localhost:8080/api/meetings -ContentType application/json `
  -Body '{"transcript":"Alex: Priya, please prepare the test report by Friday."}'
$t = $m.tasks[0]; $t | Select id,title,owner,deadline,evidence_status,review_status,version
Invoke-RestMethod -Method Patch  http://localhost:8080/api/tasks/$($t.id) -ContentType application/json -Body '{"owner":"Priya"}'
Invoke-RestMethod -Method Post   http://localhost:8080/api/tasks/$($t.id)/approve -ContentType application/json -Body "{`"version`": $((Invoke-RestMethod http://localhost:8080/api/tasks/$($t.id)).version)}"
```
```bash
curl -X POST localhost:8080/api/meetings -H 'Content-Type: application/json' -d '{"transcript":"Alex: Priya, please prepare the test report by Friday."}'
curl -X PATCH localhost:8080/api/tasks/<id> -H 'Content-Type: application/json' -d '{"owner":"Priya"}'
curl -X POST  localhost:8080/api/tasks/<id>/approve -H 'Content-Type: application/json' -d '{"version": 1}'
curl -X POST  localhost:8080/api/tasks/<id>/reject
```

### Limitations

- Extraction duplicates are suppressed only by a 30s same-transcript window; two simultaneous identical requests can still race.
- A meeting left `PENDING` (process killed mid-extraction) is not cleaned up automatically.
- No authentication; rejected tasks cannot be reopened; no Trello sync yet.

## Trello sync

Approved tasks can be sent to one Trello list **only when a user clicks “Sync to Trello”**. Nothing syncs on startup, analysis, approval, page refresh or in the background. The browser and the AI service never see Trello credentials and never call Trello; only Spring Boot does.

### Configure (backend only)

1. Create a Trello **API key** and a **token** with write access at <https://trello.com/power-ups/admin> (create a Power-Up to get a key, then use its “Token” link to authorize it for your account). Keep both secret.
2. Find the destination **list ID**: open the board in a browser, append `.json` to the board URL (e.g. `https://trello.com/b/<shortlink>/<name>.json`), search for `"lists"` and copy the `id` of the list you want. Or call `GET https://api.trello.com/1/boards/<boardId>/lists?key=…&token=…` yourself (keep that URL private).
3. Set these in the shell that starts the backend (they are not read from `.env` files):

```powershell
$env:TRELLO_API_KEY = "<key>"; $env:TRELLO_TOKEN = "<token>"; $env:TRELLO_LIST_ID = "<list id>"
# optional: $env:TRELLO_API_BASE_URL = "https://api.trello.com/1"
cd backend; .\mvnw.cmd spring-boot:run
```
```bash
export TRELLO_API_KEY=<key> TRELLO_TOKEN=<token> TRELLO_LIST_ID=<list id>
cd backend && ./mvnw spring-boot:run
```

Credentials are sent only in an `Authorization` header (never in URLs), are never logged, and are never returned by any API. `GET /api/integrations/trello` returns only `{"configured": true|false}`.

### Without Trello configured

The app builds, starts and works normally. `POST /api/tasks/{id}/sync` answers `503 trello_not_configured` before touching the task, and the dashboard shows Trello as “not configured” and disables the button with an explanation.

### Endpoints

| Method | Path | Notes |
|---|---|---|
| POST | `/api/tasks/{id}/sync` | body `{"version": <current version>}` (required). Returns the task including `sync_status`, `trello_card_id`, `trello_card_url`, `synced_at`. |
| POST | `/api/tasks/{id}/sync/reconcile` | looks for an existing card with this task’s marker after an uncertain sync; never creates a card |
| GET | `/api/integrations/trello` | `{"configured": bool}` |

The server checks, in order: task exists; already `SYNCED` (returns the stored result, no new card); review status is `APPROVED` (`task_not_approved`, 409); evidence `VERIFIED` (`evidence_not_verified`, 422); not `SYNCING` (`sync_in_progress`, 409) or `UNCERTAIN` (`sync_uncertain`, 409); version matches (`version_conflict`, 409); Trello configured (`trello_not_configured`, 503). It then claims the single active attempt with one conditional `UPDATE` (only one concurrent caller can win), calls Trello **with no database transaction open**, and records the result in a second short transaction.

Sync states: `NOT_SYNCED` → `SYNCING` → `SYNCED` | `FAILED` | `UNCERTAIN`.

| Outcome from Trello | State | API answer |
|---|---|---|
| 2xx with card id and URL | `SYNCED` (card id/URL saved) | 200 |
| 401/403 | `FAILED` (no card) | 502 `trello_auth_failed` |
| 400/404 | `FAILED` | 502 `trello_list_invalid` |
| 429 | `FAILED` | 429 `trello_rate_limited` |
| connection refused / DNS / connect timeout | `FAILED` | 503 `trello_unreachable` |
| read timeout, 5xx, unreadable 2xx reply | `UNCERTAIN` (a card may exist) | 504/502 `sync_uncertain` |

`FAILED` means no card was created, so the user may click Sync again. `UNCERTAIN` blocks further syncing; the dashboard offers **Check Trello** (reconcile): if a card whose description contains this task’s exact marker `[meet2action-task-id:<id>]` is on the configured list, its real id/URL are saved and the task becomes `SYNCED` (no duplicate); if the list was read and no such card exists, the task becomes `FAILED` (`card_not_found`) so it can be retried; if Trello cannot be reached it stays `UNCERTAIN`. A `SYNCING` row left by a crash can be reconciled once it is older than `TRELLO_STALE_SYNC_SECONDS`.

Content: card name = task title; description = task description, then Owner/Deadline only if present, a “Meeting evidence” quote section and the marker. A Trello due date is set only for an explicit ISO date (`2026-03-05`, treated as 12:00 UTC) or ISO date-time with offset; “Friday”, “next week” etc. leave it unset. Once a task is `SYNCING`, `UNCERTAIN` or `SYNCED` it can no longer be edited or rejected in Meet2Action (rejecting never deletes or archives a card). Editing a task after a `FAILED` sync resets it to pending review and `NOT_SYNCED`.

### Three-stage workflow (To Do / In Progress / Completed)

The Trello board has three lists: `🔵 To Do`, `🟠 In Progress`, `✅ Completed`. Configure their IDs (backend only, root `.env`):

```
TRELLO_TODO_LIST_ID=        # legacy TRELLO_LIST_ID still works as the To Do list when this is empty
TRELLO_IN_PROGRESS_LIST_ID=
TRELLO_COMPLETED_LIST_ID=
```

Three separate concepts, never mixed: **approval** (`review_status`), **work status** (`work_status`: `TO_DO`, `IN_PROGRESS`, `COMPLETED`) and **Trello sync** (`sync_status`). A task never becomes Completed because it was approved or synced.

1. Approve a task, then click **Sync to Trello**: the card is created in *To Do* (work status `TO_DO`).
2. In the task card use the **Work status** buttons. The dashboard calls `POST /api/tasks/{id}/work-status` with `{"work_status": "IN_PROGRESS" | "COMPLETED" | "TO_DO", "version": <current>}`; the backend validates, **moves the existing card** (`PUT /cards/{id}` with the saved card ID, never a new card), and only after Trello confirms records the new work status. The UI changes only after that confirmation.
3. Rules: the task must be approved and already synced (otherwise `409 not_synced`: sync first); the version must be current; the target list must be configured (`503 trello_list_not_configured`). Concurrent changes are serialised by an atomic database claim, so a card is moved once. A failed or uncertain move (`work_move_uncertain`) leaves the work status unchanged and is safe to retry because moving a card to a list it is already in changes nothing.

### Honest limitations

- Trello has no idempotency keys, so exactly-once creation is impossible. Duplicates are prevented by the atomic claim, by never auto-retrying a create, and by marker reconciliation, which cannot see a card a human moved off the list or archived.
- A crash between Trello’s reply and our final write leaves `SYNCING` (recoverable via reconcile after the stale window).
- Card content is not updated later and the card is never deleted.

### Testing safely

All automated tests use mocks; none can create a real card: `HttpTrelloClientTest` (mocked HTTP server), `TrelloCardContentTest`, `TrelloSyncTest` and `TrelloUnconfiguredTest` (mocked `TrelloClient`), `MySqlIntegrationTest` (real MySQL, mocked Trello), and the frontend `TaskCardSync.test.tsx` / `trelloClient.test.ts` (mocked API). A live check is only ever done by you: set real credentials for a scratch board/list, approve a task and click Sync.

## Tests and builds

```bash
cd backend && ./mvnw test         # H2 (in-memory) + mocked agent; the MySQL test is skipped unless MYSQL_PASSWORD is set
cd agent-service && pytest        # all LLM calls are mocked; no key or network needed
cd frontend && npm run build && npm test   # vitest: API client and TaskCard (fetch/API mocked)
```

## Troubleshooting

- **`503 llm_not_configured` although you set the key** — the agent loads **`agent-service/.env`** (located relative to the code, so it works from any folder) and real environment variables. It never reads `.env.example`, `frontend/.env` or the root `.env`; a key written into `.env.example` is ignored (and must not be there: it is a tracked file). Check `curl.exe http://localhost:8000/ready`: `llm_missing` lists which of `LLM_API_KEY`, `LLM_MODEL`, `LLM_BASE_URL` are empty and `env_file_found` says whether `agent-service/.env` exists. Settings are read once at startup, so **restart the agent after editing `.env`**.
- **`/api/health/db` returns DOWN** — MySQL isn't running, or the database/user doesn't exist, or `MYSQL_*` variables aren't set in the shell that launched the backend. The backend itself still starts by design.
- **Frontend shows "Unreachable"** — the service isn't running, or the port differs; set `VITE_API_BASE_URL` / `VITE_AGENT_BASE_URL` in `frontend/.env` and restart Vite.
- **CORS error in the browser** — add your frontend origin to `CORS_ALLOWED_ORIGINS` (backend and agent service).
- **Port already in use** — change `SERVER_PORT` (backend) or `--port` (uvicorn).
- **`JAVA_HOME` errors with `mvnw`** — point `JAVA_HOME` at a full JDK 21 (must contain `javac`).
- **PowerShell blocks venv activation** — `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.

## Not included

GitHub integration, authentication/authorization, deployment, an API to delete meetings or tasks, automatic Trello updates after the first sync.
