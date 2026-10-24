import { useCallback, useEffect, useState } from 'react'
import { listTasks } from '../api/client'
import type { ReviewStatus, Task } from '../api/types'
import { describeError } from '../errors'
import { TaskCard } from './TaskCard'

interface Props {
  onOpenMeeting: (meetingId: string) => void
  onTaskUpdated: (task: Task) => void
}

const FILTERS: { value: ReviewStatus | ''; label: string }[] = [
  { value: '', label: 'All' },
  { value: 'PENDING_REVIEW', label: 'Pending review' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Rejected' },
]

/** Task history across all meetings, straight from GET /api/tasks. */
export function TasksPanel({ onOpenMeeting, onTaskUpdated }: Props) {
  const [filter, setFilter] = useState<ReviewStatus | ''>('')
  const [tasks, setTasks] = useState<Task[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setTasks(await listTasks({ reviewStatus: filter || undefined }))
    } catch (e) {
      setTasks(null)
      setError(describeError(e).message)
    } finally {
      setLoading(false)
    }
  }, [filter])

  useEffect(() => {
    load()
  }, [load])

  function updated(t: Task) {
    setTasks((cur) => cur && cur.map((x) => (x.id === t.id ? t : x)))
    onTaskUpdated(t)
  }

  return (
    <section aria-labelledby="tasks-h" className="panel">
      <div className="panel-head">
        <h2 id="tasks-h">All tasks</h2>
        <div className="badges">
          <label className="inline">
            Review status{' '}
            <select value={filter} onChange={(e) => setFilter(e.target.value as ReviewStatus | '')}>
              {FILTERS.map((f) => (
                <option key={f.value} value={f.value}>
                  {f.label}
                </option>
              ))}
            </select>
          </label>
          <button type="button" disabled={loading} onClick={load}>
            {loading ? 'Loading…' : 'Refresh'}
          </button>
        </div>
      </div>
      {error && (
        <p className="alert alert-error" role="alert">
          Could not load tasks: {error}
        </p>
      )}
      {loading && !tasks && <p className="muted">Loading tasks…</p>}
      {tasks && tasks.length === 0 && <p className="empty">No tasks match this filter yet.</p>}
      {tasks && tasks.length > 0 && (
        <div className="stack">
          {tasks.map((t) => (
            <div key={t.id}>
              <TaskCard task={t} onUpdated={updated} />
              <p className="small">
                <button type="button" className="link" onClick={() => onOpenMeeting(t.meeting_id)}>
                  Open its meeting (with transcript context)
                </button>
              </p>
            </div>
          ))}
        </div>
      )}
    </section>
  )
}
