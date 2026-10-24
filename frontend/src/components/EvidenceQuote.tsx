import { useState } from 'react'

interface Props {
  quote: string
  start: number | null
  end: number | null
  match: string | null
  /** Only available when viewing a full meeting; offsets are never used without it. */
  transcript?: string
}

const CONTEXT = 80

/** Shows the quotation and, when the backend supplied offsets, where it sits in the transcript. */
export function EvidenceQuote({ quote, start, end, match, transcript }: Props) {
  const [open, setOpen] = useState(false)
  const hasOffsets = start !== null && end !== null
  const offsetsUsable = hasOffsets && transcript !== undefined && start >= 0 && end > start && end <= transcript.length

  return (
    <div className="evidence">
      {quote ? <blockquote>{quote}</blockquote> : <p className="muted">No supporting quotation was provided.</p>}
      {hasOffsets && (
        <p className="muted small">
          Transcript characters {start}–{end}
          {match ? ` (${match.replace('_', ' ')} match)` : ''}
          {offsetsUsable && (
            <>
              {' · '}
              <button type="button" className="link" aria-expanded={open} onClick={() => setOpen(!open)}>
                {open ? 'Hide context' : 'Show in transcript'}
              </button>
            </>
          )}
        </p>
      )}
      {open && offsetsUsable && transcript && (
        <pre className="context" aria-label="Quote in transcript context">
          {start > CONTEXT ? '…' : ''}
          {transcript.slice(Math.max(0, start - CONTEXT), start)}
          <mark>{transcript.slice(start, end)}</mark>
          {transcript.slice(end, end + CONTEXT)}
          {end + CONTEXT < transcript.length ? '…' : ''}
        </pre>
      )}
    </div>
  )
}
