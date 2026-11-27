import { screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { gstSummaryApi } from '../../../api/gstSummaryApi'
import type { GstSummary } from '../../../api/gstSummaryApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { toCsv } from '../../../utils/csv'
import { GstSummaryTab } from './GstSummaryTab'
import { GST_CSV_COLUMNS, gstCsvRows } from './gstSummaryCsv'

vi.mock('../../../api/gstSummaryApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/gstSummaryApi')>()),
  gstSummaryApi: { getSummary: vi.fn() },
}))

const bucket = (taxableValue: number, cgst: number, sgst: number, igst: number) => ({ taxableValue, cgst, sgst, igst })

const summary: GstSummary = {
  month: '2026-11', from: '2026-11-01', to: '2026-11-30', pumpGstin: '27PQRSX6789K1Z2',
  outwardTaxable: bucket(2000, 180, 180, 0),
  outwardFromInvoices: bucket(500, 45, 45, 0),
  outwardFromCounterSales: bucket(1500, 135, 135, 0),
  nonGstOutwardFuel: 450000,
  inputTax: bucket(1000, 0, 0, 180),
  netTax: bucket(2000, 180, 180, -180),
  counterSalesWithoutGst: 2,
  purchases: [{
    deliveryId: 41, invoiceDate: '2026-11-10', invoiceNumber: 'B-77', supplierName: 'Castrol Dist',
    supplierGstin: '29ABCDE1234F1Z5', productName: 'Engine Oil 1L', taxableValue: 1000, gstRatePercent: 18,
    cgst: 0, sgst: 0, igst: 180, total: 1180,
  }],
}

describe('GstSummaryTab', () => {
  beforeEach(() => {
    vi.mocked(gstSummaryApi.getSummary).mockResolvedValue(summary)
  })

  it('shows the accountant banner, the buckets, the net tax and the input register', async () => {
    renderWithProviders(<GstSummaryTab pumpId={3} />)

    expect(screen.getByRole('note')).toHaveTextContent('For your accountant')
    expect(await screen.findByText('Net tax payable')).toBeInTheDocument()
    const buckets = screen.getByRole('table', { name: 'GST for the month by return section' })
    expect(within(buckets).getByText('Non-GST outward supplies (petrol and diesel)')).toBeInTheDocument()
    expect(within(buckets).getByText('₹4,50,000.00')).toBeInTheDocument()
    expect(screen.getByText('2 counter sales this month were at 0% GST, so they show no tax.')).toBeInTheDocument()
    expect(screen.getByText('Castrol Dist')).toBeInTheDocument()
    expect(gstSummaryApi.getSummary).toHaveBeenCalledWith(3, expect.stringMatching(/^\d{4}-\d{2}$/))
  })

  it('exports the buckets and every purchase bill to CSV', () => {
    const csv = toCsv(GST_CSV_COLUMNS, gstCsvRows(summary))
    expect(csv).toContain('3.1(e),Non-GST outward supplies (petrol and diesel),,,,450000.00')
    expect(csv).toContain('4(A)(5),Input tax credit (counter-product purchases),,,,1000.00,,0.00,0.00,180.00')
    expect(csv).toContain('Input register,Castrol Dist – Engine Oil 1L,2026-11-10,B-77,29ABCDE1234F1Z5,1000.00,18,0.00,0.00,180.00,1180.00')
  })
})
