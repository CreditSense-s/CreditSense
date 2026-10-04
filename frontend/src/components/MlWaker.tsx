import { useEffect, useState } from 'react'
import { ML_WAKE_URL } from '../lib/config'

const FIRST_MINUTES = 10 // visits 30 s apart while a sleeping service starts
const STARTING = 30_000
const AWAKE = 5 * 60_000 // well inside the 15 idle minutes after which a free service sleeps

/**
 * Wakes the ML service from the visitor's browser, exactly as opening its health-check link by hand does, and
 * keeps it awake while anyone has the site open. The frame is invisible and is reloaded on a timer: every 30 s
 * for the first five minutes, then every five minutes.
 */
export function MlWaker({ url = ML_WAKE_URL }: { url?: string }) {
  const [visit, setVisit] = useState(0)
  const enabled = url !== '' && window.top === window.self
  useEffect(() => {
    if (!enabled) return
    let count = 0
    let timer = 0
    const schedule = () => {
      timer = window.setTimeout(() => {
        count += 1
        setVisit((v) => v + 1)
        schedule()
      }, count < FIRST_MINUTES ? STARTING : AWAKE)
    }
    schedule()
    return () => window.clearTimeout(timer)
  }, [enabled])
  if (!enabled) return null
  return (
    <iframe
      key={visit}
      src={url}
      title="Model wake-up"
      aria-hidden
      tabIndex={-1}
      loading="eager"
      referrerPolicy="no-referrer"
      style={{ position: 'absolute', width: 0, height: 0, border: 0, visibility: 'hidden' }}
    />
  )
}
