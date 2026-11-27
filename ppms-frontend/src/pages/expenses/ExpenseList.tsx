import { useMemo, useState } from 'react'
import { Trans, useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useAuthStore } from '../../store/authStore'
import { canPerformAction } from '../../permissions/permissions'
import { expenseApi } from '../../api/expenseApi'
import type { ExpenseCategory, ApproveExpenseRequest, ExpenseApprovalStatus, PumpExpense } from '../../api/expenseApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { Pagination } from '../../components/Pagination'
import { SkeletonTable } from '../../components/Skeleton'
import { EmptyState } from '../../components/EmptyState'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { formatCurrency } from '../../utils/format'
import { RejectReasonDialog } from '../../components/RejectReasonDialog'
import { CATEGORIES } from './expenseFormat'
import { ExpenseRow } from './ExpenseRow'
import { VoidExpenseDialog } from './VoidExpenseDialog'
import type { ExpenseListState } from './useExpenseList'
import { codeLabel } from '../../constants/labels'
import { CsvExportButton } from '../../components/CsvExportButton'
import { csvFilename } from '../../utils/csv'
import type { CsvColumn } from '../../utils/csv'
import { PageSection } from '../../components/layout/PageSection'
import { FilterBar } from '../../components/layout/FilterBar'

function csvColumns(t: TFunction<['expenses', 'common']>): CsvColumn<PumpExpense>[] {
  return [
    { header: t('csv.date'), value: (row) => row.expenseDate, kind: 'date' },
    { header: t('csv.category'), value: (row) => codeLabel(row.category) },
    { header: t('csv.description'), value: (row) => row.description },
    { header: t('csv.amount'), value: (row) => row.amount, kind: 'money' },
    { header: t('csv.status'), value: (row) => codeLabel(row.approvalStatus) },
    { header: t('csv.approvalNotes'), value: (row) => row.approvalNotes },
  ]
}

interface ExpenseListProps {
  pumpId: number | null
  list: ExpenseListState
}

