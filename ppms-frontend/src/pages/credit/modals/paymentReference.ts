import type { CreditPaymentMode } from '../../../api/creditApi'

/** What the user typed; for a cheque, referenceNo is the cheque number. */
export interface PaymentReference {
  referenceNo: string
  chequeBank: string
  chequeDate: string
}

export const EMPTY_PAYMENT_REFERENCE: PaymentReference = { referenceNo: '', chequeBank: '', chequeDate: '' }

const MODES_NEEDING_REFERENCE: CreditPaymentMode[] = ['UPI', 'BANK_TRANSFER', 'CHEQUE']

export function needsReference(mode: CreditPaymentMode): boolean {
  return MODES_NEEDING_REFERENCE.includes(mode)
}

/** The request fields for the mode: cheque bank and date only for a cheque, blanks left out. */
export function referenceFieldsOf(mode: CreditPaymentMode, value: PaymentReference) {
  const isCheque = mode === 'CHEQUE'
  return {
    referenceNo: value.referenceNo.trim() || undefined,
    chequeBank: isCheque ? value.chequeBank.trim() || undefined : undefined,
    chequeDate: isCheque ? value.chequeDate || undefined : undefined,
  }
}
