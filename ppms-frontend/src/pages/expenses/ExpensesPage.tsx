import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { expenseApi } from '../../api/expenseApi'
import type { ExpenseCategory, CreateExpenseRequest, ApproveExpenseRequest } from '../../api/expenseApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { Pagination } from '../../components/Pagination'
import { localDateInputValue } from '../../utils/date'

const CATEGORIES: ExpenseCategory[] = ['FUEL', 'MAINTENANCE', 'SALARY', 'UTILITIES', 'EQUIPMENT', 'OTHER']

const CATEGORY_COLORS: Record<ExpenseCategory, string> = {
  FUEL:        'bg-orange-100 text-orange-700',
  MAINTENANCE: 'bg-blue-100 text-blue-700',
  SALARY:      'bg-emerald-100 text-emerald-700',
  UTILITIES:   'bg-purple-100 text-purple-700',
  EQUIPMENT:   'bg-slate-100 text-slate-700',
  OTHER:       'bg-gray-100 text-gray-600',
}

function fmtAmt(n: number) {
  return `₹${n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function fmtDate(d: string) {
  return new Date(d).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })
}

export default function ExpensesPage() {
  const { user } = useAuthStore()
  const qc = useQueryClient()

  const { selectedPumpId: pumpId } = usePumpStore()

  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(10)
  const [categoryFilter, setCategoryFilter] = useState<ExpenseCategory | ''>('')
  const [statusFilter, setStatusFilter] = useState<'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | ''>('')

  const { data: expensesPage, isLoading } = useQuery({
    queryKey:  ['expenses', pumpId, page, pageSize, categoryFilter, statusFilter],
    queryFn:   () => expenseApi.getExpenses(
      pumpId!,
      page,
      pageSize,
      categoryFilter || undefined,
      (statusFilter as any) || undefined,
    ),
    enabled:   !!pumpId,
  })

  const expenses = expensesPage?.content ?? []

  const [form, setForm] = useState<CreateExpenseRequest>({
    category: 'MAINTENANCE',
    amount: 0,
    description: '',
    expenseDate: localDateInputValue(),
  })
  const [formError, setFormError] = useState<string | null>(null)
  const [reviewOpen, setReviewOpen] = useState(false)

  const createMutation = useMutation({
    mutationFn: (data: CreateExpenseRequest) => expenseApi.createExpense(pumpId!, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['expenses', pumpId] })
      setForm({ category: 'MAINTENANCE', amount: 0, description: '', expenseDate: localDateInputValue() })
      setFormError(null)
    },
    onError: (err: any) => setFormError(err?.response?.data?.message ?? 'Failed to record expense'),
  })

  const deleteMutation = useMutation({
    mutationFn: (expenseId: number) => expenseApi.deleteExpense(pumpId!, expenseId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['expenses', pumpId] }),
  })

  const validateForm = () => {
    if (!form.description.trim()) {
      setFormError('Description is required')
      return false
    }
    if (form.amount <= 0) {
      setFormError('Amount must be greater than zero')
      return false
    }
    setFormError(null)
    return true
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!validateForm()) return
    setReviewOpen(true)
  }

  const approveMutation = useMutation({
    mutationFn: ({ expenseId, data }: { expenseId: number; data: ApproveExpenseRequest }) =>
      expenseApi.approveExpense(pumpId!, expenseId, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['expenses', pumpId] }),
  })

  // Note: totalExpenses and pendingCount reflect the current page only.
  // For a pump with many expenses spread across pages, these numbers represent the visible page.
  const totalExpenses = expenses
    .filter(e => e.approvalStatus === 'APPROVED')
    .reduce((sum, e) => sum + e.amount, 0)
  const pendingCount = expenses.filter(e => e.approvalStatus === 'PENDING_APPROVAL').length
  const isOwner = user?.role === 'OWNER'
  const isOwnerOrAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN'
  const isAccountant = user?.role === 'ACCOUNTANT'

  return (
    <div className="ui-page ui-page--narrow space-y-5">

      {/* ── Header ── */}
      <div className="ui-section-hero">
        <div>
          <p className="ui-section-kicker">Cost tracking</p>
          <h1 className="ui-title-sm">Expense Tracker</h1>
          <p className="ui-subtitle">Record and review operational expenses at the pump</p>
        </div>
        <div className="ui-section-meta">
          <div className="ui-section-meta-pill">
            <span className="ui-section-meta-label">Records</span>
            <span className="ui-section-meta-value">{expensesPage?.totalElements ?? 0}</span>
          </div>
          <div className="ui-section-meta-pill">
            <span className="ui-section-meta-label">Pending</span>
            <span className="ui-section-meta-value">{pendingCount}</span>
          </div>
        </div>
      </div>

      {/* ── Summary strip ── */}
      {!isLoading && (
        <div className="ui-card flex flex-wrap gap-4">
          <div>
            <p className="text-xs text-slate-500 uppercase tracking-wide">Approved Total</p>
            <p className="text-xl font-bold text-slate-800">{fmtAmt(totalExpenses)}</p>
          </div>
          <div className="border-l border-slate-100 pl-4">
            <p className="text-xs text-slate-500 uppercase tracking-wide">Total Records</p>
            <p className="text-xl font-bold text-slate-800">{expensesPage?.totalElements ?? 0}</p>
          </div>
          {pendingCount > 0 && (
            <div className="border-l border-slate-100 pl-4">
              <p className="text-xs text-amber-500 uppercase tracking-wide">Pending Approval</p>
              <p className="text-xl font-bold text-amber-600">{pendingCount}</p>
            </div>
          )}
        </div>
      )}

      {/* ── Add expense form — hidden for ACCOUNTANT (read-only role) ── */}
      {!isAccountant && <form onSubmit={handleSubmit} className="ui-card space-y-3">
        <p className="text-sm font-semibold text-slate-700">Record Expense</p>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div>
            <label className="ui-label">Category</label>
            <SearchableSelect
              value={form.category}
              onChange={v => setForm(f => ({ ...f, category: v as ExpenseCategory }))}
              options={CATEGORIES.map(c => ({ value: c, label: c.replace('_', ' ') }))}
            />
          </div>

          <div>
            <label className="ui-label">Amount (₹)</label>
            <input
              type="number"
              min="0"
              step="0.01"
              value={form.amount || ''}
              onChange={e => setForm(f => ({ ...f, amount: parseFloat(e.target.value) || 0 }))}
              className="text-sm"
              placeholder="0.00"
              required
            />
          </div>

          <div>
            <label className="ui-label">Description</label>
            <input
              type="text"
              value={form.description}
              onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
              className="text-sm"
              placeholder="e.g. Pump servicing by XYZ"
              required
            />
          </div>

          <div>
            <label className="ui-label">Expense Date</label>
            <input
              type="date"
              value={form.expenseDate}
              onChange={e => setForm(f => ({ ...f, expenseDate: e.target.value }))}
              className="text-sm"
              required
            />
          </div>
        </div>

        {formError && <p className="ui-error-text">{formError}</p>}

        <button
          type="submit"
          disabled={createMutation.isPending}
          className="ui-btn ui-btn-primary"
        >
          {createMutation.isPending ? 'Saving…' : 'Review Expense'}
        </button>
      </form>}

      {reviewOpen && (
        <div className="ui-modal-backdrop" onClick={() => setReviewOpen(false)}>
          <div className="ui-modal-panel w-full max-w-md" onClick={(e) => e.stopPropagation()}>
            <div className="ui-modal-header ui-modal-header--themed ui-modal-header--warning">
              <div className="ui-modal-heading">
                <h2 className="ui-modal-title">Review Expense</h2>
                <p className="ui-modal-subtitle">Verify the expense details before saving the record.</p>
              </div>
              <button onClick={() => setReviewOpen(false)} className="ui-btn ui-btn-ghost ui-modal-close">&times;</button>
            </div>
            <div className="ui-modal-body space-y-4">
              {formError && <div className="ui-alert ui-alert-danger text-sm">{formError} Go back to modify the data and try again.</div>}
              <div className="ui-card-plain ui-card-muted divide-y divide-slate-100 overflow-hidden text-sm">
                <div className="flex items-center justify-between px-4 py-2.5">
                  <span className="text-slate-500 text-xs">Category</span>
                  <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${CATEGORY_COLORS[form.category]}`}>
                    {form.category.replace('_', ' ')}
                  </span>
                </div>
                <div className="flex items-center justify-between px-4 py-2.5">
                  <span className="text-slate-500 text-xs">Amount</span>
                  <span className="font-medium text-sm text-slate-800">{fmtAmt(form.amount)}</span>
                </div>
                <div className="flex items-center justify-between gap-3 px-4 py-2.5">
                  <span className="text-slate-500 text-xs">Description</span>
                  <span className="font-medium text-right text-sm text-slate-800">{form.description.trim()}</span>
                </div>
                <div className="flex items-center justify-between px-4 py-2.5">
                  <span className="text-slate-500 text-xs">Expense Date</span>
                  <span className="font-medium text-sm text-slate-800">{fmtDate(form.expenseDate)}</span>
                </div>
              </div>
            </div>
            <div className="ui-modal-footer">
              <button onClick={() => setReviewOpen(false)} className="ui-btn ui-btn-secondary">
                Back
              </button>
              <button
                onClick={() => {
                  if (!validateForm()) {
                    return
                  }
                  createMutation.mutate(form)
                }}
                disabled={createMutation.isPending}
                className="ui-btn ui-btn-primary"
              >
                {createMutation.isPending ? 'Saving…' : 'Add Expense'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Expense list ── */}
      <div className="ui-card p-0 overflow-hidden">
        <div className="ui-toolbar">
          <p className="ui-toolbar-title">All Expenses</p>
          <div className="ui-toolbar-actions">
            <div className="w-40">
            <SearchableSelect
              value={categoryFilter}
              onChange={v => { setCategoryFilter(v as any); setPage(0) }}
              options={[
                { value: '', label: 'All Categories' },
                ...CATEGORIES.map(c => ({ value: c, label: c.replace('_', ' ') })),
              ]}
              size="sm"
            />
          </div>

            <div className="w-44">
            <SearchableSelect
              value={statusFilter}
              onChange={v => { setStatusFilter(v as any); setPage(0) }}
              options={[
                { value: '', label: 'All Statuses' },
                { value: 'APPROVED',         label: 'Approved' },
                { value: 'PENDING_APPROVAL', label: 'Pending Approval' },
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
          </div>
        </div>

        {isLoading ? (
          <p className="ui-empty px-5 py-6">Loading…</p>
        ) : expenses.length === 0 ? (
          <p className="ui-empty px-5 py-6">No expenses recorded yet.</p>
        ) : (
          <>
          <div className="divide-y divide-slate-100">
            {expenses.map(e => (
              <div key={e.id} className="px-5 py-3 flex items-start justify-between gap-3">
                <div className="flex items-center gap-3 min-w-0">
                  <span className={`text-xs font-semibold px-2 py-0.5 rounded-full flex-shrink-0 ${CATEGORY_COLORS[e.category]}`}>
                    {e.category}
                  </span>
                  <div className="min-w-0">
                    <p className="text-sm text-slate-700 truncate">{e.description}</p>
                    <p className="text-xs text-slate-400">{fmtDate(e.expenseDate)}</p>
                    {/* Approval status badge */}
                    {e.approvalStatus === 'PENDING_APPROVAL' && (
                      <span className="inline-block mt-0.5 text-xs font-medium bg-amber-100 text-amber-700 px-2 py-0.5 rounded-full">
                        Pending Approval
                      </span>
                    )}
                    {e.approvalStatus === 'REJECTED' && (
                      <span className="inline-block mt-0.5 text-xs font-medium bg-red-100 text-red-700 px-2 py-0.5 rounded-full">
                        Rejected{e.approvalNotes ? `: ${e.approvalNotes}` : ''}
                      </span>
                    )}
                  </div>
                </div>
                <div className="flex items-center gap-2 flex-shrink-0">
                  <span className={`text-sm font-semibold ${e.approvalStatus === 'REJECTED' ? 'text-slate-400 line-through' : 'text-slate-800'}`}>
                    {fmtAmt(e.amount)}
                  </span>
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
                        onClick={() => approveMutation.mutate({ expenseId: e.id, data: { action: 'REJECTED' } })}
                        disabled={approveMutation.isPending}
                        className="ui-btn ui-btn-danger min-h-0 px-3 py-1 text-xs"
                      >
                        Reject
                      </button>
                    </>
                  )}
                  {isOwner && e.approvalStatus !== 'PENDING_APPROVAL' && (
                    <button
                      onClick={() => deleteMutation.mutate(e.id)}
                      disabled={deleteMutation.isPending}
                      className="ui-btn ui-btn-ghost min-h-0 px-0 py-0 text-xs text-red-400 hover:text-red-600 disabled:opacity-50"
                      title="Delete"
                    >
                      ✕
                    </button>
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
    </div>
  )
}
