import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useAuthStore } from '../../store/authStore'
import { expenseApi } from '../../api/expenseApi'
import type { ExpenseCategory, ApproveExpenseRequest, ExpenseApprovalStatus, PumpExpense } from '../../api/expenseApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { Pagination } from '../../components/Pagination'
import { SkeletonTable } from '../../components/Skeleton'
import { Reveal } from '../../components/Reveal'
import { EmptyState } from '../../components/EmptyState'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { formatCurrency } from '../../utils/format'
import { ConfirmButton } from '../../components/ConfirmButton'
import { RejectReasonDialog } from '../../components/RejectReasonDialog'
import { StatusBadge } from '../../components/StatusBadge'
import { approvalStatusBadge } from '../../components/statusBadges'
import { MoneyText } from '../../components/AmountText'
import { CATEGORIES, fmtDate } from './expenseFormat'
import type { ExpenseListState } from './useExpenseList'
import { codeLabel } from '../../constants/labels'
import { CsvExportButton } from '../../components/CsvExportButton'
import { csvFilename } from '../../utils/csv'
import type { CsvColumn } from '../../utils/csv'

const CSV_COLUMNS: CsvColumn<PumpExpense>[] = [
  { header: 'Date', value: (row) => row.expenseDate, kind: 'date' },
  { header: 'Category', value: (row) => codeLabel(row.category) },
  { header: 'Description', value: (row) => row.description },
  { header: 'Amount', value: (row) => row.amount, kind: 'money' },
  { header: 'Status', value: (row) => codeLabel(row.approvalStatus) },
  { header: 'Approval notes', value: (row) => row.approvalNotes },
]

function ApprovalBadge({ expense }: { expense: PumpExpense }) {
  const badge = approvalStatusBadge(expense.approvalStatus)
  const label = expense.approvalStatus === 'REJECTED' && expense.approvalNotes ? `${badge.label}: ${expense.approvalNotes}` : badge.label
  return <StatusBadge {...badge} label={label} className="whitespace-normal!" />
}

interface ExpenseListProps {
  pumpId: number | null
  list: ExpenseListState
}

