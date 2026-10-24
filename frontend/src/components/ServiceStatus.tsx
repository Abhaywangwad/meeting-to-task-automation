import { useCallback, useEffect, useState } from 'react'
import { getAgentHealth, getAgentReady, getBackendHealth, getDatabaseHealth } from '../api/client'
import { AGENT_BASE_URL, API_BASE_URL } from '../config'
import { describeError } from '../errors'
import { useTrello } from '../TrelloContext'

type State = { status: 'loading' | 'connected' | 'warning' | 'error'; detail?: string }

const LABEL = { loading: 'Checking…', connected: 'Connected', warning: 'Connected, needs attention', error: 'Unavailable' }

function Item({ name, url, state }: { name: string; url: string; state: State }) {
  return (
    <li className="status-item">
      <span className={`dot dot-${state.status}`} aria-hidden="true" />
      <div>
        <strong>{name}</strong>
        <div className={`status-label status-${state.status}`}>
          {LABEL[state.status]}
          {state.detail ? ` — ${state.detail}` : ''}
        </div>
        <code title={url}>{url}</code>
      </div>
    </li>
  )
}

export function ServiceStatus() {
  const [backend, setBackend] = useState<State>({ status: 'loading' })
  const [db, setDb] = useState<State>({ status: 'loading' })
  const [agent, setAgent] = useState<State>({ status: 'loading' })
  const trello = useTrello()
  const refreshTrello = trello.refresh

  const check = useCallback(() => {
    refreshTrello()
    setBackend({ status: 'loading' })
    setDb({ status: 'loading' })
    setAgent({ status: 'loading' })

    getBackendHealth()
      .then((h) => setBackend(h.status === 'UP' ? { status: 'connected' } : { status: 'error', detail: 'Unexpected response' }))
      .catch((e) => setBackend({ status: 'error', detail: describeError(e).message }))

    // /api/health/db answers 503 when MySQL is unreachable, which surfaces here as an error.
    getDatabaseHealth()
      .then((h) => setDb(h.status === 'UP' ? { status: 'connected' } : { status: 'error', detail: 'Database reports DOWN' }))
      .catch((e) => setDb({ status: 'error', detail: describeError(e).message }))

    getAgentHealth()
      .then(async (h) => {
        if (h.status !== 'UP') return setAgent({ status: 'error', detail: 'Unexpected response' })
        try {
          const ready = await getAgentReady()
          setAgent(ready.llm_configured ? { status: 'connected' } : { status: 'warning', detail: 'LLM not configured; analysis will fail' })
        } catch {
          setAgent({ status: 'connected', detail: 'LLM configuration unknown' })
        }
      })
      .catch((e) => setAgent({ status: 'error', detail: describeError(e).message }))
  }, [refreshTrello])

  useEffect(() => {
    check()
  }, [check])

  return (
    <section aria-labelledby="status-h" className="panel">
      <div className="panel-head">
        <h2 id="status-h">System status</h2>
        <button type="button" onClick={check}>
          Recheck
        </button>
      </div>
      <ul className="status-grid">
        <Item name="Backend (Spring Boot)" url={`${API_BASE_URL}/api/health`} state={backend} />
        <Item name="Database (MySQL)" url={`${API_BASE_URL}/api/health/db`} state={db} />
        <Item name="AI agent (FastAPI)" url={`${AGENT_BASE_URL}/health`} state={agent} />
        <Item
          name="Trello (via backend)"
          url={`${API_BASE_URL}/api/integrations/trello`}
          state={
            trello.configured === null
              ? { status: 'loading', detail: 'unknown' }
              : trello.configured
                ? { status: 'connected', detail: 'credentials configured (verified only when a sync runs)' }
                : { status: 'warning', detail: 'not configured; syncing is unavailable' }
          }
        />
      </ul>
    </section>
  )
}
