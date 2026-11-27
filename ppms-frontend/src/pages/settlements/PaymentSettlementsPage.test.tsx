import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { paymentSettlementApi } from '../../api/paymentSettlementApi'
import type { Settlement } from '../../api/paymentSettlementApi'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import PaymentSettlementsPage from './PaymentSettlementsPage'

vi.mock('../../api/paymentSettlementApi', () => ({
  paymentSettlementApi: { getWallet: vi.fn(), getPendingByAge: vi.fn(), getDailySummary: vi.fn(), getConfigs: vi.fn() },
}))

const settlement: Settlement = {
  id: 31, pumpId: 3, paymentType: 'CARD', settlementDate: '2026-11-21', amountReceived: 4500, notes: 'Morning batch',
  grossAmount: 4500, mdrAmount: 81, gstOnMdrAmount: 14.58, chargesAmount: 95.58, netAmount: 4404.42,
  coversFromDate: '2026-11-19', coversToDate: '2026-11-20',
  bankReference: 'UTR778812', recordedByUserName: 'Ravi Manager', createdAt: '2026-11-21T06:30:00Z',
  pendingAtRecordTime: 5000, isPartial: true, voidStatus: 'ACTIVE', voidReason: null, voidedAt: null,
}

function CurrentSearch() {
  return <output aria-label="Address">{useLocation().search}</output>
}

describe('PaymentSettlementsPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: { userId: 1, fullName: 'Owner', role: 'OWNER', assignedPumpId: null }, isAuthenticated: true })
    usePumpStore.setState({ selectedPumpId: 3 })
    vi.mocked(paymentSettlementApi.getWallet).mockResolvedValue({ upiPending: 500, cardPending: 0, fleetCardPending: 0, totalPending: 500 })
    vi.mocked(paymentSettlementApi.getPendingByAge).mockResolvedValue([])
    vi.mocked(paymentSettlementApi.getConfigs).mockResolvedValue([])
    vi.mocked(paymentSettlementApi.getDailySummary).mockResolvedValue({
      content: [{
        date: '2026-11-21', upiCollected: 0, cardCollected: 5000, fleetCardCollected: 0,
        upiSettled: 0, cardSettled: 4500, fleetCardSettled: 0, settlements: [settlement],
      }],
      page: 0, pageSize: 10, totalElements: 1, totalPages: 1, hasNext: false, hasPrevious: false,
    })
  })

  it('opens the record form from ?action=record-settlement and clears the action when cancelled', async () => {
    const user = userEvent.setup()
    renderWithProviders(<><PaymentSettlementsPage /><CurrentSearch /></>, { route: '/dashboard/settlements?action=record-settlement' })

    const dialog = await screen.findByRole('dialog', { name: 'Record settlement' })
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('status', { name: 'Address' })).toBeEmptyDOMElement()
  })

  it('shows each settlement as gross with its bank charges, net and the collection days it covers', async () => {
    renderWithProviders(<PaymentSettlementsPage />, { route: '/dashboard/settlements' })

    expect(await screen.findByText('+₹4,500.00')).toBeInTheDocument()
    expect(screen.getByText('Net ₹4,404.42 after ₹95.58 bank charges')).toBeInTheDocument()
    expect(screen.getByText('Covers 19–20 Nov')).toBeInTheDocument()
    expect(screen.getByText('20 Nov partly cleared')).toBeInTheDocument()
  })

  it('links owners to bank reconciliation', async () => {
    renderWithProviders(<PaymentSettlementsPage />, { route: '/dashboard/settlements' })
    expect(await screen.findByRole('link', { name: 'Bank reconciliation' })).toHaveAttribute('href', '/dashboard/bank-reconciliation')
  })

  it('hides the bank reconciliation link from managers', async () => {
    useAuthStore.setState({ user: { userId: 2, fullName: 'Manager', role: 'MANAGER', assignedPumpId: 3 }, isAuthenticated: true })
    renderWithProviders(<PaymentSettlementsPage />, { route: '/dashboard/settlements' })
    await screen.findByText('+₹4,500.00')
    expect(screen.queryByRole('link', { name: 'Bank reconciliation' })).not.toBeInTheDocument()
  })

  it('shows a settlement in a side panel kept in the address, with its bank reference and the void action', async () => {
    const user = userEvent.setup()
    renderWithProviders(<><PaymentSettlementsPage /><CurrentSearch /></>, { route: '/dashboard/settlements' })

    await user.click(await screen.findByRole('button', { name: /Details of Card settlement of ₹4,500.00/ }))

    const panel = screen.getByRole('dialog', { name: 'Settlement details' })
    expect(screen.getByRole('status', { name: 'Address' })).toHaveTextContent('settlement=31')
    expect(within(panel).getByText('UTR778812')).toBeInTheDocument()
    expect(within(panel).getByText('Ravi Manager')).toBeInTheDocument()
    expect(within(panel).getByText('20 Nov partly cleared')).toBeInTheDocument()
    expect(within(panel).getByText('Net to bank').nextSibling).toHaveTextContent('₹4,404.42')
    expect(within(panel).getByText('Total bank charges').nextSibling).toHaveTextContent('₹95.58')

    await user.click(within(panel).getByRole('button', { name: 'Void' }))
    expect(screen.queryByRole('dialog', { name: 'Settlement details' })).not.toBeInTheDocument()
    expect(screen.getByRole('status', { name: 'Address' })).not.toHaveTextContent('settlement=')
  })

  it('switches to alert settings with a tab link kept in the address', async () => {
    const user = userEvent.setup()
    renderWithProviders(<><PaymentSettlementsPage /><CurrentSearch /></>, { route: '/dashboard/settlements' })

    expect(await screen.findByRole('link', { name: 'Settlement History' })).toHaveAttribute('aria-current', 'page')
    await user.click(screen.getByRole('link', { name: 'Alerts & Charges' }))

    expect(screen.getByRole('status', { name: 'Address' })).toHaveTextContent('?tab=config')
    expect(screen.getByRole('heading', { name: 'Alerts & Charges' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Settlement History' })).not.toBeInTheDocument()
  })
})
