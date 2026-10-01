import { Link } from 'react-router-dom'

/** Said on every screen: this is a research demo, the model is simulated, and real identifiers do not belong here. */
export function ResearchNotice({ className }: { className?: string }) {
  return (
    <p role="note" className={`rounded bg-amber/15 px-3 py-2 text-xs leading-relaxed text-paper ${className ?? ''}`}>
      <strong>Research demo.</strong> The score comes from a simulated model trained on synthetic data and is not a real lending decision.{' '}
      <strong>Please do not enter real PAN, GSTIN or other personal details</strong> — use made-up values.{' '}
      <Link to="/privacy" className="underline">Privacy notice</Link>
    </p>
  )
}
