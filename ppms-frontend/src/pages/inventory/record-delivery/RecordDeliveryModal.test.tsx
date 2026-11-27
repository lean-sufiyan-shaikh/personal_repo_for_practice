import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { inventoryApi } from '../../../api/inventoryApi'
import type { ReceiptNozzle, TankStock } from '../../../api/inventoryApi'
import { pumpApi } from '../../../api/pumpApi'
import type { PumpSummary } from '../../../api/pumpApi'
import { tankerApi } from '../../../api/tankerApi'
import { supplierApi } from '../../../api/supplierApi'
import type { Supplier } from '../../../api/supplierApi'
import { useToastStore } from '../../../store/toastStore'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { RecordDeliveryModal } from './RecordDeliveryModal'

vi.mock('../../../api/inventoryApi', () => ({ inventoryApi: { recordBatchDelivery: vi.fn(), getReceiptNozzles: vi.fn() } }))
vi.mock('../../../api/tankerApi', () => ({ tankerApi: { getTankers: vi.fn() } }))
vi.mock('../../../api/pumpApi', () => ({ pumpApi: { getMyPumps: vi.fn() } }))
vi.mock('../../../api/supplierApi', () => ({ supplierApi: { list: vi.fn() } }))

const tank: TankStock = {
  tankId: 1, tankIdentifier: 'T1', fuelType: 'PETROL', capacity: 10_000, currentStock: 1_000,
  stockPercentage: 10, lowStock: true, dipTolerance: 50, pumpId: 6,
}
const bigTank: TankStock = { ...tank, capacity: 30_000 }

function supplier(id: number, name: string, defaultSupplier = false): Supplier {
  return {
    id, pumpId: 6, name, contactName: null, phone: null, email: null, notes: null, active: true,
    openingBalance: 0, openingBalanceDate: null, defaultSupplier, createdAt: '2026-01-01T00:00:00Z',
  }
}

const nozzle: ReceiptNozzle = {
  nozzleId: 11, nozzleNumber: 1, duName: 'DU-1', tankId: 1, fuelType: 'PETROL', lastReading: 1000, maxMeterValue: 99_999_999.999,
}

function figure(label: string | RegExp) {
  return screen.getAllByText(label, { selector: 'dt' })[0].nextElementSibling as HTMLElement
}

async function enterReceiptCheck(user: ReturnType<typeof userEvent.setup>, dipBefore: string, dipAfter: string, nozzleAfter: string) {
  await user.type(screen.getByLabelText('Dip before unloading'), dipBefore)
  await user.type(screen.getByLabelText('Dip after unloading'), dipAfter)
  await user.type(await screen.findByLabelText('Nozzle #1 · DU-1 after'), nozzleAfter)
}

