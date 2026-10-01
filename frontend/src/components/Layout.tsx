import clsx from 'clsx'
import { FilePlus2, Gauge, Inbox, LayoutDashboard, LogOut, ScrollText, Trash2 } from 'lucide-react'
import type { ReactNode } from 'react'
import { NavLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api, toApiError } from '../lib/api'
import { ResearchNotice } from './Notice'
import type { Role } from '../lib/types'
import { homeFor, useAuth } from '../store/auth'

const NAV: Record<Role, { to: string; label: string; icon: ReactNode }[]> = {
  APPLICANT: [
    { to: '/applicant/dashboard', label: 'My loans', icon: <LayoutDashboard className="size-5" /> },
    { to: '/applicant/apply', label: 'Apply', icon: <FilePlus2 className="size-5" /> },
  ],
  LOAN_OFFICER: [{ to: '/officer/queue', label: 'Queue', icon: <Inbox className="size-5" /> }],
  ADMIN: [
    { to: '/admin/portfolio', label: 'Portfolio', icon: <Gauge className="size-5" /> },
    { to: '/officer/queue', label: 'Queue', icon: <Inbox className="size-5" /> },
    { to: '/admin/audit', label: 'Audit', icon: <ScrollText className="size-5" /> },
  ],
}

const ROLE_SHORT: Record<Role, string> = { APPLICANT: 'Applicant', LOAN_OFFICER: 'Officer', ADMIN: 'Admin' }

export function Brand({ compact }: { compact?: boolean }) {
  return (
    <span className="flex items-center gap-2 font-display text-paper">
      <svg aria-hidden viewBox="0 0 32 32" className="size-7">
        <rect width="32" height="32" rx="6" fill="#1A2028" />
        <rect x="15" y="6" width="2" height="20" fill="#E4E7EB" />
        <rect x="17" y="9" width="9" height="4" fill="#C4453D" />
        <rect x="8" y="15" width="7" height="4" fill="#3F7D58" />
        <rect x="17" y="21" width="5" height="4" fill="#C4453D" />
      </svg>
      {!compact && <span className="text-lg font-semibold tracking-tight">CreditSense</span>}
    </span>
  )
}

export function AppShell() {
  const { user, clear } = useAuth()
  const navigate = useNavigate()
  if (!user) return null
  const erase = async () => {
    if (!window.confirm('Delete your account and every application you submitted? This cannot be undone.')) return
    try {
      await api.delete('/auth/me')
      clear()
      navigate('/login')
    } catch (e) {
      window.alert(toApiError(e).message)
    }
  }
  const logout = async () => {
    await api.post('/auth/logout').catch(() => undefined) // revokes the token and clears the cookie
    clear()
    navigate('/login')
  }
  return (
    <div className="min-h-dvh md:grid md:grid-cols-[5.5rem_minmax(0,1fr)]">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:rounded focus:bg-amber focus:px-3 focus:py-1 focus:text-ink">
        Skip to content
      </a>
      <nav
        aria-label="Primary"
        className="sticky top-0 z-20 flex items-center gap-1 bg-ink px-3 py-2 md:h-dvh md:flex-col md:items-stretch md:px-2 md:py-4"
      >
        <div className="mr-2 md:mr-0 md:mb-6 md:flex md:justify-center">
          <Brand compact />
        </div>
        {NAV[user.role].map((n) => (
          <NavLink
            key={n.to}
            to={n.to}
            className={({ isActive }) =>
              clsx(
                'flex flex-col items-center gap-1 rounded px-2 py-2 text-[11px] font-medium text-fog hover:bg-panel hover:text-paper',
                isActive && 'bg-panel text-paper shadow-[inset_2px_0_0_var(--color-amber)]',
              )
            }
          >
            {n.icon}
            {n.label}
          </NavLink>
        ))}
        <div className="ml-auto md:mt-auto md:ml-0">
          <p className="hidden truncate px-1 pb-2 text-center text-[10px] text-fog md:block" title={user.email}>
            {user.fullName.split(' ')[0]}
            <br />
            <span className="text-[9px] tracking-wider uppercase">{ROLE_SHORT[user.role]}</span>
          </p>
          {user.role === 'APPLICANT' && (
            <button onClick={erase} className="flex w-full flex-col items-center gap-1 rounded px-2 py-2 text-[11px] text-fog hover:bg-panel hover:text-paper">
              <Trash2 className="size-5" aria-hidden />
              Delete my data
            </button>
          )}
          <button onClick={logout} className="flex w-full flex-col items-center gap-1 rounded px-2 py-2 text-[11px] text-fog hover:bg-panel hover:text-paper">
            <LogOut className="size-5" aria-hidden />
            Sign out
          </button>
        </div>
      </nav>
      <main id="main" className="mx-auto w-full max-w-7xl px-4 py-6 md:px-8">
        <ResearchNotice className="mb-5" />
        <Outlet />
      </main>
    </div>
  )
}

/** Client-side guard for UX only; the API enforces every permission itself. */
export function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const user = useAuth((s) => s.user)
  const location = useLocation()
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  if (!roles.includes(user.role)) return <Navigate to={homeFor(user.role)} replace />
  return <>{children}</>
}
