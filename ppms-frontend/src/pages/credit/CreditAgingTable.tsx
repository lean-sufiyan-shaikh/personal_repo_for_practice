import { ArrowDown, ArrowUp } from 'lucide-react'
import type { CreditAgingRow, CreditStanding } from '../../api/creditApi'
import { formatCurrency } from '../../utils/format'
import { formatIstDate } from '../../utils/date'
import type { AgingSortKey, SortDirection } from './creditAging'

const STATUS_CHIP: Record<CreditStanding, { label: string; className: string }> = {
  OK:         { label: 'OK',         className: 'bg-ok-bg text-ok' },
  OVERDUE:    { label: 'Overdue',    className: 'bg-bad-bg text-bad' },
  OVER_LIMIT: { label: 'Over limit', className: 'bg-warn-bg text-warn' },
  INACTIVE:   { label: 'Inactive',   className: 'bg-neutral-bg text-neutral' },
}

const COLUMNS: { key: AgingSortKey; label: string; numeric: boolean }[] = [
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

function amountCell(amount: number) {
  return amount > 0 ? formatCurrency(amount) : <span className="text-ink-3">—</span>
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
    <div className="ui-table-wrap">
      <table className="text-sm">
        <caption className="sr-only">
          Credit accounts with their unpaid balance by age; column headers sort the table
        </caption>
        <thead>
          <tr>
            {COLUMNS.map(column => {
              const active = column.key === sortKey
              const SortIcon = sortDirection === 'asc' ? ArrowUp : ArrowDown
              return (
                <th key={column.key} scope="col" className={column.numeric ? 'text-right' : 'text-left'}
                  aria-sort={active ? (sortDirection === 'asc' ? 'ascending' : 'descending') : 'none'}>
                  <button type="button" onClick={() => onSort(column.key)}
                    className={`inline-flex items-center gap-1 font-medium ${active ? 'text-ink' : 'text-ink-2'}`}>
                    {column.label}
                    {active && <SortIcon size={12} strokeWidth={2.4} aria-hidden="true" />}
                  </button>
                </th>
              )
            })}
            <th scope="col" className="text-left">Status</th>
            <th scope="col" className="text-right"><span className="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          {rows.map(row => {
            const status = STATUS_CHIP[row.creditStatus]
            return (
              <tr key={row.clientId}>
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
                <td className="whitespace-nowrap text-right font-semibold tabular-nums text-ink">{formatCurrency(row.outstanding)}</td>
                <td className="whitespace-nowrap text-right tabular-nums">{amountCell(row.upTo30Days)}</td>
                <td className="whitespace-nowrap text-right tabular-nums">{amountCell(row.days31To60)}</td>
                <td className="whitespace-nowrap text-right tabular-nums">{amountCell(row.days61To90)}</td>
                <td className="whitespace-nowrap text-right tabular-nums">{amountCell(row.over90Days)}</td>
                <td className="whitespace-nowrap text-ink-2">{row.oldestUnpaidSaleDate ? formatIstDate(row.oldestUnpaidSaleDate) : '—'}</td>
                <td className="whitespace-nowrap text-ink-2">{row.lastPaymentDate ? formatIstDate(row.lastPaymentDate) : 'Never'}</td>
                <td className={`whitespace-nowrap text-right tabular-nums ${row.daysOverdue > 0 ? 'font-semibold text-bad' : 'text-ink-3'}`}>
                  {row.daysOverdue > 0 ? row.daysOverdue : '—'}
                </td>
                <td className="whitespace-nowrap">
                  <span className={`rounded-badge px-2 py-0.5 text-xs font-semibold ${status.className}`}>{status.label}</span>
                  {row.pendingPaymentCount > 0 && (
                    <span className="ml-1 rounded-badge bg-info-bg px-2 py-0.5 text-xs font-semibold text-info">
                      {row.pendingPaymentCount} pending
                    </span>
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
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
