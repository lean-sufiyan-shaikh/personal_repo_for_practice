import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { dayCloseApi } from '../../../api/dayCloseApi'
import { pumpApi } from '../../../api/pumpApi'
import type { UserRole } from '../../../types/auth'
import { useAuthStore } from '../../../store/authStore'
import { usePumpStore } from '../../../store/usePumpStore'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { resetScreenWidthAfterEach, setScreenWidth } from '../../../test/screenWidth'
import DayClosePage from '../DayClosePage'
import { detail, listItem, page, preview, summary } from '../dayCloseTestData'

vi.mock('../../../api/dayCloseApi', () => ({
  dayCloseApi: {
    getPreview: vi.fn(), list: vi.fn(), getDetail: vi.fn(), getVersion: vi.fn(), close: vi.fn(),
    approve: vi.fn(), addComment: vi.fn(), reopen: vi.fn(),
  },
}))
vi.mock('../../../api/pumpApi', () => ({ pumpApi: { getMyPumps: vi.fn() } }))

const PHONE_WIDTH = 375

function signIn(role: UserRole) {
  useAuthStore.setState({ user: { userId: 1, fullName: 'Someone', role, assignedPumpId: 3 }, isAuthenticated: true })
}

function renderDay(route = '/dashboard/day-close?date=2026-11-26') {
  renderWithProviders(<DayClosePage />, { route })
}

describe('Day close — the owner\'s view of a closed day', () => {
  resetScreenWidthAfterEach()

  beforeEach(() => {
    vi.clearAllMocks()
    signIn('OWNER')
    setScreenWidth(PHONE_WIDTH)
    usePumpStore.setState({ selectedPumpId: 3 })
    vi.mocked(pumpApi.getMyPumps).mockResolvedValue([{ id: 3, name: 'Highway Fuels' } as Awaited<ReturnType<typeof pumpApi.getMyPumps>>[number]])
    vi.mocked(dayCloseApi.list).mockResolvedValue(page([listItem()]))
    vi.mocked(dayCloseApi.getDetail).mockResolvedValue(detail())
    vi.mocked(dayCloseApi.approve).mockResolvedValue(detail({ status: 'APPROVED' }))
    vi.mocked(dayCloseApi.addComment).mockResolvedValue(detail())
    vi.mocked(dayCloseApi.reopen).mockResolvedValue(detail({ status: 'REOPENED' }))
    vi.mocked(dayCloseApi.getPreview).mockResolvedValue(preview())
  })

  afterEach(() => vi.restoreAllMocks())

  it('lists closed days waiting for approval with their flags and opens one', async () => {
    const user = userEvent.setup()
    renderDay('/dashboard/day-close')

    const days = await screen.findByRole('list', { name: 'Waiting for approval' })
    expect(dayCloseApi.list).toHaveBeenCalledWith(3, 0, 10, 'CLOSED')
    expect(within(days).getByText('Waiting for approval')).toBeInTheDocument()
    expect(within(days).getByText('Problem', { selector: '.sr-only' })).toBeInTheDocument()
    await user.click(within(days).getByRole('link', { name: /Thu, 26 Nov/ }))
    expect(await screen.findByRole('heading', { name: /Thursday, 26 November 2026/ })).toBeInTheDocument()
  })

  it('approves the day from a phone', async () => {
    const user = userEvent.setup()
    renderDay()

    expect(await screen.findByText('Closed by Meena on 27 Nov 2026, 10:05 am (version 1)')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the day\'s balance sheet' })).toHaveAttribute('href', '/dashboard/balance-sheets?sheet=42')
    await user.click(screen.getByRole('button', { name: 'Approve' }))
    expect(dayCloseApi.approve).toHaveBeenCalledWith(3, '2026-11-26')
  })

  it('asks the manager a question', async () => {
    const user = userEvent.setup()
    renderDay()

    await user.click(await screen.findByRole('button', { name: 'Ask a question' }))
    const dialog = screen.getByRole('dialog', { name: 'Ask a question' })
    await user.click(within(dialog).getByRole('button', { name: 'Send' }))
    expect(within(dialog).getByText('Write a message first.')).toBeInTheDocument()
    expect(dayCloseApi.addComment).not.toHaveBeenCalled()

    await user.type(within(dialog).getByLabelText('Message'), 'Why was tank T-1 short?')
    await user.click(within(dialog).getByRole('button', { name: 'Send' }))
    expect(dayCloseApi.addComment).toHaveBeenCalledWith(3, '2026-11-26', 'Why was tank T-1 short?')
  })

  it('needs a reason to re-open the day', async () => {
    const user = userEvent.setup()
    renderDay()

    await user.click(await screen.findByRole('button', { name: 'Re-open day' }))
    const dialog = screen.getByRole('dialog', { name: 'Re-open 26 Nov 2026' })
    await user.click(within(dialog).getByRole('button', { name: 'Re-open day' }))
    expect(within(dialog).getByText('A reason is required to re-open the day.')).toBeInTheDocument()
    expect(dayCloseApi.reopen).not.toHaveBeenCalled()

    await user.type(within(dialog).getByLabelText('Why re-open this day?'), 'Delivery invoice was wrong')
    await user.click(within(dialog).getByRole('button', { name: 'Re-open day' }))
    expect(dayCloseApi.reopen).toHaveBeenCalledWith(3, '2026-11-26', 'Delivery invoice was wrong')
  })

  it('shows the thread, and a manager can reply but not approve or re-open', async () => {
    signIn('MANAGER')
    vi.mocked(dayCloseApi.getDetail).mockResolvedValue(detail({
      comments: [{ id: 1, kind: 'QUESTION', message: 'Why was tank T-1 short?', authorName: 'Owner', createdAt: '2026-11-27T06:00:00Z' }],
    }))
    renderDay()

    expect(await screen.findByText('Why was tank T-1 short?')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reply' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Re-open day' })).not.toBeInTheDocument()
  })

  it('prints the summary with the pump named for the page', async () => {
    const print = vi.spyOn(window, 'print').mockImplementation(() => {})
    const user = userEvent.setup()
    renderDay()

    expect(await screen.findByText('Day summary · Highway Fuels ·')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Print' }))
    await vi.waitFor(() => expect(print).toHaveBeenCalled())
  })

  it('opens an earlier version of the summary', async () => {
    vi.mocked(dayCloseApi.getDetail).mockResolvedValue(detail({
      version: 2,
      versions: [
        { version: 2, closedByName: 'Meena', closedAt: '2026-11-28T04:35:00Z' },
        { version: 1, closedByName: 'Meena', closedAt: '2026-11-27T04:35:00Z' },
      ],
    }))
    vi.mocked(dayCloseApi.getVersion).mockResolvedValue(summary)
    const user = userEvent.setup()
    renderDay()

    await user.click(await screen.findByRole('button', { name: 'Show' }))
    expect(dayCloseApi.getVersion).toHaveBeenCalledWith(3, '2026-11-26', 1)
    expect(await screen.findByText('You are looking at version 1. The current close is version 2.')).toBeInTheDocument()
  })

  it('shows the close view for a day that was never closed', async () => {
    vi.mocked(dayCloseApi.getDetail).mockRejectedValue({ isAxiosError: true, response: { status: 404, data: {} } })
    renderDay()
    expect(await screen.findByRole('list', { name: 'Must be done' })).toBeInTheDocument()
    expect(dayCloseApi.getPreview).toHaveBeenCalledWith(3, '2026-11-26')
  })
})
