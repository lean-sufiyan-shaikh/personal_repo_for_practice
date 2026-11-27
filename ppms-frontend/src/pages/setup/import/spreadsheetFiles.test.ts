import { beforeEach, describe, expect, it, vi } from 'vitest'
import readXlsx from 'read-excel-file/browser'
import { readSheet } from 'read-excel-file/browser'
import writeXlsxFile from 'write-excel-file/browser'
import { IMPORT_TEMPLATES } from './importTemplates'
import { downloadCredentials, downloadTemplate, readImportFile, type TemplateTexts } from './spreadsheetFiles'

vi.mock('read-excel-file/browser', () => ({ default: vi.fn(), readSheet: vi.fn() }))
vi.mock('write-excel-file/browser', () => ({ default: vi.fn() }))

const toFile = vi.fn().mockResolvedValue(undefined)

const texts: TemplateTexts = {
  dataSheet: 'Data', instructionsSheet: 'Instructions', intro: ['Fill one row per record.'],
  columnTitle: 'Column', requiredTitle: 'Required', helpTitle: 'What to enter', allowedTitle: 'Allowed values',
  yes: 'Yes', no: 'No', help: (column) => `Help for ${column.header}`,
}

function xlsx(name: string, size = 10) {
  const file = new File(['x'], name)
  Object.defineProperty(file, 'size', { value: size })
  return file
}

describe('spreadsheet files', () => {
  beforeEach(() => {
    vi.mocked(writeXlsxFile).mockReturnValue({ toFile, toBlob: vi.fn() } as never)
  })

  it('writes a template with the headings, an example row and an instructions sheet', async () => {
    const template = IMPORT_TEMPLATES['tank-stock']

    await downloadTemplate(template, texts)

    const [sheets] = vi.mocked(writeXlsxFile).mock.calls[0] as unknown as [Array<{ sheet: string; data: unknown[][] }>]
    expect(sheets.map((sheet) => sheet.sheet)).toEqual(['Data', 'Instructions'])
    expect(sheets[0].data[0]).toEqual([
      { value: 'tank', fontWeight: 'bold' }, { value: 'litres', fontWeight: 'bold' }, { value: 'cost_per_litre', fontWeight: 'bold' },
    ])
    expect(sheets[0].data[1]).toEqual(['Tank 1 - Petrol', '9000', '96.80'])
    expect(sheets[1].data).toContainEqual(['litres', 'Yes', 'Help for litres', ''])
    expect(toFile).toHaveBeenCalledWith('ppms-tank-stock-template.xlsx')
  })

  it('refuses a file that is not .xlsx or is larger than 2 MB without reading it', async () => {
    const template = IMPORT_TEMPLATES['tank-stock']

    expect(await readImportFile(xlsx('stock.csv'), template)).toEqual({ problem: { code: 'notXlsx' } })
    expect(await readImportFile(xlsx('stock.xlsx', 2 * 1024 * 1024 + 1), template)).toEqual({ problem: { code: 'tooBig' } })
    expect(readSheet).not.toHaveBeenCalled()
    expect(readXlsx).not.toHaveBeenCalled()
  })

  it('reads the first sheet and reports a file it cannot open', async () => {
    const template = IMPORT_TEMPLATES['tank-stock']
    vi.mocked(readSheet).mockResolvedValueOnce([['tank', 'litres', 'cost_per_litre'], ['T1', 100, 90]] as never)

    expect(await readImportFile(xlsx('stock.xlsx'), template))
      .toEqual({ rows: [{ rowNumber: 2, tank: 'T1', litres: '100', costPerLitre: '90' }] })

    vi.mocked(readSheet).mockRejectedValueOnce(new Error('not a zip'))
    expect(await readImportFile(xlsx('broken.xlsx'), template)).toEqual({ problem: { code: 'unreadable' } })
  })

  it('saves the sign-in sheet with each new member on their own row', async () => {
    await downloadCredentials(
      [{ rowNumber: 2, fullName: 'Ravi', phoneNumber: '9876543210', employeeId: 'EMP100001', temporaryPassword: 'Tmp#Pass2026' }],
      { sheet: 'Sign-in details', warning: 'Keep private', headers: ['Name', 'Phone', 'Employee ID', 'Temporary password'] },
    )

    const [data] = vi.mocked(writeXlsxFile).mock.calls.at(-1) as unknown as [unknown[][]]
    expect(data.at(-1)).toEqual(['Ravi', '9876543210', 'EMP100001', 'Tmp#Pass2026'])
    expect(toFile).toHaveBeenCalledWith('ppms-staff-sign-in.xlsx')
  })
})
