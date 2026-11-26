import { useState } from 'react'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../test/renderWithProviders'
import { CashCountField } from './CashCountField'
import { EMPTY_COUNTS, countsTotal, countsTotalText, stepCount, toDenominations } from './denominations'
import type { DenominationCounts } from './denominations'

function CountedCash({ startCounting = true }: { startCounting?: boolean }) {
  const [amount, setAmount] = useState('')
  const [counts, setCounts] = useState<DenominationCounts | null>(startCounting ? EMPTY_COUNTS : null)
  return (
    <>
      <CashCountField label="Cash in bag" amount={amount} onAmountChange={setAmount} counts={counts} onCountsChange={setCounts} />
      <output aria-label="Amount sent">{amount}</output>
    </>
  )
}

describe('denominations', () => {
  it('adds each note by its value and the coins in rupees', () => {
    const counts = { ...EMPTY_COUNTS, notes500: '3', notes200: '2', notes10: '7', coins: '12.5' }
    expect(countsTotal(counts)).toBe(1982.5)
    expect(toDenominations(counts)).toEqual({ notes500: 3, notes200: 2, notes100: 0, notes50: 0, notes20: 0, notes10: 7, coins: 12.5 })
  })

  it('ignores a count that is not a whole number of notes', () => {
    expect(countsTotal({ ...EMPTY_COUNTS, notes100: '2.5', notes50: '-1' })).toBe(0)
  })

  it('keeps the amount empty until something is counted', () => {
    expect(countsTotalText(EMPTY_COUNTS)).toBe('')
    expect(countsTotalText({ ...EMPTY_COUNTS, coins: '0' })).toBe('0')
  })

  it('steps a count up and down without going below zero', () => {
    expect(stepCount('', 1)).toBe('1')
    expect(stepCount('1', -1)).toBe('')
    expect(stepCount('', -1)).toBe('')
  })
})

describe('DenominationCounter', () => {
  it('fills the amount from the notes and coins as they are counted', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CountedCash />)

    await user.type(screen.getByLabelText('Number of ₹500 notes'), '4')
    await user.click(screen.getByRole('button', { name: 'Add one ₹50 note' }))
    await user.click(screen.getByRole('button', { name: 'Add one ₹50 note' }))
    await user.click(screen.getByRole('button', { name: 'Remove one ₹50 note' }))
    await user.type(screen.getByLabelText('Coins in rupees'), '7')

    expect(screen.getByLabelText('Number of ₹50 notes')).toHaveValue(1)
    expect(screen.getByText('Total counted').nextSibling).toHaveTextContent('₹2,057.00')
    expect(screen.getByLabelText('Amount sent')).toHaveTextContent('2057')
  })

  it('cannot remove a note that was not counted', () => {
    renderWithProviders(<CountedCash />)
    expect(screen.getByRole('button', { name: 'Remove one ₹200 note' })).toBeDisabled()
  })

  it('moves to the next count with Enter, so a phone keypad can go down the notes', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CountedCash />)
    await user.type(screen.getByLabelText('Number of ₹500 notes'), '2{Enter}')
    expect(screen.getByLabelText('Number of ₹200 notes')).toHaveFocus()
    await user.keyboard('5')
    expect(screen.getByLabelText('Amount sent')).toHaveTextContent('2000')
  })

  it('falls back to typing the total, and can go back to counting', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CountedCash />)

    await user.click(screen.getByRole('button', { name: 'Just type the total' }))
    await user.type(screen.getByLabelText('Cash in bag'), '1234.5')
    expect(screen.queryByLabelText('Number of ₹500 notes')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Amount sent')).toHaveTextContent('1234.5')

    await user.click(screen.getByRole('button', { name: 'Count by notes' }))
    expect(screen.getByLabelText('Number of ₹500 notes')).toHaveValue(null)
    expect(screen.getByLabelText('Amount sent')).toBeEmptyDOMElement()
  })

  it('starts as a plain amount when the caller has no count yet', () => {
    renderWithProviders(<CountedCash startCounting={false} />)
    expect(screen.getByLabelText('Cash in bag')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Count by notes' })).toBeInTheDocument()
  })
})
