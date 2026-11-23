import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { canPerformAction } from '../../permissions/permissions'
import { expenseApi } from '../../api/expenseApi'
import { Reveal } from '../../components/Reveal'
import { MoneyText } from '../../components/AmountText'
import { ExpenseEntryForm } from './ExpenseEntryForm'
import { ExpenseList } from './ExpenseList'
import { useExpenseList } from './useExpenseList'

export default function ExpensesPage() {
  const { t } = useTranslation('expenses')
  const { user } = useAuthStore()

  const { selectedPumpId: pumpId } = usePumpStore()

  const list = useExpenseList(pumpId)
  const { expensesPage, isLoading } = list

  const { data: summary } = useQuery({
    queryKey: ['expenses', pumpId, 'summary'],
    queryFn: () => expenseApi.getSummary(pumpId!),
    enabled: !!pumpId,
  })

  const totalExpenses = summary?.approvedTotal ?? 0
  const pendingCount = summary?.pendingCount ?? 0
  const canCreateExpense = canPerformAction('expense:create', user?.role)

  if (!pumpId) {
    return (
      <div className="ui-page ui-page--narrow">
        <h1 className="sr-only">{t('page.title')}</h1>
        <div className="ui-alert ui-alert-warning text-sm" role="status">
          {t('page.noPump')}
        </div>
      </div>
    )
  }

  return (
    <div className="ui-page ui-page--narrow space-y-5">

      {/* ── Header ── */}
      <Reveal delay={60}>
      <div className="ui-section-hero">
        <div>
          <p className="ui-section-kicker">{t('page.kicker')}</p>
          <h1 className="ui-title-sm">{t('page.title')}</h1>
          <p className="ui-subtitle">{t('page.subtitle')}</p>
        </div>
        <div className="ui-section-meta">
          <div className="ui-section-meta-pill">
            <span className="ui-section-meta-label">{t('page.records')}</span>
            <span className="ui-section-meta-value">{expensesPage?.totalElements ?? 0}</span>
          </div>
          <div className="ui-section-meta-pill">
            <span className="ui-section-meta-label">{t('page.pending')}</span>
            <span className="ui-section-meta-value">{pendingCount}</span>
          </div>
        </div>
      </div>

      </Reveal>

      {/* ── Summary strip ── */}
      {!isLoading && (
        <Reveal delay={120}>
        <div className="ui-summary-strip">
          <div className="ui-summary-strip__item">
            <span className="ui-summary-strip__label">{t('page.approvedTotal')}</span>
            <MoneyText value={totalExpenses} className="ui-summary-strip__value" />
          </div>
          <div className="ui-summary-strip__item">
            <span className="ui-summary-strip__label">{t('page.totalRecords')}</span>
            <span className="ui-summary-strip__value">{expensesPage?.totalElements ?? 0}</span>
          </div>
          {pendingCount > 0 && (
            <div className="ui-summary-strip__item">
              <span className="ui-summary-strip__label text-warn">{t('page.pendingApproval')}</span>
              <span className="ui-summary-strip__value text-warn">{pendingCount}</span>
            </div>
          )}
        </div>
        </Reveal>
      )}

      {canCreateExpense && <ExpenseEntryForm pumpId={pumpId} />}

      {/* ── Expense list ── */}
      <ExpenseList pumpId={pumpId} list={list} />
    </div>
  )
}
