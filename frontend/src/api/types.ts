// Mirrors the Spring Boot DTOs (backend/.../web/Dtos.java). JSON is snake_case.

export type EvidenceStatus = 'VERIFIED' | 'UNVERIFIED'
export type ReviewStatus = 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED'
export type SyncStatus = 'NOT_SYNCED' | 'SYNCING' | 'SYNCED' | 'FAILED' | 'UNCERTAIN'
export type ExtractionStatus = 'PENDING' | 'COMPLETED' | 'FAILED'

export interface Task {
  id: string
  meeting_id: string
  title: string
  description: string
  owner: string | null
  deadline: string | null
  evidence_quote: string
  evidence_status: EvidenceStatus
  evidence_verified: boolean
  evidence_start: number | null
  evidence_end: number | null
  evidence_match: string | null
  confidence: number | null
  review_status: ReviewStatus
  sync_status: SyncStatus
  version: number
  created_at: string
  updated_at: string
  approved_at: string | null
  rejected_at: string | null
  trello_card_id: string | null
  trello_card_url: string | null
  synced_at: string | null
  sync_attempted_at: string | null
  sync_error_code: string | null
  sync_error_message: string | null
}

/** GET /api/integrations/trello: only whether the backend has Trello credentials; never the credentials. */
export interface TrelloStatus {
  configured: boolean
}

export interface Insight {
  id: string
  text: string
  owner: string | null
  evidence_quote: string
  evidence_status: EvidenceStatus
  evidence_verified: boolean
  evidence_start: number | null
  evidence_end: number | null
  evidence_match: string | null
}

export interface Meeting {
  id: string
  transcript: string
  created_at: string
  extraction_status: ExtractionStatus
  error_code: string | null
  error_message: string | null
  tasks: Task[]
  decisions: Insight[]
  unresolved_questions: Insight[]
  completed_updates: Insight[]
}

/** PATCH /api/tasks/{id}: only these fields exist; the server rejects anything else. */
export interface TaskPatch {
  title?: string
  description?: string
  owner?: string
  deadline?: string
  version?: number
}

export interface CreateMeetingResult {
  meeting: Meeting
  /** false when the backend returned an existing meeting for an identical recent submission (HTTP 200). */
  created: boolean
}

export interface ReadyInfo {
  llm_configured: boolean
}
