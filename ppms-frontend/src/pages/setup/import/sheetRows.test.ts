import { describe, expect, it } from 'vitest'
import { IMPORT_TEMPLATES } from './importTemplates'
import { cellText, rowsFromSheet } from './sheetRows'

const tankStock = IMPORT_TEMPLATES['tank-stock']

describe('rowsFromSheet', () => {
  it('maps cells to fields by heading, in any order, keeping the Excel row number', () => {
    const result = rowsFromSheet([
      ['Cost per litre', 'TANK', 'litres', 'Remarks'],
      [96.8, 'Tank 1', 9000, 'ignored'],
      [null, null, null, null],
      [84.1, 'Tank 2', '12,000', ''],
    ], tankStock)

    expect(result).toEqual({
      rows: [
        { rowNumber: 2, tank: 'Tank 1', litres: '9000', costPerLitre: '96.8' },
        { rowNumber: 4, tank: 'Tank 2', litres: '12,000', costPerLitre: '84.1' },
      ],
    })
  })

  it('skips the template example row left unchanged', () => {
    const headers = tankStock.columns.map((column) => column.header)
    const example = tankStock.columns.map((column) => column.example)

    expect(rowsFromSheet([headers, example], tankStock)).toEqual({ problem: { code: 'noRows' } })
    expect(rowsFromSheet([headers, example, ['Tank 3', '100', '90']], tankStock))
      .toEqual({ rows: [{ rowNumber: 3, tank: 'Tank 3', litres: '100', costPerLitre: '90' }] })
  })

  it('names the required headings the file is missing', () => {
    expect(rowsFromSheet([['tank', 'volume'], ['T1', 5]], tankStock))
      .toEqual({ problem: { code: 'missingColumns', columns: ['litres', 'cost_per_litre'] } })
  })

  it('refuses a file with more rows than the template allows', () => {
    const staff = IMPORT_TEMPLATES.staff
    const rows = Array.from({ length: staff.maxRows + 1 }, (_, index) => [`Person ${index}`, '9000000000', 'OPERATOR', 'MALE'])

    expect(rowsFromSheet([['full_name', 'phone', 'role', 'gender'], ...rows], staff))
      .toEqual({ problem: { code: 'tooManyRows', maxRows: staff.maxRows } })
  })

  it('reports an empty sheet', () => {
    expect(rowsFromSheet([], tankStock)).toEqual({ problem: { code: 'noRows' } })
  })
})

describe('cellText', () => {
  it('writes dates as YYYY-MM-DD and ticks as yes or no', () => {
    expect(cellText(new Date(Date.UTC(2026, 3, 1)))).toBe('2026-04-01')
    expect(cellText(true)).toBe('yes')
    expect(cellText(false)).toBe('no')
    expect(cellText(9876543210)).toBe('9876543210')
    expect(cellText('  padded ')).toBe('padded')
    expect(cellText(null)).toBe('')
  })
})
