import type { ClientVehicle, ClientVehicles } from '../../../api/vehicleApi'

/** The vehicle and driver a credit entry names: a listed vehicle, or a registration typed in. */
export interface VehicleEntryValue {
  vehicleId: number | null
  registration: string
  driverName: string
  driverPhone: string
}

export const EMPTY_VEHICLE_ENTRY: VehicleEntryValue = { vehicleId: null, registration: '', driverName: '', driverPhone: '' }

/** The spelling the server stores: upper case, without spaces, dashes, dots or slashes. */
export function normaliseRegistration(text: string): string {
  return text.replace(/[\s.\-/]+/g, '').toUpperCase()
}

/** The vehicle part of a credit entry request; blank parts are left out. */
export function vehicleRequestFields(value: VehicleEntryValue) {
  return {
    vehicleId: value.vehicleId ?? undefined,
    vehicleRegistration: value.vehicleId == null && value.registration.trim() ? value.registration.trim() : undefined,
    driverName: value.driverName.trim() || undefined,
    driverPhone: value.driverPhone.trim() || undefined,
  }
}

/** The listed vehicle the typed registration names, if any. */
export function matchingVehicle(vehicles: ClientVehicle[], registration: string): ClientVehicle | undefined {
  const typed = normaliseRegistration(registration)
  return typed ? vehicles.find((vehicle) => vehicle.registration === typed) : undefined
}

type VehicleNoticeKey =
  | 'vehicleEntry.blockNoVehicles' | 'vehicleEntry.blockPick' | 'vehicleEntry.unknownFlagged'
  | 'vehicleEntry.fuelRefused' | 'vehicleEntry.fuelFlagged' | 'vehicleEntry.limitRefused' | 'vehicleEntry.limitFlagged'

export interface VehicleNotice {
  /** danger: the server will refuse the entry; warning: it will be let through but flagged. */
  tone: 'danger' | 'warning'
  key: VehicleNoticeKey
  params?: Record<string, string>
}

interface EntryFigures {
  fuelType: string | null
  amount: number
  litres: number | null
  formatLitres: (value: number) => string
  formatAmount: (value: number) => string
  fuelLabel: (fuelType: string) => string
}

/** What the server's vehicle check will do with the entry as filled in so far; null when it passes. */
export function vehicleNotice(picker: ClientVehicles | undefined, value: VehicleEntryValue,
                              figures: EntryFigures): VehicleNotice | null {
  if (!picker || picker.vehicleCheck === 'OFF') return null
  const refused = picker.vehicleCheck === 'BLOCK'
  const tone = refused ? 'danger' : 'warning'
  const vehicle = picker.vehicles.find((candidate) => candidate.id === value.vehicleId)
  if (!vehicle) {
    if (refused) {
      return { tone, key: picker.vehicles.length === 0 ? 'vehicleEntry.blockNoVehicles' : 'vehicleEntry.blockPick' }
    }
    return value.registration.trim() ? { tone, key: 'vehicleEntry.unknownFlagged' } : null
  }
  const { fuelType, amount, litres } = figures
  if (fuelType && vehicle.allowedFuelTypes.length > 0 && !vehicle.allowedFuelTypes.includes(fuelType)) {
    return { tone, key: refused ? 'vehicleEntry.fuelRefused' : 'vehicleEntry.fuelFlagged',
      params: { registration: vehicle.registration, fuel: figures.fuelLabel(fuelType) } }
  }
  if (litres != null && vehicle.litresLeft != null && litres > vehicle.litresLeft) {
    return { tone, key: refused ? 'vehicleEntry.limitRefused' : 'vehicleEntry.limitFlagged',
      params: { registration: vehicle.registration, left: figures.formatLitres(vehicle.litresLeft) } }
  }
  if (vehicle.amountLeft != null && amount > vehicle.amountLeft) {
    return { tone, key: refused ? 'vehicleEntry.limitRefused' : 'vehicleEntry.limitFlagged',
      params: { registration: vehicle.registration, left: figures.formatAmount(vehicle.amountLeft) } }
  }
  return null
}
