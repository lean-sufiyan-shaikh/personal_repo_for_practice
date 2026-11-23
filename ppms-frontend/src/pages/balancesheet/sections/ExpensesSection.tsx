import type { ExpenseSummary } from '../../../api/balanceSheetApi'
import { Td } from '../bsTableCells'
import { bsColumn } from '../bsFormat'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { StatusBadge } from '../../../components/StatusBadge'
import { MoneyText } from '../../../components/AmountText'
import { codeLabel } from '../../../constants/labels'

type ExpenseLine = ExpenseSummary['lines'][number]

const COLUMNS: DataTableColumn<ExpenseLine>[] = [
  bsColumn('category', 'Category'),
  bsColumn('description', 'Description'),
  bsColumn('recordedBy', 'Recorded By'),
  bsColumn('amount', 'Amount', true),
]

// ── Approved expenses section (DAY reports only) ────────────────────────────────

export function ExpensesSection({ expenses }: { expenses: ExpenseSummary }) {
  return (
    <section>
      <h3 className="text-xs font-semibold text-ink-3 uppercase tracking-wider mb-3">
        Approved Expenses
        <span className="ml-2 text-ink-3 normal-case font-normal">
          ({expenses.lines.length} expense{expenses.lines.length !== 1 ? 's' : ''})
        </span>
      </h3>
      <DataTable
        caption="Approved expenses"
        captionHidden
        regionLabel="Expenses"
        columns={COLUMNS}
        rows={expenses.lines}
        rowKey={(line) => line.id}
        renderRow={(line) => (
          <tr>
            <Td><StatusBadge tone="neutral" label={codeLabel(line.category)} /></Td>
            <Td><span className="text-xs text-ink-3">{line.description}</span></Td>
            <Td><span className="text-xs text-ink-3">{line.recordedByName}</span></Td>
            <Td right><MoneyText value={line.amount} tone="bad" className="font-medium" /></Td>
          </tr>
        )}
        footer={
          <tr className="bg-surface-sunken">
            <Td colSpan={3}>Total Expenses</Td>
            <Td right><MoneyText value={expenses.totalAmount} tone="bad" /></Td>
          </tr>
        }
      />
    </section>
  )
}
