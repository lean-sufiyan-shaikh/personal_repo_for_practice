import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { radarApi } from '../../api/radarApi'
import type { LeakageRadar, NozzleScore, PersonScore, TankScore } from '../../api/radarApi'
import { canAccessPage } from '../../permissions/permissions'
import { usePumpStore } from '../../store/usePumpStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import { resetScreenWidthAfterEach, setScreenWidth } from '../../test/screenWidth'
import { navGroupsFor } from '../dashboard/navItems'
import RadarPage from './RadarPage'

vi.mock('../../api/radarApi', () => ({ radarApi: { get: vi.fn() } }))

const SHORT_OPERATOR: PersonScore = {
  userId: 7, name: 'Ravi Kumar', role: 'OPERATOR', score: 47, band: 'WATCH', shiftCount: 10, shortShiftCount: 6,
  shortAmount: 1800, trend: 'RISING', correctionCount: 0,
  reasons: [
    { code: 'SHORTAGE_RATE', points: 30, maxPoints: 30, params: { shortShifts: 6, shifts: 10, percent: 60, restPercent: 10 } },
    { code: 'SHORTAGE_AMOUNT', points: 7, maxPoints: 20, params: { perShift: 180, restPerShift: 10, total: 1800 } },
    { code: 'CORRECTIONS', points: 0, maxPoints: 40, params: { count: 0, expected: 1 } },
  ],
  evidence: [{
    kind: 'SHORT_SHIFT', date: '2026-11-25', amount: 300, litres: null, usualLitres: null, percent: null,
    fromReading: null, toReading: null, shiftId: 11, referenceId: null, note: null,
  }],
}

const NEWCOMER: PersonScore = {
  ...SHORT_OPERATOR, userId: 8, name: 'Asha', score: 0, band: 'NOT_ENOUGH_DATA', shiftCount: 2, shortShiftCount: 0,
  shortAmount: 0, trend: 'STEADY', reasons: [{ code: 'NOT_ENOUGH_SHIFTS', points: 0, maxPoints: 0, params: { shifts: 2, minimum: 5 } }],
  evidence: [],
}

const AMENDED_NOZZLE: NozzleScore = {
  nozzleId: 5, nozzleNumber: 1, duName: 'DU-1', fuelType: 'PETROL', score: 45, band: 'WATCH', shiftCount: 6,
  amendmentCount: 1, readingJumpCount: 1, salesOutlierCount: 0,
  reasons: [{ code: 'METER_AMENDMENTS', points: 25, maxPoints: 50, params: { count: 1, lowering: 1 } }],
  evidence: [{
    kind: 'METER_AMENDMENT', date: '2026-11-26', amount: null, litres: null, usualLitres: null, percent: null,
    fromReading: 3500, toReading: 3400, shiftId: null, referenceId: 9, note: 'Typed wrong',
  }],
}

const LOSING_TANK: TankScore = {
  tankId: 3, tankIdentifier: 'T1', fuelType: 'PETROL', score: 100, band: 'HIGH', lossAlertPercent: 0.5,
  varianceLitres: -80, variancePercent: -2, weeks: [],
  reasons: [{ code: 'PERIOD_LOSS', points: 30, maxPoints: 30, params: { percent: -2, limitPercent: 0.5 } }],
  evidence: [{
    kind: 'LOSS_WEEK', date: '2026-11-27', amount: null, litres: -20, usualLitres: 1000, percent: -2,
    fromReading: null, toReading: null, shiftId: null, referenceId: null, note: null,
  }],
}

function radar(overrides: Partial<LeakageRadar> = {}): LeakageRadar {
  return {
    periodDays: 30, from: '2026-10-29', to: '2026-11-27', minimumShifts: 5,
    people: [SHORT_OPERATOR, NEWCOMER], nozzles: [AMENDED_NOZZLE], tanks: [LOSING_TANK], ...overrides,
  }
}

