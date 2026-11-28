import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../test/renderWithProviders'
import { PhoneNumberField } from './PhoneNumberField'

function PhoneField() {
  const [digits, setDigits] = useState('')
  return <PhoneNumberField id="phone" value={digits} onChange={setDigits} />
}

describe('PhoneNumberField', () => {
  it('groups the digits 5 + 5 while typing and ticks a full number', async () => {
    const user = userEvent.setup()
    const { container } = renderWithProviders(<PhoneField />)
    const phone = screen.getByLabelText('Phone number')

    await user.type(phone, '98765')
    expect(phone).toHaveValue('98765')
    expect(container.querySelector('.ui-auth-phone__check')).toBeNull()

    await user.type(phone, '43210')
    expect(phone).toHaveValue('98765 43210')
    expect(container.querySelector('.ui-auth-phone__check')).not.toBeNull()
  })

  it('shows a pasted number with +91 as the grouped 10 digits', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PhoneField />)
    await user.click(screen.getByLabelText('Phone number'))
    await user.paste('+91 98765-43210')

    expect(screen.getByLabelText('Phone number')).toHaveValue('98765 43210')
  })

  it('says the +91 is already there to screen readers', () => {
    renderWithProviders(<PhoneField />)

    expect(screen.getByLabelText('Phone number')).toHaveAccessibleDescription('10-digit mobile number. The +91 country code is already added.')
  })
})
