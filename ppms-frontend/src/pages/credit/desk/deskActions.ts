import { creditDeskApi } from '../../../api/creditDeskApi'
import type { CreditDebitType } from '../../../api/creditDeskApi'
import type { CreditPaymentMode } from '../../../api/creditApi'
import type { ModalTone } from '../../../components/Modal'

export type DeskActionKind =
  | 'creditNote' | 'refundAdvance' | 'receiveDeposit' | 'applyDeposit' | 'refundDeposit' | 'writeOff' | 'closeAccount'

export interface DeskFormValues {
  amount: number | undefined
  paymentMode: CreditPaymentMode
  referenceNo: string
  reason: string
  date: string
  returnedQuantity: number | undefined
}

/** The sale or charge a credit note names. */
export interface DeskDebit {
  type: CreditDebitType
  id: number
  amount: number
}

export interface DeskActionConfig {
  /** 'optional' leaves the amount out when empty: a write-off of everything owed. */
  amount: 'required' | 'optional' | 'none'
  paymentMode: boolean
  reason: boolean
  date: boolean
  quantity: boolean
  tone: ModalTone
  submit: (pumpId: number, clientId: number, values: DeskFormValues, debit: DeskDebit | undefined,
           idempotencyKey: string) => Promise<unknown>
}

const reference = (values: DeskFormValues) => values.referenceNo.trim() || undefined

/** What each credit desk action asks for and which endpoint it calls. */
export const DESK_ACTIONS: Record<DeskActionKind, DeskActionConfig> = {
  creditNote: {
    amount: 'required', paymentMode: false, reason: true, date: false, quantity: true, tone: 'info',
    submit: (pumpId, clientId, values, debit, key) => creditDeskApi.issueCreditNote(pumpId, clientId, {
      amount: values.amount ?? 0, reason: values.reason.trim(), debitType: debit?.type, debitId: debit?.id,
      returnedQuantity: debit && (debit.type === 'SALE' || debit.type === 'COUNTER_SALE') ? values.returnedQuantity : undefined,
    }, key),
  },
  refundAdvance: {
    amount: 'required', paymentMode: true, reason: true, date: false, quantity: false, tone: 'warning',
    submit: (pumpId, clientId, values, _debit, key) => creditDeskApi.refundAdvance(pumpId, clientId, {
      amount: values.amount ?? 0, paymentMode: values.paymentMode, referenceNo: reference(values),
      reason: values.reason.trim(),
    }, key),
  },
  receiveDeposit: {
    amount: 'required', paymentMode: true, reason: false, date: true, quantity: false, tone: 'info',
    submit: (pumpId, clientId, values, _debit, key) => creditDeskApi.receiveDeposit(pumpId, clientId, {
      amount: values.amount ?? 0, paymentMode: values.paymentMode, referenceNo: reference(values), date: values.date,
    }, key),
  },
  applyDeposit: {
    amount: 'required', paymentMode: false, reason: true, date: false, quantity: false, tone: 'warning',
    submit: (pumpId, clientId, values, _debit, key) => creditDeskApi.applyDeposit(pumpId, clientId, {
      amount: values.amount ?? 0, reason: values.reason.trim(),
    }, key),
  },
  refundDeposit: {
    amount: 'required', paymentMode: true, reason: true, date: false, quantity: false, tone: 'warning',
    submit: (pumpId, clientId, values, _debit, key) => creditDeskApi.refundDeposit(pumpId, clientId, {
      amount: values.amount ?? 0, paymentMode: values.paymentMode, referenceNo: reference(values),
      reason: values.reason.trim(),
    }, key),
  },
  writeOff: {
    amount: 'optional', paymentMode: false, reason: true, date: false, quantity: false, tone: 'danger',
    submit: (pumpId, clientId, values, _debit, key) => creditDeskApi.writeOff(pumpId, clientId, {
      amount: values.amount, reason: values.reason.trim(),
    }, key),
  },
  closeAccount: {
    amount: 'none', paymentMode: false, reason: true, date: false, quantity: false, tone: 'danger',
    submit: (pumpId, clientId, values) => creditDeskApi.closeAccount(pumpId, clientId, values.reason.trim()),
  },
}
