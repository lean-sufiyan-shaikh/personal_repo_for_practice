import { ArrowDown, ArrowUp, Check } from 'lucide-react'
import { fmt } from './creditRow'

/** SHORT, OVER or Balanced with an icon and the word, so the result never depends on colour alone. */
export function DiscrepancyBadge({ type, amount }: { type: 'SHORT' | 'OVER' | null; amount: number }) {
  if (type === null) {
    return (
      <span className="inline-flex items-center gap-1 rounded-badge bg-ok-bg px-2 py-0.5 text-sm font-semibold text-ok">
        <Check size={14} strokeWidth={2.5} aria-hidden="true" />Balanced
      </span>
    )
  }
  const isShort = type === 'SHORT'
  const Icon = isShort ? ArrowDown : ArrowUp
  return (
    <span className={`inline-flex items-center gap-1 rounded-badge px-2 py-0.5 text-sm font-semibold ${
      isShort ? 'bg-bad-bg text-bad' : 'bg-warn-bg text-warn'
    }`}>
      <Icon size={14} strokeWidth={2.5} aria-hidden="true" />{type} ₹{fmt(amount)}
    </span>
  )
}
