import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { paymentSettlementApi } from '../../api/paymentSettlementApi'
import type { Settlement, SettlementChargesQuery } from '../../api/paymentSettlementApi'
import { roundToPaise } from '../../utils/money'
import { renderWithProviders } from '../../test/renderWithProviders'
import { RecordSettlementDialog } from './RecordSettlementDialog'
import { VoidSettlementDialog } from './VoidSettlementDialog'

vi.mock('../../api/paymentSettlementApi', () => ({ paymentSettlementApi: { record: vi.fn(), voidSettlement: vi.fn(), getCharges: vi.fn() } }))

const MDR_PERCENT = { UPI: 0, CARD: 1.8, FLEET_CARD: 0 }

function chargesFor(_pumpId: number, query: SettlementChargesQuery) {
  const mdrPercent = MDR_PERCENT[query.paymentType]
  const chargeShare = (mdrPercent / 100) * 1.18
  const grossAmount = query.amountBasis === 'GROSS' ? query.amount : roundToPaise(query.amount / (1 - chargeShare))
  const mdrAmount = query.mdrAmount ?? roundToPaise(grossAmount * mdrPercent / 100)
  const gstOnMdrAmount = query.gstOnMdrAmount ?? roundToPaise(mdrAmount * 0.18)
  const chargesAmount = roundToPaise(mdrAmount + gstOnMdrAmount)
  return Promise.resolve({
    grossAmount, mdrAmount, gstOnMdrAmount, chargesAmount, netAmount: roundToPaise(grossAmount - chargesAmount),
    mdrPercent, gstOnMdrPercent: 18,
  })
}

const wallet = { upiPending: 5000, cardPending: 1200, fleetCardPending: 0, totalPending: 6200 }

function figure(label: string) {
  return screen.getByText(label).closest('div')?.querySelector('dd')
}

async function passChargesDelay() {
  await act(() => vi.advanceTimersByTimeAsync(400))
}

function renderRecord() {
  renderWithProviders(<RecordSettlementDialog pumpId={3} wallet={wallet} pendingRanges={[]} initialType="UPI" onClose={vi.fn()} />)
}