describe('RecordDeliveryModal', () => {
  beforeEach(() => {
    vi.mocked(tankerApi.getTankers).mockResolvedValue([])
    vi.mocked(supplierApi.list).mockResolvedValue([])
    vi.mocked(inventoryApi.recordBatchDelivery).mockResolvedValue({} as never)
    vi.mocked(inventoryApi.getReceiptNozzles).mockResolvedValue([nozzle])
    vi.mocked(pumpApi.getMyPumps).mockResolvedValue([
      { id: 6, receiptTolerancePercent: 0.2, densityTolerance: 3 } as PumpSummary,
    ])
    useToastStore.setState({ toasts: [] })
  })

  it('sends the receipt check with the delivery and confirms once it is recorded', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(
      <RecordDeliveryModal pumpId={6} tanks={[tank]} isOwnerOrAdmin={false} canSetBillTotal={false} onClose={onClose} />,
    )

    await user.type(screen.getByLabelText('Invoice / bill no.'), 'INV-1')
    await user.type(screen.getByLabelText('Invoice quantity'), '5000')
    expect(await screen.findByLabelText('Nozzle #1 · DU-1 before')).toHaveValue(1000)
    await enterReceiptCheck(user, '1000', '5990', '1010')
    await user.click(screen.getByRole('button', { name: 'Review' }))
    await user.click(await screen.findByRole('button', { name: 'Confirm delivery' }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(inventoryApi.recordBatchDelivery).toHaveBeenCalledWith(6, expect.objectContaining({
      items: [expect.objectContaining({
        tankId: 1,
        quantityDelivered: 5000,
        receiptCheck: {
          dipBeforeLitres: 1000, dipAfterLitres: 5990,
          nozzleReadings: [{ nozzleId: 11, readingBefore: 1000, readingAfter: 1010 }],
          observedDensity: null, invoiceDensity: null,
        },
      })],
    }))
    expect(useToastStore.getState().toasts).toEqual([
      expect.objectContaining({ message: 'Tanker delivery recorded', variant: 'success' }),
    ])
  })

  it('books the delivery to the default supplier unless another one, or none, is picked', async () => {
    vi.mocked(supplierApi.list).mockResolvedValue([supplier(4, 'IOCL'), supplier(5, 'BPCL', true)])
    const user = userEvent.setup()
    renderWithProviders(
      <RecordDeliveryModal pumpId={6} tanks={[tank]} isOwnerOrAdmin={false} canSetBillTotal={false} onClose={vi.fn()} />,
    )

    const picker = await screen.findByLabelText('Supplier')
    await waitFor(() => expect(picker).toHaveValue('5'))
    await user.selectOptions(picker, 'Not assigned')
    expect(picker).toHaveValue('')
    expect(screen.getByText(/so it shows in that supplier's balance/)).toBeInTheDocument()
  })

  it('preselects the only supplier and sends it with the delivery', async () => {
    vi.mocked(supplierApi.list).mockResolvedValue([supplier(4, 'IOCL')])
    const user = userEvent.setup()
    renderWithProviders(
      <RecordDeliveryModal pumpId={6} tanks={[tank]} isOwnerOrAdmin={false} canSetBillTotal={false} onClose={vi.fn()} />,
    )

    await waitFor(() => expect(screen.getByLabelText('Supplier')).toHaveValue('4'))
    await user.type(screen.getByLabelText('Invoice / bill no.'), 'INV-2')
    await user.type(screen.getByLabelText('Invoice quantity'), '5000')
    await enterReceiptCheck(user, '1000', '6000', '1000')
    await user.click(screen.getByRole('button', { name: 'Review' }))
    await user.click(await screen.findByRole('button', { name: 'Confirm delivery' }))

    await waitFor(() => expect(inventoryApi.recordBatchDelivery).toHaveBeenCalledWith(6, expect.objectContaining({ supplierId: 4 })))
  })

  it('lists the missing receipt-check readings under Still needed and keeps Review unavailable', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <RecordDeliveryModal pumpId={6} tanks={[tank]} isOwnerOrAdmin={false} canSetBillTotal={false} onClose={vi.fn()} />,
    )

    await user.type(screen.getByLabelText('Invoice quantity'), '5000')
    await screen.findByLabelText('Nozzle #1 · DU-1 after')

    const review = screen.getByRole('button', { name: 'Review' })
    expect(review).toBeDisabled()
    expect(review).toHaveAccessibleDescription(
      /Tank 1: dip before unloading\s*Tank 1: dip after unloading\s*Tank 1: nozzle readings after unloading/,
    )
    expect(review).not.toHaveAccessibleDescription(/nozzle readings before unloading/)

    await enterReceiptCheck(user, '1000', '5990', '1010')
    expect(review).toBeEnabled()
  })

  it('shows a shortage exactly at the tolerance as within tolerance, counting fuel sold while unloading', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <RecordDeliveryModal pumpId={6} tanks={[bigTank]} isOwnerOrAdmin={false} canSetBillTotal={false} onClose={vi.fn()} />,
    )

    await user.type(screen.getByLabelText('Invoice quantity'), '12000')
    await enterReceiptCheck(user, '2000', '13966', '1010')

    expect(screen.getByText('Received 11,976.00 L of 12,000.00 L invoiced')).toBeInTheDocument()
    expect(screen.getByText('Short 24.00 L (0.20%) — within tolerance')).toBeInTheDocument()
    expect(screen.getByText('Includes 10.00 L sold while unloading')).toBeInTheDocument()
  })

  it('says a claim will be raised when the shortage is beyond tolerance', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <RecordDeliveryModal pumpId={6} tanks={[bigTank]} isOwnerOrAdmin={false} canSetBillTotal={false} onClose={vi.fn()} />,
    )

    await user.type(screen.getByLabelText('Invoice quantity'), '12000')
    await enterReceiptCheck(user, '2000', '13950', '1010')

    expect(screen.getByText('Short 40.00 L (0.33%) — beyond the 0.20% tolerance, a claim will be raised')).toBeInTheDocument()
  })

  it('notes an over-delivery beyond tolerance without a claim', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <RecordDeliveryModal pumpId={6} tanks={[bigTank]} isOwnerOrAdmin={false} canSetBillTotal={false} onClose={vi.fn()} />,
    )

    await user.type(screen.getByLabelText('Invoice quantity'), '12000')
    await enterReceiptCheck(user, '2000', '14100', '1010')

    expect(screen.getByText('Received 12,110.00 L of 12,000.00 L invoiced')).toBeInTheDocument()
    expect(screen.getByText('Over 110.00 L (0.92%) — beyond the 0.20% tolerance, noted only, no claim')).toBeInTheDocument()
  })

  it('flags a density difference above the pump tolerance', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <RecordDeliveryModal pumpId={6} tanks={[tank]} isOwnerOrAdmin={false} canSetBillTotal={false} onClose={vi.fn()} />,
    )

    await user.type(screen.getByLabelText('Observed density'), '830.5')
    await user.type(screen.getByLabelText('Invoice density'), '826')
    expect(await screen.findByText(/Density difference \+4\.5 kg\/m³ · more than 3\.0 kg\/m³/)).toBeInTheDocument()

    await user.clear(screen.getByLabelText('Observed density'))
    await user.type(screen.getByLabelText('Observed density'), '828')
    expect(screen.getByText(/Density difference \+2\.0 kg\/m³ · within 3\.0 kg\/m³/)).toBeInTheDocument()
  })

  it('works out the amount, the stock after delivery and the other charges as figures are typed', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <RecordDeliveryModal pumpId={6} tanks={[tank]} isOwnerOrAdmin canSetBillTotal onClose={vi.fn()} />,
    )

    expect(figure('Amount (litres × rate)')).toHaveTextContent('—')
    await user.type(screen.getByLabelText('Invoice quantity'), '1000')
    await user.type(screen.getByLabelText('Cost per litre'), '90')

    expect(figure('Amount (litres × rate)')).toHaveTextContent('₹90,000.00')
    expect(figure('Stock after delivery')).toHaveTextContent('2,000.00 L')
    expect(figure('Total invoiced')).toHaveTextContent('1,000.00 L')
    expect(screen.getByLabelText('Bill amount')).toHaveValue(90000)

    await user.clear(screen.getByLabelText('Bill amount'))
    await user.type(screen.getByLabelText('Bill amount'), '91500')
    expect(figure(/Other charges/)).toHaveTextContent('+₹1,500.00')
  })

  it('keeps Review unavailable and says why while a tank would overflow', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <RecordDeliveryModal pumpId={6} tanks={[tank]} isOwnerOrAdmin={false} canSetBillTotal={false} onClose={vi.fn()} />,
    )

    await user.type(screen.getByLabelText('Invoice quantity'), '9500')

    expect(screen.getByLabelText('Invoice quantity')).toHaveAccessibleDescription('More than the free space of 9,000.00 L')
    const review = screen.getByRole('button', { name: 'Review' })
    expect(review).toBeDisabled()
    expect(review).toHaveAccessibleDescription(/Still needed\s*One or more tank quantities exceed available capacity/)
  })

  it('reviews the totals, tank lines and receipt result with the bill number in the header', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <RecordDeliveryModal pumpId={6} tanks={[tank]} isOwnerOrAdmin canSetBillTotal={false} onClose={vi.fn()} />,
    )

    await user.type(screen.getByLabelText('Invoice / bill no.'), 'INV-7')
    await user.type(screen.getByLabelText('Invoice quantity'), '1000')
    await user.type(screen.getByLabelText('Cost per litre'), '90')
    await enterReceiptCheck(user, '1000', '1980', '1010')
    await user.click(screen.getByRole('button', { name: 'Review' }))

    const review = await screen.findByRole('dialog', { name: 'Review tanker delivery' })
    expect(review).toHaveTextContent('Bill no. INV-7')
    expect(within(review).getByText('Total invoiced').nextElementSibling).toHaveTextContent('1,000.00 L')
    expect(within(review).getByText('Calculated amount').nextElementSibling).toHaveTextContent('₹90,000.00')
    expect(within(review).getByText('Received 990.00 L of 1,000.00 L invoiced')).toBeInTheDocument()
    expect(within(review).getByText(/Short 10\.00 L \(1\.00%\) — beyond the 0\.20% tolerance/)).toBeInTheDocument()

    await user.click(within(review).getByRole('button', { name: 'Edit' }))
    expect(screen.queryByRole('dialog', { name: 'Review tanker delivery' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Invoice quantity')).toHaveValue(1000)
  })
})
