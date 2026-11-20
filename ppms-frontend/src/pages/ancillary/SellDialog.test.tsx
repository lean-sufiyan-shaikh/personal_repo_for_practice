import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ancillaryApi } from '../../api/ancillaryApi'
import type { AncillaryProduct } from '../../api/ancillaryApi'
import { useAuthStore } from '../../store/authStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import { SellDialog } from './SellDialog'

vi.mock('../../api/ancillaryApi', () => ({ ancillaryApi: { getPriceHistory: vi.fn(), recordSale: vi.fn() } }))
vi.mock('../../api/creditApi', () => ({ creditApi: { getLedgerSummary: vi.fn().mockResolvedValue([]) } }))

function product(lots: AncillaryProduct['activeFifoLots']): AncillaryProduct {
  return {
    id: 8, pumpId: 3, name: 'Engine Oil', brand: null, variant: null, packageSize: 1, unitOfMeasure: 'LITRE',
    currentStockUnits: 7, lowStockThreshold: null, status: 'ACTIVE', fifoCostPricePerUnit: lots[0]?.costPricePerUnit ?? null,
    activeFifoLots: lots, displayName: 'Engine Oil 1L', createdAt: '', updatedAt: '',
  }
}

async function sell(lots: AncillaryProduct['activeFifoLots'], quantity: string) {
  const user = userEvent.setup()
  renderWithProviders(<SellDialog pumpId={3} product={product(lots)} onClose={vi.fn()} onSuccess={vi.fn()} />)
  await screen.findByDisplayValue('120')
  const qty = screen.getByLabelText('Quantity (units) *')
  await user.clear(qty)
  await user.type(qty, quantity)
}

describe('SellDialog projected profit', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: { userId: 1, fullName: 'Op', role: 'OPERATOR', assignedPumpId: 3 }, isAuthenticated: true })
    vi.mocked(ancillaryApi.getPriceHistory).mockResolvedValue([
      { id: 1, productId: 8, pumpId: 3, pricePerUnit: 120, effectiveFrom: '2026-01-01T00:00:00Z', setByUserId: 1 },
    ])
  })

  it('walks FIFO lots so a sale spanning two batches uses each batch cost', async () => {
    await sell([{ remainingQuantity: 5, costPricePerUnit: 100 }, { remainingQuantity: 2, costPricePerUnit: 200 }], '6')
    expect(screen.getAllByText(/Projected profit: \+₹20\.00/)[0]).toBeInTheDocument()
  })

  it('shows a projected loss when later batches cost more than the price', async () => {
    await sell([{ remainingQuantity: 1, costPricePerUnit: 100 }, { remainingQuantity: 5, costPricePerUnit: 200 }], '3')
    expect(screen.getAllByText(/Projected loss: ₹-140\.00/)[0]).toBeInTheDocument()
  })

  it('shows break-even when the price equals the cost', async () => {
    await sell([{ remainingQuantity: 5, costPricePerUnit: 120 }], '2')
    expect(screen.getAllByText(/Break-even/)[0]).toBeInTheDocument()
  })

  it('shows no projection when the lots cannot cover the quantity', async () => {
    await sell([{ remainingQuantity: 2, costPricePerUnit: 100 }], '3')
    expect(screen.queryByText(/Projected|Break-even/)).not.toBeInTheDocument()
  })

  it('shows no projection without lot data', async () => {
    await sell([], '1')
    expect(screen.queryByText(/Projected|Break-even/)).not.toBeInTheDocument()
  })
})
