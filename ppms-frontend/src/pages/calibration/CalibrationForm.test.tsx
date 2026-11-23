import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { calibrationApi } from '../../api/calibrationApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import type { NozzleDetail } from '../../types/shift'
import { addDays, localDateInputValue } from '../../utils/date'
import { CalibrationForm } from './CalibrationForm'

vi.mock('../../api/calibrationApi', () => ({ calibrationApi: { recordCalibration: vi.fn() } }))

const nozzle: NozzleDetail = {
  id: 21, nozzleNumber: 1, fuelType: 'PETROL', lastReading: 1000, maxMeterValue: 99_999_999.999, tankId: 4, status: 'ACTIVE',
}

async function chooseNozzleAndTechnician() {
  const user = userEvent.setup()
  renderWithProviders(<CalibrationForm pumpId={6} nozzles={[nozzle]} onRecorded={vi.fn()} />)
  await user.click(screen.getByRole('button', { name: /Nozzle:/ }))
  await user.click(await screen.findByRole('option', { name: /Nozzle #1/ }))
  await user.type(screen.getByPlaceholderText('Technician or agency name'), 'Weights Dept')
  return user
}

describe('CalibrationForm', () => {
  beforeEach(() => {
    vi.mocked(calibrationApi.recordCalibration).mockResolvedValue({} as never)
  })

  it('refuses a calibration without a next due date', async () => {
    const user = await chooseNozzleAndTechnician()
    await user.type(screen.getByPlaceholderText('Inspection certificate number'), 'CERT-1')

    await user.click(screen.getByRole('button', { name: 'Review Calibration' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Next calibration due date is required')
    expect(calibrationApi.recordCalibration).not.toHaveBeenCalled()
  })

  it('refuses a calibration without a certificate reference', async () => {
    const user = await chooseNozzleAndTechnician()
    await user.type(screen.getByLabelText('Next Calibration Due *'), addDays(localDateInputValue(), 180))

    await user.click(screen.getByRole('button', { name: 'Review Calibration' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Certificate reference is required')
  })

  it('only offers next due dates after the calibration date', () => {
    renderWithProviders(<CalibrationForm pumpId={6} nozzles={[nozzle]} onRecorded={vi.fn()} />)

    expect(screen.getByLabelText('Next Calibration Due *')).toHaveAttribute('min', addDays(localDateInputValue(), 1))
  })
})
