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
    getTrelloStatus: vi.fn(),
  }
})
import * as api from '../api/client'

const pending: Task = {
  id: 't1',
  meeting_id: 'm1',
  title: 'Prepare test report',
  description: 'Priya prepares it',
  owner: 'Priya',
  deadline: 'Friday',
  evidence_quote: 'please prepare the test report by Friday',
  evidence_status: 'VERIFIED',
  evidence_verified: true,
  evidence_start: 6,
  evidence_end: 46,
  evidence_match: 'exact',
  confidence: 0.9,
  review_status: 'PENDING_REVIEW',
  sync_status: 'NOT_SYNCED',
  version: 4,
  created_at: '2026-01-01T00:00:00Z',
  updated_at: '2026-01-01T00:00:00Z',
  approved_at: null,
  rejected_at: null,
  trello_card_id: null,
  trello_card_url: null,
  synced_at: null,
  sync_attempted_at: null,
  sync_error_code: null,
  sync_error_message: null,
}
const approved: Task = { ...pending, review_status: 'APPROVED', version: 5 }

const syncButton = () => screen.getByRole('button', { name: /Sync to Trello|Syncing/ }) as HTMLButtonElement

beforeEach(() => vi.clearAllMocks())
afterEach(cleanup)

describe('Sync to Trello button', () => {
  it('is disabled with an explanation until the task is approved', () => {
    render(<TaskCard task={pending} onUpdated={() => {}} />)
    expect(syncButton().disabled).toBe(true)
    expect(screen.getByText(/Sync unavailable: Approve the task first/)).toBeTruthy()
  })

  it('is disabled for unverified evidence even if the task is approved', () => {
    render(<TaskCard task={{ ...approved, evidence_status: 'UNVERIFIED', evidence_verified: false }} onUpdated={() => {}} />)
    expect(syncButton().disabled).toBe(true)
    expect(screen.getByText(/Sync unavailable: Tasks with unverified evidence/)).toBeTruthy()
  })

  it('is disabled with an explanation when the server reports Trello is not configured', async () => {
    vi.mocked(api.getTrelloStatus).mockResolvedValue({ configured: false })
    render(
      <TrelloProvider>
        <TaskCard task={approved} onUpdated={() => {}} />
      </TrelloProvider>,
    )
    await waitFor(() => expect(syncButton().disabled).toBe(true))
    expect(screen.getByText(/Sync unavailable: Trello is not configured on the server/)).toBeTruthy()
  })

  it('locks edit and reject once the task is synced', () => {
    render(
      <TaskCard
        task={{ ...approved, sync_status: 'SYNCED', trello_card_url: 'https://trello.com/c/x' }}
        onUpdated={() => {}}
      />,
    )
    expect((screen.getByRole('button', { name: 'Edit' }) as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByRole('button', { name: 'Reject' }) as HTMLButtonElement).disabled).toBe(true)
  })
})

