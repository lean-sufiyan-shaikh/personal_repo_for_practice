import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import LoginPage from './pages/auth/LoginPage'
import ForgotPasswordPage from './pages/auth/ForgotPasswordPage'
import ResetPasswordPage from './pages/auth/ResetPasswordPage'
import DashboardPage from './pages/dashboard/DashboardPage'
import OverviewPage from './pages/dashboard/OverviewPage'
import ShiftsPage from './pages/shifts/ShiftsPage'
import ShiftPlanningPage from './pages/shifts/ShiftPlanningPage'
import SetupPage from './pages/setup/SetupPage'
import InventoryPage from './pages/inventory/InventoryPage'
import CreditPage from './pages/credit/CreditPage'
import BalanceSheetPage from './pages/balancesheet/BalanceSheetPage'
import ReportsPage from './pages/reports/ReportsPage'
import SuperAdminPage from './pages/superadmin/SuperAdminPage'
import ExpensesPage from './pages/expenses/ExpensesPage'
import DocumentsPage from './pages/documents/DocumentsPage'
import CashDrawerPage from './pages/cash/CashDrawerPage'
import CalibrationPage from './pages/calibration/CalibrationPage'
import AuditLogPage from './pages/audit/AuditLogPage'
import PayrollPage from './pages/payroll/PayrollPage'
import AncillaryProductsPage from './pages/ancillary/AncillaryProductsPage'
import CreditBalancePortalPage from './pages/portal/CreditBalancePortalPage'
import ProtectedRoute from './components/ProtectedRoute'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Never retry a 401 — the token is expired; retrying just fires the logout interceptor again
      retry: (failureCount, error: any) => {
        if (error?.response?.status === 401) return false
        return failureCount < 1
      },
      staleTime: 30_000,
    },
  },
})

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/forgot-password" element={<ForgotPasswordPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <DashboardPage />
              </ProtectedRoute>
            }
          >
            {/* Each nested route has a `page` key that maps to PAGE_PERMISSIONS.
                ProtectedRoute redirects unauthorized roles to their default landing page. */}
            <Route index element={
              <ProtectedRoute page="overview"><OverviewPage /></ProtectedRoute>
            } />
            <Route path="shifts" element={
              <ProtectedRoute page="shifts"><ShiftsPage /></ProtectedRoute>
            } />
            <Route path="shift-planning" element={
              <ProtectedRoute page="shiftPlanning"><ShiftPlanningPage /></ProtectedRoute>
            } />
            <Route path="inventory" element={
              <ProtectedRoute page="inventory"><InventoryPage /></ProtectedRoute>
            } />
            <Route path="credit" element={
              <ProtectedRoute page="credit"><CreditPage /></ProtectedRoute>
            } />
            <Route path="expenses" element={
              <ProtectedRoute page="expenses"><ExpensesPage /></ProtectedRoute>
            } />
            <Route path="documents" element={
              <ProtectedRoute page="documents"><DocumentsPage /></ProtectedRoute>
            } />
            <Route path="cash" element={
              <ProtectedRoute page="cashDrawer"><CashDrawerPage /></ProtectedRoute>
            } />
            <Route path="calibration" element={
              <ProtectedRoute page="calibration"><CalibrationPage /></ProtectedRoute>
            } />
            <Route path="payroll" element={
              <ProtectedRoute page="payroll"><PayrollPage /></ProtectedRoute>
            } />
            <Route path="ancillary" element={
              <ProtectedRoute page="products"><AncillaryProductsPage /></ProtectedRoute>
            } />
            <Route path="audit" element={
              <ProtectedRoute page="auditLog"><AuditLogPage /></ProtectedRoute>
            } />
            <Route path="balance-sheets" element={
              <ProtectedRoute page="balanceSheets"><BalanceSheetPage /></ProtectedRoute>
            } />
            <Route path="reports" element={
              <ProtectedRoute page="reports"><ReportsPage /></ProtectedRoute>
            } />
            <Route path="setup" element={
              <ProtectedRoute page="setup"><SetupPage /></ProtectedRoute>
            } />
          </Route>
          <Route
            path="/super-admin"
            element={
              <ProtectedRoute superAdminOnly>
                <SuperAdminPage />
              </ProtectedRoute>
            }
          />
          {/* Public credit balance portal — no authentication required */}
          <Route path="/portal/credit-balance" element={<CreditBalancePortalPage />} />
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  )
}
