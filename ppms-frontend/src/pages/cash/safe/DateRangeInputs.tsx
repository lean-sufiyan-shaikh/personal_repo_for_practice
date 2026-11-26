import { useId } from 'react'
import { useTranslation } from 'react-i18next'

interface Props {
  from: string
  to: string
  onFromChange: (value: string) => void
  onToChange: (value: string) => void
}

/** From and To date inputs for a FilterBar. */
export function DateRangeInputs({ from, to, onFromChange, onToChange }: Props) {
  const { t } = useTranslation('common')
  const fromId = useId()
  const toId = useId()
  return (
    <>
      <div className="flex items-center gap-1.5">
        <label htmlFor={fromId} className="text-xs text-ink-3 whitespace-nowrap">{t('fields.from')}</label>
        <input id={fromId} type="date" value={from} onChange={e => onFromChange(e.target.value)} className="text-xs" />
      </div>
      <div className="flex items-center gap-1.5">
        <label htmlFor={toId} className="text-xs text-ink-3 whitespace-nowrap">{t('fields.to')}</label>
        <input id={toId} type="date" value={to} onChange={e => onToChange(e.target.value)} className="text-xs" />
      </div>
    </>
  )
}
