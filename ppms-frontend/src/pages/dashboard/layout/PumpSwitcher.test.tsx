import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { PumpSummary } from '../../../api/pumpApi'
import { PumpSwitcher } from './PumpSwitcher'

const pump = (id: number, name: string, demo: boolean): PumpSummary => ({
  id, name, address: '', maxDuCount: 4, ownerId: 1, createdAt: '', discrepancyEscalationThreshold: null,
  expenseApprovalThreshold: null, dus: [], demo,
})

describe('PumpSwitcher', () => {
  it('marks the demo pump chip with a Demo badge', () => {
    render(<PumpSwitcher pumps={[pump(1, 'Highway Pump', false), pump(2, 'Demo Pump', true)]} />)

    expect(within(screen.getByRole('button', { name: 'Switch to Demo Pump' })).getByText('Demo')).toBeInTheDocument()
    expect(within(screen.getByRole('button', { name: 'Switch to Highway Pump' })).queryByText('Demo')).not.toBeInTheDocument()
  })
})
