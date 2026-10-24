import type { EvidenceStatus, ExtractionStatus, ReviewStatus, SyncStatus } from '../api/types'

function Badge({ tone, children, title }: { tone: string; children: React.ReactNode; title?: string }) {
  return (
    <span className={`badge badge-${tone}`} title={title}>
      {children}
    </span>
  )
}

export function EvidenceBadge({ status }: { status: EvidenceStatus }) {
  return status === 'VERIFIED' ? (
    <Badge tone="ok" title="The quotation was found in the transcript. This does not mean the task is correct.">
      ✓ Evidence verified
    </Badge>
  ) : (
    <Badge tone="warn" title="The quotation was not found in the transcript.">
      ⚠ Evidence unverified
    </Badge>
  )
}

const REVIEW: Record<ReviewStatus, { tone: string; label: string }> = {
  PENDING_REVIEW: { tone: 'pending', label: 'Pending review' },
  APPROVED: { tone: 'ok', label: 'Approved' },
  REJECTED: { tone: 'bad', label: 'Rejected' },
}

export function ReviewBadge({ status }: { status: ReviewStatus }) {
  const r = REVIEW[status]
  return <Badge tone={r.tone}>{r.label}</Badge>
}

const SYNC: Record<SyncStatus, { tone: string; label: string }> = {
  NOT_SYNCED: { tone: 'neutral', label: 'Not synced' },
  SYNCING: { tone: 'pending', label: 'Syncing…' },
  SYNCED: { tone: 'ok', label: 'Synced to Trello' },
  FAILED: { tone: 'bad', label: 'Sync failed' },
  UNCERTAIN: { tone: 'warn', label: 'Sync outcome unknown' },
}

export function SyncBadge({ status }: { status: SyncStatus }) {
  return <Badge tone={SYNC[status].tone}>{SYNC[status].label}</Badge>
}

export function ExtractionBadge({ status }: { status: ExtractionStatus }) {
  const map: Record<ExtractionStatus, { tone: string; label: string }> = {
    COMPLETED: { tone: 'ok', label: 'Extraction completed' },
    FAILED: { tone: 'bad', label: 'Extraction failed' },
    PENDING: { tone: 'pending', label: 'Extraction pending' },
  }
  return <Badge tone={map[status].tone}>{map[status].label}</Badge>
}
