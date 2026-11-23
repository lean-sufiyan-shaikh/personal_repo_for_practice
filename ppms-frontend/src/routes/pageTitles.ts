import { ALL_NAV_ITEMS } from '../pages/dashboard/navItems'

const APP_NAME = 'PPMS'

const OTHER_PAGE_TITLES: Record<string, string> = {
  '/dashboard/notifications': 'Notifications',
  '/login':                   'Sign in',
  '/forgot-password':         'Forgot password',
  '/reset-password':          'Reset password',
  '/change-password':         'Change password',
  '/super-admin':             'Super Admin',
  '/portal/credit-balance':   'Credit balance',
}

/** "Credit · PPMS" for the page at this path; nested pages such as /dashboard/credit/12 take their section's name. */
export function pageTitleFor(pathname: string): string {
  const path = pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname
  const name = OTHER_PAGE_TITLES[path]
    ?? ALL_NAV_ITEMS.find((item) => item.path === path)?.label
    ?? ALL_NAV_ITEMS.find((item) => item.path !== '/dashboard' && path.startsWith(`${item.path}/`))?.label
  return name ? `${name} · ${APP_NAME}` : APP_NAME
}
