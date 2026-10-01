import { Suspense, lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell, RequireRole } from './components/Layout'
import { Loading } from './components/ui'
import { ApplicantDashboard } from './pages/ApplicantDashboard'
import { ApplyPage } from './pages/ApplyPage'
import { LoginPage, RegisterPage } from './pages/AuthPages'
import { OfficerApplication } from './pages/OfficerApplication'
import { OfficerQueue } from './pages/OfficerQueue'
import { PrivacyPage } from './pages/PrivacyPage'
import { homeFor, useAuth } from './store/auth'

// Admin views pull in the charting library; load them only when an admin opens them.
const AdminPortfolio = lazy(() => import('./pages/AdminPortfolio').then((m) => ({ default: m.AdminPortfolio })))
const AdminAudit = lazy(() => import('./pages/AdminAudit').then((m) => ({ default: m.AdminAudit })))

export function App() {
  const user = useAuth((s) => s.user)
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/privacy" element={<PrivacyPage />} />
      <Route element={<RequireRole roles={['APPLICANT', 'LOAN_OFFICER', 'ADMIN']}><AppShell /></RequireRole>}>
        <Route path="/applicant/apply" element={<RequireRole roles={['APPLICANT']}><ApplyPage /></RequireRole>} />
        <Route path="/applicant/dashboard" element={<RequireRole roles={['APPLICANT']}><ApplicantDashboard /></RequireRole>} />
        <Route path="/officer/queue" element={<RequireRole roles={['LOAN_OFFICER', 'ADMIN']}><OfficerQueue /></RequireRole>} />
        <Route path="/officer/applications/:id" element={<RequireRole roles={['LOAN_OFFICER', 'ADMIN']}><OfficerApplication /></RequireRole>} />
        <Route path="/admin/portfolio" element={<RequireRole roles={['ADMIN']}><Suspense fallback={<Loading />}><AdminPortfolio /></Suspense></RequireRole>} />
        <Route path="/admin/audit" element={<RequireRole roles={['ADMIN']}><Suspense fallback={<Loading />}><AdminAudit /></Suspense></RequireRole>} />
      </Route>
      <Route path="*" element={<Navigate to={user ? homeFor(user.role) : '/login'} replace />} />
    </Routes>
  )
}
