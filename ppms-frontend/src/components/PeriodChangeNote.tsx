import { periodChange } from '../utils/datePresets'

/**
 * "▲ 12.5% up vs previous period" under a total. Green when the change is good for the business, red when bad;
 * the arrow and the word carry the meaning too, so colour is never the only signal.
 */
export function PeriodChangeNote({ current, previous, higherIsBetter = true }: {
  current: number
  previous: number
  higherIsBetter?: boolean
}) {
  const { direction, percent } = periodChange(current, previous)
  if (direction === 'same') {
    return <p className="mt-1 text-xs text-ink-3">No change vs previous period</p>
  }
  const isGood = (direction === 'up') === higherIsBetter
  const arrow = direction === 'up' ? '▲' : '▼'
  const words = percent === null
    ? `${direction === 'up' ? 'Up' : 'Down'} from zero vs previous period`
    : `${Math.abs(percent).toLocaleString('en-IN')}% ${direction} vs previous period`
  return (
    <p className={`mt-1 text-xs font-medium ${isGood ? 'text-ok' : 'text-bad'}`}>
      <span aria-hidden="true">{arrow} </span>
      {words}
    </p>
  )
}
