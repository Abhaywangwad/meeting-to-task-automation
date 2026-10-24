// Public service URLs only. Never put secrets in VITE_* variables: they are bundled into the browser app.
const trim = (url: string) => url.replace(/\/+$/, '')

export const API_BASE_URL = trim(import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080')
export const AGENT_BASE_URL = trim(import.meta.env.VITE_AGENT_BASE_URL ?? 'http://localhost:8000')

/** Backend allows 60s for the agent call; leave headroom so the server error wins over a client abort. */
export const ANALYZE_TIMEOUT_MS = 90_000
export const DEFAULT_TIMEOUT_MS = 15_000
export const MAX_TRANSCRIPT_CHARS = 200_000

/** Backend allows ~15s for Trello (plus a reconcile); leave headroom so the server's verdict wins. */
export const SYNC_TIMEOUT_MS = 45_000
