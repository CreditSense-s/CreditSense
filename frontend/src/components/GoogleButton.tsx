import { useEffect, useRef } from 'react'

interface GoogleId {
  initialize(cfg: { client_id: string; callback: (r: { credential: string }) => void }): void
  renderButton(el: HTMLElement, opts: Record<string, unknown>): void
}
declare global {
  interface Window {
    google?: { accounts: { id: GoogleId } }
  }
}

const SCRIPT = 'https://accounts.google.com/gsi/client'

function loadGoogle(): Promise<GoogleId> {
  return new Promise((resolve, reject) => {
    if (window.google) return resolve(window.google.accounts.id)
    const done = () => (window.google ? resolve(window.google.accounts.id) : reject(new Error('Google sign-in did not load')))
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${SCRIPT}"]`)
    if (existing) {
      existing.addEventListener('load', done)
      existing.addEventListener('error', () => reject(new Error('Google sign-in did not load')))
      return
    }
    const s = document.createElement('script')
    s.src = SCRIPT
    s.async = true
    s.onload = done
    s.onerror = () => reject(new Error('Google sign-in did not load'))
    document.head.appendChild(s)
  })
}

/** Renders Google's own "Sign in with Google" button; `onCredential` receives the signed ID token. */
export function GoogleButton({ clientId, onCredential, onError }: {
  clientId: string
  onCredential: (credential: string) => void
  onError: (message: string) => void
}) {
  const box = useRef<HTMLDivElement>(null)
  const latest = useRef({ onCredential, onError })
  latest.current = { onCredential, onError }

  useEffect(() => {
    let cancelled = false
    loadGoogle()
      .then((id) => {
        if (cancelled || !box.current) return
        id.initialize({ client_id: clientId, callback: (r) => latest.current.onCredential(r.credential) })
        id.renderButton(box.current, { theme: 'outline', size: 'large', text: 'signin_with', shape: 'rectangular', width: 300 })
      })
      .catch((e: Error) => latest.current.onError(e.message))
    return () => {
      cancelled = true
    }
  }, [clientId])

  return <div ref={box} className="flex min-h-11 justify-center" />
}
