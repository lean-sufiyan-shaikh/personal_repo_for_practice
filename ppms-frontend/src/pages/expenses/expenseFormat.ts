import type { ExpenseCategory, ExpensePaidFrom } from '../../api/expenseApi'

export const CATEGORIES: ExpenseCategory[] = ['FUEL', 'MAINTENANCE', 'SALARY', 'UTILITIES', 'EQUIPMENT', 'OTHER']

export const PAID_FROM: ExpensePaidFrom[] = ['SAFE', 'PETTY_CASH', 'BANK', 'OWNER_POCKET']

export { formatIstDate as fmtDate } from '../../utils/date'
