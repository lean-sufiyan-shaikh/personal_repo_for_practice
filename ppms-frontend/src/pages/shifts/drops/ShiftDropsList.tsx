import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { MoneyText } from '../../../components/AmountText'
import { CashDropCard } from '../../cash/drops/CashDropCard'
import { useShiftCashDrops } from './useShiftCashDrops'

/** The operator's drops on this shift with their status, and how much the safe has confirmed so far. */
export function ShiftDropsList({ pumpId, shiftId }: { pumpId: number; shiftId: number }) {
  const { t } = useTranslation('shifts')
  const headingId = useId()
  const { drops, confirmedTotal, pendingDrops, isError } = useShiftCashDrops(pumpId, shiftId)

  return (
    <section aria-labelledby={headingId}>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 id={headingId} className="text-xs text-ink-3">{t('drops.listTitle')}</h3>
        {drops.length > 0 && (
          <span className="text-xs text-ink-3">
            {t('drops.confirmedSoFar')} <MoneyText value={confirmedTotal} className="font-semibold text-ink" />
          </span>
        )}
      </div>
      {isError ? (
        <p role="alert" className="mt-1 text-sm text-bad">{t('drops.loadFailed')}</p>
      ) : drops.length === 0 ? (
        <p className="mt-1 text-sm text-ink-3">{t('drops.none')}</p>
      ) : (
        <>
          {pendingDrops.length > 0 && (
            <p className="mt-1 text-xs text-warn">{t('drops.waiting', { count: pendingDrops.length })}</p>
          )}
          <ul className="ui-record-list mt-1">
            {drops.map((drop) => <CashDropCard key={drop.id} drop={drop} />)}
          </ul>
        </>
      )}
    </section>
  )
}
