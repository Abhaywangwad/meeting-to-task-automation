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
    <section aria-labelledby="analyze-h" className="panel">
      <h2 id="analyze-h">Analyze a meeting</h2>
      <p className="muted">
        Paste a transcript (for example “Name: sentence” lines). The AI proposes tasks, and each one must cite a quote
        from the transcript.
      </p>
      <form onSubmit={submit}>
        <label htmlFor="transcript" className="sr-only">
          Meeting transcript
        </label>
        <textarea
          id="transcript"
          rows={10}
          value={text}
          disabled={analyzing}
          placeholder="Alex: Priya, please prepare the test report by Friday."
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
        <div className="actions">
          <button type="submit" className="primary" disabled={analyzing}>
            {analyzing ? 'Analyzing…' : 'Analyze Meeting'}
          </button>
          <button type="button" disabled={analyzing} onClick={() => setText(SAMPLE_TRANSCRIPT)}>
            Insert sample transcript
          </button>
          {text && (
            <button type="button" disabled={analyzing} onClick={() => setText('')}>
              Clear
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
