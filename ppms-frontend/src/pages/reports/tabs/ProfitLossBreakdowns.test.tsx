import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { ProfitLossReport } from '../../../api/reportApi'
import { OperatingProfitSection, RevenueBreakdown } from './ProfitLossBreakdowns'

const report: ProfitLossReport = {
  pumpId: 6, from: '2026-01-01', to: '2026-01-31', totalShifts: 2, autoClosedShiftCount: 0,
  totalRevenue: 3000, totalCogs: 2700, grossProfit: 300,
  totalCashRevenue: 2000, totalUpiRevenue: 0, totalCardRevenue: 0, totalFleetCardRevenue: 1000, totalCreditRevenue: 0, totalCollected: 3000,
  approvedExpenses: 150, bankCharges: 21.24, payrollPaid: 100, chargebacks: 0, netOperatingProfit: 28.76,
  byFuelType: [], byDay: [],
}

describe('RevenueBreakdown', () => {
  it('lists fleet card in the payment mix with the total collected', () => {
    render(<RevenueBreakdown report={report} className="" />)

    expect(screen.getByText('Fleet Card').nextSibling).toHaveTextContent('₹1,000.00')
    expect(screen.getByText('Total collected').nextSibling).toHaveTextContent('₹3,000.00')
  })
})

describe('OperatingProfitSection', () => {
  it('takes expenses, bank charges and payroll off the gross profit', () => {
    render(<OperatingProfitSection report={report} />)

    const operating = screen.getByRole('region', { name: 'Operating' })
    expect(within(operating).getByText('Approved expenses').nextSibling).toHaveTextContent('−₹150.00')
    expect(within(operating).getByText('MDR / bank charges').nextSibling).toHaveTextContent('−₹21.24')
    expect(within(operating).getByText('Payroll paid').nextSibling).toHaveTextContent('−₹100.00')
    expect(within(operating).getByText('Net operating profit').nextSibling).toHaveTextContent('₹28.76')
  })
})
