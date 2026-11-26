import i18n from '../../../i18n'

export const SLIP_ACCEPT = 'image/jpeg,image/png,application/pdf'
const MAX_SLIP_BYTES = 5 * 1024 * 1024
const ALLOWED_TYPES = SLIP_ACCEPT.split(',')

/** Why the chosen slip file cannot be sent, or null when it can; the server checks the file's content again. */
export function slipFileProblem(file: File): string | null {
  if (!ALLOWED_TYPES.includes(file.type)) return i18n.t('cash:safe.slip.wrongType')
  if (file.size > MAX_SLIP_BYTES) return i18n.t('cash:safe.slip.tooLarge')
  return null
}

/** Opens the slip in a new tab; the address is released once the tab has had time to load it. */
export function openSlip(blob: Blob) {
  const url = URL.createObjectURL(blob)
  window.open(url, '_blank', 'noopener')
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
}
