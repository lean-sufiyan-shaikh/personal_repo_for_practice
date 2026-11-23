import type { ApprovalItem } from '../../api/approvalApi'
import { DataTable } from '../../components/DataTable'
import type { DataTableColumn } from '../../components/DataTable'
import { MoneyText } from '../../components/AmountText'
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

  const columns: DataTableColumn<ApprovalItem>[] = [
    {
      key: 'select',
      className: 'w-10',
      header: (
        <input
          type="checkbox"
          aria-label="Select all items"
          checked={allSelected}
          ref={element => { if (element) element.indeterminate = someSelected && !allSelected }}
          disabled={selectable.length === 0}
          onChange={onToggleAll}
        />
      ),
    },
    { key: 'type', header: 'Type' },
    { key: 'what', header: 'Category or client' },
    { key: 'amount', header: 'Amount', numeric: true },
    { key: 'submittedBy', header: 'Submitted by' },
    { key: 'waiting', header: 'Waiting' },
    { key: 'date', header: 'Date' },
    { key: 'note', header: 'Note' },
  ]

  return (
    <DataTable
      caption="Expenses and credit payments waiting for approval, oldest first"
      captionHidden
      regionLabel="Approval queue"
      columns={columns}
      rows={items}
      rowKey={approvalKey}
      renderRow={item => {
        const key = approvalKey(item)
        const what = item.kind === 'EXPENSE' ? codeLabel(item.category ?? '') : (item.clientName ?? `Client #${item.clientId}`)
        return (
          <tr className={selectedKeys.has(key) ? 'bg-info-bg' : undefined}>
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
            <td className="text-right"><MoneyText value={item.amount} className="font-semibold text-ink" /></td>
            <td className="text-ink-2">{item.submittedByName ?? '—'}</td>
            <td className="whitespace-nowrap text-ink-2">{waitingFor(item.submittedAt, now)}</td>
            <td className="whitespace-nowrap text-ink-3">{formatIstDate(item.itemDate)}</td>
            <td className="max-w-xs text-ink-3">{item.note || '—'}</td>
          </tr>
        )
      }}
    />
  )
}
