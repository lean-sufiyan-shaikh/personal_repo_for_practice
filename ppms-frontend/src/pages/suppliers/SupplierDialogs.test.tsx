import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { supplierApi } from '../../api/supplierApi'
import type { Supplier, SupplierPayment } from '../../api/supplierApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import { RecordSupplierPaymentDialog } from './RecordSupplierPaymentDialog'
import { SupplierFormDialog } from './SupplierFormDialog'
import { VoidSupplierPaymentDialog } from './VoidSupplierPaymentDialog'

vi.mock('../../api/supplierApi', () => ({
  SUPPLIER_PAYMENT_MODES: ['BANK_TRANSFER', 'CHEQUE', 'UPI', 'CASH', 'OTHER'],
  supplierApi: { recordPayment: vi.fn(), voidPayment: vi.fn(), create: vi.fn(), update: vi.fn() },
}))

const supplier: Supplier = {
  id: 4, pumpId: 3, name: 'IOCL Depot', contactName: null, phone: null, email: null, notes: null, active: true,
  openingBalance: 0, openingBalanceDate: null, defaultSupplier: true, createdAt: '2026-01-01T00:00:00Z',
}

const payment: SupplierPayment = {
  id: 31, pumpId: 3, supplierId: 4, supplierName: 'IOCL Depot', deliveryId: null, amount: 4000,
  paymentDate: '2026-11-21', paymentMode: 'CASH', referenceNo: null, notes: null, recordedByUserId: 1,
  recordedByName: 'Owner', createdAt: '2026-11-21T06:00:00Z', voidStatus: 'ACTIVE', voidReason: null, voidedAt: null,
  cashEventId: 9,
}

describe('RecordSupplierPaymentDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(supplierApi.recordPayment).mockResolvedValue(payment)
  })

  it('records a cheque payment with an idempotency key and resends a failed attempt with the same key', async () => {
    vi.mocked(supplierApi.recordPayment).mockRejectedValueOnce(new Error('timeout'))
    const onClose = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(<RecordSupplierPaymentDialog pumpId={3} supplier={supplier} outstanding={42000} onClose={onClose} />)

    const record = screen.getByRole('button', { name: 'Record payment' })
    expect(record).toBeDisabled()
    await user.type(screen.getByLabelText('Amount paid'), '25000')
    await user.selectOptions(screen.getByLabelText('Payment mode'), 'Cheque')
    await user.type(screen.getByLabelText(/Cheque number/), 'CHQ-118')
    await user.click(record)
    await screen.findByRole('alert')
    await user.click(record)

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    const calls = vi.mocked(supplierApi.recordPayment).mock.calls
    expect(calls).toHaveLength(2)
    expect(calls[0][1]).toEqual(expect.objectContaining({ supplierId: 4, amount: 25000, paymentMode: 'CHEQUE', referenceNo: 'CHQ-118' }))
    expect(calls[0][2]).toMatch(/^[0-9a-f]{32}$/)
    expect(calls[1][2]).toBe(calls[0][2])
  })

  it('warns that a cash payment is taken out of the safe', async () => {
    const user = userEvent.setup()
    renderWithProviders(<RecordSupplierPaymentDialog pumpId={3} supplier={supplier} outstanding={0} onClose={vi.fn()} />)

    expect(screen.queryByText('A cash payment is taken out of the safe.')).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Payment mode'), 'Cash')
    expect(screen.getByText('A cash payment is taken out of the safe.')).toBeInTheDocument()
  })
})

describe('VoidSupplierPaymentDialog', () => {
  it('needs a reason, says cash goes back to the safe, and voids with the trimmed reason', async () => {
    vi.mocked(supplierApi.voidPayment).mockResolvedValue({ ...payment, voidStatus: 'VOIDED' })
    const onClose = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(<VoidSupplierPaymentDialog pumpId={3} payment={payment} onClose={onClose} />)

    expect(screen.getByText(/its cash goes back into the safe/)).toBeInTheDocument()
    const voidButton = screen.getByRole('button', { name: 'Void payment' })
    expect(voidButton).toBeDisabled()
    await user.type(screen.getByLabelText('Reason'), '  Paid by cheque instead ')
    await user.click(voidButton)

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(supplierApi.voidPayment).toHaveBeenCalledWith(3, 31, 'Paid by cheque instead')
  })
})

describe('SupplierFormDialog', () => {
  it('asks for the date of a non-zero opening balance and sends a negative balance as an advance', async () => {
    vi.mocked(supplierApi.create).mockResolvedValue(supplier)
    const user = userEvent.setup()
    renderWithProviders(<SupplierFormDialog pumpId={3} supplier={null} onClose={vi.fn()} />)

    await user.type(screen.getByLabelText('Supplier name'), 'BPCL')
    await user.type(screen.getByLabelText(/^Opening balance \(optional\)/), '-5000')
    const save = screen.getByRole('button', { name: 'Save supplier' })
    expect(save).toBeDisabled()
    await user.type(screen.getByLabelText('Opening balance as of'), '2026-11-01')
    await user.click(save)

    await waitFor(() => expect(supplierApi.create).toHaveBeenCalledWith(3, expect.objectContaining({
      name: 'BPCL', openingBalance: -5000, openingBalanceDate: '2026-11-01',
    })))
  })
})
