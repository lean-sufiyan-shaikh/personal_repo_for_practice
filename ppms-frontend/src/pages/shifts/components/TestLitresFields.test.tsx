import { useState } from 'react'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { anyAboveTestLimit, parseTestLitres } from '../../../utils/testLitres'
import { TestLitresFields } from './TestLitresFields'

function Harness() {
  const [values, setValues] = useState<Record<number, string>>({})
  const [note, setNote] = useState('')
  const noteNeeded = anyAboveTestLimit(Object.values(values).map(parseTestLitres), 10)
  return (
    <TestLitresFields
      nozzles={[{ nozzleId: 1, label: 'Nozzle #1 · Petrol', meterLitres: 120, invalid: false }]}
      values={values}
      onChange={(nozzleId, value) => setValues((prev) => ({ ...prev, [nozzleId]: value }))}
      testVolume={5}
      maxTestLitres={10}
      noteNeeded={noteNeeded}
      note={note}
      onNoteChange={setNote}
    />
  )
}

describe('TestLitresFields', () => {
  it('fills the pump test measure and shows the litres sold', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Fill 5.00 L on each nozzle' }))
    expect(screen.getByLabelText('Nozzle #1 · Petrol – test litres')).toHaveValue(5)
    expect(screen.getByText('Meter 120.00 L, sold 115.00 L')).toBeInTheDocument()
    expect(screen.queryByLabelText(/Why more than/)).not.toBeInTheDocument()
  })

  it('asks for a note once a nozzle returns more than the pump allows', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    const input = screen.getByLabelText('Nozzle #1 · Petrol – test litres')
    await user.type(input, '12')
    expect(screen.getByLabelText('Why more than 10.00 L of test fuel on a nozzle?')).toBeInTheDocument()
  })
})
