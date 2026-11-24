import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ancillaryApi } from '../../api/ancillaryApi'
import type { AncillaryProduct } from '../../api/ancillaryApi'
import { useAuthStore } from '../../store/authStore'
import type { UserRole } from '../../types/auth'
import { renderWithProviders } from '../../test/renderWithProviders'
import { ProductsTab } from './ProductsTab'

vi.mock('../../api/ancillaryApi', () => ({ ancillaryApi: { setProductStatus: vi.fn(), getPriceHistory: vi.fn(), setPrice: vi.fn() } }))

const product = {
  id: 1, name: 'Engine Oil 1L', displayName: 'Engine Oil 1L', category: 'LUBRICANT', unitType: 'LITRE', unitSize: 1, status: 'ACTIVE',
  currentStockUnits: 12, lowStockThreshold: null, fifoCostPricePerUnit: 310, currentSellingPricePerUnit: 450,
} as unknown as AncillaryProduct

function renderAs(role: UserRole, products: AncillaryProduct[] = [product]) {
  useAuthStore.setState({ user: { userId: 5, fullName: 'User', role, assignedPumpId: 1 }, isAuthenticated: true })
  const isOwnerOrAdmin = role === 'OWNER' || role === 'ADMIN'
  return renderWithProviders(
    <ProductsTab pumpId={1} products={products} loading={false} isOwnerOrAdmin={isOwnerOrAdmin}
      isManagerOrAbove={isOwnerOrAdmin || role === 'MANAGER'} isAddingProduct={false} onAddProductClosed={vi.fn()} onRefresh={vi.fn()} />,
  )
}

describe('ProductsTab by role', () => {
  beforeEach(() => vi.clearAllMocks())

  it('hides Sell and the buy price from an operator, whom the server refuses for sales', () => {
    renderAs('OPERATOR')
    expect(screen.queryByRole('button', { name: 'Sell' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Buy:/)).not.toBeInTheDocument()
  })

  it('shows the buy price to a manager', () => {
    renderAs('MANAGER')
    expect(screen.getByRole('button', { name: 'Sell' })).toBeInTheDocument()
    expect(screen.getByText(/Buy:/)).toBeInTheDocument()
  })

  it('asks before disabling a product, and disables it only on Yes', async () => {
    const user = userEvent.setup()
    vi.mocked(ancillaryApi.setProductStatus).mockResolvedValue(undefined as never)
    renderAs('OWNER')

    await user.click(screen.getByRole('button', { name: 'Disable product Engine Oil 1L' }))
    expect(screen.getByText('Disable this product?')).toBeInTheDocument()
    expect(ancillaryApi.setProductStatus).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Yes' }))
    await waitFor(() => expect(ancillaryApi.setProductStatus).toHaveBeenCalledWith(1, 1, 'INACTIVE'))
  })
})

describe('ProductsTab selling price', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows each product\'s selling price, and a No price warning where none is set', () => {
    renderAs('MANAGER', [product, { ...product, id: 2, displayName: 'Coolant 1L', currentSellingPricePerUnit: null }])

    expect(screen.getByText('₹450.00/unit')).toBeInTheDocument()
    expect(screen.getByText('No price')).toBeInTheDocument()
  })

  it('hides Set price from a manager, whom the server refuses', () => {
    renderAs('MANAGER')
    expect(screen.queryByRole('button', { name: 'Set selling price of Engine Oil 1L' })).not.toBeInTheDocument()
  })

  it('sets a new price from the Set price dialog, showing the change and the price history', async () => {
    vi.mocked(ancillaryApi.getPriceHistory).mockResolvedValue([
      { id: 2, productId: 1, pumpId: 1, pricePerUnit: 450, effectiveFrom: '2026-05-02T06:30:00Z', setByUserId: 5 },
      { id: 1, productId: 1, pumpId: 1, pricePerUnit: 400, effectiveFrom: '2026-04-01T06:30:00Z', setByUserId: 5 },
    ])
    vi.mocked(ancillaryApi.setPrice).mockResolvedValue(
      { id: 3, productId: 1, pumpId: 1, pricePerUnit: 475, effectiveFrom: '2026-06-01T06:30:00Z', setByUserId: 5 })
    const user = userEvent.setup()
    const { queryClient } = renderAs('OWNER')
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(screen.getByRole('button', { name: 'Set selling price of Engine Oil 1L' }))
    const dialog = await screen.findByRole('dialog', { name: 'Set selling price' })
    const history = await within(dialog).findByRole('region', { name: 'Price history' })
    expect(await within(history).findAllByRole('listitem')).toHaveLength(2)
    expect(within(history).getAllByRole('listitem').map(row => row.textContent)).toEqual([
      expect.stringContaining('₹450.00'),
      expect.stringContaining('₹400.00'),
    ])

    await user.type(within(dialog).getByLabelText('New price per unit'), '475')
    expect(within(dialog).getByText('Up ₹25.00 from ₹450.00')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Save price' }))

    await waitFor(() => expect(ancillaryApi.setPrice).toHaveBeenCalledWith(1, 1, { pricePerUnit: 475 }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Set selling price' })).not.toBeInTheDocument())
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['ancillaryProducts', 1] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['ancillary-price-history', 1, 1] })
  })

  it('calls the new price a first price when the product has none', async () => {
    vi.mocked(ancillaryApi.getPriceHistory).mockResolvedValue([])
    const user = userEvent.setup()
    renderAs('OWNER', [{ ...product, currentSellingPricePerUnit: null }])

    await user.click(screen.getByRole('button', { name: 'Set selling price of Engine Oil 1L' }))
    const dialog = await screen.findByRole('dialog', { name: 'Set selling price' })
    expect(within(dialog).getByText('Not set')).toBeInTheDocument()
    expect(await within(dialog).findByText('No price set yet.')).toBeInTheDocument()

    await user.type(within(dialog).getByLabelText('New price per unit'), '80')
    expect(within(dialog).getByText('First selling price for this product')).toBeInTheDocument()
  })
})
