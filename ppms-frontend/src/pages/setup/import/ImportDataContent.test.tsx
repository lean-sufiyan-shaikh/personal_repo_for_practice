import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { importApi, type ImportReport } from '../../../api/importApi'
import { useAuthStore } from '../../../store/authStore'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { resetScreenWidthAfterEach, setScreenWidth } from '../../../test/screenWidth'
import { ImportDataContent } from './ImportDataContent'
import { downloadCredentials, downloadTemplate, readImportFile } from './spreadsheetFiles'

vi.mock('../../../api/importApi', () => ({ importApi: { preview: vi.fn(), importRows: vi.fn() } }))
vi.mock('./spreadsheetFiles', () => ({ downloadTemplate: vi.fn(), readImportFile: vi.fn(), downloadCredentials: vi.fn() }))
vi.mock('./DemoPumpPanel', () => ({ DemoPumpPanel: () => <p>Demo panel</p> }))

const rows = [
  { rowNumber: 2, fullName: 'Ravi Kumar', phone: '9876543210', role: 'OPERATOR', gender: 'MALE' },
  { rowNumber: 3, fullName: 'Sita', phone: '123', role: 'OPERATOR', gender: 'FEMALE' },
]

const previewWithProblem: ImportReport = {
  totalRows: 2, createCount: 1, skipCount: 0, errorCount: 1, imported: false, credentials: [],
  rows: [
    { rowNumber: 2, outcome: 'CREATE', issues: [] },
    { rowNumber: 3, outcome: 'ERROR', issues: [{ column: 'phone', code: 'IMPORT_PHONE_INVALID', params: {} }] },
  ],
}

const cleanPreview: ImportReport = {
  totalRows: 1, createCount: 1, skipCount: 0, errorCount: 0, imported: false, credentials: [],
  rows: [{ rowNumber: 2, outcome: 'CREATE', issues: [] }],
}

const imported: ImportReport = {
  ...cleanPreview,
  imported: true,
  credentials: [{ rowNumber: 2, fullName: 'Ravi Kumar', phoneNumber: '9876543210', employeeId: 'EMP300001', temporaryPassword: 'Xy7#kLm9Pq2r' }],
}

async function chooseFile(user: ReturnType<typeof userEvent.setup>) {
  await user.upload(screen.getByLabelText('Choose Excel file'), new File(['x'], 'staff.xlsx'))
}

describe('ImportDataContent', () => {
  resetScreenWidthAfterEach()

  beforeEach(() => {
    useAuthStore.setState({ user: { userId: 1, fullName: 'Owner', role: 'OWNER', assignedPumpId: null }, isAuthenticated: true })
    vi.mocked(downloadTemplate).mockResolvedValue()
    vi.mocked(downloadCredentials).mockResolvedValue()
  })

  it('downloads the template of the chosen type with translated instructions', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ImportDataContent pumpId={3} />)

    await user.click(screen.getByRole('button', { name: 'Opening tank stock' }))
    await user.click(screen.getByRole('button', { name: 'Download template' }))

    const [template, texts] = vi.mocked(downloadTemplate).mock.calls[0]
    expect(template.type).toBe('tank-stock')
    expect(texts.instructionsSheet).toBe('Instructions')
    expect(texts.help(template.columns[1])).toBe('Litres. Litres in the tank today; not more than its capacity.')
  })

  it('previews each row with its problems and keeps import off until the file is fixed', async () => {
    const user = userEvent.setup()
    vi.mocked(readImportFile).mockResolvedValue({ rows })
    vi.mocked(importApi.preview).mockResolvedValue(previewWithProblem)
    renderWithProviders(<ImportDataContent pumpId={3} />)

    await chooseFile(user)

    expect(await screen.findByText('Phone: Enter a 10-digit phone number.')).toBeInTheDocument()
    expect(importApi.preview).toHaveBeenCalledWith(3, 'staff', rows)
    expect(screen.getByText('To add: 1')).toBeInTheDocument()
    expect(screen.getByText('With problems: 1')).toBeInTheDocument()
    expect(screen.getByText('Fix this row')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Import 1 row' })).toBeDisabled()
  })

  it('explains a file whose headings are missing', async () => {
    const user = userEvent.setup()
    vi.mocked(readImportFile).mockResolvedValue({ problem: { code: 'missingColumns', columns: ['phone'] } })
    renderWithProviders(<ImportDataContent pumpId={3} />)

    await chooseFile(user)

    expect(await screen.findByRole('alert')).toHaveTextContent('The file is missing these columns: phone.')
    expect(importApi.preview).not.toHaveBeenCalled()
  })

  it('imports a clean file and offers the sign-in sheet without showing passwords', async () => {
    const user = userEvent.setup()
    vi.mocked(readImportFile).mockResolvedValue({ rows: [rows[0]] })
    vi.mocked(importApi.preview).mockResolvedValue(cleanPreview)
    vi.mocked(importApi.importRows).mockResolvedValue(imported)
    renderWithProviders(<ImportDataContent pumpId={3} />)

    await chooseFile(user)
    await user.click(await screen.findByRole('button', { name: 'Import 1 row' }))

    expect(await screen.findByText(/Import complete/)).toBeInTheDocument()
    expect(screen.getByText('Added')).toBeInTheDocument()
    const credentials = screen.getByRole('region', { name: 'Sign-in details for new staff' })
    expect(within(credentials).queryByText(/Xy7#kLm9Pq2r/)).not.toBeInTheDocument()
    expect(document.body).not.toHaveTextContent('Xy7#kLm9Pq2r')

    await user.click(within(credentials).getByRole('button', { name: 'Download sign-in sheet' }))
    await waitFor(() => expect(downloadCredentials).toHaveBeenCalledWith(imported.credentials, expect.objectContaining({
      headers: ['Name', 'Phone', 'Employee ID', 'Temporary password'],
    })))
    expect(await screen.findByText(/Sheet downloaded/)).toBeInTheDocument()
  })

  it('stacks the preview rows as cards on a phone', async () => {
    setScreenWidth(375)
    const user = userEvent.setup()
    vi.mocked(readImportFile).mockResolvedValue({ rows })
    vi.mocked(importApi.preview).mockResolvedValue(previewWithProblem)
    renderWithProviders(<ImportDataContent pumpId={3} />)

    await chooseFile(user)

    const table = await screen.findByRole('table')
    expect(table).toHaveClass('ui-table-cards')
    expect(within(table).getAllByRole('row')).toHaveLength(3)
  })

  it('offers the demo pump to owners only', () => {
    const { unmount } = renderWithProviders(<ImportDataContent pumpId={3} />)
    expect(screen.getByText('Demo panel')).toBeInTheDocument()
    unmount()

    useAuthStore.setState({ user: { userId: 2, fullName: 'Admin', role: 'ADMIN', assignedPumpId: 3 }, isAuthenticated: true })
    renderWithProviders(<ImportDataContent pumpId={3} />)
    expect(screen.queryByText('Demo panel')).not.toBeInTheDocument()
  })
})
