import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { creditApi } from '../../api/creditApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import { CreditLimitSettingsForm } from './CreditLimitSettingsForm'

vi.mock('../../api/creditApi', () => ({
  creditApi: { getCreditLimitSettings: vi.fn(), updateCreditLimitSettings: vi.fn() },
}))

const settings = { defaultCreditLimit: 0, managerLimitCap: 50_000, nearLimitPercent: 90 }

describe('CreditLimitSettingsForm', () => {
  beforeEach(() => {
    vi.mocked(creditApi.getCreditLimitSettings).mockResolvedValue(settings)
    vi.mocked(creditApi.updateCreditLimitSettings).mockReset()
    vi.mocked(creditApi.updateCreditLimitSettings).mockResolvedValue(settings)
  })

  it('saves the limit for new clients, the manager cap and the near-limit share', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CreditLimitSettingsForm pumpId={3} />)

    const defaultLimit = await screen.findByLabelText('Limit for new clients')
    await user.clear(defaultLimit)
    await user.type(defaultLimit, '5000')
    await user.click(screen.getByRole('button', { name: 'Save credit limits' }))

    expect(creditApi.updateCreditLimitSettings).toHaveBeenCalledWith(3,
      { defaultCreditLimit: 5000, managerLimitCap: 50_000, nearLimitPercent: 90 })
  })

  it('refuses a near-limit share outside 50 to 100 without calling the server', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CreditLimitSettingsForm pumpId={3} />)

    const percent = await screen.findByLabelText('Near limit at')
    await user.clear(percent)
    await user.type(percent, '20')
    await user.click(screen.getByRole('button', { name: 'Save credit limits' }))

    expect(screen.getByText('Enter a number from 50 to 100.')).toBeInTheDocument()
    expect(creditApi.updateCreditLimitSettings).not.toHaveBeenCalled()
  })
})
