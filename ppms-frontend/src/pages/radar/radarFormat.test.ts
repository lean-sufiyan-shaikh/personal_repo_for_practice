import { describe, expect, it } from 'vitest'
import type { RadarEvidence } from '../../api/radarApi'
import { evidenceLink, evidenceText, reasonText } from './radarFormat'

function evidence(overrides: Partial<RadarEvidence>): RadarEvidence {
  return {
    kind: 'SHORT_SHIFT', date: '2026-11-25', amount: null, litres: null, usualLitres: null, percent: null,
    fromReading: null, toReading: null, shiftId: null, referenceId: null, note: null, ...overrides,
  }
}

describe('reasonText', () => {
  it('puts the rule in words with its counts and percentages', () => {
    expect(reasonText({ code: 'SHORTAGE_RATE', points: 30, maxPoints: 30, params: { shortShifts: 6, shifts: 10, percent: 60, restPercent: 10 } }))
      .toMatch(/^Short on 6 of 10 shifts \(60%\), against 10% for everyone else/)
  })

  it('shows money figures as rupees', () => {
    expect(reasonText({ code: 'SHORTAGE_AMOUNT', points: 7, maxPoints: 20, params: { perShift: 180, restPerShift: 10, total: 1800 } }))
      .toMatch(/^Shortages average ₹180.00 a shift, against ₹10.00/)
  })

  it('shows a tank loss with its sign against the loss limit', () => {
    expect(reasonText({ code: 'PERIOD_LOSS', points: 30, maxPoints: 30, params: { percent: -2, limitPercent: 0.5 } }))
      .toMatch(/^Variance over the whole period: −2% of sales, against a 0.5% loss limit/)
  })
})

describe('evidenceText', () => {
  it('says what happened with the amount, the meter readings or the litres', () => {
    expect(evidenceText(evidence({ amount: 300 }))).toBe('Shift short by ₹300.00')
    expect(evidenceText(evidence({ kind: 'METER_AMENDMENT', fromReading: 3500, toReading: 3400 })))
      .toBe('Meter changed from 3,500.000 to 3,400.000')
    expect(evidenceText(evidence({ kind: 'LOSS_WEEK', litres: -20, usualLitres: 1000, percent: -2 })))
      .toMatch(/^Week lost 20.00 L, −2.00% of 1,000.00 L sold/)
  })
})

describe('evidenceLink', () => {
  it('links each event to the page holding its record', () => {
    expect(evidenceLink(evidence({ shiftId: 11 }))).toEqual({ to: '/dashboard/shifts?shift=11', label: 'Open shift' })
    expect(evidenceLink(evidence({ kind: 'METER_AMENDMENT' }))).toEqual({ to: '/dashboard/setup?section=nozzles', label: 'Open nozzle setup' })
    expect(evidenceLink(evidence({ kind: 'LOSS_WEEK' }))).toEqual({ to: '/dashboard/inventory?tab=reconciliation', label: 'Open reconciliation' })
    expect(evidenceLink(evidence({ kind: 'CREDIT_REASSIGNED', referenceId: 4 }))?.to).toBe('/dashboard/credit/4')
    expect(evidenceLink(evidence({ kind: 'SETTLEMENT_VOID' }))?.to).toBe('/dashboard/settlements')
    expect(evidenceLink(evidence({ kind: 'EXPENSE_REJECTED' }))?.to).toBe('/dashboard/expenses')
  })

  it('has no link when the record is not known', () => {
    expect(evidenceLink(evidence({}))).toBeNull()
    expect(evidenceLink(evidence({ kind: 'CREDIT_REASSIGNED' }))).toBeNull()
  })
})
