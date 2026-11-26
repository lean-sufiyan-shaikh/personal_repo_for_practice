import { useId } from 'react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import type { ReceiptNozzle } from '../../../api/inventoryApi'
import { FormField } from '../../../components/FormField'
import { NumericInput } from '../../../components/NumericInput'
import { FormGrid } from '../../../components/layout/FormGrid'
import { limitDecimals } from '../inventoryFormat'
import { readingAfter, readingBefore } from './receiptCheck'
import type { ReceiptCheckInput } from './receiptCheck'

interface Props {
  check: ReceiptCheckInput
  nozzles: ReceiptNozzle[]
  onChange: (check: ReceiptCheckInput) => void
  /** The live result, shown under the readings. */
  result: ReactNode
}

type Moment = 'before' | 'after'

/** The two dips and nozzle readings of a receipt check: one set before unloading, one after. */
export function ReceiptCheckFields({ check, nozzles, onChange, result }: Props) {
  const { t } = useTranslation('inventory')
  const headingId = useId()

  const setReading = (nozzleId: number, moment: Moment, value: string) => onChange({
    ...check,
    readings: { ...check.readings, [nozzleId]: { ...check.readings[nozzleId], [moment]: limitDecimals(value, 3) } },
  })

  const nozzleLabel = (nozzle: ReceiptNozzle, moment: Moment) => nozzle.duName
    ? t(moment === 'before' ? 'receipt.nozzleBeforeWithDu' : 'receipt.nozzleAfterWithDu', { number: nozzle.nozzleNumber, du: nozzle.duName })
    : t(moment === 'before' ? 'receipt.nozzleBefore' : 'receipt.nozzleAfter', { number: nozzle.nozzleNumber })

  const step = (moment: Moment) => (
    <fieldset className="space-y-2">
      <legend className="text-xs font-semibold text-ink-2">
        {t(moment === 'before' ? 'receipt.beforeTitle' : 'receipt.afterTitle')}
      </legend>
      <FormGrid>
        <FormField label={t(moment === 'before' ? 'receipt.dipBefore' : 'receipt.dipAfter')}>
          <NumericInput step="0.001" min="0" unit="L"
            value={moment === 'before' ? check.dipBefore : check.dipAfter}
            onChange={e => onChange({ ...check, [moment === 'before' ? 'dipBefore' : 'dipAfter']: limitDecimals(e.target.value, 3) })} />
        </FormField>
        {nozzles.map(nozzle => (
          <FormField key={nozzle.nozzleId} label={nozzleLabel(nozzle, moment)}>
            <NumericInput step="0.001" min="0"
              value={moment === 'before' ? readingBefore(check, nozzle) : readingAfter(check, nozzle)}
              onChange={e => setReading(nozzle.nozzleId, moment, e.target.value)} />
          </FormField>
        ))}
      </FormGrid>
    </fieldset>
  )

  return (
    <section aria-labelledby={headingId} className="space-y-3 border-t border-border pt-3">
      <div>
        <h4 id={headingId} className="text-sm font-semibold text-ink">{t('receipt.title')}</h4>
        <p className="ui-help mt-0">{nozzles.length > 0 ? t('receipt.intro') : t('receipt.noNozzles')}</p>
      </div>
      {step('before')}
      {step('after')}
      <fieldset className="space-y-2">
        <legend className="text-xs font-semibold text-ink-2">{t('receipt.densityTitle')}</legend>
        <FormGrid>
          <FormField label={t('receipt.observedDensity')}>
            <NumericInput step="0.1" min="0" unit="kg/m³" value={check.observedDensity}
              onChange={e => onChange({ ...check, observedDensity: limitDecimals(e.target.value, 1) })} />
          </FormField>
          <FormField label={t('receipt.invoiceDensity')}>
            <NumericInput step="0.1" min="0" unit="kg/m³" value={check.invoiceDensity}
              onChange={e => onChange({ ...check, invoiceDensity: limitDecimals(e.target.value, 1) })} />
          </FormField>
        </FormGrid>
      </fieldset>
      {result}
    </section>
  )
}
