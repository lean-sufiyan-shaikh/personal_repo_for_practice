import { AlertTriangle, Ban } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { Settlement } from '../../api/paymentSettlementApi'
import { MoneyText } from '../../components/AmountText'
import { StatusBadge } from '../../components/StatusBadge'
import { TYPE_ACCENT, TYPE_ICONS, fmtAmt, typeLabel } from './settlementFormat'
import { coverageLabel, partialLabel } from './settlementCoverage'

interface SettlementRecordRowProps {
  settlement: Settlement
  canVoid: boolean
  onVoid: (settlement: Settlement) => void
  onOpenDetails: (settlement: Settlement) => void
}

/** One recorded settlement: gross cleared, the bank charges and net when there were any, the days it covers. */
export function SettlementRecordRow({ settlement: s, canVoid, onVoid, onOpenDetails }: SettlementRecordRowProps) {
  const { t } = useTranslation('settlements')
  const accent = TYPE_ACCENT[s.paymentType]
  const isVoided = s.voidStatus === 'VOIDED'
  const covers = coverageLabel(s)

  return (
    <div className={`flex flex-wrap items-start gap-x-3 gap-y-1 ${isVoided ? 'opacity-60' : ''}`}>
      <span className={`inline-flex items-center gap-1 text-xs font-semibold px-2 py-0.5 rounded-badge flex-shrink-0 mt-0.5 ${accent.badge}`}>
        {TYPE_ICONS[s.paymentType]} {typeLabel(s.paymentType)}
      </span>
      <div className="order-last basis-full min-w-0 sm:order-none sm:basis-0 sm:flex-1">
        <div className="flex items-center gap-2 flex-wrap">
          {isVoided && <StatusBadge tone="bad" icon={Ban} label={t('history.voided')} />}
          {!isVoided && s.isPartial && <StatusBadge tone="warn" icon={AlertTriangle} label={partialLabel(s)} />}
          <span className="text-xs text-ink-3 truncate">
            {s.notes ? `${s.notes} · ` : ''}{t('history.by', { name: s.recordedByUserName })}
          </span>
        </div>
        {covers && <p className="text-xs text-ink-3 mt-0.5">{covers}</p>}
        {s.chargesAmount > 0 && (
          <p className="text-xs text-ink-3 mt-0.5">
            {t('history.netAfterCharges', { net: fmtAmt(s.netAmount), charges: fmtAmt(s.chargesAmount) })}
          </p>
        )}
        {isVoided && s.voidReason && (
          <p className="text-xs text-bad mt-0.5">{t('history.voidReason', { reason: s.voidReason })}</p>
        )}
      </div>
      <MoneyText
        value={s.grossAmount}
        showSign
        className={`text-sm font-bold flex-shrink-0 ml-auto sm:ml-0 ${isVoided ? 'text-ink-3 line-through' : 'text-ok'}`}
      />
      <button
        type="button"
        onClick={() => onOpenDetails(s)}
        aria-label={t('history.detailsOf', { type: typeLabel(s.paymentType), amount: fmtAmt(s.grossAmount) })}
        className="ui-btn ui-btn-ghost ui-btn-compact flex-shrink-0"
      >
        {t('history.details')}
      </button>
      {canVoid && !isVoided && (
        <button type="button" onClick={() => onVoid(s)} className="ui-btn ui-btn-danger ui-btn-compact flex-shrink-0">
          {t('history.void')}
        </button>
      )}
    </div>
  )
}
