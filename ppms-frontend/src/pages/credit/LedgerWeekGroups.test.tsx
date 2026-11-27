import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CreditTransaction } from '../../api/creditApi'
import { changeAppLanguage } from '../../i18n'
import { resetScreenWidthAfterEach, setScreenWidth } from '../../test/screenWidth'
import type { LedgerRowActions } from './LedgerEntryActions'
import { LedgerWeekGroups } from './LedgerWeekGroups'

const actions: LedgerRowActions = {
  canMoveEntries: false, canWaiveInterest: false, canApprovePayments: false, canReversePayments: false, currentUserId: 1,
  pendingPaymentRecorders: new Map(), approvePayment: vi.fn(), onRejectPayment: vi.fn(), isApproving: false,
  onMove: vi.fn(), onWaiveInterest: vi.fn(), onReversePayment: vi.fn(),
  canIssueCreditNote: false, canRaiseDispute: false, canResolveDispute: false, onCreditNote: vi.fn(), onDispute: vi.fn(),
}

function row(overrides: Partial<CreditTransaction>): CreditTransaction {
  return {
    type: 'SALE', referenceId: 1, reference: null, referenceCode: null, referenceParams: {}, detail: null, amount: 100, runningBalance: 100,
    occurredAt: '2026-11-10T06:00:00Z', businessDate: '2026-11-10', status: 'ACTIVE', countedInBalance: true,
    ...overrides,
  }
}

