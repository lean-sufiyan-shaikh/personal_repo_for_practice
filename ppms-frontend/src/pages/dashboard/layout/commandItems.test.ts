import { describe, expect, it } from 'vitest'
import { commandItemsFor } from './commandItems'

const actionsFor = (role: Parameters<typeof commandItemsFor>[0]) =>
  commandItemsFor(role).filter(item => item.kind === 'action')

describe('command palette quick actions', () => {
  it('opens every task straight from the page address', () => {
    for (const item of actionsFor('OWNER')) {
      expect(new URL(item.path, 'http://ppms.test').searchParams.get('action')).toBeTruthy()
    }
  })

  it('gives the owner every task, including payroll and adding staff', () => {
    expect(actionsFor('OWNER').map(item => item.label)).toEqual([
      'Open shift', 'Record delivery', 'Record dip check', 'Record payment', 'Record settlement',
      'Add expense', 'Record cash entry', 'Sell a product', 'Generate balance sheet',
      'Generate payroll', 'Set fuel prices', 'Add staff member',
    ])
  })
})

describe('commandItemsFor', () => {
  it('offers an operator only their own pages and no manager tasks', () => {
    const labels = commandItemsFor('OPERATOR').map((item) => item.label)
    expect(labels).toEqual(expect.arrayContaining(['My Shift', 'Shifts', 'Expenses', 'Notifications']))
    expect(labels).not.toContain('Overview')
    expect(labels).not.toContain('Open shift')
    expect(labels).not.toContain('Record delivery')
  })

  it('offers a manager the forecourt, money and stock tasks they may do', () => {
    const actions = commandItemsFor('MANAGER').filter((item) => item.kind === 'action')
    expect(actions.map((item) => item.label)).toEqual([
      'Open shift', 'Record delivery', 'Record dip check', 'Record payment', 'Record settlement',
      'Add expense', 'Record cash entry', 'Sell a product', 'Generate balance sheet', 'Set fuel prices',
    ])
    expect(actions[0].path).toBe('/dashboard/shifts?action=open-shift')
  })

  it('keeps an accountant to the pages they can open', () => {
    const items = commandItemsFor('ACCOUNTANT')
    expect(items.filter((item) => item.kind === 'action')).toEqual([])
    expect(items.map((item) => item.label)).not.toContain('Payroll')
  })
})
