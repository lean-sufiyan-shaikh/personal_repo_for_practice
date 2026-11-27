import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { paymentSettlementApi } from '../../api/paymentSettlementApi'
import type { SettlementConfig } from '../../api/paymentSettlementApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import { SettlementConfigTab } from './SettlementConfigTab'

vi.mock('../../api/paymentSettlementApi', () => ({ paymentSettlementApi: { getConfigs: vi.fn(), upsertConfig: vi.fn() } }))

const configs: SettlementConfig[] = [
  { id: 1, paymentType: 'UPI', alertTime: '18:00', enabled: true, mdrPercent: 0, gstOnMdrPercent: 18 },
  { id: 2, paymentType: 'CARD', alertTime: '19:30', enabled: false, mdrPercent: 1.8, gstOnMdrPercent: 18 },
]

describe('SettlementConfigTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(paymentSettlementApi.getConfigs).mockResolvedValue(configs)
    vi.mocked(paymentSettlementApi.upsertConfig).mockResolvedValue(configs[1])
  })

  it('shows each type’s MDR and GST on MDR rates', async () => {
    renderWithProviders(<SettlementConfigTab pumpId={3} canConfigure />)
    expect(await screen.findByLabelText('Card MDR %')).toHaveValue(1.8)
    expect(screen.getByLabelText('Card GST on MDR %')).toHaveValue(18)
    expect(screen.getByLabelText('UPI MDR %')).toHaveValue(0)
  })

  it('saves a changed MDR with the type’s other settings kept', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SettlementConfigTab pumpId={3} canConfigure />)

    const mdr = await screen.findByLabelText('Card MDR %')
    await user.clear(mdr)
    await user.type(mdr, '2.1')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(paymentSettlementApi.upsertConfig).toHaveBeenCalledWith(3, 'CARD', {
      alertTime: '19:30', enabled: false, mdrPercent: 2.1, gstOnMdrPercent: 18,
    })
  })

  it('refuses an MDR above 10 % before saving', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SettlementConfigTab pumpId={3} canConfigure />)

    const mdr = await screen.findByLabelText('Card MDR %')
    await user.clear(mdr)
    await user.type(mdr, '12')

    expect(screen.getByText('Enter an MDR between 0 and 10 %.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })

  it('shows the rates read-only to someone who cannot configure them', async () => {
    renderWithProviders(<SettlementConfigTab pumpId={3} canConfigure={false} />)
    expect(await screen.findByLabelText('Card MDR %')).toBeDisabled()
    expect(screen.getByLabelText('Card GST on MDR %')).toBeDisabled()
  })
})
