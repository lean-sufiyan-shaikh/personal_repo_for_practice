import type { OperatorDiscrepancySummary, ProfitLossDayLine } from '../../../api/reportApi'
import { fmtAmt, fmtShortDayMonth } from '../reportFormat'

export function profitLossChartSummary(days: ProfitLossDayLine[]): string {
  if (days.length === 0) return 'No closed shifts in this period.'
  const revenue = days.reduce((sum, day) => sum + day.revenue, 0)
  const profit = days.reduce((sum, day) => sum + day.grossProfit, 0)
  const best = days.reduce((top, day) => (day.revenue > top.revenue ? day : top))
  const lossDays = days.filter((day) => day.grossProfit < 0).length
  return `Revenue ${fmtAmt(revenue)} and gross profit ${fmtAmt(profit)} over ${days.length} day${days.length === 1 ? '' : 's'} with sales. `
    + `Highest revenue on ${fmtShortDayMonth(best.date)} (${fmtAmt(best.revenue)}).`
    + (lossDays > 0 ? ` Gross loss on ${lossDays} day${lossDays === 1 ? '' : 's'}.` : '')
}

export function shortOverChartSummary(operators: OperatorDiscrepancySummary[]): string {
  if (operators.length === 0) return 'No short or over shifts in this period.'
  const short = operators.reduce((sum, op) => sum + op.totalShortAmount, 0)
  const over = operators.reduce((sum, op) => sum + op.totalOverAmount, 0)
  const mostShort = operators.reduce((top, op) => (op.totalShortAmount > top.totalShortAmount ? op : top))
  return `${fmtAmt(short)} short and ${fmtAmt(over)} over across ${operators.length} operator${operators.length === 1 ? '' : 's'}.`
    + (mostShort.totalShortAmount > 0 ? ` Most short: ${mostShort.operatorName} (${fmtAmt(mostShort.totalShortAmount)}).` : '')
}
