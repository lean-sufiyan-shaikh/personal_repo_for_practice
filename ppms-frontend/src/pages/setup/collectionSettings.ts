import type { CollectionSettings } from '../../api/collectionApi'

const MAX_STAGES = 10
const MAX_DAYS = 365
const UPI_ID = /^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9]{1,63}$/
const PAYEE_NAME = /^[\p{L}\p{M}0-9 .&'-]{1,100}$/u

export interface CollectionSettingsText {
  stages: string
  holdDays: string
  upiId: string
  payeeName: string
}

export type CollectionSettingsField = keyof CollectionSettingsText

/** Keys of collections.json. */
export type CollectionSettingsProblem = 'settings.stagesInvalid' | 'settings.holdDaysInvalid' | 'settings.upiIdInvalid'
  | 'settings.upiIncomplete' | 'settings.payeeNameInvalid'

export function textOf(settings: CollectionSettings): CollectionSettingsText {
  return {
    stages: settings.reminderStageDays.join(', '),
    holdDays: settings.creditHoldDays?.toString() ?? '',
    upiId: settings.upiId ?? '',
    payeeName: settings.upiPayeeName ?? '',
  }
}

function wholeDays(text: string): number | null {
  const value = Number(text)
  return /^\d+$/.test(text) && value >= 1 && value <= MAX_DAYS ? value : null
}

/** Distinct whole days from 1 to 365, at most ten, ascending; null when the text breaks that rule. */
export function parseStages(text: string): number[] | null {
  const parts = text.split(',').map((part) => part.trim()).filter((part) => part !== '')
  const days = parts.map(wholeDays)
  if (days.some((day) => day === null) || days.length > MAX_STAGES) return null
  const sorted = (days as number[]).toSorted((first, second) => first - second)
  return new Set(sorted).size === sorted.length ? sorted : null
}

/** The first problem with each field, as a collections.json settings key. */
export function problemsOf(text: CollectionSettingsText): Partial<Record<CollectionSettingsField, CollectionSettingsProblem>> {
  const upiId = text.upiId.trim()
  const payeeName = text.payeeName.trim()
  const problems: Partial<Record<CollectionSettingsField, CollectionSettingsProblem>> = {}
  if (parseStages(text.stages) === null) problems.stages = 'settings.stagesInvalid'
  if (text.holdDays.trim() !== '' && wholeDays(text.holdDays.trim()) === null) problems.holdDays = 'settings.holdDaysInvalid'
  if (upiId !== '' && !UPI_ID.test(upiId)) problems.upiId = 'settings.upiIdInvalid'
  else if (upiId === '' && payeeName !== '') problems.upiId = 'settings.upiIncomplete'
  if (payeeName !== '' && !PAYEE_NAME.test(payeeName)) problems.payeeName = 'settings.payeeNameInvalid'
  else if (payeeName === '' && upiId !== '') problems.payeeName = 'settings.upiIncomplete'
  return problems
}

export function settingsOf(text: CollectionSettingsText): CollectionSettings {
  return {
    reminderStageDays: parseStages(text.stages) ?? [],
    creditHoldDays: text.holdDays.trim() === '' ? null : Number(text.holdDays.trim()),
    upiId: text.upiId.trim() || null,
    upiPayeeName: text.payeeName.trim() || null,
  }
}
