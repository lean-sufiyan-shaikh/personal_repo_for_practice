import { IndianRupee, ReceiptText, Truck, Wallet, Zap, type LucideIcon } from 'lucide-react'
import type { UserRole } from '../../../types/auth'
import { canAccessPage, canPerformAction } from '../../../permissions/permissions'
import { translateKey } from '../../../i18n'
import { navGroupsFor, navItemLabel } from '../navItems'

export interface CommandItem {
  id: string
  kind: 'page' | 'action'
  label: string
  path: string
  icon: LucideIcon
}

/**
 * Tasks the palette can start. A page that opens the task from its address gets the parameter
 * (Shifts reads ?action=open-shift); the others open on the page where the task's button is.
 */
const QUICK_ACTIONS: { id: string; action: string; page: string; path: string; icon: LucideIcon }[] = [
  { id: 'openShift',      action: 'shift:open',                page: 'shifts',     path: '/dashboard/shifts?action=open-shift', icon: Zap },
  { id: 'recordDelivery', action: 'inventory:record-delivery', page: 'inventory',  path: '/dashboard/inventory',                icon: Truck },
  { id: 'recordPayment',  action: 'credit:record-payment',     page: 'credit',     path: '/dashboard/credit',                   icon: IndianRupee },
  { id: 'addExpense',     action: 'expense:create',            page: 'expenses',   path: '/dashboard/expenses',                 icon: ReceiptText },
  { id: 'cashEntry',      action: 'cash:record-event',         page: 'cashDrawer', path: '/dashboard/cash',                     icon: Wallet },
]

/** Every page the role can open, in sidebar order, then the quick actions the role may do. */
export function commandItemsFor(role: UserRole | undefined): CommandItem[] {
  const pages = navGroupsFor(role).flatMap(group => group.items).map<CommandItem>(item => ({
    id: `page-${item.page}`,
    kind: 'page',
    label: navItemLabel(item.page),
    path: item.path,
    icon: item.icon,
  }))
  const actions = QUICK_ACTIONS
    .filter(action => canPerformAction(action.action, role) && canAccessPage(action.page, role))
    .map<CommandItem>(action => ({
      id: `action-${action.id}`,
      kind: 'action',
      label: translateKey(`nav:palette.actions.${action.id}`),
      path: action.path,
      icon: action.icon,
    }))
  return [...pages, ...actions]
}

export function filterCommandItems(items: CommandItem[], query: string): CommandItem[] {
  const search = query.trim().toLocaleLowerCase()
  return search ? items.filter(item => item.label.toLocaleLowerCase().includes(search)) : items
}
