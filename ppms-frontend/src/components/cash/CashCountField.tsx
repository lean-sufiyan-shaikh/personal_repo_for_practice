import { useId } from 'react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { NumericInput } from '../NumericInput'
import { DenominationCounter } from './DenominationCounter'
import { EMPTY_COUNTS, countsTotalText } from './denominations'
import type { DenominationCounts } from './denominations'

interface Props {
  label: string
  /** The amount as typed, or the counted total while counting by note. */
  amount: string
  onAmountChange: (amount: string) => void
  /** The note-by-note count, or null when only the total is typed. */
  counts: DenominationCounts | null
  onCountsChange: (counts: DenominationCounts | null) => void
  /** A small button shown at the end of the label row, e.g. "Fill the rest". */
  action?: ReactNode
}

/**
 * A cash amount that is counted note by note by default, the total filling the amount; "Just type the total"
 * switches to a plain amount field and the breakdown is then not sent.
 */
export function CashCountField({ label, amount, onAmountChange, counts, onCountsChange, action }: Props) {
  const { t } = useTranslation()
  const inputId = useId()
  const isCounting = counts !== null

  const changeCounts = (next: DenominationCounts) => {
    onCountsChange(next)
    onAmountChange(countsTotalText(next))
  }
  const switchMode = () => {
    if (isCounting) {
      onCountsChange(null)
      return
    }
    changeCounts(EMPTY_COUNTS)
  }

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-baseline justify-between gap-x-2 gap-y-1">
        <label htmlFor={isCounting ? undefined : inputId} className="ui-label mb-0">{label}</label>
        <div className="flex flex-wrap items-baseline gap-x-3">
          {action}
          <button type="button" onClick={switchMode} className="ui-text-action">
            {isCounting ? t('cashCount.typeTotal') : t('cashCount.countNotes')}
          </button>
        </div>
      </div>
      {isCounting ? (
        <DenominationCounter counts={counts} onChange={changeCounts} label={label} />
      ) : (
        <NumericInput id={inputId} unit="₹" step="0.01" min="0" value={amount}
          onChange={(event) => onAmountChange(event.target.value)} />
      )}
    </div>
  )
}
