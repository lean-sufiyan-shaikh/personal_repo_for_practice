import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { ReceiptResult } from './receiptCheck'
import { ReceiptResultCard } from './ReceiptResultCard'

const tolerances = { tolerancePercent: 0.2, densityTolerance: 3 }
const noDensity = { densityDifference: null, densityFlagged: false }

function result(overrides: Partial<ReceiptResult>): ReceiptResult {
  return { kind: 'SHORT', receivedLitres: 11976, soldBetweenLitres: 0, varianceLitres: -24, variancePercent: -0.2, beyondTolerance: false, ...overrides }
}

describe('ReceiptResultCard', () => {
  it('says the receipt check is incomplete until every reading is in', () => {
    render(<ReceiptResultCard result={null} invoiceLitres={12000} {...tolerances} {...noDensity} />)

    expect(screen.queryByText(/^Received/)).not.toBeInTheDocument()
  })

  it('shows a shortage within tolerance, with the fuel sold while unloading', () => {
    render(<ReceiptResultCard result={result({ soldBetweenLitres: 10 })} invoiceLitres={12000} {...tolerances} {...noDensity} />)

    expect(screen.getByText('Received 11,976.00 L of 12,000.00 L invoiced')).toBeInTheDocument()
    expect(screen.getByText('Short 24.00 L (0.20%) — within tolerance')).toBeInTheDocument()
    expect(screen.getByText('Includes 10.00 L sold while unloading')).toBeInTheDocument()
  })

  it('says a claim will be raised when the shortage is beyond tolerance', () => {
    render(<ReceiptResultCard result={result({ receivedLitres: 11960, varianceLitres: -40, variancePercent: -0.33, beyondTolerance: true })}
      invoiceLitres={12000} {...tolerances} {...noDensity} />)

    expect(screen.getByText('Short 40.00 L (0.33%) — beyond the 0.20% tolerance, a claim will be raised')).toBeInTheDocument()
    expect(screen.queryByText(/sold while unloading/)).not.toBeInTheDocument()
  })

  it('notes an over-delivery beyond tolerance without a claim', () => {
    render(<ReceiptResultCard result={result({ kind: 'OVER', receivedLitres: 12110, varianceLitres: 110, variancePercent: 0.92, beyondTolerance: true })}
      invoiceLitres={12000} {...tolerances} {...noDensity} />)

    expect(screen.getByText('Received 12,110.00 L of 12,000.00 L invoiced')).toBeInTheDocument()
    expect(screen.getByText('Over 110.00 L (0.92%) — beyond the 0.20% tolerance, noted only, no claim')).toBeInTheDocument()
  })

  it('flags a density difference above the pump tolerance and passes one within it', () => {
    const { rerender } = render(<ReceiptResultCard result={null} invoiceLitres={0} {...tolerances} densityDifference={4.5} densityFlagged />)
    expect(screen.getByText(/Density difference \+4\.5 kg\/m³ · more than 3\.0 kg\/m³/)).toBeInTheDocument()

    rerender(<ReceiptResultCard result={null} invoiceLitres={0} {...tolerances} densityDifference={2} densityFlagged={false} />)
    expect(screen.getByText(/Density difference \+2\.0 kg\/m³ · within 3\.0 kg\/m³/)).toBeInTheDocument()
  })
})
