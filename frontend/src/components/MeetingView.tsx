import type { Meeting, Task } from '../api/types'
import { ExtractionBadge } from './Badges'
import { InsightsPanel } from './InsightsPanel'
import { TaskCard } from './TaskCard'

interface Props {
  meeting: Meeting
  reloading: boolean
  reloadError: string | null
  onReload: () => void
  onTaskUpdated: (task: Task) => void
}

export function MeetingView({ meeting, reloading, reloadError, onReload, onTaskUpdated }: Props) {
  return (
    <section aria-labelledby="meeting-h" className="panel">
      <div className="panel-head">
        <div>
          <h2 id="meeting-h">Meeting results</h2>
          <p className="muted small">
            ID <code>{meeting.id}</code> · analyzed {new Date(meeting.created_at).toLocaleString()}
          </p>
        </div>
        <div className="badges">
          <ExtractionBadge status={meeting.extraction_status} />
          <button type="button" disabled={reloading} onClick={onReload}>
            {reloading ? 'Reloading…' : 'Reload from server'}
          </button>
        </div>
      </div>

      {reloadError && (
        <p className="alert alert-error" role="alert">
          Could not reload: {reloadError}. Showing the last data received.
        </p>
      )}

      {meeting.extraction_status === 'FAILED' && (
        <p className="alert alert-error" role="alert">
          Extraction failed{meeting.error_code ? ` (${meeting.error_code})` : ''}: {meeting.error_message ?? 'unknown error'}. No
          tasks were created.
        </p>
      )}
      {meeting.extraction_status === 'PENDING' && (
        <p className="alert alert-info" role="status">
          Extraction is still marked pending. Reload in a moment; if it never completes, the attempt may have been interrupted.
        </p>
      )}

      {meeting.extraction_status === 'COMPLETED' && (
        <>
          <h3 className="section-title">
            Proposed tasks <span className="count">{meeting.tasks.length}</span>
          </h3>
          {meeting.tasks.length === 0 ? (
            <p className="empty">
              The analysis completed but found no concrete commitments in this transcript. That is a valid result, not an error.
            </p>
          ) : (
            <div className="stack">
              {meeting.tasks.map((t) => (
                <TaskCard key={t.id} task={t} transcript={meeting.transcript} onUpdated={onTaskUpdated} />
              ))}
            </div>
          )}

          <h3 className="section-title">Meeting insights</h3>
          <InsightsPanel
            decisions={meeting.decisions}
            questions={meeting.unresolved_questions}
            updates={meeting.completed_updates}
            transcript={meeting.transcript}
          />
        </>
      )}
    </section>
  )
}
