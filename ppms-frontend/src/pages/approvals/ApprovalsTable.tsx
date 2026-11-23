import type { ApprovalItem } from '../../api/approvalApi'
import { codeLabel } from '../../constants/labels'
import { formatIstDate } from '../../utils/date'
import { formatCurrency } from '../../utils/format'
import { approvalKey, waitingFor } from './approvalFormat'

interface ApprovalsTableProps {
  items: ApprovalItem[]
  selectedKeys: Set<string>
  now: number
  onToggle: (item: ApprovalItem) => void
  onToggleAll: () => void
}

export function ApprovalsTable({ items, selectedKeys, now, onToggle, onToggleAll }: ApprovalsTableProps) {
  const selectable = items.filter(item => item.blockedReason === null)
  const allSelected = selectable.length > 0 && selectable.every(item => selectedKeys.has(approvalKey(item)))
  const someSelected = selectable.some(item => selectedKeys.has(approvalKey(item)))

  return (
    <div className="ui-table-wrap">
      <table className="text-sm">
        <caption className="sr-only">Expenses and credit payments waiting for approval, oldest first</caption>
        <thead>
          <tr>
            <th scope="col" className="w-10 text-left">
              <input
                type="checkbox"
                aria-label="Select all items"
                checked={allSelected}
                ref={element => { if (element) element.indeterminate = someSelected && !allSelected }}
                disabled={selectable.length === 0}
                onChange={onToggleAll}
              />
            </th>
            <th scope="col" className="text-left">Type</th>
            <th scope="col" className="text-left">Category or client</th>
            <th scope="col" className="text-right">Amount</th>
            <th scope="col" className="text-left">Submitted by</th>
            <th scope="col" className="text-left">Waiting</th>
            <th scope="col" className="text-left">Date</th>
            <th scope="col" className="text-left">Note</th>
          </tr>
        </thead>
        <tbody>
          {items.map(item => {
            const key = approvalKey(item)
            const what = item.kind === 'EXPENSE' ? codeLabel(item.category ?? '') : (item.clientName ?? `Client #${item.clientId}`)
            return (
              <tr key={key} className={selectedKeys.has(key) ? 'bg-info-bg' : undefined}>
                <td>
                  <input
                    type="checkbox"
                    aria-label={`Select ${item.kind === 'EXPENSE' ? 'expense' : 'credit payment'} ${what} ${formatCurrency(item.amount)}`}
                    checked={selectedKeys.has(key)}
                    disabled={item.blockedReason !== null}
                    title={item.blockedReason ?? undefined}
                    onChange={() => onToggle(item)}
                  />
                </td>
                <td className="whitespace-nowrap text-ink-2">{item.kind === 'EXPENSE' ? 'Expense' : 'Credit payment'}</td>
                <td className="font-medium text-ink">
                  {what}
                  {item.blockedReason && <span className="block text-xs font-normal text-warn">{item.blockedReason}</span>}
                </td>
                <td className="whitespace-nowrap text-right font-semibold tabular-nums text-ink">{formatCurrency(item.amount)}</td>
                <td className="text-ink-2">{item.submittedByName ?? '—'}</td>
                <td className="whitespace-nowrap text-ink-2">{waitingFor(item.submittedAt, now)}</td>
                <td className="whitespace-nowrap text-ink-3">{formatIstDate(item.itemDate)}</td>
                <td className="max-w-xs text-ink-3">{item.note || '—'}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