export function ExpenseList({ pumpId, list }: ExpenseListProps) {
  const { t } = useTranslation(['expenses', 'common'])
  const columns = useMemo(() => csvColumns(t), [t])
  const { user }     = useAuthStore()
  const qc           = useQueryClient()
  const { addToast } = useToastStore()
  const {
    expensesPage, expenses, isLoading,
    categoryFilter, setCategoryFilter, statusFilter, setStatusFilter,
    setPage, setPageSize, loadAllExpenses,
  } = list

  const submitMutation = useMutation({
    mutationFn: (expenseId: number) => expenseApi.submitExpense(pumpId!, expenseId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['expenses', pumpId] })
      addToast(t('toast.submitted'), 'success')
    },
    onError: (err) => addToast(parseApiError(err, t('toast.submitFailed')), 'error'),
  })

  const deleteMutation = useMutation({
    mutationFn: (expenseId: number) => expenseApi.deleteExpense(pumpId!, expenseId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['expenses', pumpId] })
      addToast(t('toast.deleted'), 'success')
    },
    onError: (err) => addToast(parseApiError(err, t('toast.deleteFailed')), 'error'),
  })

  const [rejectingExpense, setRejectingExpense] = useState<PumpExpense | null>(null)
  const [rejectError, setRejectError] = useState<string | null>(null)

  const approveMutation = useMutation({
    mutationFn: ({ expenseId, data }: { expenseId: number; data: ApproveExpenseRequest }) =>
      expenseApi.approveExpense(pumpId!, expenseId, data),
    onSuccess: (_, { data }) => {
      qc.invalidateQueries({ queryKey: ['expenses', pumpId] })
      qc.invalidateQueries({ queryKey: ['approvals', pumpId] })
      setRejectingExpense(null)
      addToast(data.action === 'APPROVED' ? t('toast.approved') : t('toast.rejected'), data.action === 'APPROVED' ? 'success' : 'warning')
    },
    onError: (err, { data }) => {
      const message = parseApiError(err, t('toast.updateFailed'))
      if (data.action === 'REJECTED') setRejectError(message)
      else addToast(message, 'error')
    },
  })

  const [voidingExpense, setVoidingExpense] = useState<PumpExpense | null>(null)

  const openRejectDialog = (expense: PumpExpense) => {
    setRejectError(null)
    setRejectingExpense(expense)
  }

  const isOwner        = user?.role === 'OWNER'
  const isOwnerOrAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN'
  const isAccountant   = user?.role === 'ACCOUNTANT'
  const canVoid        = canPerformAction('expense:void', user?.role)

  const clearFilters = () => { setCategoryFilter(''); setStatusFilter(''); setPage(0) }

  return (
    <>
    {rejectingExpense && (
      <RejectReasonDialog
        title={t('list.rejectTitle')}
        subtitle={`${codeLabel(rejectingExpense.category)} · ${formatCurrency(rejectingExpense.amount)} · ${rejectingExpense.description}`}
        isPending={approveMutation.isPending}
        error={rejectError}
        onConfirm={(reason) => approveMutation.mutate({
          expenseId: rejectingExpense.id, data: { action: 'REJECTED', notes: reason },
        })}
        onClose={() => setRejectingExpense(null)}
      />
    )}
    {voidingExpense && pumpId && (
      <VoidExpenseDialog pumpId={pumpId} expense={voidingExpense} onClose={() => setVoidingExpense(null)} />
    )}
    <PageSection
      title={t('list.title')}
      description={isOwnerOrAdmin && (
        <Trans t={t} i18nKey="list.approvalHint" components={{ status: <span className="font-semibold text-warn" /> }} />
      )}
      actions={<CsvExportButton filename={csvFilename('expenses')} columns={columns} rows={loadAllExpenses} disabled={expenses.length === 0} />}
      flush
    >
      <FilterBar label={t('list.filtersLabel')} onClearFilters={clearFilters} hasActiveFilters={Boolean(categoryFilter || statusFilter)}>
        <div className="w-52 max-w-full">
          <SearchableSelect label={t('list.filterByCategory')}
            value={categoryFilter}
            onChange={v => { setCategoryFilter(v as ExpenseCategory | ''); setPage(0) }}
            options={[
              { value: '', label: t('list.allCategories') },
              ...CATEGORIES.map(c => ({ value: c, label: codeLabel(c) })),
            ]}
            size="sm"
          />
        </div>
        <div className="w-52 max-w-full">
          <SearchableSelect label={t('list.filterByStatus')}
            value={statusFilter}
            onChange={v => { setStatusFilter(v as ExpenseApprovalStatus | ''); setPage(0) }}
            options={[
              { value: '', label: t('list.allStatuses') },
              { value: 'DRAFT',            label: t('list.statusDraft') },
              { value: 'PENDING_APPROVAL', label: t('list.statusPendingApproval') },
              { value: 'APPROVED',         label: t('list.statusApproved') },
              { value: 'REJECTED',         label: t('list.statusRejected') },
              { value: 'VOIDED',           label: t('list.statusVoided') },
            ]}
            size="sm"
          />
        </div>
      </FilterBar>

      {isLoading ? (
        <div className="px-5 py-4"><SkeletonTable rows={4} cols={3} /></div>
      ) : expenses.length === 0 ? (
        <EmptyState
          icon="expenses"
          title={t('list.emptyTitle')}
          subtitle={isAccountant ? t('list.emptyAccountant') : t('list.emptySubtitle')}
        />
      ) : (
        <>
        <div className="divide-y divide-border">
          {expenses.map(e => (
            <ExpenseRow key={e.id} expense={e} actions={{
              canSubmit: e.approvalStatus === 'DRAFT' && (isOwnerOrAdmin || e.recordedByUserId === user?.userId),
              canReview: isOwnerOrAdmin && e.approvalStatus === 'PENDING_APPROVAL',
              canDelete: isOwner && e.approvalStatus === 'DRAFT',
              canVoid: canVoid && e.approvalStatus === 'APPROVED',
              busy: submitMutation.isPending || approveMutation.isPending || deleteMutation.isPending,
              onSubmit: () => submitMutation.mutate(e.id),
              onApprove: () => approveMutation.mutate({ expenseId: e.id, data: { action: 'APPROVED' } }),
              onReject: () => openRejectDialog(e),
              onDelete: () => deleteMutation.mutate(e.id),
              onVoid: () => setVoidingExpense(e),
            }} />
          ))}
        </div>
        {expensesPage && (
          <div className="px-5">
            <Pagination
              data={expensesPage}
              onPageChange={p => setPage(p)}
              onPageSizeChange={s => { setPageSize(s); setPage(0) }}
              pageSizeOptions={[10, 20, 50]}
            />
          </div>
        )}
        </>
      )}
    </PageSection>
    </>
  )
}
