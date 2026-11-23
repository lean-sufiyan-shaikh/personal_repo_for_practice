import { Hourglass } from 'lucide-react'
import type { CreditAgingRow } from '../../api/creditApi'
import { formatCurrency } from '../../utils/format'
import { formatIstDate } from '../../utils/date'
import { DataTable } from '../../components/DataTable'
import type { DataTableColumn } from '../../components/DataTable'
import { MoneyText } from '../../components/AmountText'
import { StatusBadge } from '../../components/StatusBadge'
import { creditStandingBadge } from '../../components/statusBadges'
import type { AgingSortKey, SortDirection } from './creditAging'

const SORTABLE_COLUMNS: { key: AgingSortKey; label: string; numeric: boolean }[] = [
  { key: 'name', label: 'Client', numeric: false },
  { key: 'outstanding', label: 'Outstanding', numeric: true },
  { key: 'upTo30Days', label: '0–30 days', numeric: true },
  { key: 'days31To60', label: '31–60', numeric: true },
  { key: 'days61To90', label: '61–90', numeric: true },
  { key: 'over90Days', label: '90+', numeric: true },
  { key: 'oldestUnpaidSaleDate', label: 'Oldest unpaid', numeric: false },
  { key: 'lastPaymentDate', label: 'Last payment', numeric: false },
  { key: 'daysOverdue', label: 'Days overdue', numeric: true },
]

const COLUMNS: DataTableColumn<CreditAgingRow>[] = [
  ...SORTABLE_COLUMNS.map(column => ({ key: column.key, header: column.label, numeric: column.numeric, sortable: true })),
  { key: 'status', header: 'Status' },
  { key: 'actions', header: <span className="sr-only">Actions</span>, align: 'right' },
]

function amountCell(amount: number) {
  return amount > 0 ? <MoneyText value={amount} /> : <span className="text-ink-3">—</span>
}
interface CreditAgingTableProps {
  rows: CreditAgingRow[]
  sortKey: AgingSortKey
  sortDirection: SortDirection
  canAdjustLimit: boolean
  onSort: (key: AgingSortKey) => void
  onOpenClient: (clientId: number) => void
  onSetLimit: (clientId: number) => void
}

export function CreditAgingTable({
  rows, sortKey, sortDirection, canAdjustLimit, onSort, onOpenClient, onSetLimit,
}: CreditAgingTableProps) {
  return (
    <DataTable
      caption="Credit accounts with their unpaid balance by age; column headers sort the table"
      captionHidden
      regionLabel="Credit aging"
      columns={COLUMNS}
      rows={rows}
      rowKey={row => row.clientId}
      sort={{ key: sortKey, direction: sortDirection }}
      onSortChange={key => onSort(key as AgingSortKey)}
      renderRow={row => (
        <tr>
          <th scope="row" className="text-left font-medium">
            <button type="button" onClick={() => onOpenClient(row.clientId)} className="ui-link text-left">
              {row.name}
            </button>
            <span className="block text-xs font-normal text-ink-3">
              {row.phone ?? 'No phone'}
              {row.subAccountCount > 0 && ` · ${row.subAccountCount} sub-account${row.subAccountCount !== 1 ? 's' : ''}`}
              {row.creditLimit > 0 && ` · limit ${formatCurrency(row.creditLimit)}`}
            </span>
          </th>
          <td className="text-right font-semibold text-ink"><MoneyText value={row.outstanding} /></td>
          <td className="text-right">{amountCell(row.upTo30Days)}</td>
          <td className="text-right">{amountCell(row.days31To60)}</td>
          <td className="text-right">{amountCell(row.days61To90)}</td>
          <td className="text-right">{amountCell(row.over90Days)}</td>
          <td className="whitespace-nowrap text-ink-2">{row.oldestUnpaidSaleDate ? formatIstDate(row.oldestUnpaidSaleDate) : '—'}</td>
          <td className="whitespace-nowrap text-ink-2">{row.lastPaymentDate ? formatIstDate(row.lastPaymentDate) : 'Never'}</td>
          <td className={`whitespace-nowrap text-right tabular-nums ${row.daysOverdue > 0 ? 'font-semibold text-bad' : 'text-ink-3'}`}>
            {row.daysOverdue > 0 ? row.daysOverdue : '—'}
          </td>
          <td className="whitespace-nowrap">
            <StatusBadge {...creditStandingBadge(row.creditStatus)} />
            {row.pendingPaymentCount > 0 && (
              <StatusBadge tone="info" icon={Hourglass} label={`${row.pendingPaymentCount} pending`} className="ml-1" />
            )}
          </td>
          <td className="whitespace-nowrap text-right">
            {canAdjustLimit && (
              <button type="button" onClick={() => onSetLimit(row.clientId)}
                aria-label={`Set credit limit for ${row.name}`} className="ui-btn ui-btn-ghost ui-btn-compact">
                Limit
              </button>
            )}
            <button type="button" onClick={() => onOpenClient(row.clientId)}
              aria-label={`View ledger of ${row.name}`} className="ui-btn ui-btn-secondary ui-btn-compact">
              View ledger
            </button>
          </td>
        </tr>
      )}
    />
  )
}
