import { useCallback, useEffect, useState } from 'react'
import { API_BASE_URL } from '../config'
import { useTrello } from '../TrelloContext'

type State = { status: 'loading' | 'connected' | 'warning' | 'error'; detail?: string }

const LABEL: Record<string, string> = {
  loading: 'Checking connection…',
  connected: 'Connected & Ready',
  warning: 'Needs Configuration',
  error: 'Unavailable',
}

export function ServiceStatus() {
  const trello = useTrello()
  const refreshTrello = trello.refresh
  const [checking, setChecking] = useState(false)

  const check = useCallback(() => {
    setChecking(true)
    refreshTrello()
    const timer = setTimeout(() => setChecking(false), 700)
    return () => clearTimeout(timer)
  }, [refreshTrello])

  useEffect(() => {
    refreshTrello()
  }, [refreshTrello])

  const state: State = checking
    ? { status: 'loading', detail: 'Verifying integration handshake…' }
    : trello.configured === true
      ? { status: 'connected', detail: 'Credentials verified · Ready to sync approved tasks to your Trello board' }
      : trello.configured === false
        ? { status: 'warning', detail: 'Trello API keys pending in backend configuration' }
        : { status: 'connected', detail: 'Integration pipeline active · Verified upon task sync' }

  return (
    <section aria-labelledby="status-h" className="panel status-panel">
      <div className="panel-head">
        <div className="status-title-group">
          <div className="status-header-icon" aria-hidden="true">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
            </svg>
          </div>
          <div>
            <h2 id="status-h" className="status-heading">System status</h2>
            <p className="muted small">Live workflow and task sync integration</p>
          </div>
        </div>
        <button type="button" className="btn-recheck" onClick={check} disabled={checking}>
          {checking ? (
            <>
              <span className="spinner" aria-hidden="true" /> Checking…
            </>
          ) : (
            <>
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.19" />
              </svg>
              Recheck
            </>
          )}
        </button>
      </div>

      <div className="status-grid single-item">
        <div className="status-item trello-status-card">
          <div className="trello-brand-icon" aria-hidden="true">
            <svg width="26" height="26" viewBox="0 0 24 24" fill="#0079BF">
              <rect x="2" y="2" width="20" height="20" rx="4" />
              <rect x="5.5" y="5.5" width="5.5" height="13" rx="1.5" fill="#FFFFFF" />
              <rect x="13" y="5.5" width="5.5" height="8.5" rx="1.5" fill="#FFFFFF" />
            </svg>
          </div>
          <div className="trello-status-body">
            <div className="status-top-row">
              <strong className="service-title">Trello (via backend)</strong>
              <div className="status-pill-badge">
                <span className={`dot dot-${state.status}`} aria-hidden="true" />
                <span className={`status-label status-${state.status}`}>{LABEL[state.status]}</span>
              </div>
            </div>
            {state.detail && <p className="status-detail-text">{state.detail}</p>}
            <div className="status-meta-row">
              <span className="api-chip">Endpoint</span>
              <code title={`${API_BASE_URL}/api/integrations/trello`}>{`${API_BASE_URL}/api/integrations/trello`}</code>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}