describe('RadarPage', () => {
  resetScreenWidthAfterEach()

  beforeEach(() => {
    usePumpStore.setState({ selectedPumpId: 4 })
    vi.mocked(radarApi.get).mockReset()
    vi.mocked(radarApi.get).mockResolvedValue(radar())
  })

  it('shows each score with its band, facts and trend for the last 30 days, a newcomer without a score, and how scores work', async () => {
    renderWithProviders(<RadarPage />)

    const card = await screen.findByRole('article', { name: 'Ravi Kumar' })
    expect(radarApi.get).toHaveBeenCalledWith(4, 30)
    expect(within(card).getByLabelText('Score 47 of 100')).toBeInTheDocument()
    expect(within(card).getByText('Watch')).toBeInTheDocument()
    expect(within(card).getByText('Shortages rising')).toBeInTheDocument()
    expect(within(card).getByText(/Operator · 10 shifts · 6 short \(₹1,800.00\) · 0 corrections/)).toBeInTheDocument()
    expect(screen.getByRole('article', { name: 'DU-1 · Nozzle 1' })).toBeInTheDocument()
    expect(within(screen.getByRole('article', { name: 'T1' })).getByText('High')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Last 30 days' })).toHaveAttribute('aria-pressed', 'true')

    const newcomer = screen.getByRole('article', { name: 'Asha' })
    expect(within(newcomer).getByText('Not enough data yet')).toBeInTheDocument()
    expect(within(newcomer).queryByLabelText(/Score \d+ of 100/)).not.toBeInTheDocument()

    expect(screen.getByRole('heading', { name: 'How the scores work' })).toBeInTheDocument()
    expect(screen.getByText('Low is under 30, Watch is 30 to 59, High is 60 and above.')).toBeInTheDocument()
    expect(screen.getByText(/With fewer than 5 closed shifts/)).toBeInTheDocument()
    expect(screen.getByText(/not proof that anything is wrong/)).toBeInTheDocument()
  })

  it('opens why this score with the rule points and the events linked to their records', async () => {
    const user = userEvent.setup()
    renderWithProviders(<RadarPage />)
    const card = await screen.findByRole('article', { name: 'Ravi Kumar' })

    await user.click(within(card).getByRole('button', { name: 'Why this score' }))

    expect(within(card).getByText('30 of 30 points')).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'Open shift' })).toHaveAttribute('href', '/dashboard/shifts?shift=11')
  })

  it('switches to the last 90 days', async () => {
    const user = userEvent.setup()
    renderWithProviders(<RadarPage />)
    await screen.findByRole('article', { name: 'Ravi Kumar' })

    await user.click(screen.getByRole('button', { name: 'Last 90 days' }))

    await waitFor(() => expect(radarApi.get).toHaveBeenLastCalledWith(4, 90))
    expect(screen.getByRole('button', { name: 'Last 90 days' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('says when there is nothing to score', async () => {
    vi.mocked(radarApi.get).mockResolvedValue(radar({ people: [], nozzles: [], tanks: [] }))
    renderWithProviders(<RadarPage />)

    expect(await screen.findByText('No closed shifts, voids or rejections in this period.')).toBeInTheDocument()
    expect(screen.getByText('No nozzles are set up yet.')).toBeInTheDocument()
    expect(screen.getByText('No tanks are set up yet.')).toBeInTheDocument()
  })

  it('shows loading rows, then a retry when the radar cannot be loaded', async () => {
    const user = userEvent.setup()
    vi.mocked(radarApi.get).mockReturnValueOnce(new Promise(() => {}))
    const { unmount } = renderWithProviders(<RadarPage />)
    expect(document.querySelector('.ui-skeleton')).not.toBeNull()
    unmount()

    vi.mocked(radarApi.get).mockRejectedValueOnce(new Error('offline'))
    renderWithProviders(<RadarPage />)
    expect(await screen.findByText('The radar could not be loaded.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByRole('article', { name: 'Ravi Kumar' })).toBeInTheDocument()
  })

  it('stacks the cards in one column on a phone', async () => {
    setScreenWidth(375)
    renderWithProviders(<RadarPage />)

    const card = await screen.findByRole('article', { name: 'Ravi Kumar' })
    expect(card.parentElement).toHaveClass('space-y-3')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Period' })).toBeInTheDocument()
  })

  it('is listed for owners and admins only', () => {
    const pagesFor = (role: 'OWNER' | 'ADMIN' | 'MANAGER' | 'OPERATOR' | 'ACCOUNTANT') =>
      navGroupsFor(role).flatMap(group => group.items.map(item => item.page))

    expect(pagesFor('OWNER')).toContain('radar')
    expect(pagesFor('ADMIN')).toContain('radar')
    expect(pagesFor('MANAGER')).not.toContain('radar')
    expect(pagesFor('OPERATOR')).not.toContain('radar')
    expect(pagesFor('ACCOUNTANT')).not.toContain('radar')
    expect(canAccessPage('radar', 'MANAGER')).toBe(false)
  })
})
