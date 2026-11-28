import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { cashApi } from '../../api/cashApi'
import type { CashEvent } from '../../api/cashApi'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import CashDrawerPage from './CashDrawerPage'

vi.mock('../../api/cashApi', () => ({ cashApi: { getCashEvents: vi.fn(), recordCashEvent: vi.fn(), reverseCashEvent: vi.fn() } }))

function CurrentSearch() {
  return <output aria-label="Address">{useLocation().search}</output>
}

async function openEntryForm() {
  renderWithProviders(<><CashDrawerPage /><CurrentSearch /></>, { route: '/dashboard/cash?action=cash-entry' })
  const form = await screen.findByRole('dialog', { name: 'Record cash movement' })
  await within(form).findByText('₹2,000.00')
  return form
}

const handEntry: CashEvent = {
  id: 51, pumpId: 3, eventType: 'CASH_IN', amount: 400, description: 'Float from owner', eventDate: '2026-11-26',
  recordedByUserId: 1, createdAt: '2026-11-26T05:00:00Z', shiftId: null, recordedByHand: true, reversesEventId: null, reversedAt: null,
}

function withEvents(content: CashEvent[]) {
  vi.mocked(cashApi.getCashEvents).mockResolvedValue({
    currentBalance: 2000,
    events: { content, page: 0, pageSize: 10, totalElements: content.length, totalPages: 1, hasNext: false, hasPrevious: false },
  })
}

function figure(form: HTMLElement, label: string) {
  return within(form).getByText(label).nextElementSibling
}

describe('CashDrawerPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({ user: { userId: 1, fullName: 'Manager', role: 'MANAGER', assignedPumpId: 3 }, isAuthenticated: true })
    usePumpStore.setState({ selectedPumpId: 3 })
    vi.mocked(cashApi.getCashEvents).mockResolvedValue({
      currentBalance: 2000,
      events: { content: [], page: 0, pageSize: 10, totalElements: 0, totalPages: 0, hasNext: false, hasPrevious: false },
    })
  })

  it('opens the cash entry form from ?action=cash-entry and checks a cash-out against the drawer balance', async () => {
    const user = userEvent.setup()
    const form = await openEntryForm()
    await user.click(within(form).getByRole('button', { name: /^Event type/ }))
    await user.click(screen.getByRole('option', { name: 'Cash out' }))
    await user.click(within(form).getByLabelText('Amount'))
    await user.paste('2500')
    await user.click(within(form).getByLabelText('Description'))
    await user.paste('Bank deposit')
    await user.click(within(form).getByRole('button', { name: 'Review' }))

    expect(within(form).getByRole('alert')).toHaveTextContent('exceeds the current drawer balance')
    expect(cashApi.recordCashEvent).not.toHaveBeenCalled()

    await user.click(within(form).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('status', { name: 'Address' })).toBeEmptyDOMElement()
  })

  it('keeps Review disabled and says what is still needed until amount and description are in', async () => {
    const user = userEvent.setup()
    const form = await openEntryForm()
    const review = within(form).getByRole('button', { name: 'Review' })

    expect(review).toBeDisabled()
    expect(review).toHaveAccessibleDescription('Still needed Amount Description')

    await user.click(within(form).getByLabelText('Amount'))
    await user.paste('500')
    expect(review).toHaveAccessibleDescription('Still needed Description')
    await user.click(within(form).getByLabelText('Description'))
    await user.paste('Change float')
    expect(review).toBeEnabled()
    expect(review).not.toHaveAccessibleDescription()
  })

  it('shows what will be in the safe after a cash-in or cash-out as the amount is typed', async () => {
    const user = userEvent.setup()
    const form = await openEntryForm()

    expect(figure(form, 'In the safe after this')).toHaveTextContent('—')
    await user.click(within(form).getByLabelText('Amount'))
    await user.paste('750')
    expect(figure(form, 'In the safe after this')).toHaveTextContent('₹2,750.00')

    await user.click(within(form).getByRole('button', { name: /^Event type/ }))
    await user.click(screen.getByRole('option', { name: 'Cash out' }))
    expect(figure(form, 'In the safe after this')).toHaveTextContent('₹1,250.00')
  })

  it('shows the movement and the new balance on the review step before recording', async () => {
    const user = userEvent.setup()
    vi.mocked(cashApi.recordCashEvent).mockResolvedValue({} as Awaited<ReturnType<typeof cashApi.recordCashEvent>>)
    const form = await openEntryForm()
    await user.click(within(form).getByLabelText('Amount'))
    await user.paste('300')
    await user.click(within(form).getByLabelText('Description'))
    await user.paste('Change float')
    await user.click(within(form).getByRole('button', { name: 'Review' }))

    const review = await screen.findByRole('dialog', { name: 'Review cash movement' })
    expect(within(review).getByText('Cash in')).toBeInTheDocument()
    expect(figure(review, 'In the safe after this')).toHaveTextContent('₹2,300.00')
    await user.click(within(review).getByRole('button', { name: 'Record movement' }))
    expect(cashApi.recordCashEvent).toHaveBeenCalledWith(3, expect.objectContaining({ eventType: 'CASH_IN', amount: 300, description: 'Change float' }))
  })

  it('keeps the opening balance choice for owners and admins only', async () => {
    const user = userEvent.setup()
    const form = await openEntryForm()
    await user.click(within(form).getByRole('button', { name: /^Event type/ }))
    expect(screen.queryByRole('option', { name: 'Opening balance' })).not.toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Cash out' })).toBeInTheDocument()
  })

  it('lets an owner reverse a movement typed by hand, with a reason', async () => {
    const user = userEvent.setup()
    useAuthStore.setState({ user: { userId: 2, fullName: 'Owner', role: 'OWNER', assignedPumpId: 3 }, isAuthenticated: true })
    withEvents([handEntry, { ...handEntry, id: 52, description: 'Shift close', recordedByHand: false }])
    vi.mocked(cashApi.reverseCashEvent).mockResolvedValue({ ...handEntry, id: 53, eventType: 'CASH_OUT' } as never)
    renderWithProviders(<CashDrawerPage />, { route: '/dashboard/cash' })

    expect(await screen.findAllByRole('button', { name: /^Reverse / })).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: 'Reverse Float from owner' }))
    const dialog = screen.getByRole('dialog', { name: 'Reverse cash movement' })
    await user.click(within(dialog).getByRole('button', { name: 'Reverse movement' }))
    expect(within(dialog).getByText('Give a reason for the reversal.')).toBeInTheDocument()
    expect(cashApi.reverseCashEvent).not.toHaveBeenCalled()

    await user.click(within(dialog).getByLabelText('Why it is reversed'))
    await user.paste('Typed twice')
    await user.click(within(dialog).getByRole('button', { name: 'Reverse movement' }))
    expect(cashApi.reverseCashEvent).toHaveBeenCalledWith(3, 51, 'Typed twice')
  })

  it('marks reversed movements and reversal entries and offers no reverse to a manager', async () => {
    withEvents([{ ...handEntry, reversedAt: '2026-11-26T06:00:00Z' }, { ...handEntry, id: 60, eventType: 'CASH_OUT', reversesEventId: 51, recordedByHand: false }])
    renderWithProviders(<CashDrawerPage />, { route: '/dashboard/cash' })

    expect(await screen.findByText('Reversed')).toBeInTheDocument()
    expect(screen.getByText('Reversal')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Reverse / })).not.toBeInTheDocument()
  })
})
