import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/renderWithProviders'
import { LanguageSwitcher } from './LanguageSwitcher'

describe('LanguageSwitcher', () => {
  it('names each language in its own script, marked with its language', () => {
    renderWithProviders(<LanguageSwitcher value="en" onChange={vi.fn()} />)

    expect(screen.getByText('English')).toHaveAttribute('lang', 'en')
    expect(screen.getByText('हिन्दी')).toHaveAttribute('lang', 'hi')
    expect(screen.getByText('मराठी')).toHaveAttribute('lang', 'mr')
  })

  it('checks the current language and reports the one picked', async () => {
    const onChange = vi.fn()
    renderWithProviders(<LanguageSwitcher value="en" onChange={onChange} />)

    expect(screen.getByRole('radio', { name: 'English' })).toBeChecked()
    await userEvent.setup().click(screen.getByRole('radio', { name: 'मराठी' }))
    expect(onChange).toHaveBeenCalledWith('mr')
  })
})
