import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { CreditLimitFigures } from './CreditLimitFigures'

const client = { outstandingBalance: 12_000, creditLimit: 50_000, unlimited: false }

function figure(label: string) {
  return within(screen.getByLabelText('Effect of the new limit')).getByText(label).nextSibling
}

describe('CreditLimitFigures', () => {
  it('shows the credit left under the new limit', () => {
    render(<CreditLimitFigures client={client} unlimited={false} newLimit={50_000} />)

    expect(figure('Current limit')).toHaveTextContent('₹50,000.00')
    expect(figure('Credit left')).toHaveTextContent('₹38,000.00')
  })

  it('shows how far the balance is over a lower new limit', () => {
    render(<CreditLimitFigures client={client} unlimited={false} newLimit={10_000} />)

    expect(figure('Over the new limit by')).toHaveTextContent('₹2,000.00')
  })

  it('reads a new limit of 0 as no credit allowed', () => {
    render(<CreditLimitFigures client={client} unlimited={false} newLimit={0} />)

    expect(figure('New limit')).toHaveTextContent('₹0.00')
    expect(figure('Over the new limit by')).toHaveTextContent('₹12,000.00')
  })

  it('says there is no limit for unlimited credit', () => {
    render(<CreditLimitFigures client={{ ...client, unlimited: true }} unlimited newLimit={null} />)

    expect(figure('Current limit')).toHaveTextContent('No limit')
    expect(figure('New limit')).toHaveTextContent('No limit')
    expect(figure('Credit left')).toHaveTextContent('No limit')
  })
})
