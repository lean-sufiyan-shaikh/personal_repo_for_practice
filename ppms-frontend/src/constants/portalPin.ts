/** What a client types on the balance page; PINs set before 6 digits were required may still be 4 or 5 digits. */
export const PORTAL_PIN_PATTERN = /^\d{4,6}$/

const NEW_PORTAL_PIN_PATTERN = /^\d{6}$/

const COMMON_PINS = new Set([
  '123123', '121212', '112233', '000000', '123456', '654321', '111222', '159753',
  '147258', '123321', '696969', '101010', '007007', '520520',
])

export const digitsOnly = (value: string) => value.replace(/\D/g, '')

export type PortalPinProblem = 'length' | 'repeated' | 'sequential' | 'common'

function isRun(pin: string, step: number): boolean {
  return [...pin].every((digit, index) =>
    index === 0 || Number(digit) - Number(pin[index - 1]) === step)
}

/** Why a new portal PIN would be refused, mirroring the server's check; null when it is acceptable. */
export function portalPinProblem(pin: string): PortalPinProblem | null {
  if (!NEW_PORTAL_PIN_PATTERN.test(pin)) return 'length'
  if (/^(\d)\1*$/.test(pin)) return 'repeated'
  if (isRun(pin, 1) || isRun(pin, -1)) return 'sequential'
  if (COMMON_PINS.has(pin)) return 'common'
  return null
}