describe('sync results', () => {
  it('sends the current version only after an explicit click and shows the real card link', async () => {
    const synced: Task = {
      ...approved,
      version: 7,
      sync_status: 'SYNCED',
      trello_card_id: 'c1',
      trello_card_url: 'https://trello.com/c/AbCd1234/x',
      synced_at: '2026-01-02T00:00:00Z',
    }
    vi.mocked(api.syncTask).mockResolvedValue(synced)
    const onUpdated = vi.fn()
    const { rerender } = render(<TaskCard task={approved} onUpdated={onUpdated} />)
    expect(api.syncTask).not.toHaveBeenCalled() // nothing happens automatically

    expect(syncButton().disabled).toBe(false)
    await userEvent.click(syncButton())

    expect(api.syncTask).toHaveBeenCalledWith('t1', 5)
    await waitFor(() => expect(onUpdated).toHaveBeenCalledWith(synced))
    rerender(<TaskCard task={synced} onUpdated={onUpdated} />)
    const link = screen.getByRole('link', { name: 'Open the Trello card' }) as HTMLAnchorElement
    expect(link.href).toBe('https://trello.com/c/AbCd1234/x')
    expect(link.rel).toContain('noopener')
    expect(syncButton().disabled).toBe(true)
  })

  it('does not render a non-https card URL as a link', () => {
    const odd: Task = { ...approved, sync_status: 'SYNCED', trello_card_id: 'c1', trello_card_url: 'javascript:alert(1)' }
    render(<TaskCard task={odd} onUpdated={() => {}} />)
    expect(screen.queryByRole('link')).toBeNull()
  })

  it('shows a missing-configuration error from the server and reloads the task', async () => {
    vi.mocked(api.syncTask).mockRejectedValue(
      new ApiError(503, 'trello_not_configured', 'Trello is not configured on the server. Set TRELLO_API_KEY'),
    )
    vi.mocked(api.getTask).mockResolvedValue(approved)
    render(<TaskCard task={approved} onUpdated={() => {}} />)

    await userEvent.click(syncButton())

    expect((await screen.findAllByText(/Trello is not configured on the server/)).length).toBeGreaterThan(0)
    expect(api.getTask).toHaveBeenCalledWith('t1')
    expect(screen.queryByRole('link')).toBeNull()
  })

  it('shows a truthful failure and the FAILED state', async () => {
    vi.mocked(api.syncTask).mockRejectedValue(new ApiError(502, 'trello_auth_failed', 'Trello rejected the API key or token.'))
    const failed = { ...approved, sync_status: 'FAILED' as const, sync_error_code: 'trello_auth_failed' }
    vi.mocked(api.getTask).mockResolvedValue(failed)
    const onUpdated = vi.fn()
    render(<TaskCard task={approved} onUpdated={onUpdated} />)

    await userEvent.click(syncButton())

    expect((await screen.findAllByText(/Trello rejected the API key or token/)).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/No card was created/).length).toBeGreaterThan(0)
    await waitFor(() => expect(onUpdated).toHaveBeenCalledWith(failed))
  })

  it('presents an uncertain outcome as unknown, blocks a blind retry and offers a check', async () => {
    const uncertain: Task = { ...approved, sync_status: 'UNCERTAIN', sync_error_message: 'Trello did not answer in time.' }
    vi.mocked(api.syncTask).mockRejectedValue(
      new ApiError(504, 'sync_uncertain', 'Trello did not answer in time. A card may or may not have been created.'),
    )
    vi.mocked(api.getTask).mockResolvedValue(uncertain)
    const onUpdated = vi.fn()
    const { rerender } = render(<TaskCard task={approved} onUpdated={onUpdated} />)

    await userEvent.click(syncButton())
    await waitFor(() => expect(onUpdated).toHaveBeenCalledWith(uncertain))
    rerender(<TaskCard task={uncertain} onUpdated={onUpdated} />)

    expect(screen.getAllByText(/may or may not/).length).toBeGreaterThan(0)
    expect(screen.getByText('Sync outcome unknown.', { selector: 'strong' })).toBeTruthy()
    expect(syncButton().disabled).toBe(true)
    expect(screen.getByRole('button', { name: 'Check Trello' })).toBeTruthy()
    expect(screen.queryByText(/Open the Trello card/)).toBeNull()
  })

  it('Check Trello calls reconcile and reports the adopted card', async () => {
    const uncertain: Task = { ...approved, sync_status: 'UNCERTAIN' }
    const found: Task = { ...uncertain, sync_status: 'SYNCED', trello_card_id: 'c9', trello_card_url: 'https://trello.com/c/zz' }
    vi.mocked(api.reconcileTask).mockResolvedValue(found)
    const onUpdated = vi.fn()
    render(<TaskCard task={uncertain} onUpdated={onUpdated} />)

    await userEvent.click(screen.getByRole('button', { name: 'Check Trello' }))

    expect(api.reconcileTask).toHaveBeenCalledWith('t1')
    await waitFor(() => expect(onUpdated).toHaveBeenCalledWith(found))
    expect(await screen.findByText(/No duplicate was created/)).toBeTruthy()
  })

  it('sends one sync request for a rapid double click', async () => {
    let resolve!: (t: Task) => void
    vi.mocked(api.syncTask).mockReturnValue(new Promise((r) => (resolve = r)))
    render(<TaskCard task={approved} onUpdated={() => {}} />)
    await userEvent.dblClick(syncButton())
    expect(api.syncTask).toHaveBeenCalledTimes(1)
    resolve({ ...approved, sync_status: 'SYNCED', trello_card_id: 'c', trello_card_url: 'https://trello.com/c/x' })
  })
})
