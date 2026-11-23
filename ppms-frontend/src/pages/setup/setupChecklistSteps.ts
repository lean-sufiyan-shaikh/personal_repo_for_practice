import type { SetupStep } from '../../api/pumpApi'
import type { SectionKey } from './sectionKey'

export const SETUP_STEP_LABELS: Record<SetupStep, string> = {
  DISPENSERS:        'Dispensing units and nozzles',
  TANKS:             'Tanks, with every nozzle linked',
  FUEL_PRICES:       "Today's fuel prices",
  SHIFT_DEFINITIONS: 'Shift definitions',
  STAFF:             'Staff added',
  GST_DETAILS:       'GST details',
  CREDIT_CLIENTS:    'Credit clients',
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