describe('RecordSettlementDialog', () => {
  afterEach(() => vi.useRealTimers())

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.clearAllMocks()
    vi.mocked(paymentSettlementApi.record).mockResolvedValue({} as Settlement)
    vi.mocked(paymentSettlementApi.getCharges).mockImplementation(chargesFor)
  })

  it('shows what is still pending for the chosen type as the amount is typed, and when it is fully settled', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderRecord()

    expect(figure('UPI pending now')).toHaveTextContent('₹5,000.00')
    expect(figure('Still pending after this')).toHaveTextContent('—')

    await user.click(screen.getByLabelText('Amount received'))
    await user.paste('4500')
    expect(figure('This settlement')).toHaveTextContent('₹4,500.00')
    expect(figure('Still pending after this')).toHaveTextContent('₹500.00')
    await passChargesDelay()
    await waitFor(() => expect(figure('Net to bank')).toHaveTextContent('₹4,500.00'))

    await user.clear(screen.getByLabelText('Amount received'))
    await user.click(screen.getByLabelText('Amount received'))
    await user.paste('5000')
    expect(figure('Still pending after this')).toHaveTextContent('Fully settled')

    await user.click(screen.getByRole('button', { name: 'Card' }))
    expect(figure('Card pending now')).toHaveTextContent('₹1,200.00')
    expect(screen.getByLabelText('Amount received')).toHaveAccessibleDescription(/more than the ₹1,200.00 still pending for Card/)
  })

  it('keeps Record settlement disabled until an amount is entered, then records it', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderRecord()
    const record = screen.getByRole('button', { name: 'Record settlement' })

    expect(record).toBeDisabled()
    expect(record).toHaveAccessibleDescription('Still needed Amount received')

    await user.click(screen.getByLabelText('Amount received'))
    await user.paste('2000')
    await user.click(record)
    expect(paymentSettlementApi.record).toHaveBeenCalledWith(3, expect.objectContaining({ paymentType: 'UPI', amountReceived: 2000, amountBasis: 'NET' }))
  })

  it('refuses a zero amount', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderRecord()
    await user.type(screen.getByLabelText('Amount received'), '0')
    expect(screen.getByRole('button', { name: 'Record settlement' })).toBeDisabled()
    expect(paymentSettlementApi.getCharges).not.toHaveBeenCalled()
  })

  it('treats the amount as net by default and clears pending collections by the gross the server works out', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderRecord()
    await user.click(screen.getByRole('button', { name: 'Card' }))
    expect(screen.getByRole('button', { name: 'Net — what reached the bank' })).toHaveAttribute('aria-pressed', 'true')

    await user.click(screen.getByLabelText('Amount received'))
    await user.paste('978.76')

    await passChargesDelay()

    await waitFor(() => expect(figure('Gross amount')).toHaveTextContent('₹1,000.00'))
    expect(figure('MDR (1.8%)')).toHaveTextContent('₹18.00')
    expect(figure('GST on MDR (18%)')).toHaveTextContent('₹3.24')
    expect(figure('Total bank charges')).toHaveTextContent('₹21.24')
    expect(figure('Net to bank')).toHaveTextContent('₹978.76')
    expect(figure('This settlement')).toHaveTextContent('₹1,000.00')
    expect(figure('Still pending after this')).toHaveTextContent('₹200.00')
    expect(paymentSettlementApi.getCharges).toHaveBeenLastCalledWith(3, { paymentType: 'CARD', amount: 978.76, amountBasis: 'NET' })

    await user.click(screen.getByRole('button', { name: 'Record settlement' }))
    expect(paymentSettlementApi.record).toHaveBeenCalledWith(3, expect.objectContaining({ paymentType: 'CARD', amountReceived: 978.76, amountBasis: 'NET' }))
    expect(vi.mocked(paymentSettlementApi.record).mock.calls[0][1]).not.toHaveProperty('mdrAmount')
  })

  it('takes a gross amount and shows the net after charges', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderRecord()
    await user.click(screen.getByRole('button', { name: 'Card' }))
    await user.click(screen.getByRole('button', { name: 'Gross — before bank charges' }))
    await user.click(screen.getByLabelText('Amount received'))
    await user.paste('1000')

    await passChargesDelay()

    await waitFor(() => expect(figure('Net to bank')).toHaveTextContent('₹978.76'))
    await user.click(screen.getByRole('button', { name: 'Record settlement' }))
    expect(paymentSettlementApi.record).toHaveBeenCalledWith(3, expect.objectContaining({ amountReceived: 1000, amountBasis: 'GROSS' }))
  })

  it('sends both charges when the user overrides them', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderRecord()
    await user.click(screen.getByRole('button', { name: 'Card' }))
    await user.click(screen.getByRole('button', { name: 'Gross — before bank charges' }))
    await user.click(screen.getByLabelText('Amount received'))
    await user.paste('1000')
    await passChargesDelay()
    await waitFor(() => expect(figure('MDR (1.8%)')).toHaveTextContent('₹18.00'))

    await user.click(screen.getByRole('button', { name: 'Edit charges' }))
    expect(screen.getByLabelText('MDR charged')).toHaveValue(18)
    expect(screen.getByLabelText('GST on MDR charged')).toHaveValue(3.24)
    await user.clear(screen.getByLabelText('MDR charged'))
    await user.click(screen.getByLabelText('MDR charged'))
    await user.paste('20')
    await user.clear(screen.getByLabelText('GST on MDR charged'))
    await user.click(screen.getByLabelText('GST on MDR charged'))
    await user.paste('3.6')

    await passChargesDelay()

    await waitFor(() => expect(figure('Net to bank')).toHaveTextContent('₹976.40'))
    await user.click(screen.getByRole('button', { name: 'Record settlement' }))
    expect(paymentSettlementApi.record).toHaveBeenCalledWith(3, expect.objectContaining({
      amountReceived: 1000, amountBasis: 'GROSS', mdrAmount: 20, gstOnMdrAmount: 3.6,
    }))
  })
})

describe('VoidSettlementDialog', () => {
  const settlement = {
    id: 31, pumpId: 3, paymentType: 'UPI', settlementDate: '2026-11-20', amountReceived: 4500, notes: null,
    grossAmount: 4500, mdrAmount: 0, gstOnMdrAmount: 0, chargesAmount: 0, netAmount: 4500, chargebacksAmount: 0, netAfterChargebacks: 4500, coversFromDate: null, coversToDate: null,
    bankReference: 'UTR1', recordedByUserName: 'Ravi', createdAt: '2026-11-20T10:00:00Z', pendingAtRecordTime: 5000,
    isPartial: true, voidStatus: 'ACTIVE', voidReason: null, voidedAt: null,
  } as Settlement

  it('states how much the pending balance goes back up and needs a reason before voiding', async () => {
    const user = userEvent.setup()
    vi.mocked(paymentSettlementApi.voidSettlement).mockResolvedValue({} as Settlement)
    renderWithProviders(<VoidSettlementDialog pumpId={3} voidTarget={settlement} onClose={vi.fn()} />)

    expect(figure('UPI pending goes up by')).toHaveTextContent('+₹4,500.00')
    const voidButton = screen.getByRole('button', { name: 'Void settlement' })
    expect(voidButton).toBeDisabled()
    expect(voidButton).toHaveAccessibleDescription('Still needed Reason')

    await user.click(screen.getByLabelText('Reason'))
    await user.paste('Recorded twice')
    await user.click(voidButton)
    expect(paymentSettlementApi.voidSettlement).toHaveBeenCalledWith(3, 31, 'Recorded twice')
  })
})
