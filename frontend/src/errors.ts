import { ApiError } from './api/client'

export interface ErrorInfo {
  code: string
  message: string
  hint?: string
}

const HINTS: Record<string, string> = {
  llm_not_configured:
    'The AI service has no LLM configured. Set LLM_API_KEY and LLM_MODEL in agent-service/.env and restart it.',
  agent_unavailable: 'The backend could not reach the AI agent service. Start it (uvicorn) and try again.',
  agent_timeout: 'The AI agent took too long to respond. Try again, or use a shorter transcript.',
  agent_malformed_response: 'The AI agent returned an invalid result, so nothing was stored as tasks.',
  llm_rate_limited: 'The LLM provider is rate limiting requests. Wait a moment and retry.',
  llm_provider_busy:
    'The LLM provider is overloaded right now (HTTP 503). It was retried automatically. Wait a minute and analyze again, or set LLM_MODEL to a different model.',
  llm_provider_error: 'The LLM provider call failed (check the key, model name and base URL).',
  llm_timeout: 'The LLM provider timed out. Try again.',
  llm_malformed_output: 'The model returned output that could not be validated. Try again.',
  database_error: 'The database is unavailable. Check that MySQL is running and the backend credentials are correct.',
  persistence_failed: 'The extraction worked but its result could not be saved to the database.',
  version_conflict: 'This task changed since it was loaded. Reload it and try again.',
  evidence_not_verified: 'Tasks whose quote is not found in the transcript cannot be approved.',
  task_rejected: 'Rejected tasks are final and cannot be edited or approved.',
  invalid_state: 'This action is not allowed in the task’s current review state.',
  trello_not_configured:
    'Trello is not configured on the server. Set TRELLO_API_KEY, TRELLO_TOKEN and TRELLO_LIST_ID for the backend and restart it.',
  trello_auth_failed: 'Trello rejected the API key or token. No card was created.',
  trello_list_invalid: 'Trello could not use the destination list (check TRELLO_LIST_ID and board access). No card was created.',
  trello_rate_limited: 'Trello is rate limiting requests. No card was created; try again shortly.',
  trello_unreachable: 'Trello could not be reached. No card was created.',
  trello_rejected: 'Trello rejected the request. No card was created.',
  sync_uncertain:
    'The outcome is unknown: a card may or may not exist in Trello. Use "Check Trello" to look for it before syncing again.',
  sync_in_progress: 'A sync for this task is already running.',
  already_synced: 'This task is already synced to Trello and can no longer be changed here.',
  task_not_approved: 'Only approved tasks can be synced.',
  nothing_to_reconcile: 'There is no uncertain sync to check for this task.',
  not_synced: 'This task has no Trello card yet. Click "Sync to Trello" first; a card can only be moved after it exists.',
  trello_list_not_configured:
    'The Trello lists for work statuses are not configured. Set TRELLO_TODO_LIST_ID, TRELLO_IN_PROGRESS_LIST_ID and TRELLO_COMPLETED_LIST_ID for the backend and restart it.',
  work_move_in_progress: 'Another status change for this task is still running. Wait a moment and try again.',
  work_move_uncertain:
    'Trello did not confirm the move, so the card may or may not have moved. The status here was NOT changed. Check the board or try again; repeating a move is safe.',
  trello_card_or_list_not_found: 'Trello could not find the card or the destination list. The card was not moved.',
  work_move_failed: 'The card could not be moved and the work status was not changed.',
  network_error: 'Check that the backend is running and that VITE_API_BASE_URL is correct.',
  timeout: 'The request did not finish in time.',
}

export function describeError(e: unknown): ErrorInfo {
  if (e instanceof ApiError) {
    return { code: e.code, message: e.message, hint: HINTS[e.code] }
  }
  return { code: 'unexpected_error', message: e instanceof Error ? e.message : 'Unexpected error' }
}
