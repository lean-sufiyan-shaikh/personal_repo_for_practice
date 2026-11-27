import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { supplierApi } from '../../api/supplierApi'
import type { Supplier, SupplierBalance, SupplierLedger, SupplierPayment } from '../../api/supplierApi'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import SuppliersPage from './SuppliersPage'

vi.mock('../../api/supplierApi', () => ({
  SUPPLIER_PAYMENT_MODES: ['BANK_TRANSFER', 'CHEQUE', 'UPI', 'CASH', 'OTHER'],
  supplierApi: {
    list: vi.fn(), getBalances: vi.fn(), getLedger: vi.fn(), listPayments: vi.fn(), makeDefault: vi.fn(),
    create: vi.fn(), update: vi.fn(), recordPayment: vi.fn(), voidPayment: vi.fn(),
  },
}))

const aging = { days0To15: 30000, days16To30: 0, days31To60: 12000, over60Days: 0 }

const iocl: Supplier = {
  id: 4, pumpId: 3, name: 'IOCL Depot', contactName: null, phone: null, email: null, notes: null, active: true,
  openingBalance: 0, openingBalanceDate: null, defaultSupplier: true, createdAt: '2026-01-01T00:00:00Z',
}

const balance: SupplierBalance = {
  supplierId: 4, supplierName: 'IOCL Depot', active: true, defaultSupplier: true, openingBalance: 0,
  openingBalanceDate: null, totalDebit: 92000, totalCredit: 50000, outstanding: 42000, advance: 0, aging,
  lastDeliveryDate: '2026-11-20', lastPaymentDate: '2026-11-21',
}

const ledger: SupplierLedger = {
  supplierId: 4, supplierName: 'IOCL Depot', active: true, defaultSupplier: true, openingBalance: 0,
  openingBalanceDate: null, totalDebit: 92000, totalCredit: 50000, balance: 42000, outstanding: 42000, advance: 0,
  aging, asOf: '2026-11-28',
  entries: [
    { date: '2026-11-20', type: 'DELIVERY', reference: 'INV-9', description: 'PETROL 1000.00 L', debit: 92000, credit: 0,
      balance: 92000, paymentId: null, paymentMode: null, deliveryIds: [7] },
    { date: '2026-11-21', type: 'PAYMENT', reference: 'UTR-1', description: null, debit: 0, credit: 50000,
      balance: 42000, paymentId: 31, paymentMode: 'BANK_TRANSFER', deliveryIds: [] },
  ],
}

const payment: SupplierPayment = {
  id: 31, pumpId: 3, supplierId: 4, supplierName: 'IOCL Depot', deliveryId: null, amount: 50000,
  paymentDate: '2026-11-21', paymentMode: 'BANK_TRANSFER', referenceNo: 'UTR-1', notes: null, recordedByUserId: 1,
  recordedByName: 'Owner', createdAt: '2026-11-21T06:00:00Z', voidStatus: 'ACTIVE', voidReason: null, voidedAt: null,
  cashEventId: null,
}

function signIn(role: 'OWNER' | 'MANAGER') {
  useAuthStore.setState({ user: { userId: 1, fullName: 'User', role, assignedPumpId: null }, isAuthenticated: true })
}

describe('SuppliersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    signIn('OWNER')
    usePumpStore.setState({ selectedPumpId: 3 })
    vi.mocked(supplierApi.list).mockResolvedValue([iocl])
    vi.mocked(supplierApi.getBalances).mockResolvedValue({
      suppliers: [balance], unassigned: { invoiceCount: 2, amount: 15000 }, totalOutstanding: 42000, totalAdvance: 0,
    })
    vi.mocked(supplierApi.getLedger).mockResolvedValue(ledger)
    vi.mocked(supplierApi.listPayments).mockResolvedValue({
      content: [payment, { ...payment, id: 32, amount: 900, voidStatus: 'VOIDED', voidReason: 'Wrong supplier' }],
      page: 0, pageSize: 50, totalElements: 2, totalPages: 1, hasNext: false, hasPrevious: false,
    })
  })

  it('lists each supplier with what is outstanding, how old it is, and the deliveries booked to no supplier', async () => {
    renderWithProviders(<SuppliersPage />, { route: '/dashboard/suppliers' })

    const row = (await screen.findByRole('button', { name: 'IOCL Depot' })).closest('tr') as HTMLElement
    expect(within(row).getByText('Default')).toBeInTheDocument()
    expect(within(row).getAllByText('₹42,000.00').length).toBeGreaterThan(0)
    expect(within(row).getByText('₹12,000.00')).toBeInTheDocument()
    expect(screen.getByText(/2 tanker invoices worth ₹15,000.00 are not booked to any supplier/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add supplier' })).toBeInTheDocument()
  })

  it('opens a supplier ledger with its running balance, payments and voided payments', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SuppliersPage />, { route: '/dashboard/suppliers' })

    await user.click(await screen.findByRole('button', { name: 'IOCL Depot' }))

    expect(await screen.findByRole('heading', { name: 'IOCL Depot', level: 1 })).toBeInTheDocument()
    const ledgerTable = screen.getByRole('table', { name: 'Ledger' })
    expect(within(ledgerTable).getByText('Tanker invoice')).toBeInTheDocument()
    expect(within(ledgerTable).getByText('Bank transfer')).toBeInTheDocument()
    expect(within(ledgerTable).getAllByText('₹42,000.00').length).toBeGreaterThan(0)
    expect(screen.getByText('Voided: Wrong supplier')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Void' })).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Export CSV' })).toBeEnabled()
  })

  it('lets a manager read the ledger but not pay, void or edit', async () => {
    signIn('MANAGER')
    renderWithProviders(<SuppliersPage />, { route: '/dashboard/suppliers?supplier=4' })

    expect(await screen.findByRole('table', { name: 'Ledger' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Record payment' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Void' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
  })
})