export function ExpenseList({ pumpId, list }: ExpenseListProps) {
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
      addToast('Expense submitted for approval', 'success')
    },
    onError: (err) => addToast(parseApiError(err, 'Failed to submit expense'), 'error'),
  })

  const deleteMutation = useMutation({
    mutationFn: (expenseId: number) => expenseApi.deleteExpense(pumpId!, expenseId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['expenses', pumpId] })
      addToast('Expense deleted', 'success')
    },
    onError: (err) => addToast(parseApiError(err, 'Failed to delete expense'), 'error'),
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
      addToast(`Expense ${data.action === 'APPROVED' ? 'approved' : 'rejected'}`, data.action === 'APPROVED' ? 'success' : 'warning')
    },
    onError: (err, { data }) => {
      const message = parseApiError(err, 'Failed to update expense')
      if (data.action === 'REJECTED') setRejectError(message)
      else addToast(message, 'error')
    },
  })

  const openRejectDialog = (expense: PumpExpense) => {
    setRejectError(null)
    setRejectingExpense(expense)
  }

  const isOwner        = user?.role === 'OWNER'
  const isOwnerOrAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN'
  const isAccountant   = user?.role === 'ACCOUNTANT'

  return (
    <Reveal delay={240}>
    {rejectingExpense && (
      <RejectReasonDialog
        title="Reject Expense"
        subtitle={`${codeLabel(rejectingExpense.category)} · ${formatCurrency(rejectingExpense.amount)} · ${rejectingExpense.description}`}
        isPending={approveMutation.isPending}
        error={rejectError}
        onConfirm={(reason) => approveMutation.mutate({
          expenseId: rejectingExpense.id, data: { action: 'REJECTED', notes: reason },
        })}
        onClose={() => setRejectingExpense(null)}
      />
    )}
    <div className="ui-card p-0 overflow-hidden">
      <div className="ui-toolbar">
        <p className="ui-toolbar-title">All Expenses</p>
        <div className="ui-toolbar-actions">
          <div className="w-52 max-w-full">
          <SearchableSelect label="Filter by category"
            value={categoryFilter}
            onChange={v => { setCategoryFilter(v as ExpenseCategory | ''); setPage(0) }}
            options={[
              { value: '', label: 'All Categories' },
              ...CATEGORIES.map(c => ({ value: c, label: codeLabel(c) })),
            ]}
            size="sm"
          />
        </div>

          <div className="w-52 max-w-full">
          <SearchableSelect label="Filter by status"
            value={statusFilter}
            onChange={v => { setStatusFilter(v as ExpenseApprovalStatus | ''); setPage(0) }}
            options={[
              { value: '', label: 'All Statuses' },
              { value: 'DRAFT',            label: 'Draft' },
              { value: 'PENDING_APPROVAL', label: 'Pending Approval' },
              { value: 'APPROVED',         label: 'Approved' },
              { value: 'REJECTED',         label: 'Rejected' },
            ]}
            size="sm"
          />
        </div>

          {(categoryFilter || statusFilter) && (
            <button
              onClick={() => { setCategoryFilter(''); setStatusFilter(''); setPage(0) }}
              className="ui-btn ui-btn-ghost text-xs"
            >
              Clear filters
            </button>
          )}
          <CsvExportButton filename={csvFilename('expenses')} columns={CSV_COLUMNS} rows={loadAllExpenses}
            disabled={expenses.length === 0} />
        </div>
      </div>
      {isOwnerOrAdmin && (
        <div className="px-5 py-2 text-xs text-ink-3 border-b border-border bg-surface-sunken">
          Approve and reject actions appear only for expenses in <span className="font-semibold text-warn">Pending Approval</span>.
        </div>
      )}

      {isLoading ? (
        <div className="px-5 py-4"><SkeletonTable rows={4} cols={3} /></div>
      ) : expenses.length === 0 ? (
        <EmptyState
          icon="expenses"
          title="No expenses recorded yet"
          subtitle={isAccountant ? 'No records to display.' : 'Use the form above to record your first expense.'}
        />
      ) : (
        <>
        <div className="divide-y divide-border">
          {expenses.map(e => (
            <div key={e.id} className="px-5 py-3 flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between sm:gap-3">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1 min-w-0">
                <StatusBadge tone="neutral" label={codeLabel(e.category)} className="flex-shrink-0" />
                <div className="min-w-0">
                  <div className="flex items-center gap-2 min-w-0 flex-wrap">
                    <p className="text-sm text-ink break-words sm:truncate">{e.description}</p>
                    <ApprovalBadge expense={e} />
                  </div>
                  <p className="text-xs text-ink-3">{fmtDate(e.expenseDate)}</p>
                </div>
              </div>
              <div className="flex flex-wrap items-center gap-2 sm:flex-shrink-0">
                <MoneyText
                  value={e.amount}
                  className={`text-sm font-semibold mr-auto sm:mr-0 ${e.approvalStatus === 'REJECTED' ? 'text-ink-3 line-through' : 'text-ink'}`}
                />
                {/* Submit — shown on DRAFT expenses to the creator or OWNER/ADMIN */}
                {e.approvalStatus === 'DRAFT' && (isOwnerOrAdmin || e.recordedByUserId === user?.userId) && (
                  <button
                    onClick={() => submitMutation.mutate(e.id)}
                    disabled={submitMutation.isPending}
                    className="ui-btn ui-btn-primary min-h-0 px-3 py-1 text-xs"
                  >
                    Submit
                  </button>
                )}
                {/* Approve/Reject — only for Owner/Admin on pending expenses */}
                {isOwnerOrAdmin && e.approvalStatus === 'PENDING_APPROVAL' && (
                  <>
                    <button
                      onClick={() => approveMutation.mutate({ expenseId: e.id, data: { action: 'APPROVED' } })}
                      disabled={approveMutation.isPending}
                      className="ui-btn ui-btn-primary min-h-0 px-3 py-1 text-xs"
                    >
                      Approve
                    </button>
                    <button
                      onClick={() => openRejectDialog(e)}
                      disabled={approveMutation.isPending}
                      className="ui-btn ui-btn-danger min-h-0 px-3 py-1 text-xs"
                    >
                      Reject
                    </button>
                  </>
                )}
                {/* Delete — DRAFT only, backend enforces this; OWNER only */}
                {isOwner && e.approvalStatus === 'DRAFT' && (
                  <ConfirmButton
                    onConfirm={() => deleteMutation.mutate(e.id)}
                    disabled={deleteMutation.isPending}
                    triggerAriaLabel="Delete expense"
                  />
                )}
              </div>
            </div>
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
    </div>
    </Reveal>
  )
}
