import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { dayCloseApi } from '../../../api/dayCloseApi'
import { pumpApi } from '../../../api/pumpApi'
import type { UserRole } from '../../../types/auth'
import { useAuthStore } from '../../../store/authStore'
import { usePumpStore } from '../../../store/usePumpStore'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { localDateInputValue } from '../../../utils/date'
import DayClosePage from '../DayClosePage'
import { detail, listItem, openShiftsWarning, page, preview, pricesDone, pricesMissing, safeCountDone, safeCountMissing } from '../dayCloseTestData'

vi.mock('../../../api/dayCloseApi', () => ({
  dayCloseApi: {
    getPreview: vi.fn(), list: vi.fn(), getDetail: vi.fn(), getVersion: vi.fn(), close: vi.fn(),
    approve: vi.fn(), addComment: vi.fn(), reopen: vi.fn(),
  },
}))
vi.mock('../../../api/pumpApi', () => ({ pumpApi: { getMyPumps: vi.fn() } }))

function signIn(role: UserRole) {
  useAuthStore.setState({ user: { userId: 1, fullName: 'Someone', role, assignedPumpId: 3 }, isAuthenticated: true })
}

function renderClose(date = '2026-11-26') {
  renderWithProviders(<DayClosePage />, { route: `/dashboard/day-close?view=close&date=${date}` })
}

describe('Day close — closing a day', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    signIn('MANAGER')
    usePumpStore.setState({ selectedPumpId: 3 })
    vi.mocked(pumpApi.getMyPumps).mockResolvedValue([])
    vi.mocked(dayCloseApi.list).mockResolvedValue(page([]))
    vi.mocked(dayCloseApi.getPreview).mockResolvedValue(preview())
    vi.mocked(dayCloseApi.close).mockResolvedValue(detail())
    vi.mocked(dayCloseApi.getDetail).mockResolvedValue(detail())
  })

  it('keeps Close day disabled and says what is still needed while a blocking check is missing', async () => {
    vi.mocked(dayCloseApi.getPreview).mockResolvedValue(preview({ canClose: false, checks: [safeCountMissing, pricesMissing, openShiftsWarning] }))
    renderClose()

    const mustDo = await screen.findByRole('list', { name: 'Must be done' })
    expect(within(mustDo).getByText('Count the safe — no safe count was recorded on this day')).toBeInTheDocument()
    expect(within(mustDo).getByText('Set or confirm the next day\'s fuel prices (Petrol, Diesel)')).toBeInTheDocument()
    expect(within(mustDo).getByRole('link', { name: 'Count safe' })).toHaveAttribute('href', '/dashboard/cash?tab=safe')
    expect(within(mustDo).getByRole('link', { name: 'Set prices' })).toHaveAttribute('href', '/dashboard/setup?section=prices')

    const warnings = screen.getByRole('list', { name: 'Warnings' })
    expect(within(warnings).getByText('2 shifts are still open — they are left out of this day\'s figures')).toBeInTheDocument()

    const closeButton = screen.getByRole('button', { name: /^Close 26 Nov 2026/ })
    expect(closeButton).toBeDisabled()
    expect(screen.getByText('Still needed')).toBeInTheDocument()
    expect(closeButton).toHaveAccessibleDescription(/Count the safe/)
  })

  it('shows the blocking checks as done and no warnings when the day is ready', async () => {
    renderClose()
    const mustDo = await screen.findByRole('list', { name: 'Must be done' })
    expect(within(mustDo).getByText('The safe was counted on this day')).toBeInTheDocument()
    expect(within(mustDo).getByText('Next day\'s fuel prices are set or confirmed')).toBeInTheDocument()
    expect(within(mustDo).queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText('No warnings for this day.')).toBeInTheDocument()
    expect(screen.queryByText('Still needed')).not.toBeInTheDocument()
  })

  it('previews the summary with each section\'s flag and its reason in words', async () => {
    renderClose()
    const sales = await screen.findByRole('region', { name: 'Sales & margin' })
    expect(within(sales).getByText('All good')).toBeInTheDocument()
    expect(within(sales).getByText('All 3 shifts of the day are closed and counted')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Cash & payments' })).getByText('1 shift(s) came up short by ₹150.00 in total')).toBeInTheDocument()
    const stock = screen.getByRole('region', { name: 'Stock & deliveries' })
    expect(within(stock).getByText('Problem')).toBeInTheDocument()
    expect(within(stock).getByText('Tank T-1 is off by -80.00 L, outside its tolerance')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Credit & alerts' })).toBeInTheDocument()
  })

  it('lets a manager close the day, without approving it', async () => {
    const user = userEvent.setup()
    renderClose()
    expect(await screen.findByRole('list', { name: 'Must be done' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Close and approve' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Close 26 Nov 2026' }))
    expect(dayCloseApi.close).toHaveBeenCalledWith(3, '2026-11-26', false)
    expect(await screen.findByRole('heading', { name: /Thursday, 26 November 2026/ })).toBeInTheDocument()
  })

  it('lets an owner close and approve in one step', async () => {
    signIn('OWNER')
    vi.mocked(dayCloseApi.close).mockResolvedValue(detail({ status: 'APPROVED' }))
    const user = userEvent.setup()
    renderClose()

    await user.click(await screen.findByRole('button', { name: 'Close and approve' }))
    expect(dayCloseApi.close).toHaveBeenCalledWith(3, '2026-11-26', true)
  })

  it('shows the error the server gives when the close is refused', async () => {
    vi.mocked(dayCloseApi.close).mockRejectedValue({
      isAxiosError: true,
      response: { status: 400, data: { code: 'DAY_CLOSE_SAFE_COUNT_MISSING', message: '2026-11-26 cannot be closed: no safe count was recorded on that day.' } },
    })
    const user = userEvent.setup()
    renderClose()

    await user.click(await screen.findByRole('button', { name: 'Close 26 Nov 2026' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('no safe count was recorded')
  })

  it('shows a closed day\'s status instead of the checklist and the close buttons', async () => {
    vi.mocked(dayCloseApi.getPreview).mockResolvedValue(preview({ status: 'CLOSED', canClose: false, checks: [safeCountDone, pricesDone] }))
    renderClose()

    expect(await screen.findByText('26 Nov 2026 is closed and waiting for the owner\'s approval.')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Must be done' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Close/ })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the summary' })).toHaveAttribute('href', '/dashboard/day-close?date=2026-11-26')
  })

  it('starts on yesterday until it is closed, then on today', async () => {
    renderWithProviders(<DayClosePage />, { route: '/dashboard/day-close?view=close' })
    expect(await screen.findByLabelText('Day to close')).toHaveValue(localDateInputValue(-1))
  })

  it('starts on today once yesterday is closed', async () => {
    vi.mocked(dayCloseApi.list).mockResolvedValue(page([listItem({ businessDate: localDateInputValue(-1), status: 'APPROVED' })]))
    renderWithProviders(<DayClosePage />, { route: '/dashboard/day-close?view=close' })
    await vi.waitFor(() => expect(dayCloseApi.getPreview).toHaveBeenLastCalledWith(3, localDateInputValue()))
  })
})
