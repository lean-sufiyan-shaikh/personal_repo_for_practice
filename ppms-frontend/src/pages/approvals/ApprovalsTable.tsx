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

function itemName(item: ApprovalItem) {
  return item.kind === 'EXPENSE' ? codeLabel(item.category ?? '') : (item.clientName ?? `Client #${item.clientId}`)
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
      cell: item => (
        <input
          type="checkbox"
          aria-label={`Select ${item.kind === 'EXPENSE' ? 'expense' : 'credit payment'} ${itemName(item)} ${formatCurrency(item.amount)}`}
          checked={selectedKeys.has(approvalKey(item))}
          disabled={item.blockedReason !== null}
          title={item.blockedReason ?? undefined}
          onChange={() => onToggle(item)}
        />
      ),
    },
    { key: 'type', header: 'Type', className: 'whitespace-nowrap', cell: item => <span className="text-ink-2">{item.kind === 'EXPENSE' ? 'Expense' : 'Credit payment'}</span> },
    {
      key: 'what',
      header: 'Category or client',
      cell: item => (
        <span className="font-medium text-ink">
          {itemName(item)}
          {item.blockedReason && <span className="block text-xs font-normal text-warn">{item.blockedReason}</span>}
        </span>
      ),
    },
    { key: 'amount', header: 'Amount', numeric: true, cell: item => <MoneyText value={item.amount} className="font-semibold text-ink" /> },
    { key: 'submittedBy', header: 'Submitted by', cell: item => <span className="text-ink-2">{item.submittedByName ?? '—'}</span> },
    { key: 'waiting', header: 'Waiting', className: 'whitespace-nowrap', cell: item => <span className="text-ink-2">{waitingFor(item.submittedAt, now)}</span> },
    { key: 'date', header: 'Date', className: 'whitespace-nowrap', cell: item => <span className="text-ink-3">{formatIstDate(item.itemDate)}</span> },
    { key: 'note', header: 'Note', className: 'max-w-xs', cell: item => <span className="text-ink-3">{item.note || '—'}</span> },
  ]

  return (
    <DataTable
      caption="Expenses and credit payments waiting for approval, oldest first"
      captionHidden
      regionLabel="Approval queue"
      columns={columns}
      rows={items}
      rowKey={approvalKey}
      rowClassName={item => selectedKeys.has(approvalKey(item)) ? 'bg-info-bg' : undefined}
    />
  )
}
