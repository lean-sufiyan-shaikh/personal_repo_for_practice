import { useMutation, useQueryClient } from '@tanstack/react-query'
import { inventoryApi } from '../../api/inventoryApi'
import type { DipCheck } from '../../api/inventoryApi'
import { AlertTriangle, Check } from 'lucide-react'
import { StatusBadge } from '../../components/StatusBadge'
import { LitresText } from '../../components/AmountText'
import { FUEL_COLORS, fmtQty } from './inventoryFormat'

// ── DIP item (compact card inside date group) ──────────────────────────────────

export function DipItem({ check, pumpId, canReview }: {
  check: DipCheck
  pumpId: number
  canReview: boolean
}) {
  const qc = useQueryClient()
  const cols = FUEL_COLORS[check.fuelType] ?? FUEL_COLORS.PETROL
  const over = check.variance > 0

  const reviewMutation = useMutation({
    mutationFn: () => inventoryApi.reviewDipCheck(pumpId, check.id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['dipChecks', pumpId] }),
  })

  const statusBadge = () => {
    if (check.status === 'WITHIN_TOLERANCE') return null
    if (check.status === 'PENDING_REVIEW') {
      return <StatusBadge tone="bad" icon={AlertTriangle} label="Needs Review" className="shrink-0" />
    }
    return (
      <StatusBadge
        tone="neutral"
        icon={Check}
        label={`Reviewed${check.reviewedByUserName ? ` · ${check.reviewedByUserName}` : ''}`}
        className="shrink-0"
      />
    )
  }

  return (
    <div className={`flex flex-wrap items-center gap-2 rounded-control px-3 py-2.5 ${
      check.status === 'PENDING_REVIEW' ? 'bg-bad-bg border border-bad' : 'bg-bg'
    }`}>
      <span className={`text-xs font-medium px-2 py-0.5 rounded-badge shrink-0 ${cols.badge}`}>
        {check.fuelType}
      </span>
      <span className="text-xs font-semibold text-ink w-14 shrink-0">{check.tankIdentifier}</span>
      <span className="text-xs text-ink-3 shrink-0">
        Measured: <span className="font-medium text-ink">{fmtQty(check.measuredQuantity)}</span>
      </span>
      <span className="text-xs text-ink-3 shrink-0">System: {fmtQty(check.systemStock)}</span>
      <span className="shrink-0">
        {check.variance === 0 ? (
          <span className="text-xs text-ok font-medium">Balanced</span>
        ) : (
          <LitresText value={check.variance} showSign tone={over ? 'warn' : 'bad'} className="text-xs font-semibold" />
        )}
      </span>
      {statusBadge()}
      {check.status === 'PENDING_REVIEW' && canReview && (
        <button
          onClick={() => reviewMutation.mutate()}
          disabled={reviewMutation.isPending}
          className="ui-btn ui-btn-danger min-h-0 px-2.5 py-0.5 text-xs disabled:opacity-50 shrink-0"
        >
          {reviewMutation.isPending ? '…' : 'Acknowledge'}
        </button>
      )}
      {check.notes && (
        <span className="text-xs text-ink-3 italic shrink-0">{check.notes}</span>
      )}
      <span className="ml-auto text-xs text-ink-3 shrink-0">{check.loggedByUserName}</span>
    </div>
  )
}
