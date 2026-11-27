import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Check, Hourglass, X } from 'lucide-react'
import { dipApi } from '../../../api/dipApi'
import type { MeterAdjustmentStatus } from '../../../api/dipApi'
import { StatusBadge } from '../../../components/StatusBadge'
import type { StatusBadgeSpec } from '../../../components/statusBadges'
import { codeLabel } from '../../../constants/labels'
import { formatNumberIn } from '../../../i18n/format'
import { formatIstDateTime } from '../../../utils/date'
import { formatCurrency } from '../../../utils/format'

const SHOWN_ADJUSTMENTS = 5

const STATUS_BADGE: Record<MeterAdjustmentStatus, Omit<StatusBadgeSpec, 'label'>> = {
  APPLIED: { tone: 'ok', icon: Check },
  PENDING_APPROVAL: { tone: 'warn', icon: Hourglass },
  REJECTED: { tone: 'neutral', icon: X },
}

/** The nozzle's latest meter adjustments, with any still waiting for an Owner or Admin. */
export function ReadingAdjustmentHistory({ pumpId, nozzleId }: { pumpId: number; nozzleId: number }) {
  const { t } = useTranslation('setup')
  const { data: adjustments = [] } = useQuery({
    queryKey: ['readingAdjustments', pumpId, nozzleId],
    queryFn: () => dipApi.getAdjustments(pumpId, nozzleId),
  })
  if (adjustments.length === 0) return null
  const reading = (value: number) => formatNumberIn(value, { maximumFractionDigits: 3 })

  return (
    <section aria-label={t('nozzles.reading.history')} className="ui-reading-editor__section space-y-2">
      <h4 className="ui-label">{t('nozzles.reading.history')}</h4>
      <ul className="space-y-2">
        {adjustments.slice(0, SHOWN_ADJUSTMENTS).map(adjustment => (
          <li key={adjustment.id} className="flex flex-wrap items-start justify-between gap-2 text-xs">
            <span className="min-w-0">
              <span className="block font-medium text-ink">
                {t('nozzles.reading.historyChange', { from: reading(adjustment.previousReading), to: reading(adjustment.newReading) })}
              </span>
              <span className="block text-ink-3">
                {formatIstDateTime(adjustment.createdAt)}
                {adjustment.unbilledLitres !== null && ` · ${t('nozzles.reading.historyUnbilled', {
                  litres: reading(adjustment.unbilledLitres),
                  value: adjustment.unbilledValue === null ? '—' : formatCurrency(adjustment.unbilledValue),
                })}`}
              </span>
              <span className="block text-ink-2">{adjustment.reviewReason ?? adjustment.reason}</span>
            </span>
            <StatusBadge {...STATUS_BADGE[adjustment.status ?? 'APPLIED']} label={codeLabel(adjustment.status ?? 'APPLIED')} />
          </li>
        ))}
      </ul>
    </section>
  )
}
