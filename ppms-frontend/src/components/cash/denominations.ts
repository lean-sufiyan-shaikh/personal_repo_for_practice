import type { CashDenominations } from '../../api/cashApi'
import { roundToPaise } from '../../utils/money'

export type NoteKey = 'notes500' | 'notes200' | 'notes100' | 'notes50' | 'notes20' | 'notes10'

/** The notes in circulation, largest first. ₹2000 is left out: it was withdrawn and banks no longer take it. */
export const NOTES: ReadonlyArray<{ key: NoteKey; value: number }> = [
  { key: 'notes500', value: 500 },
  { key: 'notes200', value: 200 },
  { key: 'notes100', value: 100 },
  { key: 'notes50', value: 50 },
  { key: 'notes20', value: 20 },
  { key: 'notes10', value: 10 },
]

/** What the person typed: a count per note and the coins in rupees, kept as text while it is being edited. */
export type DenominationCounts = Record<NoteKey, string> & { coins: string }

export const EMPTY_COUNTS: DenominationCounts = {
  notes500: '', notes200: '', notes100: '', notes50: '', notes20: '', notes10: '', coins: '',
}

const MAX_NOTES = 100_000

function noteCount(text: string): number {
  const count = Number(text)
  return Number.isInteger(count) && count >= 0 ? Math.min(count, MAX_NOTES) : 0
}

function coinAmount(text: string): number {
  const amount = Number(text)
  return Number.isFinite(amount) && amount >= 0 ? roundToPaise(amount) : 0
}

export function countsTotal(counts: DenominationCounts): number {
  const notes = NOTES.reduce((sum, note) => sum + note.value * noteCount(counts[note.key]), 0)
  return roundToPaise(notes + coinAmount(counts.coins))
}

/** The total as the text a money field holds: empty while nothing is counted. */
export function countsTotalText(counts: DenominationCounts): string {
  const total = countsTotal(counts)
  return total === 0 && Object.values(counts).every((text) => text === '') ? '' : String(total)
}

export function toDenominations(counts: DenominationCounts): CashDenominations {
  return {
    notes500: noteCount(counts.notes500),
    notes200: noteCount(counts.notes200),
    notes100: noteCount(counts.notes100),
    notes50: noteCount(counts.notes50),
    notes20: noteCount(counts.notes20),
    notes10: noteCount(counts.notes10),
    coins: coinAmount(counts.coins),
  }
}

/** Adds (or with -1 removes) one note, never going below zero. */
export function stepCount(text: string, change: 1 | -1): string {
  const next = Math.max(0, noteCount(text) + change)
  return next === 0 ? '' : String(next)
}
