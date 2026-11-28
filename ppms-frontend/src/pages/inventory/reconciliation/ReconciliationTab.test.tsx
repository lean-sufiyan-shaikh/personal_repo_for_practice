import { fireEvent, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { inventoryApi } from '../../../api/inventoryApi'
import type { ReconciliationPeriod, TankReconciliation, TankStock } from '../../../api/inventoryApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { resetScreenWidthAfterEach, setScreenWidth } from '../../../test/screenWidth'
import { localDateInputValue } from '../../../utils/date'
import { ReconciliationTab } from './ReconciliationTab'

vi.mock('../../../api/inventoryApi', () => ({ inventoryApi: { getReconciliation: vi.fn() } }))

const TANKS = [{ tankId: 1, tankIdentifier: 'P1', fuelType: 'PETROL' }] as TankStock[]

function period(overrides: Partial<ReconciliationPeriod>): ReconciliationPeriod {
  return {
    periodStart: '2026-11-20', periodEnd: '2026-11-20', days: 1, status: 'RECONCILED',
    openingLitres: 10_000, deliveredLitres: 0, soldLitres: 1_000, removedLitres: 0, expectedClosingLitres: 9_000,
    closingLitres: 9_000, varianceLitres: 0, variancePercent: 0, withinTolerance: true,
    cumulativeVarianceLitres: 0, cumulativeVariancePercent: 0, ...overrides,
  }
}

const LOSING_TANK: TankReconciliation = {
  tankId: 1, tankIdentifier: 'P1', fuelType: 'PETROL', dipTolerance: 50, lossAlertPercent: 0.5,
  summary: { salesLitres: 2_000, varianceLitres: -80, variancePercent: -4, reconciledPeriodCount: 2, flaggedPeriodCount: 1, status: 'LOSS_ABOVE_LIMIT' },
  periods: [
    period({ periodStart: '2026-11-19', periodEnd: '2026-11-19', status: 'NEEDS_OPENING_DIP', openingLitres: null, deliveredLitres: null,
      soldLitres: null, removedLitres: null, expectedClosingLitres: null, closingLitres: 10_000, varianceLitres: null,
      variancePercent: null, withinTolerance: null, cumulativeVarianceLitres: null, cumulativeVariancePercent: null }),
    period({ periodStart: '2026-11-20', periodEnd: '2026-11-20' }),
    period({ periodStart: '2026-11-21', periodEnd: '2026-11-22', days: 2, openingLitres: 9_000, closingLitres: 7_920,
      expectedClosingLitres: 8_000, varianceLitres: -80, variancePercent: -8, withinTolerance: false,
      cumulativeVarianceLitres: -80, cumulativeVariancePercent: -4 }),
  ],
}

function respondWith(tanks: TankReconciliation[]) {
  vi.mocked(inventoryApi.getReconciliation).mockResolvedValue({ from: '2026-11-16', to: '2026-11-22', tanks })
}

describe('ReconciliationTab', () => {
  resetScreenWidthAfterEach()

  beforeEach(() => {
    vi.mocked(inventoryApi.getReconciliation).mockReset()
  })

  it('asks for the last 7 days, explains ambient litres, and shows the tank card and each period in words', async () => {
    respondWith([LOSING_TANK])
    renderWithProviders(<ReconciliationTab pumpId={3} tanks={TANKS} />)

    const card = await screen.findByRole('article', { name: 'P1 · Petrol' })
    expect(inventoryApi.getReconciliation).toHaveBeenCalledWith(3, localDateInputValue(-6), localDateInputValue(), undefined)
    expect(screen.getByText(/variances include the effect of temperature/)).toBeInTheDocument()

    expect(within(card).getByText('Loss above 0.50%')).toBeInTheDocument()
    expect(within(card).getByText('−80.00 L')).toBeInTheDocument()
    expect(within(card).getByText('−4.00%')).toBeInTheDocument()
    expect(within(card).getByText(/1 period outside dip tolerance/)).toBeInTheDocument()
    expect(within(card).getByRole('img', { name: 'Variance per period for P1' })).toBeInTheDocument()

    const table = await screen.findByRole('table', { name: 'Daily reconciliation for P1' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByText('Needs an opening dip')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Within tolerance')).toBeInTheDocument()
    expect(within(rows[2]).getByText('2 days')).toBeInTheDocument()
    expect(within(rows[2]).getByText('Outside tolerance')).toBeInTheDocument()
    expect(within(rows[2]).getByText('−8.00%')).toBeInTheDocument()
  })

  it('flags a period whose shifts sold more than book stock, so the missing delivery gets recorded', async () => {
    respondWith([{ ...LOSING_TANK, periods: [period({ stockShortfallLitres: 150 })] }])
    renderWithProviders(<ReconciliationTab pumpId={3} tanks={TANKS} />)

    const table = await screen.findByRole('table', { name: 'Daily reconciliation for P1' })
    expect(within(table).getByText(/Shifts sold 150(\.00)? L more than book stock/)).toBeInTheDocument()
  })

  it('shows each period as a card with its figures in words on a phone', async () => {
    setScreenWidth(375)
    respondWith([LOSING_TANK])
    renderWithProviders(<ReconciliationTab pumpId={3} tanks={TANKS} />)

    const table = await screen.findByRole('table', { name: 'Daily reconciliation for P1' })
    expect(table).toHaveClass('ui-table-cards')
    const flagged = within(table).getAllByRole('row')[3]
    expect(within(flagged).getByText(/Expected 8,000.00 L · dip 7,920.00 L/)).toBeInTheDocument()
    expect(within(flagged).getByText('Outside tolerance')).toBeInTheDocument()
  })

  it('says when a tank has no dips in the dates and when there are no tanks', async () => {
    respondWith([{ ...LOSING_TANK, periods: [], summary: { ...LOSING_TANK.summary, status: 'NO_DATA', flaggedPeriodCount: 0 } }])
    const { unmount } = renderWithProviders(<ReconciliationTab pumpId={3} tanks={TANKS} />)
    expect(await screen.findByText('No dips recorded for this tank in the selected dates.')).toBeInTheDocument()
    expect(screen.getByText('No reconciled days')).toBeInTheDocument()
    unmount()

    respondWith([])
    renderWithProviders(<ReconciliationTab pumpId={3} tanks={[]} />)
    expect(await screen.findByText('No tanks to reconcile.')).toBeInTheDocument()
  })

  it('shows a retry when the reconciliation cannot be loaded', async () => {
    vi.mocked(inventoryApi.getReconciliation).mockRejectedValueOnce(new Error('offline'))
    respondWith([LOSING_TANK])
    renderWithProviders(<ReconciliationTab pumpId={3} tanks={TANKS} />)

    await user().click(await screen.findByRole('button', { name: 'Retry' }))

    expect(await screen.findByRole('article', { name: 'P1 · Petrol' })).toBeInTheDocument()
  })

  it('shows loading rows while the first answer is on its way', () => {
    vi.mocked(inventoryApi.getReconciliation).mockReturnValue(new Promise(() => {}))
    renderWithProviders(<ReconciliationTab pumpId={3} tanks={TANKS} />)

    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(document.querySelector('.ui-skeleton')).not.toBeNull()
  })

  it('refuses a range longer than 92 days without asking the server', async () => {
    respondWith([LOSING_TANK])
    renderWithProviders(<ReconciliationTab pumpId={3} tanks={TANKS} />)
    await screen.findByRole('article', { name: 'P1 · Petrol' })
    vi.mocked(inventoryApi.getReconciliation).mockClear()

    fireEvent.change(screen.getByLabelText('From date'), { target: { value: localDateInputValue(-100) } })

    expect(screen.getByRole('alert')).toHaveTextContent('Choose a range of at most 92 days.')
    expect(inventoryApi.getReconciliation).not.toHaveBeenCalled()
  })
})

function user() {
  return userEvent.setup()
}
