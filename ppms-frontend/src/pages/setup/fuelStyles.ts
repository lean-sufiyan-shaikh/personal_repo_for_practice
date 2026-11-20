import type { FuelType } from '../../types/shift'

// ── Shared fuel-type display helpers ─────────────────────────────────────────

export const ALL_FUEL_TYPES: FuelType[] = ['PETROL', 'SPEED_PETROL', 'DIESEL', 'SPEED_DIESEL', 'CNG']

export const FUEL_COLOR: Record<string, string> = {
  PETROL:       'bg-emerald-100 text-emerald-700 border-emerald-300',
  SPEED_PETROL: 'bg-teal-100 text-teal-700 border-teal-300',
  DIESEL:       'bg-blue-100 text-blue-700 border-blue-300',
  SPEED_DIESEL: 'bg-indigo-100 text-indigo-700 border-indigo-300',
  CNG:          'bg-amber-100 text-amber-700 border-amber-300',
}
