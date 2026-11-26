import { useId } from 'react'
import { Minus, Plus } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { NumericInput } from '../NumericInput'
import { IconButton } from '../IconButton'
import { MoneyText } from '../AmountText'
import { formatNumberIn } from '../../i18n/format'
import { NOTES, countsTotal, stepCount } from './denominations'
import type { DenominationCounts, NoteKey } from './denominations'

interface Props {
  counts: DenominationCounts
  onChange: (counts: DenominationCounts) => void
  /** Read out as the counter's name, e.g. "Cash in the bag". */
  label: string
}

/** Count cash note by note: type or step the number of each note, add the coins, and the total updates as you go. */
export function DenominationCounter({ counts, onChange, label }: Props) {
  const { t } = useTranslation()
  const headingId = useId()
  const total = countsTotal(counts)
  const setCount = (key: NoteKey | 'coins', text: string) => onChange({ ...counts, [key]: text })

  return (
    <section aria-labelledby={headingId} className="ui-denominations">
      <h4 id={headingId} className="sr-only">{label}</h4>
      {NOTES.map((note) => {
        const noteLabel = t('cashCount.note', { value: formatNumberIn(note.value) })
        const count = Number(counts[note.key]) || 0
        return (
          <div key={note.key} className="ui-denomination-row">
            <span className="text-sm font-semibold text-ink tabular-nums">{noteLabel}</span>
            <div className="ui-denomination-row__stepper">
              <IconButton label={t('cashCount.removeNote', { note: noteLabel })} icon={<Minus size={18} />}
                className="ui-btn ui-btn-secondary" disabled={count === 0}
                onClick={() => setCount(note.key, stepCount(counts[note.key], -1))} />
              <NumericInput aria-label={t('cashCount.noteCount', { note: noteLabel })} inputMode="numeric" step="1" min="0"
                placeholder="0" value={counts[note.key]}
                onChange={(event) => setCount(note.key, event.target.value)} />
              <IconButton label={t('cashCount.addNote', { note: noteLabel })} icon={<Plus size={18} />}
                className="ui-btn ui-btn-secondary"
                onClick={() => setCount(note.key, stepCount(counts[note.key], 1))} />
            </div>
            <MoneyText value={note.value * count} className="text-right text-sm text-ink-2" />
          </div>
        )
      })}
      <div className="ui-denomination-row">
        <span className="text-sm font-semibold text-ink">{t('cashCount.coins')}</span>
        <div className="ui-denomination-row__stepper">
          <span aria-hidden="true" />
          <NumericInput aria-label={t('cashCount.coinsAmount')} step="0.01" min="0" placeholder="0" value={counts.coins}
            onChange={(event) => setCount('coins', event.target.value)} />
        </div>
        <span />
      </div>
      <p className="ui-denomination-total" aria-live="polite">
        <span className="text-sm font-semibold text-ink-2">{t('cashCount.total')}</span>
        <MoneyText value={total} className="text-lg font-bold text-ink" />
      </p>
    </section>
  )
}
