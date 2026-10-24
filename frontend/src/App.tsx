import { useCallback, useEffect, useState } from 'react'
import './App.css'
import { ApiError, createMeeting, getMeeting } from './api/client'
import type { Meeting, Task } from './api/types'
import { MeetingView } from './components/MeetingView'
import { ServiceStatus } from './components/ServiceStatus'
import { TasksPanel } from './components/TasksPanel'
import { TranscriptForm } from './components/TranscriptForm'
import { TrelloProvider } from './TrelloContext'
import { describeError, type ErrorInfo } from './errors'

const STORAGE_KEY = 'meet2action.lastMeetingId'

// Only a non-sensitive identifier is remembered; the database stays the source of truth.
function readStoredId(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}
function storeId(id: string | null) {
  try {
    if (id) localStorage.setItem(STORAGE_KEY, id)
    else localStorage.removeItem(STORAGE_KEY)
  } catch {
    // storage unavailable (private mode etc.); the app works without it
  }
}

type Tab = 'meeting' | 'tasks'

export default function App() {
  return (
    <TrelloProvider>
      <Dashboard />
    </TrelloProvider>
  )
}

function Dashboard() {
  const [meeting, setMeeting] = useState<Meeting | null>(null)
  const [tab, setTab] = useState<Tab>('meeting')
  const [analyzing, setAnalyzing] = useState(false)
  const [analyzeError, setAnalyzeError] = useState<ErrorInfo | null>(null)
  const [failedMeetingId, setFailedMeetingId] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const [reloading, setReloading] = useState(false)
  const [reloadError, setReloadError] = useState<string | null>(null)

  const loadMeeting = useCallback(async (id: string, quiet = false) => {
    setReloading(true)
    setReloadError(null)
    try {
      const m = await getMeeting(id)
      setMeeting(m)
      storeId(m.id)
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        storeId(null)
        setMeeting(null)
      } else if (!quiet) {
        setReloadError(describeError(e).message)
      }
    } finally {
      setReloading(false)
    }
  }, [])

  // Restore the last meeting after a browser refresh.
  useEffect(() => {
    const id = readStoredId()
    if (id) void loadMeeting(id, true)
  }, [loadMeeting])

  async function analyze(transcript: string) {
    setAnalyzing(true)
    setAnalyzeError(null)
    setFailedMeetingId(null)
    setInfo(null)
    try {
      const { meeting: m, created } = await createMeeting(transcript)
      setMeeting(m)
      storeId(m.id)
      setTab('meeting')
      if (!created) setInfo('This exact transcript was analyzed moments ago, so the existing result is shown (no new analysis was run).')
    } catch (e) {
      setAnalyzeError(describeError(e))
      if (e instanceof ApiError && e.meetingId) setFailedMeetingId(e.meetingId)
    } finally {
      setAnalyzing(false)
    }
  }

  const onTaskUpdated = useCallback((t: Task) => {
    setMeeting((m) => (m && m.id === t.meeting_id ? { ...m, tasks: m.tasks.map((x) => (x.id === t.id ? t : x)) } : m))
  }, [])

  function openMeeting(id: string) {
    setTab('meeting')
    void loadMeeting(id)
  }

  return (
    <div className="container">
      <header className="hero">
        <div className="hero-badge">✦ AI MEETING INTELLIGENCE · HACKATHON 2026</div>
        <h1>Meet2Action</h1>
        <p className="tagline">Turn meeting conversations into evidence-backed commitments.</p>

        <div className="workflow-wrapper">
          <span className="workflow-heading">AUTOMATION PIPELINE:</span>
          <ol className="workflow" aria-label="Workflow">
            {['1. Transcript Input', '2. AI Agent Extraction', '3. Evidence Grounding', '4. Human Approval', '5. Trello Kanban Sync'].map((s) => (
              <li key={s}>{s}</li>
            ))}
          </ol>
        </div>
      </header>

      <ServiceStatus />
      <TranscriptForm analyzing={analyzing} error={analyzeError} failedMeetingId={failedMeetingId} onAnalyze={analyze} />
      {info && (
        <p className="alert alert-info" role="status">
          {info}
        </p>
      )}

      <nav className="tabs" aria-label="Views">
        <button type="button" className={tab === 'meeting' ? 'tab active' : 'tab'} aria-pressed={tab === 'meeting'} onClick={() => setTab('meeting')}>
          <span className="tab-icon">📊</span> Current meeting results
        </button>
        <button type="button" className={tab === 'tasks' ? 'tab active' : 'tab'} aria-pressed={tab === 'tasks'} onClick={() => setTab('tasks')}>
          <span className="tab-icon">📋</span> All tasks repository
        </button>
      </nav>

      {tab === 'meeting' &&
        (meeting ? (
          <MeetingView
            meeting={meeting}
            reloading={reloading}
            reloadError={reloadError}
            onReload={() => loadMeeting(meeting.id)}
            onTaskUpdated={onTaskUpdated}
          />
        ) : (
          <div className="empty-state-card">
            <div className="empty-state-icon">💡</div>
            <h3>Ready for Meeting Analysis</h3>
            <p className="muted">
              Paste your team's meeting transcript in the box above and click <strong>“Analyze Meeting”</strong> (or click <strong>“Insert sample transcript”</strong>) to see automated AI extraction and quote verification in action.
            </p>
          </div>
        ))}
      {tab === 'tasks' && <TasksPanel onOpenMeeting={openMeeting} onTaskUpdated={onTaskUpdated} />}
    </div>
  )
}

