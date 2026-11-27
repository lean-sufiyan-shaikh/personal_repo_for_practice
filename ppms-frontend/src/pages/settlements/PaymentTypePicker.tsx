import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import type { SettlementPaymentType } from '../../api/paymentSettlementApi'
import { TYPES, TYPE_ICONS, typeLabel } from './settlementFormat'

interface PaymentTypePickerProps {
  value: SettlementPaymentType
  onChange: (type: SettlementPaymentType) => void
}

export function PaymentTypePicker({ value, onChange }: PaymentTypePickerProps) {
  const { t } = useTranslation('settlements')
  const labelId = useId()
  return (
    <div>
      <span id={labelId} className="ui-label">{t('record.paymentType')}</span>
      <div role="group" aria-labelledby={labelId} className="grid grid-cols-3 gap-2">
        {TYPES.map(type => (
          <button
            key={type}
            type="button"
            aria-pressed={value === type}
            onClick={() => onChange(type)}
            className="ui-chip inline-flex min-h-11 items-center justify-center gap-1.5 text-sm"
          >
            <span aria-hidden="true" className="leading-none">{TYPE_ICONS[type]}</span>
            <span>{typeLabel(type)}</span>
          </button>
        ))}
      </div>
    </div>
  )
}
