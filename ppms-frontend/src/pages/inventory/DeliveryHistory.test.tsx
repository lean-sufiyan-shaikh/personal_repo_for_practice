import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { inventoryApi } from '../../api/inventoryApi'
import type { TankerDelivery } from '../../api/inventoryApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import { DeliveryHistory } from './DeliveryHistory'

vi.mock('../../api/inventoryApi', () => ({
  inventoryApi: { getDeliveries: vi.fn(), getLatestInvoiceLines: vi.fn(), deleteLatestDelivery: vi.fn() },
}))

function delivery(id: number, invoiceReference: string, deliveryDate: string, createdAt: string): TankerDelivery {
  return {
    id, pumpId: 6, tankId: 1, tankIdentifier: 'T1', fuelType: 'PETROL', quantityDelivered: 100,
    costPricePerUnit: 90, totalCost: 9000, billTotal: null, tankerName: null, deliveryDate,
    invoiceReference, loggedByUserName: 'Owner', createdAt, checkStatus: 'NOT_CHECKED', densityFlagged: false,
    receiptCheck: null, shortageClaimId: null, shortageClaimStatus: null,
  }
}

const newestByDate = delivery(1, 'INV-TODAY', '2026-03-15T00:00:00+05:30', '2026-03-15T09:00:00+05:30')
const lastEntered = delivery(2, 'INV-BACKDATED', '2026-03-12T00:00:00+05:30', '2026-03-15T10:00:00+05:30')

describe('DeliveryHistory', () => {
  beforeEach(() => {
    vi.mocked(inventoryApi.getDeliveries).mockResolvedValue({
      content: [newestByDate, lastEntered], page: 0, pageSize: 10, totalElements: 2, totalPages: 1,
      hasNext: false, hasPrevious: false,
    })
    vi.mocked(inventoryApi.getLatestInvoiceLines).mockResolvedValue([lastEntered])
    vi.mocked(inventoryApi.deleteLatestDelivery).mockResolvedValue({} as never)
  })

  it('offers delete only on the most recently entered invoice and names it in the request', async () => {
    const user = userEvent.setup()
    renderWithProviders(<DeliveryHistory pumpId={6} tanks={[]} isOwnerOrAdmin canDeleteInvoice />)

    const deleteButtons = await screen.findAllByRole('button', { name: 'Delete this invoice' })
    expect(deleteButtons).toHaveLength(1)
    await user.click(deleteButtons[0])
    await user.click(screen.getByRole('button', { name: 'Delete invoice' }))

    expect(inventoryApi.deleteLatestDelivery).toHaveBeenCalledWith(6, 'INV-BACKDATED')
  })

  it("shows an invoice's tank lines in a side panel and closes it again", async () => {
    const user = userEvent.setup()
    renderWithProviders(<DeliveryHistory pumpId={6} tanks={[]} isOwnerOrAdmin canDeleteInvoice />)

    expect(screen.getByRole('heading', { name: 'Tanker Delivery History' })).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: /INV-TODAY/ }))

    const panel = screen.getByRole('dialog', { name: 'Invoice INV-TODAY' })
    expect(within(panel).getByText('T1')).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Edit delivery' })).toBeInTheDocument()

    await user.click(within(panel).getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('shows each invoice\'s receipt-check result, with the most serious line and any density flag on the row', async () => {
    const user = userEvent.setup()
    const checked = (id: number, tankIdentifier: string, checkStatus: TankerDelivery['checkStatus'], receivedLitres: number) => ({
      ...delivery(id, 'INV-CHECKED', '2026-03-16T00:00:00+05:30', '2026-03-16T09:00:00+05:30'),
      tankIdentifier, checkStatus, densityFlagged: checkStatus === 'SHORT',
      receiptCheck: {
        dipBeforeLitres: 500, dipAfterLitres: 500 + receivedLitres, soldBetweenLitres: 0, receivedLitres,
        varianceLitres: receivedLitres - 100, variancePercent: receivedLitres - 100, tolerancePercent: 0.2,
        observedDensity: null, invoiceDensity: null, densityDifference: null, densityTolerance: 3,
      },
    })
    vi.mocked(inventoryApi.getDeliveries).mockResolvedValue({
      content: [checked(3, 'T1', 'OK', 100), checked(4, 'T2', 'SHORT', 95), newestByDate], page: 0, pageSize: 10,
      totalElements: 3, totalPages: 1, hasNext: false, hasPrevious: false,
    })
    renderWithProviders(<DeliveryHistory pumpId={6} tanks={[]} isOwnerOrAdmin canDeleteInvoice />)

    const checkedRow = await screen.findByRole('button', { name: /INV-CHECKED/ })
    expect(within(checkedRow).getByText('Short')).toBeInTheDocument()
    expect(within(checkedRow).getByText('Density flag')).toBeInTheDocument()
    expect(within(screen.getByRole('button', { name: /INV-TODAY/ })).getByText('Not checked')).toBeInTheDocument()

    await user.click(checkedRow)
    const panel = screen.getByRole('dialog', { name: 'Invoice INV-CHECKED' })
    expect(within(panel).getByText('Checked OK')).toBeInTheDocument()
    expect(within(panel).getByText('Received 95.00 L of 100.00 L')).toBeInTheDocument()
  })
})
