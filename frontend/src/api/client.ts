import { AGENT_BASE_URL, ANALYZE_TIMEOUT_MS, API_BASE_URL, DEFAULT_TIMEOUT_MS, SYNC_TIMEOUT_MS } from '../config'
import type { CreateMeetingResult, Meeting, ReadyInfo, ReviewStatus, Task, TaskPatch, TrelloStatus } from './types'

/** Any failed request. `status` 0 means the server was never reached (network error or timeout). */
export class ApiError extends Error {
  status: number
  code: string
  meetingId?: string

  constructor(status: number, code: string, message: string, meetingId?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.meetingId = meetingId
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH'
  body?: unknown
  timeoutMs?: number
  baseUrl?: string
}

async function request<T>(path: string, opts: RequestOptions = {}): Promise<{ data: T; status: number }> {
  const { method = 'GET', body, timeoutMs = DEFAULT_TIMEOUT_MS, baseUrl = API_BASE_URL } = opts
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)

  let response: Response
  try {
    response = await fetch(`${baseUrl}${path}`, {
      method,
      headers: body === undefined ? { Accept: 'application/json' } : { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw new ApiError(0, 'timeout', `The request timed out after ${Math.round(timeoutMs / 1000)}s. Nothing is confirmed as saved.`)
    }
    throw new ApiError(0, 'network_error', `Cannot reach the service at ${baseUrl}. Is it running? (CORS or network failure)`)
  } finally {
    clearTimeout(timer)
  }

  const text = await response.text()
  let json: unknown
  try {
    json = text ? JSON.parse(text) : undefined
  } catch {
    json = undefined
  }

  if (!response.ok) {
    const err = (json && typeof json === 'object' ? json : {}) as Record<string, unknown>
    const message =
      typeof err.message === 'string'
        ? err.message
        : typeof err.error === 'string'
          ? err.error
          : `Request failed with HTTP ${response.status}`
    throw new ApiError(
      response.status,
      typeof err.code === 'string' ? err.code : `http_${response.status}`,
      message,
      typeof err.meeting_id === 'string' ? err.meeting_id : undefined,
    )
  }
  if (json === undefined) {
    throw new ApiError(response.status, 'invalid_response', 'The server returned a response that is not valid JSON.')
  }
  return { data: json as T, status: response.status }
}

// ---- Spring Boot backend ----
export const getBackendHealth = () => request<{ status: string }>('/api/health').then((r) => r.data)
export const getDatabaseHealth = () => request<{ status: string }>('/api/health/db').then((r) => r.data)

export async function createMeeting(transcript: string): Promise<CreateMeetingResult> {
  const { data, status } = await request<Meeting>('/api/meetings', {
    method: 'POST',
    body: { transcript },
    timeoutMs: ANALYZE_TIMEOUT_MS,
  })
  return { meeting: data, created: status === 201 }
}

export const getMeeting = (id: string) =>
  request<Meeting>(`/api/meetings/${encodeURIComponent(id)}`).then((r) => r.data)

export function listTasks(filter: { meetingId?: string; reviewStatus?: ReviewStatus } = {}) {
  const params = new URLSearchParams()
  if (filter.meetingId) params.set('meeting_id', filter.meetingId)
  if (filter.reviewStatus) params.set('review_status', filter.reviewStatus)
  const qs = params.toString()
  return request<Task[]>(`/api/tasks${qs ? `?${qs}` : ''}`).then((r) => r.data)
}

export const getTask = (id: string) => request<Task>(`/api/tasks/${encodeURIComponent(id)}`).then((r) => r.data)

export const updateTask = (id: string, patch: TaskPatch) =>
  request<Task>(`/api/tasks/${encodeURIComponent(id)}`, { method: 'PATCH', body: patch }).then((r) => r.data)

/** The backend requires the task's current version in the body. */
export const approveTask = (id: string, version: number) =>
  request<Task>(`/api/tasks/${encodeURIComponent(id)}/approve`, { method: 'POST', body: { version } }).then((r) => r.data)

export const rejectTask = (id: string, version?: number) =>
  request<Task>(`/api/tasks/${encodeURIComponent(id)}/reject`, {
    method: 'POST',
    body: version === undefined ? {} : { version },
  }).then((r) => r.data)

/** Explicit Trello sync. The backend validates everything and is the only caller of Trello. Needs the current version. */
export const syncTask = (id: string, version: number) =>
  request<Task>(`/api/tasks/${encodeURIComponent(id)}/sync`, {
    method: 'POST',
    body: { version },
    timeoutMs: SYNC_TIMEOUT_MS,
  }).then((r) => r.data)

/** Asks the backend to look for an already-created card after an uncertain sync. Creates nothing. */
export const reconcileTask = (id: string) =>
  request<Task>(`/api/tasks/${encodeURIComponent(id)}/sync/reconcile`, {
    method: 'POST',
    timeoutMs: SYNC_TIMEOUT_MS,
  }).then((r) => r.data)

export const getTrelloStatus = () => request<TrelloStatus>('/api/integrations/trello').then((r) => r.data)

// ---- FastAPI agent service (health only; extraction always goes through the backend) ----
export const getAgentHealth = () => request<{ status: string }>('/health', { baseUrl: AGENT_BASE_URL }).then((r) => r.data)
export const getAgentReady = () => request<ReadyInfo>('/ready', { baseUrl: AGENT_BASE_URL }).then((r) => r.data)
