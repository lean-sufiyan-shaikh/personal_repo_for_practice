import { describe, expect, it } from 'vitest'
import i18n from '../../i18n'
import { onboardSchema } from './superAdminSchemas'

const schema = onboardSchema(i18n.getFixedT('en', 'superadmin'))

const filled = {
  fullName: 'Lakshmi Rao', phoneNumber: '9876500012', password: 'Start@2026', pumpName: 'Rao Fuels', pumpAddress: 'Pune', maxDuCount: '4',
}

function problems(values: Record<string, string>): string[] {
  const result = schema.safeParse(values)
  return result.success ? [] : result.error.issues.map((issue) => issue.message)
}

describe('onboardSchema', () => {
  it('accepts a filled form and reads the DU count as a number', () => {
    expect(schema.parse(filled).maxDuCount).toBe(4)
  })

  it('names every missing field of an empty form', () => {
    expect(problems({ ...filled, fullName: '', phoneNumber: '', pumpName: '' })).toEqual(expect.arrayContaining([
      'Full name is required', 'Phone number must be exactly 10 digits', 'Pump name is required',
    ]))
  })

  it('needs a password with 8 characters, an upper and lower case letter and a digit', () => {
    expect(problems({ ...filled, password: 'Start@ab' })).toHaveLength(1)
    expect(problems({ ...filled, password: 'start@2026' })).toHaveLength(1)
    expect(problems({ ...filled, password: 'Sa1' })).toHaveLength(1)
  })

  it('allows between 1 and 20 DUs', () => {
    expect(problems({ ...filled, maxDuCount: '0' })).toHaveLength(1)
    expect(problems({ ...filled, maxDuCount: '21' })).toHaveLength(1)
    expect(problems({ ...filled, maxDuCount: '20' })).toEqual([])
  })
})
