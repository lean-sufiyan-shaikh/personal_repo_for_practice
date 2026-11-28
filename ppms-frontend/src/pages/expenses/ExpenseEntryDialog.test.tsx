import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/renderWithProviders'
import { ExpenseEntryDialog } from './ExpenseEntryDialog'

vi.mock('../../api/expenseApi', () => ({ expenseApi: { createExpense: vi.fn() } }))

describe('ExpenseEntryDialog', () => {
  it('keeps Review disabled and lists what is still needed until amount, description and source are in', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ExpenseEntryDialog pumpId={3} onClose={vi.fn()} />)

    const form = screen.getByRole('dialog', { name: 'Record expense' })
    const review = within(form).getByRole('button', { name: 'Review' })
    expect(review).toBeDisabled()
    expect(review).toHaveAccessibleDescription('Still needed Amount Description Paid from')

    await user.type(within(form).getByLabelText('Description'), 'Generator diesel')
    await user.type(within(form).getByLabelText('Amount'), '450')
    expect(review).toHaveAccessibleDescription('Still needed Paid from')
    await user.click(within(form).getByRole('button', { name: /^Paid from/ }))
    await user.click(screen.getByRole('option', { name: 'Bank' }))
    expect(review).toBeEnabled()
  })
})
