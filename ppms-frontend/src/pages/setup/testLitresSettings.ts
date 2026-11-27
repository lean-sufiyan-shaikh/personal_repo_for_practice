export const MAX_TEST_VOLUME_LITRES = 100
export const MAX_TEST_LITRES_LIMIT = 1000
export const DEFAULT_TEST_VOLUME_LITRES = 5
export const DEFAULT_MAX_TEST_LITRES_PER_NOZZLE = 10

export function litresInRange(text: string, max: number): boolean {
  const value = Number(text)
  return text.trim() !== '' && Number.isFinite(value) && value >= 0 && value <= max
}
