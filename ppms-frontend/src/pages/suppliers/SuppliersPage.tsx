import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Plus } from 'lucide-react'
import { supplierApi } from '../../api/supplierApi'
import type { Supplier } from '../../api/supplierApi'
import { EmptyState } from '../../components/EmptyState'
import { LoadError } from '../../components/LoadError'
import { Page } from '../../components/layout/Page'
import { PageHeader } from '../../components/layout/PageHeader'
import { useUrlParam } from '../../components/layout/useUrlParam'
import { canPerformAction } from '../../permissions/permissions'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { SupplierBalancesTable } from './SupplierBalancesTable'
import { SupplierFormDialog } from './SupplierFormDialog'
import { SupplierLedgerView } from './SupplierLedgerView'

/** What the pump owes each oil company. The address holds the open supplier (?supplier=4). */
export default function SuppliersPage() {
  const { t } = useTranslation('suppliers')
  const { user } = useAuthStore()
  const { selectedPumpId: pumpId } = usePumpStore()
  const canManage = canPerformAction('supplier:manage', user?.role)
  const openSupplier = useUrlParam('supplier')
  const [editing, setEditing] = useState<Supplier | 'new' | null>(null)

  const balances = useQuery({
    queryKey: ['supplier-balances', pumpId],
    queryFn: () => supplierApi.getBalances(pumpId!),
    enabled: !!pumpId,
  })
  const suppliers = useQuery({
    queryKey: ['suppliers', pumpId],
    queryFn: () => supplierApi.list(pumpId!),
    enabled: !!pumpId,
  })

  if (!pumpId) {
    return (
      <Page>
        <PageHeader title={t('page.title')} />
        <EmptyState icon="generic" title={t('page.noPump')} />
      </Page>
    )
  }

  const selectedId = openSupplier.value ? Number(openSupplier.value) : null
  const selectedSupplier = suppliers.data?.find(supplier => supplier.id === selectedId) ?? null

  return (
    <Page>
      {selectedId ? (
        <SupplierLedgerView
          pumpId={pumpId}
          supplierId={selectedId}
          supplier={selectedSupplier}
          onEdit={() => selectedSupplier && setEditing(selectedSupplier)}
        />
      ) : (
        <>
          <PageHeader
            title={t('page.title')}
            description={t('page.subtitle')}
            primaryAction={canManage && (
              <button type="button" onClick={() => setEditing('new')} className="ui-btn ui-btn-primary">
                <Plus size={16} strokeWidth={2} aria-hidden="true" />
                {t('page.add')}
              </button>
            )}
          />
          {balances.error ? (
            <LoadError onRetry={() => balances.refetch()} />
          ) : (
            <SupplierBalancesTable
              balances={balances.data}
              isLoading={balances.isLoading}
              onOpen={supplierId => openSupplier.set(String(supplierId))}
            />
          )}
        </>
      )}

      {editing && (
        <SupplierFormDialog
          pumpId={pumpId}
          supplier={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
        />
      )}
    </Page>
  )
}
