import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { GoogleButton } from '../components/GoogleButton'
import { Brand } from '../components/Layout'
import { ResearchNotice } from '../components/Notice'
import { Button, Field, Input, Loading } from '../components/ui'
import { api, toApiError } from '../lib/api'
import { useAuthOptions } from '../lib/options'
import type { TokenResponse } from '../lib/types'
import { homeFor, useAuth } from '../store/auth'

function AuthFrame({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="grid min-h-dvh md:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
      <aside className="flex flex-col justify-between bg-ink p-8 md:p-12">
        <Brand />
        <div className="my-10 max-w-md">
          <p className="font-display text-3xl leading-tight font-semibold text-paper md:text-4xl">
            Every score arrives with its reasons.
          </p>
          <p className="mt-4 text-sm leading-relaxed text-fog">
            Compliance is checked by rule before any model sees an application. Each risk score is broken into signed line items
            that add up to the decision, so lenders can justify it and applicants can understand it.
          </p>
        </div>
        <div className="space-y-4">
          <ResearchNotice />
          <LedgerMotif />
        </div>
      </aside>
      <main className="flex items-center justify-center bg-panel p-6">
        <div className="w-full max-w-sm rounded-md bg-paper p-6 text-ink">
          <h1 className="mb-5 text-xl font-semibold">{title}</h1>
          {children}
        </div>
      </main>
    </div>
  )
}

function LedgerMotif() {
  const rows = [0.84, -0.56, 0.49, -0.48, 0.34, 0.25]
  return (
    <div aria-hidden className="max-w-xs space-y-1.5 opacity-80">
      {rows.map((v, i) => (
        <div key={i} className="relative h-2">
          <span className="absolute inset-y-[-2px] left-1/2 w-px bg-rule" />
          <span className={`absolute top-0 h-2 rounded-sm ${v > 0 ? 'left-1/2 bg-risk' : 'right-1/2 bg-confirm'}`} style={{ width: `${Math.abs(v) * 55}%` }} />
        </div>
      ))}
    </div>
  )
}

const loginSchema = z.object({
  email: z.email('enter a valid email'),
  password: z.string().min(1, 'enter your password'),
})

const DEMO = [
  { email: 'applicant@creditsense.demo', role: 'Applicant' },
  { email: 'officer@creditsense.demo', role: 'Loan officer' },
  { email: 'admin@creditsense.demo', role: 'Admin' },
]

