import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import type { SettlementAmountBasis, SettlementCharges } from '../../api/paymentSettlementApi'
import { FormField } from '../../components/FormField'
import { LiveFigures } from '../../components/LiveFigures'
import { MoneyText } from '../../components/AmountText'
import { NumericInput } from '../../components/NumericInput'
import { FormGrid } from '../../components/layout/FormGrid'
import { formatNumberIn } from '../../i18n/format'

const BASES: SettlementAmountBasis[] = ['NET', 'GROSS']

export interface ChargeOverrides {
  editing: boolean
  mdrAmount: string
  gstOnMdrAmount: string
}

interface SettlementChargesFieldsProps {
  basis: SettlementAmountBasis
  onBasisChange: (basis: SettlementAmountBasis) => void
  charges: SettlementCharges | undefined
  chargesError: string | null
  overrides: ChargeOverrides
  onOverridesChange: (overrides: ChargeOverrides) => void
}

function percent(value: number): string {
  return formatNumberIn(value, { maximumFractionDigits: 2 })
}

/** Whether the typed amount is before or after bank charges, the server's split of it, and an override for the charges. */
export function SettlementChargesFields({ basis, onBasisChange, charges, chargesError, overrides, onOverridesChange }: SettlementChargesFieldsProps) {
  const { t } = useTranslation('settlements')
  const basisLabelId = useId()

  const startEditing = () => onOverridesChange({
    editing: true,
    mdrAmount: charges ? String(charges.mdrAmount) : '',
    gstOnMdrAmount: charges ? String(charges.gstOnMdrAmount) : '',
  })
  const stopEditing = () => onOverridesChange({ editing: false, mdrAmount: '', gstOnMdrAmount: '' })

  return (
    <div className="flex flex-col gap-3">
      <div>
        <span id={basisLabelId} className="ui-label">{t('charges.basisLabel')}</span>
        <div role="group" aria-labelledby={basisLabelId} className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          {BASES.map(option => (
            <button key={option} type="button" aria-pressed={basis === option} onClick={() => onBasisChange(option)}
              className="ui-chip min-h-11 text-sm">
              {option === 'NET' ? t('charges.basisNet') : t('charges.basisGross')}
            </button>
          ))}
        </div>
      </div>

      <LiveFigures
        label={t('charges.figuresLabel')}
        figures={[
          {
            label: charges ? t('charges.mdrAt', { percent: percent(charges.mdrPercent) }) : t('charges.mdr'),
            value: <MoneyText value={charges?.mdrAmount ?? null} />,
          },
          {
            label: charges ? t('charges.gstAt', { percent: percent(charges.gstOnMdrPercent) }) : t('charges.gst'),
            value: <MoneyText value={charges?.gstOnMdrAmount ?? null} />,
          },
          { label: t('charges.total'), value: <MoneyText value={charges?.chargesAmount ?? null} /> },
          { label: t('charges.gross'), value: <MoneyText value={charges?.grossAmount ?? null} /> },
          { label: t('charges.net'), value: <MoneyText value={charges?.netAmount ?? null} />, isResult: true },
        ]}
      />
      {chargesError && <p role="alert" className="ui-error-text">{chargesError}</p>}

      {overrides.editing ? (
        <>
          <FormGrid>
            <FormField label={t('charges.mdrAmount')}>
              <NumericInput unit="₹" min={0} step={0.01} value={overrides.mdrAmount}
                onChange={e => onOverridesChange({ ...overrides, mdrAmount: e.target.value })} />
            </FormField>
            <FormField label={t('charges.gstAmount')}>
              <NumericInput unit="₹" min={0} step={0.01} value={overrides.gstOnMdrAmount}
                onChange={e => onOverridesChange({ ...overrides, gstOnMdrAmount: e.target.value })} />
            </FormField>
          </FormGrid>
          <button type="button" onClick={stopEditing} className="ui-btn ui-btn-ghost ui-btn-compact self-start">
            {t('charges.useStandard')}
          </button>
        </>
      ) : (
        <button type="button" onClick={startEditing} className="ui-btn ui-btn-ghost ui-btn-compact self-start">
          {t('charges.edit')}
        </button>
      )}
    </div>
  )
}
