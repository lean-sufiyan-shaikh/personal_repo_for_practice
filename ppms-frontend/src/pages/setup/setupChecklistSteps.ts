import type { SetupStep } from '../../api/pumpApi'
import { translateKey } from '../../i18n'
import type { SectionKey } from './sectionKey'

export function setupStepLabel(step: SetupStep): string {
  return translateKey(`setup:checklist.steps.${step}`)
}

export const SETUP_STEP_SECTIONS: Partial<Record<SetupStep, SectionKey>> = {
  DISPENSERS:        'nozzles',
  TANKS:             'tanks',
  FUEL_PRICES:       'prices',
  SHIFT_DEFINITIONS: 'shifts',
  STAFF:             'staff',
  GST_DETAILS:       'gst',
}

/** Where a step is set up: its Setup section, or the Credit page for credit clients. */
export function setupStepHref(step: SetupStep): string {
  const section = SETUP_STEP_SECTIONS[step]
  return section ? `/dashboard/setup?section=${section}` : '/dashboard/credit'
}
