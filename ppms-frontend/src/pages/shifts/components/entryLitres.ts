/** Litres of a fuel credit entry: as typed in litres mode, else the amount at the shift's price; null when unknown. */
export function entryLitres(mode: 'amount' | 'liters', litresText: string, amount: number, price: number | null): number | null {
  if (mode === 'liters') {
    const litres = Number(litresText)
    return litresText.trim() !== '' && Number.isFinite(litres) ? litres : null
  }
  return price && price > 0 && amount > 0 ? Math.round((amount / price) * 100) / 100 : null
}
