import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/renderWithProviders'
import { ImportStatementCard } from './ImportStatementCard'

vi.mock('../../api/bankReconciliationApi', () => ({ bankReconciliationApi: { importStatement: vi.fn() } }))

describe('ImportStatementCard', () => {
  it('refuses a file that is not a CSV', async () => {
    const user = userEvent.setup({ applyAccept: false })
    renderWithProviders(<ImportStatementCard pumpId={3} onImported={vi.fn()} />)

    await user.upload(screen.getByLabelText('Statement file (.csv)'), new File(['x'], 'statement.pdf', { type: 'application/pdf' }))
    await user.type(screen.getByLabelText('Bank name'), 'HDFC')

    expect(screen.getByText('Choose a .csv file.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Import statement' })).toBeDisabled()
  })

  it('needs a bank name before importing', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ImportStatementCard pumpId={3} onImported={vi.fn()} />)

    await user.upload(screen.getByLabelText('Statement file (.csv)'), new File(['x'], 'HDFC.CSV', { type: 'text/csv' }))
    expect(screen.getByRole('button', { name: 'Import statement' })).toBeDisabled()
    await user.type(screen.getByLabelText('Bank name'), 'HDFC')
    expect(screen.getByRole('button', { name: 'Import statement' })).toBeEnabled()
  })
})
