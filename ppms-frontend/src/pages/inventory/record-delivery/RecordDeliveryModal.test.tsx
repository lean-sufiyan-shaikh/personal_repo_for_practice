import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { inventoryApi } from '../../../api/inventoryApi'
import type { TankStock } from '../../../api/inventoryApi'
import { tankerApi } from '../../../api/tankerApi'
import { useToastStore } from '../../../store/toastStore'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { RecordDeliveryModal } from './RecordDeliveryModal'

vi.mock('../../../api/inventoryApi', () => ({ inventoryApi: { recordBatchDelivery: vi.fn() } }))
vi.mock('../../../api/tankerApi', () => ({ tankerApi: { getTankers: vi.fn() } }))

const tank: TankStock = {
  tankId: 1, tankIdentifier: 'T1', fuelType: 'PETROL', capacity: 10_000, currentStock: 1_000,
  stockPercentage: 10, lowStock: true, dipTolerance: 50, pumpId: 6,
}

describe('RecordDeliveryModal', () => {
  beforeEach(() => {
    vi.mocked(tankerApi.getTankers).mockResolvedValue([])
    vi.mocked(inventoryApi.recordBatchDelivery).mockResolvedValue({} as never)
    useToastStore.setState({ toasts: [] })
  })

  it('confirms with a message once the tanker delivery is recorded', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(
      <RecordDeliveryModal pumpId={6} tanks={[tank]} isOwnerOrAdmin={false} canSetBillTotal={false} onClose={onClose} />,
    )

    await user.type(screen.getByLabelText(/Invoice \/ Bill No\./), 'INV-1')
    await user.type(screen.getByLabelText(/Quantity \(Litres\)/), '5000')
    await user.click(screen.getByRole('button', { name: 'Review Delivery' }))
    await user.click(await screen.findByRole('button', { name: 'Confirm Delivery' }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(useToastStore.getState().toasts).toEqual([
      expect.objectContaining({ message: 'Tanker delivery recorded', variant: 'success' }),
    ])
  })
})
