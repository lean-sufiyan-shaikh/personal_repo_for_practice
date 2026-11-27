import { useTranslation } from 'react-i18next'
import type { SupplierPayment } from '../../api/supplierApi'
import { MoneyText } from '../../components/AmountText'
import { DataTable } from '../../components/DataTable'
import type { DataTableColumn } from '../../components/DataTable'
import { EmptyState } from '../../components/EmptyState'
import { SkeletonTable } from '../../components/Skeleton'
import { StatusBadge } from '../../components/StatusBadge'
import { PageSection } from '../../components/layout/PageSection'
import { codeLabel } from '../../constants/labels'
import { formatSupplierDate } from './supplierFormat'

interface Props {
  payments: SupplierPayment[] | undefined
  isLoading: boolean
  canVoid: boolean
  onVoid: (payment: SupplierPayment) => void
}

/** Every payment to the supplier, voided ones kept with their reason so the history stays complete. */
export function SupplierPaymentsList({ payments, isLoading, canVoid, onVoid }: Props) {
  const { t } = useTranslation('suppliers')

  const columns: DataTableColumn<SupplierPayment>[] = [
    { key: 'date', header: t('payments.date'), cell: payment => formatSupplierDate(payment.paymentDate) },
    { key: 'mode', header: t('payments.mode'), cell: payment => codeLabel(payment.paymentMode) },
    { key: 'reference', header: t('payments.reference'), cell: payment => payment.referenceNo ?? '—' },
    { key: 'recordedBy', header: t('payments.recordedBy'), className: 'hidden md:table-cell',
      cell: payment => payment.recordedByName },
    { key: 'amount', header: t('payments.amount'), numeric: true,
      cell: payment => <MoneyText value={payment.amount} className={payment.voidStatus === 'VOIDED' ? 'line-through text-ink-3' : ''} /> },
    {
      key: 'status', header: t('payments.status'),
      cell: payment => payment.voidStatus === 'VOIDED'
        ? <StatusBadge tone="neutral" wrap label={t('payments.voided', { reason: payment.voidReason ?? '' })} />
        : <StatusBadge tone="ok" label={t('payments.active')} />,
    },
    {
      key: 'actions', header: <span className="sr-only">{t('payments.actions')}</span>, align: 'right',
      cell: payment => canVoid && payment.voidStatus === 'ACTIVE' && (
        <button type="button" onClick={() => onVoid(payment)} className="ui-btn ui-btn-secondary ui-btn-compact">
          {t('void.action')}
        </button>
      ),
    },
  ]

  return (
    <PageSection title={t('payments.title')}>
      {isLoading ? <SkeletonTable rows={3} cols={5} /> : (
        <DataTable
          caption={t('payments.title')}
          captionHidden
          columns={columns}
          rows={payments ?? []}
          rowKey={payment => payment.id}
          cardsOnPhone
          empty={<EmptyState icon="generic" title={t('payments.empty')} />}
        />
      )}
    </PageSection>
  )
}
