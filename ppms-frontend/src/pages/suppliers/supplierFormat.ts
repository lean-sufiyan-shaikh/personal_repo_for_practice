import type { LedgerEntry } from '../../api/supplierApi'
import { codeLabel } from '../../constants/labels'
import { translateKey } from '../../i18n'
import type { CsvColumn } from '../../utils/csv'
import { formatIstDate } from '../../utils/date'

export function formatSupplierDate(date: string | null): string {
  return date ? formatIstDate(date) : '—'
}

export function entryTypeLabel(type: LedgerEntry['type']): string {
  return translateKey(`suppliers:ledger.types.${type}`)
}

/** What a ledger row is about: the invoice's fuel lines, the claimed invoice, or the payment mode and notes. */
export function entryDetails(entry: LedgerEntry): string {
  if (entry.type === 'PAYMENT') {
    return [codeLabel(entry.paymentMode), entry.description].filter(Boolean).join(' · ')
  }
  if (entry.type === 'CREDIT_NOTE' && entry.description) {
    return translateKey('suppliers:ledger.creditNoteFor', { invoice: entry.description })
  }
  return entry.description ?? ''
}

export function ledgerCsvColumns(): CsvColumn<LedgerEntry>[] {
  return [
    { header: translateKey('suppliers:ledger.date'), value: entry => entry.date, kind: 'date' },
    { header: translateKey('suppliers:ledger.type'), value: entry => entryTypeLabel(entry.type) },
    { header: translateKey('suppliers:ledger.reference'), value: entry => entry.reference },
    { header: translateKey('suppliers:ledger.details'), value: entry => entryDetails(entry) },
    { header: translateKey('suppliers:ledger.debit'), value: entry => entry.debit, kind: 'money' },
    { header: translateKey('suppliers:ledger.credit'), value: entry => entry.credit, kind: 'money' },
    { header: translateKey('suppliers:ledger.balance'), value: entry => entry.balance, kind: 'money' },
  ]
}
