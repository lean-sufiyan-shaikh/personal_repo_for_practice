import { RefreshCw, TrendingDown, TrendingUp } from 'lucide-react'
import { useAuthStore } from '../../store/authStore'
import type { BalanceSheetSummary } from '../../api/balanceSheetApi'
import { StatusBadge } from '../../components/StatusBadge'
import { MoneyText } from '../../components/AmountText'
import { fmtDate, fmtLitres } from './bsFormat'

// ── Report list item ────────────────────────────────────────────────────────────

interface ReportListItemProps {
  report: BalanceSheetSummary
  isSelected: boolean
  onClick: () => void
}

export function ReportListItem({ report, isSelected, onClick }: ReportListItemProps) {
  const { user } = useAuthStore()
  const isOwnerOrAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN'
  const isDay = report.reportType === 'DAY'
  const discrepancyColor = report.cashDiscrepancy === 0
    ? 'text-ok'
    : report.cashDiscrepancy < 0
      ? 'text-bad'
      : 'text-warn'

  return (
    <button
      onClick={onClick}
      className={`w-full text-left px-4 py-3.5 border-b border-border transition-colors ${
        isSelected ? 'bg-info-bg border-l-2 border-l-primary ring-1 ring-inset ring-info' : 'hover:bg-bg'
      }`}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="flex items-center gap-1.5 mb-0.5">
            <StatusBadge tone={isDay ? 'neutral' : 'info'} label={isDay ? 'Day' : 'Shift'} />
            <span className="text-sm font-medium text-ink truncate">{report.periodLabel}</span>
          </div>
          {report.stale && (
            <StatusBadge tone="warn" icon={RefreshCw} label="Stale — regenerate" className="mb-0.5" />
          )}
          <p className="text-xs text-ink-3">{fmtDate(report.reportDate)}</p>
          <p className="text-xs text-ink-3 mt-0.5">
            {report.shiftCount} shift{report.shiftCount !== 1 ? 's' : ''} · {fmtLitres(report.totalLitresSold)} sold
          </p>
        </div>
        <div className="text-right flex-shrink-0">
          <MoneyText value={report.totalExpectedRevenue} className="block text-sm font-semibold text-ink" />
          <p className={`text-xs font-medium ${discrepancyColor}`}>
            {report.cashDiscrepancy === 0
              ? 'Balanced'
              : <span className="inline-flex items-center gap-0.5">{report.cashDiscrepancy < 0 ? <TrendingDown size={12} strokeWidth={2} /> : <TrendingUp size={12} strokeWidth={2} />}<MoneyText value={Math.abs(report.cashDiscrepancy)} /></span>}
          </p>
          {isOwnerOrAdmin && <p className="text-xs text-ok"><MoneyText value={report.totalGrossProfit} /> profit</p>}
        </div>
      </div>
    </button>
  )
}
