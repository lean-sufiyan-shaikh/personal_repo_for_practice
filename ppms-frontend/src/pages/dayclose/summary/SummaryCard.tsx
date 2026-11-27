import type { ReactNode } from 'react'
import { useId } from 'react'
import type { SectionFlag } from '../../../api/dayCloseApi'
import { StatusBadge } from '../../../components/StatusBadge'
import { flagBadge, flagTone, reasonText } from '../dayCloseText'

const BORDER_BY_TONE = { ok: 'border-l-ok', warn: 'border-l-warn', bad: 'border-l-bad', info: 'border-l-info', neutral: 'border-l-border' }
const TEXT_BY_TONE = { ok: 'text-ok', warn: 'text-warn', bad: 'text-bad', info: 'text-info', neutral: 'text-ink-2' }

/** One section of the day summary: its flag, the reasons for it in words, then its figures. */
export function SummaryCard({ title, flag, children }: { title: string; flag: SectionFlag; children: ReactNode }) {
  const headingId = useId()
  const badge = flagBadge(flag.colour)
  return (
    <section aria-labelledby={headingId}
      className={`ui-card border-l-4 ${BORDER_BY_TONE[badge.tone]} space-y-3 print:break-inside-avoid print:p-2`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={headingId} className="text-base font-semibold text-ink">{title}</h2>
        <StatusBadge {...badge} />
      </div>
      <ul className="space-y-1">
        {flag.reasons.map((reason) => (
          <li key={`${reason.code}-${JSON.stringify(reason.params)}`} className={`text-sm ${TEXT_BY_TONE[flagTone(reason.colour)]}`}>
            {reasonText(reason)}
          </li>
        ))}
      </ul>
      {children}
    </section>
  )
}

/** Label and value rows inside a summary section. */
export function FigureList({ label, rows }: { label: string; rows: { label: ReactNode; value: ReactNode }[] }) {
  return (
    <dl aria-label={label} className="grid grid-cols-1 gap-x-6 gap-y-1 text-sm sm:grid-cols-2 print:grid-cols-2">
      {rows.map((row, index) => (
        <div key={index} className="flex items-baseline justify-between gap-3 border-b border-border py-1 last:border-b-0">
          <dt className="text-ink-2">{row.label}</dt>
          <dd className="font-medium text-ink">{row.value}</dd>
        </div>
      ))}
    </dl>
  )
}