describe('LedgerWeekGroups shop sales', () => {
  it('labels a shop sale on credit, shows what was sold and adds it to the week sales without a Move action', () => {
    render(
      <LedgerWeekGroups
        srcClientId={7}
        actions={{ ...actions, canMoveEntries: true }}
        groups={[{ label: 'Week', items: [
          row({ referenceId: 1, type: 'COUNTER_SALE', reference: 'Castrol Engine Oil 1 L × 2 (Bill B-42)', amount: 800 }),
          row({ referenceId: 2, detail: 'PETROL', amount: 200 }),
        ] }]}
      />,
    )

    expect(screen.getByText('Shop sale')).toBeInTheDocument()
    expect(screen.getByText('Castrol Engine Oil 1 L × 2 (Bill B-42)')).toBeInTheDocument()
    expect(screen.getByText('+₹1,000.00 sales')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Move' })).toHaveLength(1)
  })
})

describe('LedgerWeekGroups server-written references', () => {
  afterEach(async () => {
    await changeAppLanguage('en')
  })

  it('shows a shop sale and a payment by unknown staff in the chosen language', async () => {
    await changeAppLanguage('hi')
    render(
      <LedgerWeekGroups
        srcClientId={7}
        actions={actions}
        groups={[{ label: 'Week', items: [
          row({
            referenceId: 1, type: 'COUNTER_SALE', reference: 'Castrol Engine Oil — 1 LITRE × 2 (Bill B-42)',
            referenceCode: 'COUNTER_SALE_DETAILS_WITH_BILL',
            referenceParams: { productName: 'Castrol Engine Oil — 1 LITRE', quantity: 2, billNo: 'B-42' },
          }),
          row({
            referenceId: 2, type: 'PAYMENT', reference: 'Unknown staff', referenceCode: 'UNKNOWN_STAFF',
            detail: 'CASH', status: 'APPROVED',
          }),
        ] }]}
      />,
    )

    expect(screen.getByText('Castrol Engine Oil — 1 LITRE × 2 (बिल B-42)')).toBeInTheDocument()
    expect(screen.getByText('अज्ञात कर्मचारी')).toBeInTheDocument()
    expect(screen.queryByText('Unknown staff')).not.toBeInTheDocument()
  })

  it('keeps the server English for English users', () => {
    render(
      <LedgerWeekGroups
        srcClientId={7}
        actions={actions}
        groups={[{ label: 'Week', items: [
          row({
            referenceId: 1, type: 'COUNTER_SALE', reference: 'Engine Oil — 1 LITRE × 3',
            referenceCode: 'COUNTER_SALE_DETAILS', referenceParams: { productName: 'Engine Oil — 1 LITRE', quantity: 3 },
          }),
          row({ referenceId: 2, type: 'PAYMENT', reference: 'Unknown staff', referenceCode: 'UNKNOWN_STAFF', status: 'APPROVED' }),
        ] }]}
      />,
    )

    expect(screen.getByText('Engine Oil — 1 LITRE × 3')).toBeInTheDocument()
    expect(screen.getByText('Unknown staff')).toBeInTheDocument()
  })
})

describe('LedgerWeekGroups detail column', () => {
  it('shows the fuel name and payment mode as words, and leaves an interest period as sent', () => {
    render(
      <LedgerWeekGroups
        srcClientId={7}
        actions={actions}
        groups={[{ label: 'Week', items: [
          row({ referenceId: 1, detail: 'SPEED_PETROL' }),
          row({ referenceId: 2, type: 'PAYMENT', detail: 'BANK_TRANSFER', status: 'APPROVED' }),
          row({ referenceId: 3, type: 'INTEREST', detail: '2026-11-01 – 2026-11-07' }),
        ] }]}
      />,
    )

    expect(screen.getByText('Speed Petrol')).toBeInTheDocument()
    expect(screen.queryByText('SPEED_PETROL')).not.toBeInTheDocument()
    expect(screen.getByText('Bank transfer')).toBeInTheDocument()
    expect(screen.getByText('2026-11-01 – 2026-11-07')).toBeInTheDocument()
  })
})

describe('LedgerWeekGroups interest waiver', () => {
  it('offers Waive only on interest that is still owed and marks a waived charge', () => {
    render(
      <LedgerWeekGroups
        srcClientId={7}
        actions={{ ...actions, canWaiveInterest: true }}
        groups={[{ label: 'Week', items: [
          row({ referenceId: 3, type: 'INTEREST', detail: 'October' }),
          row({ referenceId: 4, type: 'INTEREST', detail: 'September', status: 'WAIVED', countedInBalance: false }),
        ] }]}
      />,
    )

    expect(screen.getAllByRole('button', { name: 'Waive this interest charge' })).toHaveLength(1)
    expect(screen.getByText('Waived')).toBeInTheDocument()
  })

  it('hides Waive from someone who may not waive interest', () => {
    render(
      <LedgerWeekGroups
        srcClientId={7}
        actions={actions}
        groups={[{ label: 'Week', items: [row({ referenceId: 3, type: 'INTEREST', detail: 'October' })] }]}
      />,
    )

    expect(screen.queryByRole('button', { name: 'Waive this interest charge' })).not.toBeInTheDocument()
  })
})

describe('LedgerWeekGroups payment reversal', () => {
  const reversedPaymentRows = [
    row({ referenceId: 9, type: 'BOUNCE_CHARGE', amount: 250, runningBalance: 4250,
      reference: 'Bounce charge for the payment of 2026-11-03', referenceCode: 'BOUNCE_CHARGE_DETAILS',
      referenceParams: { paidAt: '2026-11-03' }, linkedReferenceId: 2 }),
    row({ referenceId: 9, type: 'PAYMENT_REVERSAL', detail: 'CHEQUE', amount: 4000, runningBalance: 4000,
      reference: 'Payment of 2026-11-03 reversed: Cheque bounced', referenceCode: 'PAYMENT_REVERSAL_DETAILS',
      referenceParams: { paidAt: '2026-11-03', reason: 'Cheque bounced' }, linkedReferenceId: 2 }),
    row({ referenceId: 2, type: 'PAYMENT', detail: 'CHEQUE', amount: 4000, runningBalance: 0, status: 'REVERSED', linkedReferenceId: 9 }),
    row({ referenceId: 3, type: 'PAYMENT', detail: 'CASH', amount: 100, runningBalance: 4000, status: 'APPROVED' }),
  ]

  it('marks the reversed payment and lists its reversal and bounce charge as debits', () => {
    render(<LedgerWeekGroups srcClientId={7} actions={actions} groups={[{ label: 'Week', items: reversedPaymentRows }]} />)

    expect(screen.getByText('Reversed')).toBeInTheDocument()
    expect(screen.getByText('Payment reversed')).toBeInTheDocument()
    expect(screen.getByText('Bounce charge')).toBeInTheDocument()
    expect(screen.getByText('Payment of 2026-11-03 reversed: Cheque bounced')).toBeInTheDocument()
    expect(screen.getByText('+₹250.00').closest('td')).toHaveClass('text-bad')
    expect(screen.getByText('+₹4,000.00').closest('td')).toHaveClass('text-bad')
  })

  it('offers Reverse only on approved payments to someone who may reverse them, and no Move on a reversed one', async () => {
    const user = userEvent.setup()
    const onReversePayment = vi.fn()
    render(
      <LedgerWeekGroups srcClientId={7}
        actions={{ ...actions, canReversePayments: true, canMoveEntries: true, onReversePayment }}
        groups={[{ label: 'Week', items: reversedPaymentRows }]} />,
    )

    const reverseButtons = screen.getAllByRole('button', { name: 'Reverse this payment' })
    expect(reverseButtons).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: 'Move' })).toHaveLength(1)
    await user.click(reverseButtons[0])
    expect(onReversePayment).toHaveBeenCalledWith(expect.objectContaining({ referenceId: 3 }))
  })

  it('hides Reverse from a manager', () => {
    render(<LedgerWeekGroups srcClientId={7} actions={actions} groups={[{ label: 'Week', items: reversedPaymentRows }]} />)

    expect(screen.queryByRole('button', { name: 'Reverse this payment' })).not.toBeInTheDocument()
  })
})

