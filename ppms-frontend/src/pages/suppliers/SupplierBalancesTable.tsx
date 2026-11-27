import { useTranslation } from 'react-i18next'
import type { SupplierBalance, SupplierBalances } from '../../api/supplierApi'
import { MoneyText } from '../../components/AmountText'
import { DataTable } from '../../components/DataTable'
import type { DataTableColumn } from '../../components/DataTable'
import { EmptyState } from '../../components/EmptyState'
import { SkeletonTable } from '../../components/Skeleton'
import { StatusBadge } from '../../components/StatusBadge'
import { PageSection } from '../../components/layout/PageSection'
import { formatCurrency } from '../../utils/format'
import { formatSupplierDate } from './supplierFormat'

interface Props {
  balances: SupplierBalances | undefined
  isLoading: boolean
  onOpen: (supplierId: number) => void
}

/** Every supplier's outstanding or advance with its aging, and the tanker invoices booked to no supplier. */
export function SupplierBalancesTable({ balances, isLoading, onOpen }: Props) {
  const { t } = useTranslation('suppliers')

  if (isLoading || !balances) {
    return <SkeletonTable rows={3} cols={4} />
  }

  const columns: DataTableColumn<SupplierBalance>[] = [
    {
      key: 'name',
      header: t('balances.supplier'),
      cell: row => (
        <span className="flex flex-wrap items-center gap-2">
          <button type="button" onClick={() => onOpen(row.supplierId)} className="ui-link font-medium">
            {row.supplierName}
          </button>
          {row.defaultSupplier && <StatusBadge tone="info" label={t('balances.default')} />}
          {!row.active && <StatusBadge tone="neutral" label={t('balances.inactive')} />}
        </span>
      ),
    },
    { key: 'outstanding', header: t('balances.outstanding'), numeric: true,
      cell: row => <MoneyText value={row.outstanding} className="font-semibold" /> },
    { key: 'advance', header: t('balances.advance'), numeric: true,
      cell: row => <MoneyText value={row.advance} tone={row.advance > 0 ? 'ok' : undefined} /> },
    { key: 'days0To15', header: t('aging.days0To15'), numeric: true, className: 'hidden md:table-cell',
      cell: row => <MoneyText value={row.aging.days0To15} /> },
    { key: 'days16To30', header: t('aging.days16To30'), numeric: true, className: 'hidden md:table-cell',
      cell: row => <MoneyText value={row.aging.days16To30} /> },
    { key: 'days31To60', header: t('aging.days31To60'), numeric: true, className: 'hidden md:table-cell',
      cell: row => <MoneyText value={row.aging.days31To60} tone={row.aging.days31To60 > 0 ? 'warn' : undefined} /> },
    { key: 'over60Days', header: t('aging.over60Days'), numeric: true, className: 'hidden md:table-cell',
      cell: row => <MoneyText value={row.aging.over60Days} tone={row.aging.over60Days > 0 ? 'bad' : undefined} /> },
    { key: 'lastDelivery', header: t('balances.lastDelivery'), className: 'hidden lg:table-cell',
      cell: row => formatSupplierDate(row.lastDeliveryDate) },
  ]

  return (
    <PageSection title={t('balances.title')} description={t('balances.description')}>
      {balances.unassigned.invoiceCount > 0 && (
        <p className="ui-alert ui-alert-warning mb-4">
          {t('balances.unassigned', {
            count: balances.unassigned.invoiceCount,
            amount: formatCurrency(balances.unassigned.amount),
          })}
        </p>
      )}
      <DataTable
        caption={t('balances.title')}
        captionHidden
        columns={columns}
        rows={balances.suppliers}
        rowKey={row => row.supplierId}
        cardsOnPhone
        empty={<EmptyState icon="generic" title={t('balances.empty')} subtitle={t('balances.emptyHint')} />}
        footer={balances.suppliers.length === 0 ? undefined : (
          <tr>
            <td className="font-semibold">{t('balances.total')}</td>
            <td className="text-right"><MoneyText value={balances.totalOutstanding} className="font-semibold" /></td>
            <td className="text-right"><MoneyText value={balances.totalAdvance} /></td>
            <td colSpan={5} className="hidden md:table-cell" />
          </tr>
        )}
      />
    </PageSection>
  )
}
