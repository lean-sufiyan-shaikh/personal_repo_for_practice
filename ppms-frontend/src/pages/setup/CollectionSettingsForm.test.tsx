import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { collectionApi } from '../../api/collectionApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import { CollectionSettingsForm } from './CollectionSettingsForm'
import { parseStages, problemsOf } from './collectionSettings'

vi.mock('../../api/collectionApi', () => ({
  collectionApi: { getSettings: vi.fn(), updateSettings: vi.fn() },
}))

describe('collection settings rules', () => {
  it('reads reminder stages as distinct days from 1 to 365, ascending', () => {
    expect(parseStages('30, 7,15')).toEqual([7, 15, 30])
    expect(parseStages('')).toEqual([])
    expect(parseStages('7, 7')).toBeNull()
    expect(parseStages('0')).toBeNull()
    expect(parseStages('7.5')).toBeNull()
    expect(parseStages('1,2,3,4,5,6,7,8,9,10,11')).toBeNull()
  })

  it('needs a UPI ID like name@bank together with a payee name', () => {
    const base = { stages: '7', holdDays: '45', upiId: '', payeeName: '', paymentThreshold: '', backdatedDays: '7', groupInvoice: 'false' }
    expect(problemsOf(base)).toEqual({})
    expect(problemsOf({ ...base, upiId: 'ramfuels' }).upiId).toBe('settings.upiIdInvalid')
    expect(problemsOf({ ...base, upiId: 'ramfuels@okaxis' }).payeeName).toBe('settings.upiIncomplete')
    expect(problemsOf({ ...base, payeeName: 'Ram?pa=x', upiId: 'ram@okaxis' }).payeeName).toBe('settings.payeeNameInvalid')
    expect(problemsOf({ ...base, holdDays: '0' }).holdDays).toBe('settings.holdDaysInvalid')
  })

  it('takes an approval amount above zero in rupees and paise, and back-dated days from 0 to 365', () => {
    const base = { stages: '7', holdDays: '45', upiId: '', payeeName: '', paymentThreshold: '', backdatedDays: '0', groupInvoice: 'false' }
    expect(problemsOf(base)).toEqual({})
    expect(problemsOf({ ...base, paymentThreshold: '5000.50' })).toEqual({})
    expect(problemsOf({ ...base, paymentThreshold: '0' }).paymentThreshold).toBe('settings.paymentThresholdInvalid')
    expect(problemsOf({ ...base, paymentThreshold: '10.555' }).paymentThreshold).toBe('settings.paymentThresholdInvalid')
    expect(problemsOf({ ...base, backdatedDays: '366' }).backdatedDays).toBe('settings.backdatedDaysInvalid')
    expect(problemsOf({ ...base, backdatedDays: '' }).backdatedDays).toBe('settings.backdatedDaysInvalid')
  })
})

describe('CollectionSettingsForm', () => {
  beforeEach(() => {
    vi.mocked(collectionApi.getSettings).mockResolvedValue({
      reminderStageDays: [7, 15, 30], creditHoldDays: 45, upiId: null, upiPayeeName: null,
      creditPaymentApprovalThreshold: null, backdatedPaymentApprovalDays: 7,
    })
    vi.mocked(collectionApi.updateSettings).mockImplementation((_pumpId, settings) => Promise.resolve(settings))
  })

  it('saves the stages, the hold and the UPI details', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CollectionSettingsForm pumpId={3} />)

    const stages = await screen.findByLabelText('Reminder stages (days overdue)')
    expect(stages).toHaveValue('7, 15, 30')
    await user.clear(stages)
    await user.type(stages, '10, 20')
    await user.clear(screen.getByLabelText('Hold credit at (days overdue)'))
    await user.type(screen.getByLabelText('UPI ID'), 'ramfuels@okaxis')
    await user.type(screen.getByLabelText('Payee name'), 'Ram Fuels')
    await user.click(screen.getByRole('button', { name: 'Save collection settings' }))

    expect(collectionApi.updateSettings).toHaveBeenCalledWith(3, {
      reminderStageDays: [10, 20], creditHoldDays: null, upiId: 'ramfuels@okaxis', upiPayeeName: 'Ram Fuels',
      creditPaymentApprovalThreshold: null, backdatedPaymentApprovalDays: 7, groupInvoiceByDefault: false,
    })
  })

  it('saves the credit payment approval amount and the back-dated days', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CollectionSettingsForm pumpId={3} />)

    await user.type(await screen.findByLabelText('Approve automatically up to (₹)'), '5000')
    const days = screen.getByLabelText('Back-dated payments allowed (days)')
    expect(days).toHaveValue(7)
    await user.clear(days)
    await user.type(days, '3')
    await user.click(screen.getByRole('button', { name: 'Save collection settings' }))

    expect(collectionApi.updateSettings).toHaveBeenCalledWith(3, expect.objectContaining({
      creditPaymentApprovalThreshold: 5000, backdatedPaymentApprovalDays: 3,
    }))
  })

  it('saves invoicing a parent once for its whole group as the pump default', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CollectionSettingsForm pumpId={3} />)

    await user.click(await screen.findByRole('checkbox', { name: /One invoice for a parent and its sub-accounts/ }))
    await user.click(screen.getByRole('button', { name: 'Save collection settings' }))

    expect(collectionApi.updateSettings).toHaveBeenCalledWith(3, expect.objectContaining({ groupInvoiceByDefault: true }))
  })

  it('shows what is wrong instead of saving an invalid UPI ID', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CollectionSettingsForm pumpId={3} />)

    await user.type(await screen.findByLabelText('UPI ID'), 'ramfuels')
    await user.click(screen.getByRole('button', { name: 'Save collection settings' }))

    expect(screen.getByText('Enter a UPI ID like name@bank.')).toBeInTheDocument()
    expect(collectionApi.updateSettings).not.toHaveBeenCalled()
  })
})
