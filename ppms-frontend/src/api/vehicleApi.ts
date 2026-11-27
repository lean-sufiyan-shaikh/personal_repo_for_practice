import client from './client'

/** OFF takes any vehicle; WARN lets an entry outside the rules through but flags it; BLOCK refuses it. */
export type VehicleCheckMode = 'OFF' | 'WARN' | 'BLOCK'

/** Why a WARN account's credit entry was let through. */
export type VehicleFlag = 'UNKNOWN_VEHICLE' | 'FUEL_NOT_ALLOWED' | 'OVER_MONTHLY_LIMIT'

export interface ClientVehicle {
  id: number
  clientId: number
  clientName: string
  registration: string
  /** Empty allows every fuel. */
  allowedFuelTypes: string[]
  monthlyLitreLimit: number | null
  monthlyAmountLimit: number | null
  defaultDriverName: string | null
  defaultDriverPhone: string | null
  active: boolean
  litresThisMonth: number
  amountThisMonth: number
  /** Null without a litre limit. */
  litresLeft: number | null
  /** Null without a rupee limit. */
  amountLeft: number | null
}

export interface ClientVehicles {
  vehicleCheck: VehicleCheckMode
  /** The client the check is set on: the parent for a sub-account. */
  checkClientId: number
  checkClientName: string
  vehicles: ClientVehicle[]
}

export interface SaveVehicleInput {
  registration: string
  allowedFuelTypes: string[]
  monthlyLitreLimit: number | null
  monthlyAmountLimit: number | null
  defaultDriverName: string | null
  defaultDriverPhone: string | null
}

const base = (pumpId: number, clientId: number) => `/pumps/${pumpId}/credit-clients/${clientId}`

export const vehicleApi = {
  getVehicles: (pumpId: number, clientId: number) =>
    client.get<ClientVehicles>(`${base(pumpId, clientId)}/vehicles`).then(r => r.data),

  /** Active vehicles a credit entry for the client may name, with what is left of their limits this month. */
  getVehiclePicker: (pumpId: number, clientId: number) =>
    client.get<ClientVehicles>(`${base(pumpId, clientId)}/vehicle-picker`).then(r => r.data),

  addVehicle: (pumpId: number, clientId: number, input: SaveVehicleInput) =>
    client.post<ClientVehicle>(`${base(pumpId, clientId)}/vehicles`, input).then(r => r.data),

  updateVehicle: (pumpId: number, clientId: number, vehicleId: number, input: SaveVehicleInput) =>
    client.put<ClientVehicle>(`${base(pumpId, clientId)}/vehicles/${vehicleId}`, input).then(r => r.data),

  setVehicleActive: (pumpId: number, clientId: number, vehicleId: number, active: boolean) =>
    client.patch<ClientVehicle>(`${base(pumpId, clientId)}/vehicles/${vehicleId}/status`, { active }).then(r => r.data),

  setVehicleCheck: (pumpId: number, clientId: number, vehicleCheck: VehicleCheckMode) =>
    client.put<ClientVehicles>(`${base(pumpId, clientId)}/vehicle-check`, { vehicleCheck }).then(r => r.data),
}
