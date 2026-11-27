import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ancillaryApi } from '../../api/ancillaryApi'
import type { AncillaryProduct } from '../../api/ancillaryApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import { StockInDialog } from './StockInDialog'
import { BackfillSaleModal } from './BackfillSaleModal'

vi.mock('../../api/ancillaryApi', () => ({ ancillaryApi: { recordDelivery: vi.fn(), backfillSale: vi.fn() } }))
vi.mock('../../api/pumpApi', () => ({ pumpApi: {
  getCreditClients: vi.fn().mockResolvedValue([]),
  getMyPumps: vi.fn().mockResolvedValue([{ id: 3, gstin: '27PQRSX6789K1Z2' }]),
} }))

const oil = {
  id: 8, pumpId: 3, name: 'Engine Oil', brand: null, variant: null, packageSize: 1, unitOfMeasure: 'LITRE',
  currentStockUnits: 7, lowStockThreshold: null, status: 'ACTIVE', fifoCostPricePerUnit: 100,
  activeFifoLots: [], currentSellingPricePerUnit: 150, soldTodayUnits: 0, soldTodayAmount: 0, displayName: 'Engine Oil 1L', createdAt: '', updatedAt: '',
} as AncillaryProduct

function figure(label: string | RegExp) {
  return screen.getByText(label).closest('div')?.querySelector('dd')
}

describe('StockInDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(ancillaryApi.recordDelivery).mockResolvedValue({} as Awaited<ReturnType<typeof ancillaryApi.recordDelivery>>)
  })

  it('works out the total cost and the stock after delivery as quantity and cost are typed', async () => {
    const user = userEvent.setup()
    renderWithProviders(<StockInDialog pumpId={3} products={[oil]} onClose={vi.fn()} onSuccess={vi.fn()} />)

    expect(figure(/^Total cost/)).toHaveTextContent('—')
    const qty = screen.getByLabelText('Quantity')
    await user.clear(qty)
    await user.type(qty, '12')
    await user.type(screen.getByLabelText('Cost price per unit'), '250')

    expect(screen.getByText('12 × ₹250.00')).toBeInTheDocument()
    expect(figure(/^Total cost/)).toHaveTextContent('₹3,000.00')
    expect(figure('Stock after delivery')).toHaveTextContent('19 units')
  })

  it('keeps Review disabled until the cost price is entered, then records the delivery from the review step', async () => {
    const user = userEvent.setup()
    renderWithProviders(<StockInDialog pumpId={3} products={[oil]} onClose={vi.fn()} onSuccess={vi.fn()} />)

    const review = screen.getByRole('button', { name: 'Review' })
    expect(review).toBeDisabled()
    expect(review).toHaveAccessibleDescription('Still needed Cost price')

    await user.type(screen.getByLabelText('Cost price per unit'), '250')
    await user.click(review)
    expect(await screen.findByRole('dialog', { name: 'Review stock delivery' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add stock' }))
    expect(ancillaryApi.recordDelivery).toHaveBeenCalledWith(3, 8, expect.objectContaining({ quantityUnits: 1, costPricePerUnit: 250 }))
  })
})

describe('StockInDialog GST bill', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(ancillaryApi.recordDelivery).mockResolvedValue({} as Awaited<ReturnType<typeof ancillaryApi.recordDelivery>>)
  })

  it('splits the GST by the supplier state, checks the GSTIN and sends the bill with the delivery', async () => {
    const user = userEvent.setup()
    renderWithProviders(<StockInDialog pumpId={3} products={[oil]} onClose={vi.fn()} onSuccess={vi.fn()} />)

    const qty = screen.getByLabelText('Quantity')
    await user.clear(qty)
    await user.type(qty, '10')
    await user.type(screen.getByLabelText('Cost price per unit'), '100')
    await user.click(screen.getByRole('checkbox', { name: /This bill shows GST/ }))
    await user.type(screen.getByLabelText('Supplier name'), 'Castrol Dist')
    const gstin = screen.getByLabelText('Supplier GSTIN')
    await user.type(gstin, '27ABCDE')
    expect(screen.getByText('GSTIN must be 15 characters in the format 27ABCDE1234F1Z5.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Review' })).toBeDisabled()

    await user.type(gstin, '1234F1Z5')
    expect(await screen.findByText(/same state as the pump/)).toBeInTheDocument()
    expect(figure('CGST')).toHaveTextContent('₹90.00')
    expect(figure('SGST')).toHaveTextContent('₹90.00')
    await user.clear(gstin)
    await user.type(gstin, '29ABCDE1234F1Z5')
    expect(figure('IGST')).toHaveTextContent('₹180.00')

    await user.type(screen.getByLabelText(/Invoice reference/), 'B-77')
    await user.click(screen.getByRole('button', { name: 'Review' }))
    await user.click(await screen.findByRole('button', { name: 'Add stock' }))
    expect(ancillaryApi.recordDelivery).toHaveBeenCalledWith(3, 8, expect.objectContaining({
      invoiceReference: 'B-77',
      gst: expect.objectContaining({ supplierName: 'Castrol Dist', supplierGstin: '29ABCDE1234F1Z5', taxableValue: 1000, gstRatePercent: 18 }),
    }))
  })
})

describe('BackfillSaleModal', () => {
  it('shows the stock left after the sale and asks for a credit client before review', async () => {
    const user = userEvent.setup()
    renderWithProviders(<BackfillSaleModal pumpId={3} products={[oil]} onClose={vi.fn()} onSuccess={vi.fn()} />)

    const qty = screen.getByLabelText('Quantity')
    await user.clear(qty)
    await user.type(qty, '3')
    expect(figure('Stock after sale')).toHaveTextContent('4 units')

    await user.click(screen.getByRole('button', { name: /Payment mode/ }))
    await user.click(await screen.findByRole('option', { name: 'Credit' }))
    expect(screen.getByRole('button', { name: 'Review' })).toHaveAccessibleDescription('Still needed Credit client')
  })
})
