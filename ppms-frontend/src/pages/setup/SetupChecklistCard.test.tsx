import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { SetupChecklist } from '../../api/pumpApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import { SetupChecklistCard } from './SetupChecklistCard'

const checklist: SetupChecklist = {
  pumpId: 3, doneCount: 1, requiredCount: 2,
  items: [
    { step: 'DISPENSERS', done: true, optional: false, detail: '4 active nozzles' },
    { step: 'TANKS', done: false, optional: false, detail: '1 nozzle not linked to a tank' },
    { step: 'CREDIT_CLIENTS', done: false, optional: true, detail: 'Add customers who buy on credit, if any' },
  ],
}

describe('SetupChecklistCard', () => {
  it('shows progress and each step as done, to do or optional in words', () => {
    renderWithProviders(<SetupChecklistCard checklist={checklist} />)

    expect(screen.getByText('1 of 2 done')).toHaveClass('text-warn')
    expect(screen.getByRole('progressbar', { name: 'Setup progress' })).toHaveAttribute('aria-valuenow', '1')
    expect(screen.getByText('Done')).toHaveClass('text-ok')
    expect(screen.getByText('To do')).toHaveClass('text-warn')
    expect(screen.getByText('Optional')).toBeInTheDocument()
    expect(screen.getByText('1 nozzle not linked to a tank')).toBeInTheDocument()
  })

  it('links each step to where it is set up', () => {
    renderWithProviders(<SetupChecklistCard checklist={checklist} />)

    expect(screen.getByRole('link', { name: 'Review Dispensing units and nozzles' })).toHaveAttribute('href', '/dashboard/setup?section=nozzles')
    expect(screen.getByRole('link', { name: 'Set up Tanks, with every nozzle linked' })).toHaveAttribute('href', '/dashboard/setup?section=tanks')
    expect(screen.getByRole('link', { name: 'Set up Credit clients' })).toHaveAttribute('href', '/dashboard/credit')
  })

  it('turns green when every required step is done', () => {
    renderWithProviders(<SetupChecklistCard checklist={{ ...checklist, doneCount: 2 }} />)
    expect(screen.getByText('2 of 2 done')).toHaveClass('text-ok')
  })
})
