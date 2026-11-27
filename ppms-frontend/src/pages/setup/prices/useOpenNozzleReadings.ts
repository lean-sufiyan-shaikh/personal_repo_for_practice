import { useState } from 'react'
import { useQueries } from '@tanstack/react-query'
import { pumpApi } from '../../../api/pumpApi'
import type { OpenNozzleReading } from '../../../api/pumpApi'
import type { FuelType } from '../../../types/shift'

export interface NozzleMeterReading {
  nozzleId: number
  meterReading: number
}

/**
 * The nozzles on open shifts selling each fuel whose price is about to change, and the meter reading entered for each,
 * prefilled with the last reading known. Litres up to that reading keep the old price.
 */
export function useOpenNozzleReadings(pumpId: number, fuelTypes: FuelType[]) {
  const [edited, setEdited] = useState<Record<number, string>>({})
  const results = useQueries({
    queries: fuelTypes.map((fuelType) => ({
      queryKey: ['openNozzles', pumpId, fuelType],
      queryFn: () => pumpApi.getOpenNozzles(pumpId, fuelType),
    })),
  })
  const nozzlesByFuel = new Map<FuelType, OpenNozzleReading[]>(
    fuelTypes.map((fuelType, index) => [fuelType, results[index]?.data ?? []]),
  )

  const valueOf = (nozzle: OpenNozzleReading) => edited[nozzle.nozzleId] ?? String(nozzle.lastKnownReading)

  const readingsFor = (fuelType: FuelType): NozzleMeterReading[] =>
    (nozzlesByFuel.get(fuelType) ?? []).map((nozzle) => ({ nozzleId: nozzle.nozzleId, meterReading: Number(valueOf(nozzle)) }))

  const missingReading = [...nozzlesByFuel.values()].flat().some((nozzle) => valueOf(nozzle).trim() === '')

  return {
    nozzlesByFuel,
    valueOf,
    setReading: (nozzleId: number, value: string) => setEdited((previous) => ({ ...previous, [nozzleId]: value })),
    readingsFor,
    missingReading,
    isLoading: results.some((result) => result.isLoading),
    reset: () => setEdited({}),
  }
}
