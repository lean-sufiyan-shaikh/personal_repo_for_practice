import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { inventoryApi } from '../../../api/inventoryApi'
import type { ShortageClaim } from '../../../api/inventoryApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { localDateInputValue } from '../../../utils/date'
import { ClaimsTab } from './ClaimsTab'

vi.mock('../../../api/inventoryApi', () => ({
  inventoryApi: {
    getShortageClaims: vi.fn(), getShortageClaim: vi.fn(), settleShortageClaim: vi.fn(), rejectShortageClaim: vi.fn(),
  },
}))

const claim: ShortageClaim = {
  id: 7, deliveryId: 40, tankId: 1, tankIdentifier: 'T1', fuelType: 'DIESEL', invoiceReference: 'INV-88',
  deliveryDate: '2026-11-20T00:00:00+05:30', tankerName: 'MH12 Tanker', invoiceLitres: 12_000, receivedLitres: 11_950,
  shortLitres: 50, shortPercent: 0.42, tolerancePercent: 0.2, pricePerLitre: 88.5, claimAmount: 4_425,
  dipBeforeLitres: 2_000, dipAfterLitres: 13_940, soldBetweenLitres: 10, observedDensity: 831.4, invoiceDensity: 826.9,
  densityDifference: 4.5, densityTolerance: 3, densityFlagged: true, status: 'RAISED',
  raisedAt: '2026-11-20T10:00:00+05:30', raisedByUserName: 'Manager Ravi', creditNoteAmount: null, creditNoteReference: null,
  creditNoteDate: null, rejectionReason: null, resolvedAt: null, resolvedByUserName: null, nozzleReadings: null, pump: null,
}

const detailed: ShortageClaim = {
  ...claim,
  nozzleReadings: [{ nozzleId: 3, nozzleNumber: 1, duName: 'DU-1', readingBefore: 1000, readingAfter: 1010, litresSold: 10 }],
  pump: { name: 'Shree Fuels', address: 'Pune Road', gstin: '27ABCDE1234F1Z5', legalName: null },
}

function listOf(claims: ShortageClaim[]) {
  return {
    claims: { content: claims, page: 0, pageSize: 10, totalElements: claims.length, totalPages: 1, hasNext: false, hasPrevious: false },
    totals: { openCount: 2, openAmount: 6_200, settledCount: 1, settledAmount: 1_500, rejectedCount: 1 },
  }
}

describe('ClaimsTab', () => {
  beforeEach(() => {
    vi.mocked(inventoryApi.getShortageClaims).mockReset().mockResolvedValue(listOf([claim]))
    vi.mocked(inventoryApi.getShortageClaim).mockResolvedValue(detailed)
    vi.mocked(inventoryApi.settleShortageClaim).mockResolvedValue({ ...claim, status: 'SETTLED' })
    vi.mocked(inventoryApi.rejectShortageClaim).mockResolvedValue({ ...claim, status: 'REJECTED' })
  })

  it('shows the open and settled totals and filters the list by status', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ClaimsTab pumpId={6} canResolve />)

    expect((await screen.findByText('2 open claims')).nextElementSibling).toHaveTextContent('₹6,200.00')
    expect(screen.getByText('1 settled · credited').nextElementSibling).toHaveTextContent('₹1,500.00')
    expect(screen.getByText('INV-88')).toBeInTheDocument()
    expect(inventoryApi.getShortageClaims).toHaveBeenCalledWith(6, undefined, 0, 10)

    await user.click(screen.getByRole('button', { name: 'Filter by status: All statuses' }))
    await user.click(screen.getByRole('option', { name: 'Settled' }))
    await waitFor(() => expect(inventoryApi.getShortageClaims).toHaveBeenLastCalledWith(6, 'SETTLED', 0, 10))
  })

  it('opens the printable claim note with every figure, the pump details and signature lines', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ClaimsTab pumpId={6} canResolve />)

    await user.click(await screen.findByRole('button', { name: 'View claim for INV-88 · T1' }))
    const panel = await screen.findByRole('dialog', { name: 'Shortage claim #7' })
    await within(panel).findByText('Shree Fuels')
    expect(panel).toHaveTextContent('GSTIN 27ABCDE1234F1Z5')
    expect(panel).toHaveTextContent('Delivery shortage claim note #7')
    expect(within(panel).getByText('Invoiced').nextElementSibling).toHaveTextContent('12,000.00 L')
    expect(within(panel).getByText('Received').nextElementSibling).toHaveTextContent('11,950.00 L')
    expect(within(panel).getByText('Short (0.42%, tolerance 0.20%)').nextElementSibling).toHaveTextContent('50.00 L')
    expect(within(panel).getByText('Claim amount').nextElementSibling).toHaveTextContent('₹4,425.00')
    expect(within(panel).getByText('Observed').nextElementSibling).toHaveTextContent('831.4 kg/m³')
    expect(panel).toHaveTextContent('+4.5 kg/m³ · Density flag')
    expect(within(panel).getByRole('table', { name: 'Nozzle readings between the dips' })).toHaveTextContent('1,010.000')
    expect(panel).toHaveTextContent('Supplier / tanker driver')
    expect(within(panel).getByRole('button', { name: 'Print claim note' })).toBeInTheDocument()
  })

  it('settles a raised claim with the credit note', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ClaimsTab pumpId={6} canResolve />)

    await user.click(await screen.findByRole('button', { name: 'View claim for INV-88 · T1' }))
    await user.click(await screen.findByRole('button', { name: 'Settle' }))
    const dialog = screen.getByRole('dialog', { name: 'Settle claim' })
    const confirm = within(dialog).getByRole('button', { name: 'Settle claim' })
    expect(confirm).toBeDisabled()
    expect(confirm).toHaveAccessibleDescription(/The credit note number/)

    await user.clear(within(dialog).getByLabelText('Credit note amount'))
    await user.type(within(dialog).getByLabelText('Credit note amount'), '4000')
    await user.type(within(dialog).getByLabelText('Credit note number'), 'CN-12')
    await user.click(confirm)

    await waitFor(() => expect(inventoryApi.settleShortageClaim).toHaveBeenCalledWith(6, 7, {
      creditNoteAmount: 4000, creditNoteReference: 'CN-12', creditNoteDate: localDateInputValue(),
    }))
  })

  it('rejects a raised claim only with a reason', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ClaimsTab pumpId={6} canResolve />)

    await user.click(await screen.findByRole('button', { name: 'View claim for INV-88 · T1' }))
    await user.click(await screen.findByRole('button', { name: 'Reject' }))
    const dialog = screen.getByRole('dialog', { name: 'Reject claim' })
    const confirm = within(dialog).getByRole('button', { name: 'Reject claim' })
    expect(confirm).toBeDisabled()

    await user.type(within(dialog).getByLabelText('Reason'), 'Supplier re-dipped, quantity was correct')
    await user.click(confirm)

    await waitFor(() => expect(inventoryApi.rejectShortageClaim).toHaveBeenCalledWith(6, 7, 'Supplier re-dipped, quantity was correct'))
  })

  it('lets a manager read a claim but not settle or reject it', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ClaimsTab pumpId={6} canResolve={false} />)

    await user.click(await screen.findByRole('button', { name: 'View claim for INV-88 · T1' }))
    const panel = await screen.findByRole('dialog', { name: 'Shortage claim #7' })
    await within(panel).findByText('Shree Fuels')
    expect(within(panel).queryByRole('button', { name: 'Settle' })).not.toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
  })
})
