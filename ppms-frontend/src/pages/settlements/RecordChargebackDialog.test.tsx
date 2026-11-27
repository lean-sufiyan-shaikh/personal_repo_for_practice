import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { paymentSettlementApi } from '../../api/paymentSettlementApi'
import type { Settlement } from '../../api/paymentSettlementApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import { RecordChargebackDialog } from './RecordChargebackDialog'
import { SettlementRecordRow } from './SettlementRecordRow'

vi.mock('../../api/paymentSettlementApi', () => ({ paymentSettlementApi: { recordChargeback: vi.fn() } }))

const settlement: Settlement = {
  id: 31, pumpId: 3, paymentType: 'CARD', settlementDate: '2026-11-21', amountReceived: 4500, notes: null,
  grossAmount: 4500, mdrAmount: 81, gstOnMdrAmount: 14.58, chargesAmount: 95.58, netAmount: 4404.42,
  chargebacksAmount: 400, netAfterChargebacks: 4004.42, coversFromDate: null, coversToDate: null,
  bankReference: null, recordedByUserName: 'Ravi', createdAt: '2026-11-21T06:30:00Z',
  pendingAtRecordTime: null, isPartial: false, voidStatus: 'ACTIVE', voidReason: null, voidedAt: null,
}

describe('RecordChargebackDialog', () => {
  beforeEach(() => {
    vi.mocked(paymentSettlementApi.recordChargeback).mockReset().mockResolvedValue({} as never)
  })

  it('takes a chargeback against a settlement, no more than what is still in the bank', async () => {
    const user = userEvent.setup()
    renderWithProviders(<RecordChargebackDialog pumpId={3} settlement={settlement} onClose={vi.fn()} />)
    const dialog = screen.getByRole('dialog', { name: 'Chargeback or refund' })
    const save = within(dialog).getByRole('button', { name: 'Record' })

    await user.type(within(dialog).getByLabelText('Amount'), '5000')
    expect(within(dialog).getByText('At most ₹4,004.42 is left of this settlement')).toBeInTheDocument()
    expect(save).toBeDisabled()

    await user.clear(within(dialog).getByLabelText('Amount'))
    await user.type(within(dialog).getByLabelText('Amount'), '250')
    await user.type(within(dialog).getByLabelText('Reason'), 'Card holder disputed')
    await user.click(save)

    expect(paymentSettlementApi.recordChargeback).toHaveBeenCalledWith(3, expect.objectContaining({
      kind: 'CHARGEBACK', amount: 250, reason: 'Card holder disputed', settlementId: 31,
    }), expect.any(String))
  })

  it('asks for the day collected when no settlement is picked', async () => {
    renderWithProviders(<RecordChargebackDialog pumpId={3} settlement={null} onClose={vi.fn()} />)
    const dialog = screen.getByRole('dialog', { name: 'Chargeback or refund' })

    expect(within(dialog).getByLabelText('Collected on')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Record' })).toHaveAccessibleDescription(/Collection date/)
  })

  it('shows on the settlement row what chargebacks took and what is left', () => {
    renderWithProviders(<SettlementRecordRow settlement={settlement} canVoid={false} onVoid={vi.fn()} onOpenDetails={vi.fn()} />)

    expect(screen.getByText('Chargebacks ₹400.00 · ₹4,004.42 left in the bank')).toBeInTheDocument()
  })
})
