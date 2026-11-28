import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { RadarEvidence } from '../../api/radarApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import { RadarScoreDetails } from './RadarScoreDetails'

const amendment: RadarEvidence = {
  kind: 'METER_AMENDMENT', date: '2026-11-26', amount: null, litres: null, usualLitres: null, percent: null,
  fromReading: 3500, toReading: 3400, shiftId: null, referenceId: 9, note: 'Typed wrong',
}

describe('RadarScoreDetails', () => {
  it('lists each rule with its points and each event with its note and the page to check', () => {
    renderWithProviders(<RadarScoreDetails id="why" evidence={[amendment]} reasons={[
      { code: 'METER_AMENDMENTS', points: 25, maxPoints: 50, params: { count: 1, lowering: 1 } },
      { code: 'NOT_ENOUGH_SHIFTS', points: 0, maxPoints: 0, params: { shifts: 2, minimum: 5 } },
    ]} />)

    expect(screen.getByText('25 of 50 points')).toBeInTheDocument()
    expect(screen.getAllByText(/of \d+ points/)).toHaveLength(1)
    expect(screen.getByText('Meter changed from 3,500.000 to 3,400.000')).toBeInTheDocument()
    expect(screen.getByText('Reason given: Typed wrong')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open nozzle setup' })).toHaveAttribute('href', '/dashboard/setup?section=nozzles')
  })
})
