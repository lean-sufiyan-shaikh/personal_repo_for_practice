import { useId, useState } from 'react'
import type { ReactNode } from 'react'
import { ChevronDown } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { RadarBand, RadarEvidence, ScoreReason } from '../../api/radarApi'
import { StatusBadge } from '../../components/StatusBadge'
import { RadarScoreDetails } from './RadarScoreDetails'
import { BAND_BADGES } from './radarFormat'

interface RadarScoreCardProps {
  title: string
  facts: string
  /** Shown beside the band, e.g. a rising shortage trend. */
  extraBadge?: ReactNode
  score: number
  band: RadarBand
  reasons: ScoreReason[]
  evidence: RadarEvidence[]
}

/** One person, nozzle or tank: its score, band and facts, with "Why this score" opening the rules and events. */
export function RadarScoreCard({ title, facts, extraBadge, score, band, reasons, evidence }: RadarScoreCardProps) {
  const { t } = useTranslation('radar')
  const [open, setOpen] = useState(false)
  const detailsId = useId()
  const badge = BAND_BADGES[band]
  const hasScore = band !== 'NOT_ENOUGH_DATA'

  return (
    <article className="ui-card-plain space-y-2 p-4" aria-label={title}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 space-y-1">
          <h3 className="text-sm font-semibold text-ink">{title}</h3>
          <p className="text-xs text-ink-3">{facts}</p>
          <div className="flex flex-wrap items-center gap-1.5">
            <StatusBadge tone={badge.tone} icon={badge.icon} label={t(`band.${band}`)} />
            {extraBadge}
          </div>
        </div>
        {hasScore && (
          <p className="shrink-0 text-right" aria-label={t('card.scoreLabel', { score })}>
            <span className="ui-stat-value text-ink tabular-nums" aria-hidden="true">{score}</span>
            <span className="text-xs text-ink-3" aria-hidden="true">/100</span>
          </p>
        )}
      </div>
      <button
        type="button"
        className="ui-text-action inline-flex items-center gap-1 text-xs"
        aria-expanded={open}
        aria-controls={open ? detailsId : undefined}
        onClick={() => setOpen(value => !value)}
      >
        {t('card.showWhy')}
        <ChevronDown size={14} strokeWidth={2} aria-hidden="true" className={`ui-accordion-arrow ${open ? 'ui-accordion-arrow--open' : ''}`} />
      </button>
      {open && <RadarScoreDetails id={detailsId} reasons={reasons} evidence={evidence} />}
    </article>
  )
}
