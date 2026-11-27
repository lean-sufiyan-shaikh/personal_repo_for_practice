import type { ImportType } from '../../../api/importApi'

export type ColumnKind = 'text' | 'number' | 'date' | 'choice'

export interface TemplateColumn {
  /** The header in the spreadsheet, kept in English so a file works whatever language the app is in. */
  header: string
  /** The name the import endpoint reads the cell under. */
  field: string
  required: boolean
  kind: ColumnKind
  /** The values a choice column accepts, shown in the instructions. */
  choices?: readonly string[]
  example: string
}

export interface ImportTemplate {
  type: ImportType
  /** Rows one file may hold; the server refuses more. */
  maxRows: number
  columns: readonly TemplateColumn[]
}

const text = (header: string, field: string, required: boolean, example: string): TemplateColumn =>
  ({ header, field, required, kind: 'text', example })
const number = (header: string, field: string, required: boolean, example: string): TemplateColumn =>
  ({ header, field, required, kind: 'number', example })
const date = (header: string, field: string, example: string): TemplateColumn =>
  ({ header, field, required: false, kind: 'date', example })
const choice = (header: string, field: string, required: boolean, choices: readonly string[], example: string): TemplateColumn =>
  ({ header, field, required, kind: 'choice', choices, example })

export const MAX_FILE_BYTES = 2 * 1024 * 1024

export const IMPORT_TEMPLATES: Record<ImportType, ImportTemplate> = {
  staff: {
    type: 'staff',
    maxRows: 100,
    columns: [
      text('full_name', 'fullName', true, 'Ravi Kumar'),
      text('phone', 'phone', true, '9876543210'),
      choice('role', 'role', true, ['OPERATOR', 'MANAGER', 'ADMIN', 'ACCOUNTANT'], 'OPERATOR'),
      choice('gender', 'gender', true, ['MALE', 'FEMALE', 'OTHER'], 'MALE'),
      text('employee_id', 'employeeId', false, ''),
      date('date_of_joining', 'dateOfJoining', '2026-04-01'),
      choice('night_shift_consent', 'nightShiftConsent', false, ['yes', 'no'], 'no'),
      text('address', 'address', false, 'Near bus stand, Pune'),
      choice('language', 'language', false, ['en', 'hi', 'mr'], 'hi'),
      number('daily_rate', 'dailyRate', false, ''),
      number('day_hourly_rate', 'dayHourlyRate', false, '60'),
      number('night_hourly_rate', 'nightHourlyRate', false, '75'),
    ],
  },
  'credit-clients': {
    type: 'credit-clients',
    maxRows: 500,
    columns: [
      text('name', 'name', true, 'Sharma Transport'),
      text('phone', 'phone', true, '9822012345'),
      text('parent_name', 'parentName', false, ''),
      text('credit_limit', 'creditLimit', false, '50000'),
      number('opening_balance', 'openingBalance', false, '12500'),
      date('opening_balance_date', 'openingBalanceDate', '2026-04-01'),
      text('gstin', 'gstin', false, '27ABCDE1234F1Z5'),
      text('billing_address', 'billingAddress', false, 'MIDC, Pune'),
      text('notes', 'notes', false, ''),
    ],
  },
  products: {
    type: 'products',
    maxRows: 500,
    columns: [
      text('name', 'name', true, 'Engine Oil 20W-40'),
      choice('unit', 'unit', true, ['LITRE', 'KG', 'PIECE'], 'LITRE'),
      number('package_size', 'packageSize', true, '1'),
      text('brand', 'brand', false, 'Castrol'),
      text('variant', 'variant', false, ''),
      number('gst_rate', 'gstRate', false, '18'),
      text('hsn_code', 'hsnCode', false, '27101980'),
      number('selling_price', 'sellingPrice', false, '420'),
      number('low_stock_threshold', 'lowStockThreshold', false, '5'),
      number('opening_stock', 'openingStock', false, '24'),
      number('opening_cost', 'openingCost', false, '330'),
    ],
  },
  'tank-stock': {
    type: 'tank-stock',
    maxRows: 500,
    columns: [
      text('tank', 'tank', true, 'Tank 1 - Petrol'),
      number('litres', 'litres', true, '9000'),
      number('cost_per_litre', 'costPerLitre', true, '96.80'),
    ],
  },
}

export const IMPORT_TYPES: readonly ImportType[] = ['staff', 'credit-clients', 'products', 'tank-stock']

export function templateFileName(type: ImportType): string {
  return `ppms-${type}-template.xlsx`
}
