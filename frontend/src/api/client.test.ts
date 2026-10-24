import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, approveTask, createMeeting, getMeeting, listTasks, rejectTask, updateTask } from './client'

function mockFetch(impl: (url: string, init: RequestInit) => Promise<Response> | Response) {
  const fn = vi.fn(async (url: string | URL | Request, init?: RequestInit) => impl(String(url), init ?? {}))
  vi.stubGlobal('fetch', fn)
  return fn
}
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

afterEach(() => vi.unstubAllGlobals())

describe('request contract', () => {
  it('approve POSTs the current version to the approve endpoint', async () => {
    const f = mockFetch(() => json({ id: 't1', review_status: 'APPROVED' }))
    await approveTask('t1', 3)
    const [url, init] = f.mock.calls[0]
    expect(String(url)).toBe('http://localhost:8080/api/tasks/t1/approve')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toEqual({ version: 3 })
  })

  it('reject POSTs to the reject endpoint with optional version', async () => {
    const f = mockFetch(() => json({ id: 't1' }))
    await rejectTask('t1', 2)
    expect(String(f.mock.calls[0][0])).toContain('/api/tasks/t1/reject')
    expect(JSON.parse(String(f.mock.calls[0][1]?.body))).toEqual({ version: 2 })
  })

  it('edit PATCHes exactly the given fields (no evidence or status fields)', async () => {
    const f = mockFetch(() => json({ id: 't1' }))
    await updateTask('t1', { owner: 'Sam', version: 1 })
    expect(f.mock.calls[0][1]?.method).toBe('PATCH')
    expect(JSON.parse(String(f.mock.calls[0][1]?.body))).toEqual({ owner: 'Sam', version: 1 })
  })

  it('creates a meeting with {transcript} and reports created vs existing', async () => {
    const f = mockFetch(() => json({ id: 'm1', tasks: [] }, 201))
    expect((await createMeeting('hello')).created).toBe(true)
    expect(JSON.parse(String(f.mock.calls[0][1]?.body))).toEqual({ transcript: 'hello' })
    mockFetch(() => json({ id: 'm1', tasks: [] }, 200))
    expect((await createMeeting('hello')).created).toBe(false)
  })

  it('uses the backend query parameter names for task filters', async () => {
    const f = mockFetch(() => json([]))
    await listTasks({ meetingId: 'm1', reviewStatus: 'APPROVED' })
    expect(String(f.mock.calls[0][0])).toBe('http://localhost:8080/api/tasks?meeting_id=m1&review_status=APPROVED')
  })

  it('encodes ids in the path', async () => {
    const f = mockFetch(() => json({ id: 'x' }))
    await getMeeting('a/b')
    expect(String(f.mock.calls[0][0])).toContain('/api/meetings/a%2Fb')
  })
})

describe('error handling', () => {
  it('turns a backend JSON error into ApiError with code, message and meeting id', async () => {
    mockFetch(() => json({ status: 503, code: 'llm_not_configured', message: 'LLM missing', meeting_id: 'm9' }, 503))
    const err = await createMeeting('x').catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err).toMatchObject({ status: 503, code: 'llm_not_configured', message: 'LLM missing', meetingId: 'm9' })
  })

  it('handles a non-JSON error body without crashing', async () => {
    mockFetch(() => new Response('<html>Bad gateway</html>', { status: 502 }))
    const err = await getMeeting('m1').catch((e) => e)
    expect(err).toMatchObject({ status: 502, code: 'http_502' })
    expect(err.message).toContain('HTTP 502')
  })

  it('rejects a 200 response that is not JSON instead of faking data', async () => {
    mockFetch(() => new Response('ok', { status: 200 }))
    await expect(getMeeting('m1')).rejects.toMatchObject({ code: 'invalid_response' })
  })

  it('reports an unreachable server as network_error with status 0', async () => {
    mockFetch(() => {
      throw new TypeError('Failed to fetch')
    })
    await expect(getMeeting('m1')).rejects.toMatchObject({ status: 0, code: 'network_error' })
  })

  it('maps 409 version conflicts', async () => {
    mockFetch(() => json({ code: 'version_conflict', message: 'stale' }, 409))
    await expect(approveTask('t1', 1)).rejects.toMatchObject({ status: 409, code: 'version_conflict' })
  })
})
