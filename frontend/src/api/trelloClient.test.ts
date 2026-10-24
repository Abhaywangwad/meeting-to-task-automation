import { afterEach, describe, expect, it, vi } from 'vitest'
import { getTrelloStatus, reconcileTask, syncTask } from './client'

function mockFetch(impl: () => Response) {
  const fn = vi.fn(async () => impl())
  vi.stubGlobal('fetch', fn)
  return fn
}
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

afterEach(() => vi.unstubAllGlobals())

describe('trello endpoints', () => {
  it('sync POSTs the current version to /sync', async () => {
    const f = mockFetch(() => json({ id: 't1', sync_status: 'SYNCED' }))
    await syncTask('t1', 7)
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/tasks/t1/sync')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ version: 7 })
  })

  it('reconcile POSTs to /sync/reconcile without a body', async () => {
    const f = mockFetch(() => json({ id: 't1' }))
    await reconcileTask('t1')
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/tasks/t1/sync/reconcile')
    expect(init.body).toBeUndefined()
  })

  it('reads only the configured flag from the integration status endpoint', async () => {
    const f = mockFetch(() => json({ configured: false, work_lists_configured: false }))
    expect(await getTrelloStatus()).toEqual({ configured: false, work_lists_configured: false })
    expect((f.mock.calls[0] as unknown as [string])[0]).toBe('http://localhost:8080/api/integrations/trello')
  })

  it('surfaces sync errors with their code and never calls Trello directly', async () => {
    const f = mockFetch(() => json({ code: 'trello_not_configured', message: 'not configured' }, 503))
    await expect(syncTask('t1', 1)).rejects.toMatchObject({ status: 503, code: 'trello_not_configured' })
    for (const call of f.mock.calls) {
      expect((call as unknown as [string])[0]).not.toContain('trello.com')
    }
  })
})

describe('work status endpoint', () => {
  it('POSTs the target status and current version to /work-status on the backend only', async () => {
    const f = mockFetch(() => json({ id: 't1', work_status: 'IN_PROGRESS' }))
    const { setWorkStatus } = await import('./client')
    await setWorkStatus('t1', 'IN_PROGRESS', 7)
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/tasks/t1/work-status')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ work_status: 'IN_PROGRESS', version: 7 })
    expect(url).not.toContain('trello.com')
  })
})
