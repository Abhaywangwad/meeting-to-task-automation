// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { Task } from '../api/types'
import { TrelloProvider } from '../TrelloContext'
import { TaskCard } from './TaskCard'

vi.mock('../api/client', async (orig) => {
  const actual = await orig<typeof import('../api/client')>()
  return {
    ...actual,
    approveTask: vi.fn(),
    rejectTask: vi.fn(),
    updateTask: vi.fn(),
    getTask: vi.fn(),
    syncTask: vi.fn(),
    reconcileTask: vi.fn(),
    setWorkStatus: vi.fn(),
    getTrelloStatus: vi.fn(),
  }
})
import * as api from '../api/client'

const synced: Task = {
  id: 't1',
  meeting_id: 'm1',
  title: 'Prepare test report',
  description: 'd',
  owner: 'Priya',
  deadline: 'Friday',
  evidence_quote: 'q',
  evidence_status: 'VERIFIED',
  evidence_verified: true,
  evidence_start: 0,
  evidence_end: 1,
  evidence_match: 'exact',
  confidence: 0.9,
  review_status: 'APPROVED',
  sync_status: 'SYNCED',
  version: 7,
  created_at: '2026-01-01T00:00:00Z',
  updated_at: '2026-01-01T00:00:00Z',
  approved_at: '2026-01-01T00:00:00Z',
  rejected_at: null,
  trello_card_id: 'card1',
  trello_card_url: 'https://trello.com/c/x',
  synced_at: '2026-01-02T00:00:00Z',
  sync_attempted_at: null,
  sync_error_code: null,
  sync_error_message: null,
  work_status: 'TO_DO',
  work_status_updated_at: null,
}

const btn = (name: RegExp) => screen.getByRole('button', { name }) as HTMLButtonElement

beforeEach(() => vi.clearAllMocks())
afterEach(cleanup)

describe('work status control', () => {
  it('shows the work status separately from review and sync status', () => {
    render(<TaskCard task={{ ...synced, work_status: 'IN_PROGRESS' }} onUpdated={() => {}} />)
    expect(screen.getAllByText('Approved').length).toBeGreaterThan(0)
    expect(screen.getByText('Synced to Trello')).toBeTruthy()
    expect(screen.getAllByText(/In Progress/).length).toBeGreaterThan(0)
    expect(btn(/In Progress/).getAttribute('aria-pressed')).toBe('true')
    expect(btn(/To Do/).getAttribute('aria-pressed')).toBe('false')
  })

  it('is not offered for tasks that are not approved', () => {
    render(<TaskCard task={{ ...synced, review_status: 'PENDING_REVIEW', sync_status: 'NOT_SYNCED', trello_card_id: null }} onUpdated={() => {}} />)
    expect(screen.queryByRole('group', { name: 'Set work status' })).toBeNull()
  })

  it('is disabled with an explanation until the task has a Trello card', () => {
    render(<TaskCard task={{ ...synced, sync_status: 'NOT_SYNCED', trello_card_id: null, trello_card_url: null }} onUpdated={() => {}} />)
    for (const name of [/To Do/, /In Progress/, /Completed/]) expect(btn(name).disabled).toBe(true)
    expect(screen.getByText(/Status control unavailable: Sync to Trello first/)).toBeTruthy()
  })

  it('is disabled when the server reports the work lists are not configured', async () => {
    vi.mocked(api.getTrelloStatus).mockResolvedValue({ configured: true, work_lists_configured: false })
    render(
      <TrelloProvider>
        <TaskCard task={synced} onUpdated={() => {}} />
      </TrelloProvider>,
    )
    await waitFor(() => expect(btn(/In Progress/).disabled).toBe(true))
    expect(screen.getByText(/lists are not configured on the server/)).toBeTruthy()
  })

  it('sends the target and current version, and changes the UI only after the server confirms', async () => {
    let resolve!: (t: Task) => void
    vi.mocked(api.setWorkStatus).mockReturnValue(new Promise((r) => (resolve = r)))
    const onUpdated = vi.fn()
    render(<TaskCard task={synced} onUpdated={onUpdated} />)

    await userEvent.click(btn(/In Progress/))

    expect(api.setWorkStatus).toHaveBeenCalledWith('t1', 'IN_PROGRESS', 7)
    // pending: still To Do, controls locked, loading text visible, nothing reported upward yet
    expect(btn(/To Do/).getAttribute('aria-pressed')).toBe('true')
    expect(btn(/Completed/).disabled).toBe(true)
    expect(screen.getByText(/Moving the Trello card/)).toBeTruthy()
    expect(onUpdated).not.toHaveBeenCalled()

    const confirmed = { ...synced, work_status: 'IN_PROGRESS' as const, version: 9 }
    resolve(confirmed)
    await waitFor(() => expect(onUpdated).toHaveBeenCalledWith(confirmed))
    expect(await screen.findByText(/Moved the Trello card to/)).toBeTruthy()
  })

  it('sends one request for a rapid double click', async () => {
    let resolve!: (t: Task) => void
    vi.mocked(api.setWorkStatus).mockReturnValue(new Promise((r) => (resolve = r)))
    render(<TaskCard task={synced} onUpdated={() => {}} />)
    await userEvent.dblClick(btn(/Completed/))
    expect(api.setWorkStatus).toHaveBeenCalledTimes(1)
    resolve({ ...synced, work_status: 'COMPLETED' })
  })

  it('shows a failed move, keeps the old status, and reloads the real state', async () => {
    vi.mocked(api.setWorkStatus).mockRejectedValue(
      new ApiError(504, 'work_move_uncertain', 'Trello did not confirm the move. The work status was NOT changed.'),
    )
    vi.mocked(api.getTask).mockResolvedValue(synced)
    const onUpdated = vi.fn()
    render(<TaskCard task={synced} onUpdated={onUpdated} />)

    await userEvent.click(btn(/Completed/))

    expect((await screen.findAllByText(/NOT changed/)).length).toBeGreaterThan(0)
    expect(api.getTask).toHaveBeenCalledWith('t1')
    expect(onUpdated).toHaveBeenCalledTimes(1)
    expect(onUpdated).toHaveBeenCalledWith(expect.objectContaining({ work_status: 'TO_DO' })) // never the failed target
    expect(btn(/To Do/).getAttribute('aria-pressed')).toBe('true')
  })

  it('explains a not_synced rejection from the server', async () => {
    vi.mocked(api.setWorkStatus).mockRejectedValue(new ApiError(409, 'not_synced', 'This task has no Trello card yet.'))
    vi.mocked(api.getTask).mockResolvedValue(synced)
    render(<TaskCard task={synced} onUpdated={() => {}} />)
    await userEvent.click(btn(/In Progress/))
    expect((await screen.findAllByText(/Sync to Trello/)).length).toBeGreaterThan(0)
  })
})
