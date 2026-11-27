import { useId } from 'react'
import { ChevronDown } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { StaffMember } from '../../api/userApi'
import type { PayrollRecord, PayrollStatus } from '../../api/payrollApi'
import { Pagination } from '../../components/Pagination'
import { StatusBadge } from '../../components/StatusBadge'
import { MoneyText } from '../../components/AmountText'
import { statusBadge } from './payrollFormat'
import { PayrollRecordCard } from './PayrollRecordCard'

// ── Collapsible group header ──────────────────────────────────────────────────
interface GroupProps {
  status: PayrollStatus
  /** Current page's records (already sliced). */
  records: PayrollRecord[]
  /** Total count for this status across all pages — shown in header. */
  totalRecords: number
  /** Gross total across ALL records for this status — shown in header. */
  totalAmount: number
  page: number
  pageSize: number
  onPageChange: (p: number) => void
  onPageSizeChange: (s: number) => void
  isOpen: boolean
  onToggle: () => void
  staff: StaffMember[]
  onStatusChange: (recordId: number, status: PayrollStatus) => void
  onMarkPaid: (record: PayrollRecord) => void
  onDelete: (recordId: number) => void
  isPending: boolean
  isDeletePending: boolean
}

export function PayrollGroup({
  status, records, totalRecords, totalAmount,
  page, pageSize, onPageChange, onPageSizeChange,
  isOpen, onToggle, staff, onStatusChange, onMarkPaid, onDelete, isPending, isDeletePending,
}: GroupProps) {
  const { t } = useTranslation(['payroll', 'common'])
  const recordsId = useId()

  const totalPages   = Math.max(1, Math.ceil(totalRecords / pageSize))
  const pagedData = {
    content:       records,
    page,
    pageSize,
    totalElements: totalRecords,
    totalPages,
    hasNext:       page < totalPages - 1,
    hasPrevious:   page > 0,
  }

  return (
    <div className="ui-card p-0 overflow-hidden">
      {/* Group header — click to toggle */}
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={isOpen}
        aria-controls={isOpen ? recordsId : undefined}
        className="ui-accordion-trigger"
      >
        <div className="flex items-center gap-3">
          <StatusBadge {...statusBadge(status)} />
          <span className="text-sm text-ink-3">
            {t('group.records', { count: totalRecords })}
          </span>
          <MoneyText value={totalAmount} className="text-sm font-semibold text-ink" />
        </div>
        <ChevronDown size={14} strokeWidth={2} className={`ui-accordion-arrow ${isOpen ? 'ui-accordion-arrow--open' : ''}`} />
      </button>

      {/* Records — shown only when expanded */}
      {isOpen && (
        <div id={recordsId} className="ui-accordion-content border-t border-border">
        <div className="divide-y divide-border">
          {records.map(r => (
            <PayrollRecordCard
              key={r.id}
              record={r}
              staffName={staff.find((s) => s.id === r.userId)?.fullName}
              onStatusChange={onStatusChange}
              onMarkPaid={onMarkPaid}
              onDelete={onDelete}
              isPending={isPending}
              isDeletePending={isDeletePending}
            />
          ))}
        </div>
        <Pagination
          data={pagedData}
          onPageChange={onPageChange}
          onPageSizeChange={onPageSizeChange}
          pageSizeOptions={[10, 20, 50]}
        />
        </div>
      )}
    </div>
  )
}
