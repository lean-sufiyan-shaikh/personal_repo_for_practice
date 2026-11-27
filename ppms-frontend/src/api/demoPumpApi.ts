import client from './client'

export interface DemoPump {
  pumpId: number
  pumpName: string
}

/** Building a fortnight of sample shifts takes several seconds on a small server. */
export const DEMO_PUMP_TIMEOUT_MS = 90_000

export const demoPumpApi = {
  create: () =>
    client.post<DemoPump>('/demo-pump', undefined, { timeout: DEMO_PUMP_TIMEOUT_MS }).then((r) => r.data),

  delete: () =>
    client.delete('/demo-pump', { timeout: DEMO_PUMP_TIMEOUT_MS }).then(() => undefined),
}
