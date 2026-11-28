import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { pumpApi } from '../../api/pumpApi'
import { shiftApi } from '../../api/shiftApi'
import { cashApi } from '../../api/cashApi'
import type { PumpSummary } from '../../api/pumpApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import { resetScreenWidthAfterEach, setScreenWidth } from '../../test/screenWidth'
import CloseShiftModal from './CloseShiftModal'
import { cashTotalField, drop, endReading, shift } from './closeShiftModalTestData'

vi.mock('../../api/pumpApi', () => ({ pumpApi: { getCreditClients: vi.fn(), getMyPumps: vi.fn() } }))
vi.mock('../../api/shiftApi', () => ({
  shiftApi: { closeShift: vi.fn(), createHandover: vi.fn(), voidCreditEntry: vi.fn(), getShift: vi.fn(), recordBlindCount: vi.fn() },
}))
vi.mock('../../api/userApi', () => ({ userApi: { getOperators: vi.fn() } }))
vi.mock('../../api/cashApi', () => ({ cashApi: { getShiftDrops: vi.fn(), confirmDrop: vi.fn(), rejectDrop: vi.fn() } }))

describe('CloseShiftModal live summary', () => {
  resetScreenWidthAfterEach()

  beforeEach(() => {
    vi.mocked(pumpApi.getCreditClients).mockResolvedValue([])
    vi.mocked(pumpApi.getMyPumps).mockResolvedValue([{ id: 3, discrepancyEscalationThreshold: 50 } as PumpSummary])
    vi.mocked(shiftApi.getShift).mockResolvedValue(shift)
    vi.mocked(cashApi.getShiftDrops).mockResolvedValue([])
  })

  it('shows the drops already in the safe and what is expected in the bag', async () => {
    setScreenWidth(1280)
    vi.mocked(cashApi.getShiftDrops).mockResolvedValue([drop({ id: 1, amount: 600, status: 'CONFIRMED' })])
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    await user.click(endReading())
    await user.paste('1010')
    await user.click(await cashTotalField(user, 'Cash in bag (₹)'))
    await user.paste('350')

    const summary = screen.getByRole('complementary', { name: 'Summary' })
    expect(within(summary).getByText('Expected in bag').nextSibling).toHaveTextContent('₹400.00')
    expect(within(summary).getByText('Dropped to safe').nextSibling).toHaveTextContent('₹600.00')
    expect(within(summary).getByText('Cash in bag').nextSibling).toHaveTextContent('₹350.00')
    expect(within(summary).getByText('Collected').nextSibling).toHaveTextContent('₹950.00')
    expect(within(summary).getByText('Short ₹50.00')).toBeInTheDocument()
  })

  it('sits beside the form on a wide screen and shows a dash until there is something to add up', async () => {
    setScreenWidth(1280)
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)

    const summary = screen.getByRole('complementary', { name: 'Summary' })
    expect(within(summary).getByText('Total due').nextSibling).toHaveTextContent('—')
    expect(within(summary).getByText('Collected').nextSibling).toHaveTextContent('—')
    expect(within(summary).getByText('Difference').nextSibling).toHaveTextContent('—')
    expect(screen.queryByRole('button', { name: 'Details' })).not.toBeInTheDocument()

    await user.click(endReading())
    await user.paste('1010')
    await user.click(screen.getByLabelText('UPI (₹)'))
    await user.paste('300')
    await user.click(await cashTotalField(user))
    await user.paste('600')

    expect(within(summary).getByText('Sold').nextSibling).toHaveTextContent('10.00 L')
    expect(within(summary).getByText('Total due').nextSibling).toHaveTextContent('₹1,000.00')
    expect(within(summary).getByText('Collected').nextSibling).toHaveTextContent('₹900.00')
    expect(within(summary).getByText('UPI').nextSibling).toHaveTextContent('₹300.00')
    expect(within(summary).getByText('Short ₹100.00')).toBeInTheDocument()
    expect(within(summary).getByText(/more than the ₹50.00 limit/)).toBeInTheDocument()
    expect(within(summary).getByText('Reason for the difference')).toBeInTheDocument()
  })

  it('shows Over and then Balanced in the summary as cash changes', async () => {
    setScreenWidth(1280)
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    const summary = screen.getByRole('complementary', { name: 'Summary' })

    await user.click(endReading())
    await user.paste('1010')
    await user.click(await cashTotalField(user))
    await user.paste('1020')
    expect(within(summary).getByText('Over ₹20.00')).toBeInTheDocument()

    await user.clear(screen.getByLabelText('Cash (₹)'))
    await user.click(await cashTotalField(user))
    await user.paste('1000')
    expect(within(summary).getByText('Balanced')).toBeInTheDocument()
    expect(within(summary).getByText('Ready to review')).toBeInTheDocument()
  })

  it('becomes a compact bar above the buttons on a phone, with the breakdown behind Details', async () => {
    setScreenWidth(375)
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)

    expect(screen.queryByRole('complementary', { name: 'Summary' })).not.toBeInTheDocument()
    const bar = screen.getByRole('region', { name: 'Summary' })
    expect(bar.closest('.ui-modal-footer')).not.toBeNull()
    expect(within(bar).getByText('Due').nextSibling).toHaveTextContent('—')

    await user.click(endReading())
    await user.paste('1010')
    await user.click(await cashTotalField(user))
    await user.paste('900')
    expect(within(bar).getByText('Due').nextSibling).toHaveTextContent('₹1,000.00')
    expect(within(bar).getByText('Short ₹100.00')).toBeInTheDocument()

    const details = within(bar).getByRole('button', { name: 'Details' })
    expect(details).toHaveAttribute('aria-expanded', 'false')
    expect(within(bar).getByText('Sold')).not.toBeVisible()

    await user.click(details)
    expect(within(bar).getByRole('button', { name: 'Hide' })).toHaveAttribute('aria-expanded', 'true')
    expect(within(bar).getByText('Sold')).toBeVisible()
  })
})
