import type { PricedPart } from '../../../utils/priceSegments'
import { formatCurrency, formatDecimal } from '../../../utils/format'

/** "10.00 L × ₹100.00 + 20.00 L × ₹110.00": a nozzle's litres at each price it sold at. */
export function pricedPartsText(parts: PricedPart[], unit: string): string {
  return parts.map((part) => `${formatDecimal(part.units)} ${unit} × ${formatCurrency(part.pricePerUnit)}`).join(' + ')
}
