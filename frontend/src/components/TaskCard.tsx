import { useRef, useState } from 'react'
import { approveTask, getTask, reconcileTask, rejectTask, setWorkStatus, syncTask, updateTask } from '../api/client'
import type { Task, TaskPatch, WorkStatus } from '../api/types'
import { describeError, type ErrorInfo } from '../errors'
import { useTrello } from '../TrelloContext'
import { EvidenceBadge, ReviewBadge, SyncBadge, WORK_LABEL, WorkBadge } from './Badges'

const WORK_ORDER: WorkStatus[] = ['TO_DO', 'IN_PROGRESS', 'COMPLETED']
import { EvidenceQuote } from './EvidenceQuote'

interface Props {
  task: Task
  /** Transcript of the meeting, when known; enables "show in transcript". */
  transcript?: string
  /** Called with the server's version of the task after every successful mutation or reload. */
  onUpdated: (task: Task) => void
}

type Action = 'approve' | 'reject' | 'save' | 'reload' | 'sync' | 'reconcile' | 'work'

/** Only ever render http(s) links coming from the server. */
const safeUrl = (u: string | null) => (u && /^https:\/\//i.test(u) ? u : null)

export function TaskCard({ task, transcript, onUpdated }: Props) {
  const trello = useTrello()
  const [busy, setBusy] = useState<Action | null>(null)
  const lock = useRef(false) // synchronous guard: state updates are too slow to stop a double click
  const [error, setError] = useState<ErrorInfo | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [editing, setEditing] = useState(false)
  const [confirmReject, setConfirmReject] = useState(false)
  const [form, setForm] = useState({ title: '', description: '', owner: '', deadline: '' })

  const rejected = task.review_status === 'REJECTED'
  const approved = task.review_status === 'APPROVED'
  const unverified = task.evidence_status !== 'VERIFIED'
  const canApprove = task.review_status === 'PENDING_REVIEW' && !unverified
  const disabled = busy !== null

  // Trello state is separate from review state; the backend decides, the UI only explains.
  const sync = task.sync_status
  const syncLocked = sync === 'SYNCING' || sync === 'UNCERTAIN' || sync === 'SYNCED'
  const cardUrl = safeUrl(task.trello_card_url)
  let syncBlocker: string | null = null
  if (sync === 'SYNCED') syncBlocker = 'Already synced to Trello.'
  else if (sync === 'UNCERTAIN') syncBlocker = 'The outcome of the last sync is unknown. Check Trello status first.'
  else if (sync === 'SYNCING') syncBlocker = 'A sync is in progress.'
  else if (!approved) syncBlocker = 'Approve the task first. Only approved tasks can be synced.'
  else if (unverified) syncBlocker = 'Tasks with unverified evidence cannot be synced.'
  else if (trello.configured === false) syncBlocker = 'Trello is not configured on the server.'
  const canSync = syncBlocker === null

  // Work status (To Do / In Progress / Completed) moves the EXISTING Trello card; it needs a synced card.
  let workBlocker: string | null = null
  if (!approved) workBlocker = 'Approve the task first.'
  else if (sync !== 'SYNCED' || !task.trello_card_id) workBlocker = 'Sync to Trello first: the card must exist before it can be moved.'
  else if (trello.workListsConfigured === false) workBlocker = 'The In Progress / Completed Trello lists are not configured on the server.'

  async function run(
    action: Action,
    fn: () => Promise<Task>,
    success?: (t: Task) => string,
    refreshOnError = false,
  ) {
    if (lock.current) return
    lock.current = true
    setBusy(action)
    setError(null)
    setNotice(null)
    try {
      const updated = await fn()
      onUpdated(updated)
      setNotice(success ? success(updated) : null)
      return true
    } catch (e) {
      setError(describeError(e))
      if (refreshOnError) {
        // The server may have changed the task (e.g. FAILED / UNCERTAIN); show its real state.
        try {
          onUpdated(await getTask(task.id))
        } catch {
          // keep the original error; the task data on screen may be stale
        }
      }
      return false
    } finally {
      lock.current = false
      setBusy(null)
    }
  }

  function startEdit() {
    setForm({
      title: task.title,
      description: task.description,
      owner: task.owner ?? '',
      deadline: task.deadline ?? '',
    })
    setError(null)
    setNotice(null)
    setEditing(true)
  }

  async function save(ev: React.FormEvent) {
    ev.preventDefault()
    const title = form.title.trim()
    if (!title) {
      setError({ code: 'validation_error', message: 'Title must not be blank.' })
      return
    }
    // Send only fields that actually changed, plus the version we edited so stale edits are rejected.
    const patch: TaskPatch = { version: task.version }
    if (title !== task.title) patch.title = title
    if (form.description.trim() !== task.description) patch.description = form.description.trim()
    if (form.owner.trim() !== (task.owner ?? '')) patch.owner = form.owner.trim()
    if (form.deadline.trim() !== (task.deadline ?? '')) patch.deadline = form.deadline.trim()
    if (Object.keys(patch).length === 1) {
      setEditing(false)
      setNotice('No changes to save.')
      return
    }
    const wasApproved = approved
    const ok = await run('save', () => updateTask(task.id, patch), (t) =>
      wasApproved && t.review_status === 'PENDING_REVIEW'
        ? 'Saved. Because the task was approved, editing reset it to Pending review; it needs approval again.'
        : 'Saved.',
    )
    if (ok) setEditing(false)
  }

  return (
    <article className={`card task task-${task.review_status.toLowerCase()}`} aria-labelledby={`t-${task.id}`}>
      <header className="card-head">
        <h3 id={`t-${task.id}`}>{task.title}</h3>
        <div className="badges">
          <ReviewBadge status={task.review_status} />
          <EvidenceBadge status={task.evidence_status} />
          <SyncBadge status={task.sync_status} />
          {approved && <WorkBadge status={task.work_status} />}
        </div>
      </header>

      {editing ? (
        <form className="edit-form" onSubmit={save}>
          <label>
            Title
            <input value={form.title} maxLength={500} required onChange={(e) => setForm({ ...form, title: e.target.value })} />
          </label>
          <label>
            Description
            <textarea rows={3} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
          </label>
          <div className="row">
            <label>
              Owner (leave empty for none)
              <input value={form.owner} maxLength={255} onChange={(e) => setForm({ ...form, owner: e.target.value })} />
            </label>
            <label>
              Deadline (leave empty for none)
              <input value={form.deadline} maxLength={255} onChange={(e) => setForm({ ...form, deadline: e.target.value })} />
            </label>
          </div>
          {approved && (
            <p className="note" role="note">
              This task is approved. Saving a change resets it to Pending review.
            </p>
          )}
          <div className="actions">
            <button type="submit" className="primary" disabled={disabled}>
              {busy === 'save' ? 'Saving…' : 'Save changes'}
            </button>
            <button type="button" disabled={disabled} onClick={() => setEditing(false)}>
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <>
          {task.description && <p>{task.description}</p>}
          <dl className="facts">
            <div>
              <dt>Owner</dt>
              <dd className={task.owner ? '' : 'muted'}>{task.owner ?? 'Not specified'}</dd>
            </div>
            <div>
              <dt>Deadline</dt>
              <dd className={task.deadline ? '' : 'muted'}>{task.deadline ?? 'Not specified'}</dd>
            </div>
            <div>
              <dt>Model confidence</dt>
              <dd className={task.confidence === null ? 'muted' : ''}>
                {task.confidence === null ? 'Not provided' : `${Math.round(task.confidence * 100)}% (self-reported, not proof)`}
              </dd>
            </div>
          </dl>
        </>
      )}

      <section aria-label="Supporting evidence">
        <h4>Supporting evidence</h4>
        <EvidenceQuote
          quote={task.evidence_quote}
          start={task.evidence_start}
          end={task.evidence_end}
          match={task.evidence_match}
          transcript={transcript}
        />
        {unverified && (
          <p className="alert alert-warn" role="alert">
            This quotation was not found in the transcript. Under the current backend policy this task cannot be
            approved. You can edit it or reject it.
          </p>
        )}
        {!unverified && (
          <p className="muted small">
            Verified means the quote appears in the transcript; it does not mean the task is correct. Approval is your decision.
          </p>
        )}
      </section>

      {approved && !editing && (
        <section aria-label="Work status" className="work-status">
          <h4>Work status</h4>
          <div className="segmented" role="group" aria-label="Set work status">
            {WORK_ORDER.map((w) => (
              <button
                key={w}
                type="button"
                aria-pressed={task.work_status === w}
                className={task.work_status === w ? 'seg active' : 'seg'}
                disabled={disabled || workBlocker !== null || task.work_status === w}
                title={workBlocker ?? `Move the existing Trello card to ${WORK_LABEL[w]}`}
                onClick={() =>
                  run(
                    'work',
                    () => setWorkStatus(task.id, w, task.version),
                    () => `Moved the Trello card to ${WORK_LABEL[w]}.`,
                    true,
                  )
                }
              >
                {WORK_LABEL[w]}
              </button>
            ))}
          </div>
          {busy === 'work' && (
            <p className="muted small" role="status">
              Moving the Trello card…
            </p>
          )}
          <p className="muted small">
            {workBlocker
              ? `Status control unavailable: ${workBlocker}`
              : 'Changing the status moves the existing Trello card; no card is created. It changes here only after Trello confirms.'}
          </p>
        </section>
      )}
      {sync === 'SYNCED' && (
        <p className="alert alert-ok" role="status">
          Synced to Trello
          {task.synced_at ? ` on ${new Date(task.synced_at).toLocaleString()}` : ''}.{' '}
          {cardUrl ? (
            <a href={cardUrl} target="_blank" rel="noopener noreferrer">
              Open the Trello card
            </a>
          ) : (
            <span>Card ID {task.trello_card_id}</span>
          )}
        </p>
      )}
      {sync === 'UNCERTAIN' && (
        <div className="alert alert-warn" role="alert">
          <strong>Sync outcome unknown.</strong> {task.sync_error_message ?? 'Trello did not give a clear answer.'} A card
          may or may not exist in Trello. Do not sync again until you check.
          <div>
            <button
              type="button"
              disabled={disabled}
              onClick={() =>
                run(
                  'reconcile',
                  () => reconcileTask(task.id),
                  (t) =>
                    t.sync_status === 'SYNCED'
                      ? 'Found the existing Trello card and linked it. No duplicate was created.'
                      : t.sync_status === 'FAILED'
                        ? 'No card was found on the list. You can sync again.'
                        : 'Checked.',
                  true,
                )
              }
            >
              {busy === 'reconcile' ? 'Checking…' : 'Check Trello'}
            </button>
          </div>
        </div>
      )}
      {sync === 'SYNCING' && (
        <div className="alert alert-info" role="status">
          A sync is in progress (or was interrupted). If it does not finish, use Check Trello.
          <div>
            <button
              type="button"
              disabled={disabled}
              onClick={() => run('reconcile', () => reconcileTask(task.id), () => 'Checked.', true)}
            >
              {busy === 'reconcile' ? 'Checking…' : 'Check Trello'}
            </button>
          </div>
        </div>
      )}
      {sync === 'FAILED' && !error && (
        <p className="alert alert-error" role="alert">
          The last Trello sync failed{task.sync_error_code ? ` (${task.sync_error_code})` : ''}:{' '}
          {task.sync_error_message ?? 'unknown error'} No card was created, so you can try again.
        </p>
      )}

      {error && (
        <div className="alert alert-error" role="alert">
          <strong>{error.message}</strong> <code>{error.code}</code>
          {error.hint && <div>{error.hint}</div>}
          {(error.code === 'version_conflict' || error.code === 'invalid_state') && (
            <button
              type="button"
              disabled={disabled}
              onClick={() => run('reload', () => getTask(task.id), () => 'Task reloaded from the server.')}
            >
              Reload task
            </button>
          )}
        </div>
      )}
      {notice && (
        <p className="alert alert-ok" role="status">
          {notice}
        </p>
      )}

      {!editing && (
        <div className="actions">
          <button
            type="button"
            className="primary"
            disabled={disabled || !canApprove}
            title={unverified ? 'Unverified evidence cannot be approved' : undefined}
            onClick={() =>
              run('approve', () => approveTask(task.id, task.version), () => 'Approved. Nothing has been synced anywhere.')
            }
          >
            {busy === 'approve' ? 'Approving…' : approved ? 'Approved' : 'Approve'}
          </button>
          <button
            type="button"
            disabled={disabled || rejected || syncLocked}
            onClick={startEdit}
            title={rejected ? 'Rejected tasks cannot be edited' : syncLocked ? 'Locked while synced or syncing' : undefined}
          >
            Edit
          </button>
          {confirmReject ? (
            <>
              <span className="small">Reject permanently?</span>
              <button
                type="button"
                className="danger"
                disabled={disabled}
                onClick={async () => {
                  const ok = await run('reject', () => rejectTask(task.id, task.version), () => 'Rejected.')
                  if (ok !== undefined) setConfirmReject(false)
                }}
              >
                {busy === 'reject' ? 'Rejecting…' : 'Yes, reject'}
              </button>
              <button type="button" disabled={disabled} onClick={() => setConfirmReject(false)}>
                Keep
              </button>
            </>
          ) : (
            <button
              type="button"
              disabled={disabled || rejected || syncLocked}
              title={syncLocked ? 'Locked while synced or syncing' : undefined}
              onClick={() => setConfirmReject(true)}
            >
              {rejected ? 'Rejected' : 'Reject'}
            </button>
          )}
          <button
            type="button"
            className={canSync ? 'primary' : undefined}
            disabled={disabled || !canSync}
            title={syncBlocker ?? 'Create a Trello card for this approved task'}
            onClick={() =>
              run('sync', () => syncTask(task.id, task.version), () => 'Card created in Trello.', true)
            }
          >
            {busy === 'sync' ? 'Syncing…' : 'Sync to Trello'}
          </button>
        </div>
      )}
      {!editing && syncBlocker && sync !== 'SYNCED' && <p className="muted small">Sync unavailable: {syncBlocker}</p>}
      {rejected && <p className="muted small">Rejected tasks are final: they can’t be edited or approved.</p>}
    </article>
  )
}
