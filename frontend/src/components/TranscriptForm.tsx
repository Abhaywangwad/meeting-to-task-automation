import { useState } from 'react'
import { MAX_TRANSCRIPT_CHARS } from '../config'
import type { ErrorInfo } from '../errors'
import { SAMPLE_TRANSCRIPT } from '../sample'

interface Props {
  analyzing: boolean
  error: ErrorInfo | null
  failedMeetingId: string | null
  onAnalyze: (transcript: string) => void
}

export function TranscriptForm({ analyzing, error, failedMeetingId, onAnalyze }: Props) {
  const [text, setText] = useState('')
  const [touched, setTouched] = useState(false)
  const empty = text.trim().length === 0
  const tooLong = text.length > MAX_TRANSCRIPT_CHARS

  function submit(ev: React.FormEvent) {
    ev.preventDefault()
    setTouched(true)
    if (empty || tooLong || analyzing) return
    onAnalyze(text)
  }

  return (
    <section aria-labelledby="analyze-h" className="panel form-panel">
      <div className="panel-head">
        <div className="status-title-group">
          <div className="status-header-icon form-icon" aria-hidden="true">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
              <polyline points="14 2 14 8 20 8" />
              <line x1="16" y1="13" x2="8" y2="13" />
              <line x1="16" y1="17" x2="8" y2="17" />
              <polyline points="10 9 9 9 8 9" />
            </svg>
          </div>
          <div>
            <h2 id="analyze-h" className="status-heading">Analyze Meeting Transcript</h2>
            <p className="muted small">
              Paste meeting dialogue below. The AI extracts action items and links each to verbatim transcript quotes.
            </p>
          </div>
        </div>
      </div>
      <form onSubmit={submit}>
        <label htmlFor="transcript" className="sr-only">
          Meeting transcript
        </label>
        <textarea
          id="transcript"
          rows={9}
          value={text}
          disabled={analyzing}
          placeholder="Paste transcript here (e.g., Alex: Priya, please prepare the test report by Friday)..."
          aria-invalid={touched && (empty || tooLong)}
          aria-describedby="transcript-help"
          onChange={(e) => setText(e.target.value)}
        />
        <div id="transcript-help" className="form-foot">
          <span className={tooLong ? 'error-text' : 'muted small'}>
            {text.length.toLocaleString()} / {MAX_TRANSCRIPT_CHARS.toLocaleString()} characters
          </span>
          {touched && empty && <span className="error-text">Please enter a transcript first.</span>}
          {tooLong && <span className="error-text">Transcript is too long.</span>}
        </div>
        <div className="actions form-actions">
          <button type="submit" className="primary btn-large" disabled={analyzing}>
            {analyzing ? (
              <>
                <span className="spinner" aria-hidden="true" /> Analyzing Meeting…
              </>
            ) : (
              <>
                <span className="btn-icon">⚡</span> Analyze Meeting
              </>
            )}
          </button>
          <button type="button" className="btn-sample" disabled={analyzing} onClick={() => setText(SAMPLE_TRANSCRIPT)}>
            <span className="btn-icon">📋</span> Insert sample transcript
          </button>
          {text && (
            <button type="button" className="btn-clear" disabled={analyzing} onClick={() => setText('')}>
              ✕ Clear
            </button>
          )}
        </div>
      </form>

      {analyzing && (
        <p className="alert alert-info" role="status">
          <span className="spinner" aria-hidden="true" /> Extracting commitments and checking each quote against the
          transcript. This can take up to a minute.
        </p>
      )}
      {error && (
        <div className="alert alert-error" role="alert">
          <strong>Analysis failed:</strong> {error.message} <code>{error.code}</code>
          {error.hint && <div>{error.hint}</div>}
          {failedMeetingId ? (
            <div className="small">
              A record of the failed attempt was saved (meeting {failedMeetingId}); no tasks were created.
            </div>
          ) : (
            <div className="small">Nothing was saved.</div>
          )}
        </div>
      )}
    </section>
  )
}