export function LoginPage() {
  const { user, setSession } = useAuth()
  const navigate = useNavigate()
  const location = useLocation() as { state?: { from?: string } }
  const [error, setError] = useState<string | null>(null)
  const options = useAuthOptions()
  const { register, handleSubmit, setValue, formState } = useForm<z.infer<typeof loginSchema>>({ resolver: zodResolver(loginSchema) })

  if (user) return <Navigate to={homeFor(user.role)} replace />

  const goHome = (data: TokenResponse) => {
    setSession(data)
    navigate(location.state?.from ?? homeFor(data.user.role), { replace: true })
  }
  const onGoogle = async (credential: string) => {
    setError(null)
    try {
      goHome((await api.post<TokenResponse>('/auth/google', { credential })).data)
    } catch (e) {
      setError(toApiError(e).message)
    }
  }

  if (options.isPending) {
    return (
      <AuthFrame title="Server waking up">
        <Loading label="Starting the demo server" />
        <p className="mt-3 text-sm text-muted">
          The free demo server sleeps when nobody is using it. This can take a few minutes the first time; this page continues by itself.
        </p>
      </AuthFrame>
    )
  }
  const { googleClientId, passwordLogin } = options.data!

  const onSubmit = handleSubmit(async (values) => {
    setError(null)
    try {
      goHome((await api.post<TokenResponse>('/auth/login', values)).data)
    } catch (e) {
      setError(toApiError(e).message)
    }
  })

  return (
    <AuthFrame title="Sign in">
      {googleClientId ? (
        <GoogleButton clientId={googleClientId} onCredential={onGoogle} onError={setError} />
      ) : (
        !passwordLogin && <p className="text-sm text-risk-deep">Sign-in is not configured on this server.</p>
      )}
      {googleClientId && passwordLogin && <p className="my-4 text-center text-xs text-muted">or use a local account</p>}
      {passwordLogin && (
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        <Field label="Email" htmlFor="email" error={formState.errors.email?.message}>
          <Input id="email" type="email" autoComplete="username" {...register('email')} aria-invalid={!!formState.errors.email} />
        </Field>
        <Field label="Password" htmlFor="password" error={formState.errors.password?.message}>
          <Input id="password" type="password" autoComplete="current-password" {...register('password')} aria-invalid={!!formState.errors.password} />
        </Field>
        <Button type="submit" className="w-full" busy={formState.isSubmitting}>Sign in</Button>
      </form>
      )}
      {error && <p role="alert" className="mt-4 rounded bg-risk/10 px-3 py-2 text-sm text-risk-deep">{error}</p>}
      {passwordLogin && (
      <p className="mt-4 text-sm text-muted">
        New business? <Link to="/register" className="font-medium text-ink underline">Create an account</Link>
      </p>
      )}
      <p className="mt-4 text-xs text-muted">
        By signing in you accept the <Link to="/privacy" className="underline">privacy notice</Link>. This is a research demo: use made-up business details.
      </p>
      {passwordLogin && (
      <div className="mt-6 border-t border-paper-rule pt-4">
        <p className="text-xs font-medium tracking-wide text-muted uppercase">Demo accounts (password Demo@1234)</p>
        <div className="mt-2 flex flex-wrap gap-2">
          {DEMO.map((d) => (
            <button
              key={d.email}
              type="button"
              onClick={() => {
                setValue('email', d.email)
                setValue('password', 'Demo@1234')
              }}
              className="rounded border border-paper-rule px-2 py-1 text-xs hover:bg-paper-deep"
            >
              {d.role}
            </button>
          ))}
        </div>
      </div>
      )}
    </AuthFrame>
  )
}

const registerSchema = z.object({
  fullName: z.string().trim().min(2, 'enter your name').max(120),
  email: z.email('enter a valid email'),
  password: z
    .string()
    .min(8, 'at least 8 characters')
    .max(72)
    .regex(/^(?=.*[A-Za-z])(?=.*\d).+$/, 'use letters and digits'),
})

export function RegisterPage() {
  const { user, setSession } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)
  const options = useAuthOptions()
  const { register, handleSubmit, formState } = useForm<z.infer<typeof registerSchema>>({ resolver: zodResolver(registerSchema) })
  if (user) return <Navigate to={homeFor(user.role)} replace />
  if (options.data && !options.data.passwordLogin) return <Navigate to="/login" replace /> // Google is the only way in

  const onSubmit = handleSubmit(async (values) => {
    setError(null)
    try {
      const { data } = await api.post<TokenResponse>('/auth/register', values)
      setSession(data)
      navigate('/applicant/apply', { replace: true })
    } catch (e) {
      setError(toApiError(e).message)
    }
  })

  return (
    <AuthFrame title="Create an applicant account">
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        <Field label="Full name" htmlFor="fullName" error={formState.errors.fullName?.message}>
          <Input id="fullName" autoComplete="name" {...register('fullName')} aria-invalid={!!formState.errors.fullName} />
        </Field>
        <Field label="Email" htmlFor="email" error={formState.errors.email?.message}>
          <Input id="email" type="email" autoComplete="email" {...register('email')} aria-invalid={!!formState.errors.email} />
        </Field>
        <Field label="Password" htmlFor="password" error={formState.errors.password?.message} hint="8+ characters with letters and digits">
          <Input id="password" type="password" autoComplete="new-password" {...register('password')} aria-invalid={!!formState.errors.password} />
        </Field>
        {error && <p role="alert" className="rounded bg-risk/10 px-3 py-2 text-sm text-risk-deep">{error}</p>}
        <Button type="submit" className="w-full" busy={formState.isSubmitting}>Create account</Button>
      </form>
      <p className="mt-4 text-sm text-muted">
        Already registered? <Link to="/login" className="font-medium text-ink underline">Sign in</Link>
      </p>
    </AuthFrame>
  )
}
