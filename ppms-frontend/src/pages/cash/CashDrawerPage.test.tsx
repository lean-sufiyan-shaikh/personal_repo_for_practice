import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { cashApi } from '../../api/cashApi'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import CashDrawerPage from './CashDrawerPage'

vi.mock('../../api/cashApi', () => ({ cashApi: { getCashEvents: vi.fn(), recordCashEvent: vi.fn() } }))

function CurrentSearch() {
  return <output aria-label="Address">{useLocation().search}</output>
}

describe('CashDrawerPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: { userId: 1, fullName: 'Manager', role: 'MANAGER', assignedPumpId: 3 }, isAuthenticated: true })
    usePumpStore.setState({ selectedPumpId: 3 })
    vi.mocked(cashApi.getCashEvents).mockResolvedValue({
      currentBalance: 2000,
      events: { content: [], page: 0, pageSize: 10, totalElements: 0, totalPages: 0, hasNext: false, hasPrevious: false },
    })
  })

  it('opens the cash entry form from ?action=cash-entry and checks a cash-out against the drawer balance', async () => {
    const user = userEvent.setup()
    renderWithProviders(<><CashDrawerPage /><CurrentSearch /></>, { route: '/dashboard/cash?action=cash-entry' })

    const form = await screen.findByRole('dialog', { name: 'Record cash movement' })
    await user.click(within(form).getByRole('button', { name: /^Event Type/ }))
    await user.click(screen.getByRole('option', { name: 'Cash Out' }))
    await user.type(within(form).getByLabelText('Amount (₹)'), '2500')
    await user.type(within(form).getByLabelText('Description'), 'Bank deposit')
    await user.click(within(form).getByRole('button', { name: 'Review Event' }))

    expect(within(form).getByRole('alert')).toHaveTextContent('exceeds the current drawer balance')
    expect(cashApi.recordCashEvent).not.toHaveBeenCalled()

    await user.click(within(form).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('status', { name: 'Address' })).toBeEmptyDOMElement()
  })
})