describe('LedgerWeekGroups credit desk', () => {
  it('shows a credit note as lowering the balance and badges a disputed sale that can only be resolved', async () => {
    const user = userEvent.setup()
    const onDispute = vi.fn()
    const onCreditNote = vi.fn()
    render(
      <LedgerWeekGroups srcClientId={7}
        actions={{ ...actions, canIssueCreditNote: true, canRaiseDispute: true, canResolveDispute: true, onDispute, onCreditNote }}
        groups={[{ label: 'Week', items: [
          row({ referenceId: 1, openDisputeId: 9 }),
          row({ referenceId: 2, type: 'CREDIT_NOTE', reference: 'Short delivery', amount: 40 }),
          row({ referenceId: 3, detail: 'DIESEL' }),
        ] }]} />,
    )

    expect(screen.getByText('Disputed')).toBeInTheDocument()
    expect(screen.getByText('−₹40.00').closest('td')).toHaveClass('text-ok')
    expect(screen.getAllByRole('button', { name: 'Credit note' })).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: 'Resolve dispute' }))
    expect(onDispute).toHaveBeenCalledWith(expect.objectContaining({ referenceId: 1 }))
    await user.click(screen.getByRole('button', { name: 'Credit note' }))
    expect(onCreditNote).toHaveBeenCalledWith(expect.objectContaining({ referenceId: 3 }), 7)
  })
})

describe('LedgerWeekGroups on a phone', () => {
  resetScreenWidthAfterEach()

  it('stacks each entry with its date, description, signed amount and running balance instead of a wide table', async () => {
    setScreenWidth(375)
    const user = userEvent.setup()
    const approvePayment = vi.fn()
    render(
      <LedgerWeekGroups
        srcClientId={7}
        actions={{ ...actions, canApprovePayments: true, pendingPaymentRecorders: new Map([[2, 99]]), approvePayment }}
        groups={[{ label: 'Week of 10 Nov', items: [
          row({ referenceId: 1, detail: 'PETROL', amount: 500, runningBalance: 1500 }),
          row({ referenceId: 2, type: 'PAYMENT', detail: 'CASH', amount: 400, runningBalance: 1100, status: 'PENDING_APPROVAL' }),
        ] }]}
      />,
    )

    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    const list = screen.getByRole('list', { name: 'Transactions, Week of 10 Nov' })
    const [sale, payment] = within(list).getAllByTestId('ledger-entry')

    expect(within(sale).getByText('10 Nov')).toBeInTheDocument()
    expect(within(sale).getByText('Petrol')).toBeInTheDocument()
    expect(within(sale).getByText('+₹500.00')).toHaveClass('text-warn')
    expect(within(sale).getByText('₹1,500.00')).toBeInTheDocument()
    expect(within(payment).getByText('−₹400.00')).toHaveClass('text-ok')
    expect(within(payment).getByText('Cash')).toBeInTheDocument()

    await user.click(within(payment).getByRole('button', { name: 'Approve' }))
    expect(approvePayment).toHaveBeenCalledWith(2)
  })

  it('keeps the table from tablet width up', () => {
    setScreenWidth(1024)
    render(<LedgerWeekGroups srcClientId={7} actions={actions} groups={[{ label: 'Week', items: [row({})] }]} />)

    expect(screen.getByRole('table', { name: 'Transactions, Week' })).toHaveClass('min-w-208')
    expect(screen.queryByTestId('ledger-entry')).not.toBeInTheDocument()
  })
})
