import { Clock, LayoutDashboard, ClipboardCheck, Zap, CalendarDays, Database, CreditCard, ReceiptText, FileText, Wallet, Wrench, HardHat, Package, ClipboardList, Landmark, BarChart2, TrendingUp, Settings, type LucideIcon } from 'lucide-react'
import type { UserRole } from '../../types/auth'
import { canAccessPage } from '../../permissions/permissions'

export const ALL_NAV_ITEMS: { label: string; path: string; icon: LucideIcon; page: string; bg: string; color: string }[] = [
  { label: 'My Shift',       path: '/dashboard/my-shift',       icon: Clock,           page: 'myShift',       bg: 'bg-ok-bg', color: 'text-ok' },
  { label: 'Overview',       path: '/dashboard',                icon: LayoutDashboard, page: 'overview',      bg: 'bg-info-bg',    color: 'text-primary'    },
  { label: 'Approvals',      path: '/dashboard/approvals',      icon: ClipboardCheck,  page: 'approvals',     bg: 'bg-warn-bg',     color: 'text-warn'        },
  { label: 'Shifts',         path: '/dashboard/shifts',         icon: Zap,             page: 'shifts',        bg: 'bg-ok-bg', color: 'text-ok' },
  { label: 'Shift Planning', path: '/dashboard/shift-planning', icon: CalendarDays,    page: 'shiftPlanning', bg: 'bg-info-bg',  color: 'text-primary'  },
  { label: 'Inventory',      path: '/dashboard/inventory',      icon: Database,        page: 'inventory',     bg: 'bg-warn-bg',   color: 'text-warn'   },
  { label: 'Credit',         path: '/dashboard/credit',         icon: CreditCard,      page: 'credit',        bg: 'bg-info-bg',    color: 'text-primary'    },
  { label: 'Expenses',       path: '/dashboard/expenses',       icon: ReceiptText,     page: 'expenses',      bg: 'bg-bad-bg',    color: 'text-bad'    },
  { label: 'Documents',      path: '/dashboard/documents',      icon: FileText,        page: 'documents',     bg: 'bg-surface-sunken',   color: 'text-ink-2'   },
  { label: 'Cash Drawer',    path: '/dashboard/cash',           icon: Wallet,          page: 'cashDrawer',    bg: 'bg-ok-bg',    color: 'text-ok'    },
  { label: 'Calibration',    path: '/dashboard/calibration',    icon: Wrench,          page: 'calibration',   bg: 'bg-warn-bg',  color: 'text-warn'  },
  { label: 'Payroll',        path: '/dashboard/payroll',        icon: HardHat,         page: 'payroll',       bg: 'bg-info-bg',  color: 'text-primary'  },
  { label: 'Products',       path: '/dashboard/ancillary',      icon: Package,         page: 'products',      bg: 'bg-ok-bg',    color: 'text-ok'    },
  { label: 'Audit Log',      path: '/dashboard/audit',          icon: ClipboardList,   page: 'auditLog',      bg: 'bg-surface-sunken',    color: 'text-ink-3'    },
  { label: 'Settlements',    path: '/dashboard/settlements',    icon: Landmark,        page: 'settlements',   bg: 'bg-info-bg',  color: 'text-primary'  },
  { label: 'Balance Sheets', path: '/dashboard/balance-sheets', icon: BarChart2,       page: 'balanceSheets', bg: 'bg-info-bg',     color: 'text-primary'     },
  { label: 'Reports',        path: '/dashboard/reports',        icon: TrendingUp,      page: 'reports',       bg: 'bg-ok-bg',   color: 'text-ok'   },
  { label: 'Setup',          path: '/dashboard/setup',          icon: Settings,        page: 'setup',         bg: 'bg-surface-sunken',    color: 'text-ink-2'    },
]

const BOTTOM_TAB_LABELS: Record<string, string> = {
  myShift:       'My shift',
  overview:      'Home',
  shifts:        'Shifts',
  shiftPlanning: 'Plan',
  inventory:     'Stock',
  credit:        'Credit',
  expenses:      'Expenses',
  cashDrawer:    'Cash',
  reports:       'Reports',
}

const BOTTOM_TAB_PAGES_BY_ROLE: Partial<Record<UserRole, string[]>> = {
  OPERATOR:   ['myShift', 'expenses', 'shiftPlanning'],
  MANAGER:    ['overview', 'shifts', 'inventory', 'cashDrawer'],
  OWNER:      ['overview', 'shifts', 'credit', 'reports'],
  ADMIN:      ['overview', 'shifts', 'credit', 'reports'],
  ACCOUNTANT: ['overview', 'credit', 'expenses'],
}

export interface BottomTab {
  label: string
  path: string
  icon: LucideIcon
}

/** The phone tab bar for a role, limited to pages the role can open; "More" is added by the bar itself. */
export function bottomTabsFor(role: UserRole | undefined): BottomTab[] {
  const pages = role ? BOTTOM_TAB_PAGES_BY_ROLE[role] ?? [] : []
  return pages
    .filter(page => canAccessPage(page, role))
    .flatMap(page => {
      const item = ALL_NAV_ITEMS.find(navItem => navItem.page === page)
      return item ? [{ label: BOTTOM_TAB_LABELS[page], path: item.path, icon: item.icon }] : []
    })
}

export function isNavPathActive(itemPath: string, pathname: string): boolean {
  return pathname === itemPath || (itemPath !== '/dashboard' && pathname.startsWith(itemPath))
}
