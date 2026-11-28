import { describe, expect, it } from 'vitest'
import type { MatchedRecord, StatementLine } from '../../api/bankReconciliationApi'
import { amountsDiffer, lineAmount, matchLinkFor } from './reconciliationFormat'

function line(overrides: Partial<StatementLine>): StatementLine {
  return {
    id: 1, importId: 12, txnDate: '2026-11-20', narration: 'NEFT CR', debitAmount: null, creditAmount: 1000, balance: null,
    utrReference: null, matchStatus: 'UNMATCHED', matchedShiftId: null, matchedAncillarySaleId: null, matchedPaymentId: null,
    matchedSettlementId: null, matchedDepositId: null, matchedSupplierPaymentId: null, matchedChargebackId: null, matchedRecord: null, matchNotes: null,
    createdAt: '2026-11-25T05:00:00Z', ...overrides,
  }
}

const deposit: MatchedRecord = { type: 'DEPOSIT', id: 5, date: '2026-11-19', amount: 2400, reference: 'SLIP-9', description: 'HDFC 0042' }

describe('lineAmount', () => {
  it('reads money in from the credit column and money out from the debit column', () => {
    expect(lineAmount(line({ creditAmount: 2500 }))).toEqual({ amount: 2500, isCredit: true })
    expect(lineAmount(line({ creditAmount: null, debitAmount: 800 }))).toEqual({ amount: 800, isCredit: false })
    expect(lineAmount(line({ creditAmount: 0, debitAmount: 800 }))).toEqual({ amount: 800, isCredit: false })
  })
})

describe('amountsDiffer', () => {
  it('compares the line and the record to the paisa', () => {
    expect(amountsDiffer(line({ creditAmount: 2500 }), deposit)).toBe(true)
    expect(amountsDiffer(line({ creditAmount: 2400 }), deposit)).toBe(false)
    expect(amountsDiffer(line({ creditAmount: 0.1 + 0.2 }), { ...deposit, amount: 0.3 })).toBe(false)
  })

  it('never flags a record without an amount', () => {
    expect(amountsDiffer(line({ creditAmount: 2500 }), { ...deposit, amount: null })).toBe(false)
  })
})

describe('matchLinkFor', () => {
  it('sends the one link field the server expects for each kind of record', () => {
    expect(matchLinkFor(deposit)).toEqual({ matchedDepositId: 5 })
    expect(matchLinkFor({ ...deposit, type: 'SETTLEMENT' })).toEqual({ matchedSettlementId: 5 })
    expect(matchLinkFor({ ...deposit, type: 'CREDIT_PAYMENT' })).toEqual({ matchedPaymentId: 5 })
    expect(matchLinkFor({ ...deposit, type: 'SUPPLIER_PAYMENT' })).toEqual({ matchedSupplierPaymentId: 5 })
    expect(matchLinkFor({ ...deposit, type: 'CHARGEBACK' })).toEqual({ matchedChargebackId: 5 })
    expect(matchLinkFor({ ...deposit, type: 'SHIFT' })).toEqual({ matchedShiftId: 5 })
    expect(matchLinkFor({ ...deposit, type: 'ANCILLARY_SALE' })).toEqual({ matchedSaleId: 5 })
  })
})
