// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { Task } from '../api/types'
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

const base: Task = {
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
  work_status: 'TO_DO',
  work_status_updated_at: null,
}
const transcript = 'Alex: please prepare the test report by Friday.'

beforeEach(() => vi.clearAllMocks())
afterEach(cleanup)

describe('TaskCard rendering', () => {
  it('shows task data, evidence, review and sync status', () => {
    render(<TaskCard task={base} transcript={transcript} onUpdated={() => {}} />)
    expect(screen.getByText('Prepare test report')).toBeTruthy()
    expect(screen.getByText('please prepare the test report by Friday')).toBeTruthy()
    expect(screen.getByText(/Evidence verified/)).toBeTruthy()
    expect(screen.getByText('Pending review')).toBeTruthy()
    expect(screen.getByText('Not synced')).toBeTruthy()
    expect(screen.getByText(/90%/)).toBeTruthy()
  })

  it('shows "Not specified" for missing owner and deadline', () => {
    render(<TaskCard task={{ ...base, owner: null, deadline: null }} onUpdated={() => {}} />)
    expect(screen.getAllByText('Not specified')).toHaveLength(2)
  })

  it('warns about unverified evidence and disables approval', () => {
    render(<TaskCard task={{ ...base, evidence_status: 'UNVERIFIED', evidence_verified: false }} onUpdated={() => {}} />)
    expect(screen.getByRole('alert').textContent).toMatch(/cannot be approved/)
    expect((screen.getByRole('button', { name: 'Approve' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('disables every action on a rejected task', () => {
    render(<TaskCard task={{ ...base, review_status: 'REJECTED' }} onUpdated={() => {}} />)
    for (const name of ['Approve', 'Edit', 'Rejected', 'Sync to Trello']) {
      expect((screen.getByRole('button', { name }) as HTMLButtonElement).disabled).toBe(true)
    }
  })
})

describe('TaskCard approval', () => {
  it('sends the current version and shows the server-returned state', async () => {
    const approved = { ...base, review_status: 'APPROVED' as const, version: 5 }
    vi.mocked(api.approveTask).mockResolvedValue(approved)
    const onUpdated = vi.fn()
    render(<TaskCard task={base} onUpdated={onUpdated} />)

    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))

    expect(api.approveTask).toHaveBeenCalledWith('t1', 4)
    await waitFor(() => expect(onUpdated).toHaveBeenCalledWith(approved))
  })

  it('shows a stale-version conflict and does not report success', async () => {
    vi.mocked(api.approveTask).mockRejectedValue(new ApiError(409, 'version_conflict', 'Task version is 5 but the request used 4'))
    const onUpdated = vi.fn()
    render(<TaskCard task={base} onUpdated={onUpdated} />)

    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))

    expect((await screen.findByText(/Task version is 5/)).textContent).toBeTruthy()
    expect(onUpdated).not.toHaveBeenCalled()
    expect(screen.queryByText(/^Approved/)).toBeNull()
    expect(screen.getByRole('button', { name: 'Reload task' })).toBeTruthy()
  })

  it('sends only one request for a rapid double click', async () => {
    let resolve!: (t: Task) => void
    vi.mocked(api.approveTask).mockReturnValue(new Promise((r) => (resolve = r)))
    render(<TaskCard task={base} onUpdated={() => {}} />)

    const button = screen.getByRole('button', { name: 'Approve' })
    await userEvent.dblClick(button)
    expect(api.approveTask).toHaveBeenCalledTimes(1)
    resolve({ ...base, review_status: 'APPROVED', version: 5 })
  })
})

describe('TaskCard editing', () => {
  it('warns that editing an approved task resets approval and sends only changed fields with the version', async () => {
    const approved: Task = { ...base, review_status: 'APPROVED', version: 5 }
    const reset: Task = { ...approved, review_status: 'PENDING_REVIEW', owner: 'Sam', version: 6 }
    vi.mocked(api.updateTask).mockResolvedValue(reset)
    const onUpdated = vi.fn()
    render(<TaskCard task={approved} onUpdated={onUpdated} />)

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    expect(screen.getByRole('note').textContent).toMatch(/resets it to Pending review/)
    const owner = screen.getByLabelText(/Owner/)
    await userEvent.clear(owner)
    await userEvent.type(owner, 'Sam')
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    expect(api.updateTask).toHaveBeenCalledWith('t1', { owner: 'Sam', version: 5 })
    await waitFor(() => expect(onUpdated).toHaveBeenCalledWith(reset))
    expect((await screen.findByRole('status')).textContent).toMatch(/reset it to Pending review/)
  })

  it('rejects a blank title locally without calling the API', async () => {
    render(<TaskCard task={base} onUpdated={() => {}} />)
    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    await userEvent.clear(screen.getByLabelText('Title'))
    // `required` blocks native submit in browsers; call submit through the button anyway
    await userEvent.type(screen.getByLabelText('Title'), '   ')
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(api.updateTask).not.toHaveBeenCalled()
  })
})

describe('TaskCard rejection', () => {
  it('requires confirmation, then sends reject', async () => {
    const rejected: Task = { ...base, review_status: 'REJECTED', version: 5 }
    vi.mocked(api.rejectTask).mockResolvedValue(rejected)
    const onUpdated = vi.fn()
    render(<TaskCard task={base} onUpdated={onUpdated} />)

    await userEvent.click(screen.getByRole('button', { name: 'Reject' }))
    expect(api.rejectTask).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Yes, reject' }))

    expect(api.rejectTask).toHaveBeenCalledWith('t1', 4)
    await waitFor(() => expect(onUpdated).toHaveBeenCalledWith(rejected))
  })
})
