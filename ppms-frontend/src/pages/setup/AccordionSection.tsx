import { AlertTriangle, Check, ChevronDown, type LucideIcon } from 'lucide-react'
import { StatusBadge } from '../../components/StatusBadge'
import type { StatusTone } from '../../components/StatusBadge'
import type { SectionKey } from './sectionKey'

const BADGE_ICON: Partial<Record<StatusTone, LucideIcon>> = { ok: Check, warn: AlertTriangle }

export function AccordionSection({
  sectionKey,
  open,
  onToggle,
  icon,
  title,
  summary,
  badgeTone,
  children,
}: {
  sectionKey: SectionKey
  open: boolean
  onToggle: () => void
  icon: React.ReactNode
  title: string
  summary: string
  /** ok when the step is done, warn when it still needs doing, neutral when it is optional. */
  badgeTone: StatusTone
  children: React.ReactNode
}) {
  return (
    <div id={`setup-section-${sectionKey}`} className="scroll-mt-4">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        className="w-full flex items-center justify-between px-5 py-4 hover:bg-bg transition-colors text-left"
      >
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          {icon}
          <span className="text-sm font-semibold text-ink">{title}</span>
          <StatusBadge tone={badgeTone} icon={BADGE_ICON[badgeTone]} label={summary} />
        </div>
        <ChevronDown size={14} strokeWidth={2} className={`text-ink-3 transition-transform duration-200 ${open ? 'rotate-180' : ''}`} />
      </button>

      {open && (
        <div className="px-5 pb-6 pt-2 border-t border-border">
          {children}
        </div>
      )}
    </div>
  )
}
