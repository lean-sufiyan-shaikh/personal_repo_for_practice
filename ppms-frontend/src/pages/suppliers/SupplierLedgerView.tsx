import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { IndianRupee, Pencil, Star } from 'lucide-react'
import { supplierApi } from '../../api/supplierApi'
import type { Supplier, SupplierPayment } from '../../api/supplierApi'
import { CsvExportButton } from '../../components/CsvExportButton'
import { LoadError } from '../../components/LoadError'
import { SkeletonTable } from '../../components/Skeleton'
import { StatusBadge } from '../../components/StatusBadge'
import { PageHeader } from '../../components/layout/PageHeader'
import { canPerformAction } from '../../permissions/permissions'
import { useAuthStore } from '../../store/authStore'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { csvFilename } from '../../utils/csv'
import { RecordSupplierPaymentDialog } from './RecordSupplierPaymentDialog'
import { SupplierBalanceSummary } from './SupplierBalanceSummary'
import { SupplierLedgerTable } from './SupplierLedgerTable'
import { SupplierPaymentsList } from './SupplierPaymentsList'
import { VoidSupplierPaymentDialog } from './VoidSupplierPaymentDialog'
import { ledgerCsvColumns } from './supplierFormat'

interface Props {
  pumpId: number
  supplierId: number
  /** Null while loading, or for an inactive supplier, which cannot be edited or paid. */
  supplier: Supplier | null
  onEdit: () => void
}

/** One supplier's ledger with its running balance, its payments, and the actions an owner or admin may take. */
export function SupplierLedgerView({ pumpId, supplierId, supplier, onEdit }: Props) {
  const { t } = useTranslation('suppliers')
  const { user } = useAuthStore()
  const { addToast } = useToastStore()
  const queryClient = useQueryClient()
  const canManage = canPerformAction('supplier:manage', user?.role)
  const canPay = canPerformAction('supplier:record-payment', user?.role)
  const canVoid = canPerformAction('supplier:void-payment', user?.role)
  const [recording, setRecording] = useState(false)
  const [voidTarget, setVoidTarget] = useState<SupplierPayment | null>(null)

  const ledger = useQuery({
    queryKey: ['supplier-ledger', pumpId, supplierId],
    queryFn: () => supplierApi.getLedger(pumpId, supplierId),
  })
  const payments = useQuery({
    queryKey: ['supplier-payments', pumpId, supplierId],
    queryFn: () => supplierApi.listPayments(pumpId, supplierId),
  })
  const makeDefault = useMutation({
    mutationFn: () => supplierApi.makeDefault(pumpId, supplierId),
    onSuccess: saved => {
      queryClient.invalidateQueries({ queryKey: ['suppliers', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['supplier-balances', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['supplier-ledger', pumpId] })
      addToast(t('detail.defaultSet', { name: saved.name }), 'success')
    },
    onError: err => addToast(parseApiError(err, t('detail.defaultFailed')), 'error'),
  })

  const data = ledger.data
  const active = supplier !== null
  const actions = (
    <>
      {data && (
        <CsvExportButton filename={csvFilename(`supplier-ledger-${supplierId}-${data.asOf}`)} columns={ledgerCsvColumns()}
          rows={data.entries} />
      )}
      {canManage && active && !data?.defaultSupplier && (
        <button type="button" onClick={() => makeDefault.mutate()} disabled={makeDefault.isPending} className="ui-btn ui-btn-secondary">
          <Star size={16} strokeWidth={2} aria-hidden="true" />
          {t('detail.makeDefault')}
        </button>
      )}
      {canManage && active && (
        <button type="button" onClick={onEdit} className="ui-btn ui-btn-secondary">
          <Pencil size={16} strokeWidth={2} aria-hidden="true" />
          {t('detail.edit')}
        </button>
      )}
    </>
  )

  return (
    <>
      <PageHeader
        title={data?.supplierName ?? t('detail.loading')}
        backLink={{ to: '/dashboard/suppliers', label: t('detail.back') }}
        badges={data && <>
          {data.defaultSupplier && <StatusBadge tone="info" label={t('balances.default')} />}
          {!data.active && <StatusBadge tone="neutral" label={t('balances.inactive')} />}
        </>}
        secondaryActions={actions}
        primaryAction={canPay && active && (
          <button type="button" onClick={() => setRecording(true)} className="ui-btn ui-btn-primary">
            <IndianRupee size={16} strokeWidth={2} aria-hidden="true" />
            {t('payment.record')}
          </button>
        )}
      />

      {ledger.error ? <LoadError onRetry={() => ledger.refetch()} /> : !data ? <SkeletonTable rows={4} cols={5} /> : (
        <>
          <SupplierBalanceSummary ledger={data} />
          <SupplierLedgerTable ledger={data} />
        </>
      )}
      <SupplierPaymentsList payments={payments.data?.content} isLoading={payments.isLoading} canVoid={canVoid}
        onVoid={setVoidTarget} />

      {recording && supplier && (
        <RecordSupplierPaymentDialog pumpId={pumpId} supplier={supplier} outstanding={data?.outstanding ?? 0}
          onClose={() => setRecording(false)} />
      )}
      {voidTarget && (
        <VoidSupplierPaymentDialog pumpId={pumpId} payment={voidTarget} onClose={() => setVoidTarget(null)} />
      )}
    </>
  )
}
