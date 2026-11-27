import { useTranslation } from 'react-i18next'
import type { LedgerEntry, SupplierLedger } from '../../api/supplierApi'
import { MoneyText } from '../../components/AmountText'
import { DataTable } from '../../components/DataTable'
import type { DataTableColumn } from '../../components/DataTable'
import { EmptyState } from '../../components/EmptyState'
import { PageSection } from '../../components/layout/PageSection'
import { entryDetails, entryTypeLabel, formatSupplierDate } from './supplierFormat'

function amountCell(amount: number) {
  return amount > 0 ? <MoneyText value={amount} /> : <span className="text-ink-3">—</span>
}

/** Debits are what the pump owes (opening balance, tanker invoices); credits are payments and credit notes. */
export function SupplierLedgerTable({ ledger }: { ledger: SupplierLedger }) {
  const { t } = useTranslation('suppliers')

  const columns: DataTableColumn<LedgerEntry>[] = [
    { key: 'date', header: t('ledger.date'), cell: entry => formatSupplierDate(entry.date) },
    { key: 'type', header: t('ledger.type'), cell: entry => entryTypeLabel(entry.type) },
    { key: 'reference', header: t('ledger.reference'), cell: entry => entry.reference ?? '—' },
    { key: 'details', header: t('ledger.details'), className: 'hidden md:table-cell',
      cell: entry => <span className="text-ink-2">{entryDetails(entry)}</span> },
    { key: 'debit', header: t('ledger.debit'), numeric: true, cell: entry => amountCell(entry.debit) },
    { key: 'credit', header: t('ledger.credit'), numeric: true, cell: entry => amountCell(entry.credit) },
    { key: 'balance', header: t('ledger.balance'), numeric: true,
      cell: entry => <MoneyText value={entry.balance} className="font-medium" /> },
  ]

  return (
    <PageSection title={t('ledger.title')} description={t('ledger.description')}>
      <DataTable
        caption={t('ledger.title')}
        captionHidden
        columns={columns}
        rows={ledger.entries}
        rowKey={entry => `${entry.type}-${entry.paymentId ?? entry.deliveryIds.join('.')}-${entry.date}`}
        cardsOnPhone
        empty={<EmptyState icon="generic" title={t('ledger.empty')} />}
        footer={ledger.entries.length === 0 ? undefined : (
          <tr>
            <td colSpan={3} className="font-semibold">{t('ledger.total')}</td>
            <td className="hidden md:table-cell" />
            <td className="text-right"><MoneyText value={ledger.totalDebit} className="font-semibold" /></td>
            <td className="text-right"><MoneyText value={ledger.totalCredit} className="font-semibold" /></td>
            <td className="text-right"><MoneyText value={ledger.balance} className="font-semibold" /></td>
          </tr>
        )}
      />
    </PageSection>
  )
}
