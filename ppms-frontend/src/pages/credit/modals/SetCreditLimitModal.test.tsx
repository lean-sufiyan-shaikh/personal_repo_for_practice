import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { creditApi } from '../../../api/creditApi'
import type { CreditClient } from '../../../api/creditApi'
import { useAuthStore } from '../../../store/authStore'
import type { UserRole } from '../../../types/auth'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { SetCreditLimitModal } from './SetCreditLimitModal'

vi.mock('../../../api/creditApi', () => ({
  creditApi: {
    updateCreditLimit: vi.fn(),
    getCreditLimitChanges: vi.fn(),
    getCreditLimitSettings: vi.fn(),
  },
}))

const PUMP_ID = 3
const client: CreditClient = {
  id: 40, pumpId: PUMP_ID, name: 'Acme Transport', phone: '9876543210', notes: null,
  creditLimit: 50_000, unlimited: false, outstandingBalance: 12_000, outstandingInterest: 0, totalInterestRecovered: 0,
  monthlyInterestRate: 0, interestGraceDays: 1, createdAt: '2026-01-01T00:00:00Z',
  parentClientId: null, parentClientName: null, isParent: false,
}

function signInAs(role: UserRole) {
  useAuthStore.setState({ user: { userId: 1, fullName: 'Someone', role, assignedPumpId: PUMP_ID }, isAuthenticated: true })
}

function figures() {
  return within(screen.getByLabelText('Effect of the new limit'))
}

describe('SetCreditLimitModal', () => {
  beforeEach(() => {
    signInAs('OWNER')
    vi.mocked(creditApi.updateCreditLimit).mockReset()
    vi.mocked(creditApi.updateCreditLimit).mockResolvedValue({} as never)
    vi.mocked(creditApi.getCreditLimitChanges).mockResolvedValue([])
    vi.mocked(creditApi.getCreditLimitSettings).mockResolvedValue({ defaultCreditLimit: 0, managerLimitCap: 50_000, nearLimitPercent: 90 })
  })

  it('reads 0 as no credit allowed, not as no limit, and asks why the limit changes', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SetCreditLimitModal client={client} pumpId={PUMP_ID} onClose={vi.fn()} />)

    const limit = screen.getByLabelText('Credit limit')
    await user.clear(limit)
    await user.type(limit, '0')
    expect(figures().getByText('New limit').nextSibling).toHaveTextContent('₹0.00')

    await user.click(screen.getByRole('button', { name: 'Save limit' }))
    expect(screen.getByText('Say why the limit is changing.')).toBeInTheDocument()
    expect(creditApi.updateCreditLimit).not.toHaveBeenCalled()

    await user.type(screen.getByLabelText('Reason'), 'Stopped paying')
    await user.click(screen.getByRole('button', { name: 'Save limit' }))
    expect(creditApi.updateCreditLimit).toHaveBeenCalledWith(PUMP_ID, client.id,
      { creditLimit: 0, unlimited: false, reason: 'Stopped paying' })
  })

  it('lets an owner give unlimited credit', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SetCreditLimitModal client={client} pumpId={PUMP_ID} onClose={vi.fn()} />)

    await user.click(screen.getByLabelText('No limit (unlimited credit)'))
    expect(screen.queryByLabelText('Credit limit')).not.toBeInTheDocument()
    expect(figures().getByText('New limit').nextSibling).toHaveTextContent('No limit')

    await user.type(screen.getByLabelText('Reason'), 'Government fleet')
    await user.click(screen.getByRole('button', { name: 'Save limit' }))
    expect(creditApi.updateCreditLimit).toHaveBeenCalledWith(PUMP_ID, client.id,
      { creditLimit: 0, unlimited: true, reason: 'Government fleet' })
  })

  it('shows a manager the highest limit they may set and keeps unlimited credit for an Owner or Admin', async () => {
    signInAs('MANAGER')
    const user = userEvent.setup()
    renderWithProviders(<SetCreditLimitModal client={client} pumpId={PUMP_ID} onClose={vi.fn()} />)

    expect(screen.getByLabelText('No limit (unlimited credit)')).toBeDisabled()
    expect(screen.getByText('Only an Owner or Admin can give unlimited credit.')).toBeInTheDocument()
    expect(await screen.findByText('You can set up to ₹50,000.00. A higher limit needs an Owner or Admin.')).toBeInTheDocument()

    const limit = screen.getByLabelText('Credit limit')
    await user.clear(limit)
    await user.type(limit, '60000')
    await user.type(screen.getByLabelText('Reason'), 'Bigger orders')
    await user.click(screen.getByRole('button', { name: 'Save limit' }))

    expect(screen.getByText(/Managers can set a limit up to ₹50,000.00/)).toBeInTheDocument()
    expect(creditApi.updateCreditLimit).not.toHaveBeenCalled()
  })
})
