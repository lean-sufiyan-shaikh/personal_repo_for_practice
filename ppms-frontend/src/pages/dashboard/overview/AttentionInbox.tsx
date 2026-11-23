import { Link, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  AlertTriangle, BarChart2, CheckCircle2, ClipboardCheck, Clock, CreditCard, FileText, Fuel, Gauge, Landmark,
  Search, Settings, Wrench, ZapOff, type LucideIcon,
} from 'lucide-react'
import { attentionApi } from '../../../api/attentionApi'
import type { AttentionItem, AttentionSeverity, AttentionType } from '../../../api/attentionApi'
import { SkeletonRows } from '../../../components/Skeleton'
import { usePumpStore } from '../../../store/usePumpStore'
import { formatCurrency } from '../../../utils/format'

const ICON_BY_TYPE: Record<AttentionType, LucideIcon> = {
  PENDING_APPROVALS: ClipboardCheck,
  ESCALATED_SHORTAGES: AlertTriangle,
  LONG_INVESTIGATIONS: Search,
  OVERDUE_CREDIT: CreditCard,
  OVER_LIMIT_CREDIT: Gauge,
  UNSETTLED_PAYMENTS: Landmark,
  BALANCE_SHEETS: BarChart2,
  OVERDUE_SHIFTS: Clock,
  AUTO_CLOSED_SHIFTS: ZapOff,
  EXPIRING_DOCUMENTS: FileText,
  CALIBRATIONS_DUE: Wrench,
  STALE_FUEL_PRICES: Fuel,
  SETUP_INCOMPLETE: Settings,
}

const SEVERITY_STYLE: Record<AttentionSeverity, { chip: string; label: string }> = {
  HIGH:   { chip: 'bg-bad-bg text-bad',   label: 'Urgent' },
  MEDIUM: { chip: 'bg-warn-bg text-warn', label: 'Soon' },
  LOW:    { chip: 'bg-info-bg text-info', label: 'When you can' },
}

function AttentionRow({ item, showPumpName }: { item: AttentionItem; showPumpName: boolean }) {
  const navigate = useNavigate()
  const { selectedPumpId, setSelectedPumpId } = usePumpStore()
  const Icon = ICON_BY_TYPE[item.type]
  const severity = SEVERITY_STYLE[item.severity]
  const figure = item.amount != null ? formatCurrency(item.amount) : String(item.count)
  const actionLabel = `Review: ${showPumpName ? `${item.pumpName}, ` : ''}${item.reason}`

  // The target pages show the selected pump, so an item from another pump switches to it first
  const openOtherPump = () => {
    setSelectedPumpId(item.pumpId)
    navigate(item.targetPath)
  }

  return (
    <li className="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-3">
      <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${severity.chip}`} aria-hidden="true">
        <Icon size={18} strokeWidth={2} />
      </span>
      <div className="min-w-0 flex-1 basis-48">
        <p className="text-sm font-medium text-ink">
          <span className="sr-only">{severity.label}: </span>
          {item.reason}
        </p>
        <p className="text-xs text-ink-3">
          {showPumpName && <span>{item.pumpName} · </span>}
          {item.severity === 'HIGH' && <span className="font-semibold text-bad" aria-hidden="true">Urgent · </span>}
          {item.amount != null ? `${item.count} · ` : ''}
          <span className="tabular-nums">{figure}</span>
        </p>
      </div>
      {item.pumpId === selectedPumpId || !showPumpName ? (
        <Link to={item.targetPath} aria-label={actionLabel} className="ui-btn ui-btn-secondary ui-btn-compact">
          Review
        </Link>
      ) : (
        <button type="button" onClick={openOtherPump} aria-label={actionLabel} className="ui-btn ui-btn-secondary ui-btn-compact">
          Review
        </button>
      )}
    </li>
  )
}

/** The ranked list of things to act on today, each linking to the page and filter that handles it. */
export function AttentionInbox({ pumpId, allPumps }: { pumpId: number | null; allPumps: boolean }) {
  const { data: items, isLoading, isError } = useQuery({
    queryKey: ['attention', allPumps ? 'all' : pumpId],
    queryFn: () => (allPumps ? attentionApi.getForMyPumps() : attentionApi.getForPump(pumpId!)),
    enabled: allPumps || pumpId != null,
    refetchInterval: 5 * 60_000,
    refetchIntervalInBackground: false,
  })

  return (
    <section aria-labelledby="attention-heading" className="ui-card p-0 overflow-hidden">
      <div className="ui-toolbar">
        <h2 id="attention-heading" className="ui-toolbar-title">Needs attention today</h2>
        {items && items.length > 0 && (
          <span className="text-xs text-ink-3">{items.length} item{items.length !== 1 ? 's' : ''}</span>
        )}
      </div>
      {isLoading ? (
        <div className="px-4 py-3" aria-busy="true" aria-label="Loading what needs attention">
          <SkeletonRows count={3} />
        </div>
      ) : isError ? (
        <p role="alert" className="px-4 py-3 text-sm text-bad">
          Could not load what needs attention. Refresh to try again.
        </p>
      ) : !items || items.length === 0 ? (
        <p role="status" className="flex items-center gap-2 px-4 py-4 text-sm font-medium text-ok">
          <CheckCircle2 size={18} strokeWidth={2} aria-hidden="true" />
          All clear for today
        </p>
      ) : (
        <ul className="divide-y divide-border">
          {items.map(item => (
            <AttentionRow key={`${item.pumpId}-${item.type}`} item={item} showPumpName={allPumps} />
          ))}
        </ul>
      )}
    </section>
  )
}
