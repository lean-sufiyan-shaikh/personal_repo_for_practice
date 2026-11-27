import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { RadarEvidence, ScoreReason } from '../../api/radarApi'
import { formatIstDate } from '../../utils/date'
import { evidenceLink, evidenceText, reasonText } from './radarFormat'

const EVIDENCE_LIMIT = 25

/** "Why this score": each rule with its points, then the events behind them, newest first. */
export function RadarScoreDetails({ id, reasons, evidence }: { id: string; reasons: ScoreReason[]; evidence: RadarEvidence[] }) {
  const { t } = useTranslation('radar')
  return (
    <div id={id} className="ui-accordion-content space-y-3 border-t border-border pt-3">
      <div>
        <h4 className="text-xs font-semibold text-ink-2">{t('card.reasonsTitle')}</h4>
        <ul className="mt-1 space-y-1.5">
          {reasons.map(reason => (
            <li key={reason.code} className="flex flex-col gap-0.5 text-xs text-ink-2 sm:flex-row sm:items-start sm:justify-between sm:gap-3">
              <span>{reasonText(reason)}</span>
              {reason.maxPoints > 0 && (
                <span className="shrink-0 font-semibold text-ink tabular-nums">
                  {t('card.points', { points: reason.points, max: reason.maxPoints })}
                </span>
              )}
            </li>
          ))}
        </ul>
      </div>
      <div>
        <h4 className="text-xs font-semibold text-ink-2">{t('card.evidenceTitle')}</h4>
        {evidence.length === 0
          ? <p className="mt-1 text-xs text-ink-3">{t('card.noEvidence')}</p>
          : (
            <ul className="mt-1 divide-y divide-border" aria-label={t('card.evidenceTitle')}>
              {evidence.map((item, index) => <EvidenceRow key={`${item.kind}-${item.date}-${index}`} evidence={item} />)}
            </ul>
          )}
        {evidence.length >= EVIDENCE_LIMIT && <p className="mt-1 text-xs text-ink-3">{t('card.moreEvidence', { count: EVIDENCE_LIMIT })}</p>}
      </div>
    </div>
  )
}

function EvidenceRow({ evidence }: { evidence: RadarEvidence }) {
  const { t } = useTranslation('radar')
  const link = evidenceLink(evidence)
  return (
    <li className="flex flex-col gap-0.5 py-1.5 text-xs sm:flex-row sm:items-start sm:justify-between sm:gap-3">
      <div className="min-w-0">
        <p className="text-ink">
          <span className="mr-2 text-ink-3 tabular-nums">{formatIstDate(evidence.date, { day: 'numeric', month: 'short' })}</span>
          {evidenceText(evidence)}
        </p>
        {evidence.note && <p className="break-words text-ink-3">{t('evidence.note', { note: evidence.note })}</p>}
      </div>
      {link && <Link to={link.to} className="ui-link shrink-0">{link.label}</Link>}
    </li>
  )
}
