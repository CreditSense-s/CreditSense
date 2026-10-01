import clsx from 'clsx'
import { ArrowLeft, RefreshCw, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Documents, Financials, PipelineStepper, ScoreBlock } from '../components/ApplicationParts'
import { ComplianceChecklist } from '../components/ComplianceChecklist'
import { Ledger } from '../components/Ledger'
import { DecisionTag, StatusBadge } from '../components/badges'
import { Button, Card, ErrorState, Field, Loading, Textarea } from '../components/ui'
import { toApiError } from '../lib/api'
import { date, dateTime, humanize, lakh } from '../lib/format'
import { stateName } from '../lib/gstin'
import { purposeLabel, sectorLabel } from '../lib/labels'
import { useApplication, useApplicationAction } from '../lib/queries'
import type { Decision, Detail } from '../lib/types'
import { useAuth } from '../store/auth'

export function OfficerApplication() {
  const id = Number(useParams().id)
  const q = useApplication(id)
  if (q.isLoading) return <Loading />
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />
  if (!q.data) return null
  return <View app={q.data} />
}

function View({ app }: { app: Detail }) {
  const actions = useApplicationAction(app.id)
  const role = useAuth((s) => s.user?.role)
  const b = app.business
  const r = app.riskAssessment
  const canRescore = ['COMPLIANCE_REVIEW', 'MANUAL_REVIEW', 'RISK_SCORED'].includes(app.status)
  const canRecheck = !['APPROVED', 'REJECTED'].includes(app.status)
  const actionError = actions.recheck.error ?? actions.rescore.error

  return (
    <div className="space-y-5">
      <Link to="/officer/queue" className="inline-flex items-center gap-1 text-sm text-fog hover:text-paper">
        <ArrowLeft aria-hidden className="size-4" /> Queue
      </Link>

      <Card>
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <p className="num text-xs text-muted">{app.reference}</p>
            <h1 className="mt-1 text-2xl font-semibold">{b.businessName}</h1>
            <p className="text-sm text-muted">
              {b.ownerName} · {sectorLabel(b.sector)} · {b.city}, {stateName(b.stateCode)} · since {date(b.businessStartDate)}
            </p>
            <p className="num mt-1 text-xs text-muted">PAN {b.pan} · GSTIN {b.gstin}{b.udyamNumber ? ` · ${b.udyamNumber}` : ''}</p>
          </div>
          <div className="text-right">
            <StatusBadge status={app.status} />
            <p className="num mt-2 text-xl">{lakh(app.amountRequested)}</p>
            <p className="text-xs text-muted">{purposeLabel(app.purpose)} · {app.tenureMonths} months</p>
          </div>
        </div>
        <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-paper-rule pt-4">
          <PipelineStepper app={app} />
          <div className="flex gap-2">
            {canRecheck && (
              <Button variant="ghost" busy={actions.recheck.isPending} onClick={() => actions.recheck.mutate()}>
                <ShieldCheck aria-hidden className="size-4" /> Re-run compliance
              </Button>
            )}
            {canRescore && (
              <Button variant="ghost" busy={actions.rescore.isPending} onClick={() => actions.rescore.mutate()}>
                <RefreshCw aria-hidden className="size-4" /> Re-score
              </Button>
            )}
          </div>
        </div>
        {actionError && <p role="alert" className="mt-3 text-sm text-risk-deep">{toApiError(actionError).message}</p>}
      </Card>

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="space-y-5">
          {app.status === 'MANUAL_REVIEW' && (
            <Card className="ring-2 ring-amber">
              <p className="text-sm font-medium">Manual review required</p>
              <p className="text-sm text-muted">{app.manualReviewReason}</p>
            </Card>
          )}
          {r ? (
            <Card title="Risk score and explanation">
              <ScoreBlock app={app} />
              <div className="mt-5">
                <Ledger baseValue={r.baseValue} contributions={r.contributions} probabilityOfDefault={r.probabilityOfDefault} audience="officer" />
              </div>
            </Card>
          ) : (
            app.status === 'COMPLIANCE_FAILED' && (
              <Card title="Not scored">
                <p className="text-sm">The application failed the compliance gate, so it was never sent to the risk model.</p>
              </Card>
            )
          )}
          <div className="grid gap-5 md:grid-cols-2">
            <Card title="Compliance gate"><ComplianceChecklist checks={app.complianceChecks} /></Card>
            <Card title="KYC documents"><Documents app={app} /></Card>
          </div>
          <Card title="Audit trail">
            <ol className="space-y-3">
              {app.timeline.map((t, i) => (
                <li key={i} className="grid grid-cols-[10.5rem_minmax(0,1fr)] gap-3 text-sm">
                  <span className="num text-xs text-muted">{dateTime(t.at)}</span>
                  <div>
                    <p className="font-medium">{humanize(t.action)}</p>
                    <p className="text-xs text-muted">{t.actorEmail}</p>
                  </div>
                </li>
              ))}
            </ol>
          </Card>
        </div>

        <div className="space-y-5">
          <DecisionPanel app={app} canDecide={role === 'LOAN_OFFICER' || role === 'ADMIN'} />
          <Card title="Financial snapshot"><Financials app={app} /></Card>
        </div>
      </div>
    </div>
  )
}

function DecisionPanel({ app, canDecide }: { app: Detail; canDecide: boolean }) {
  const { decide } = useApplicationAction(app.id)
  const [choice, setChoice] = useState<Decision | null>(null)
  const [reason, setReason] = useState('')

  if (app.decision) {
    return (
      <Card title="Decision">
        <p className={clsx('text-lg font-semibold', app.decision.decision === 'APPROVE' ? 'text-confirm-deep' : 'text-risk-deep')}>
          {app.decision.decision === 'APPROVE' ? 'Approved' : 'Rejected'}
          {app.decision.override && <span className="ml-2 rounded bg-amber px-1.5 py-0.5 align-middle text-[10px] text-ink uppercase">override</span>}
        </p>
        <p className="text-xs text-muted">by {app.decision.decidedBy} · {dateTime(app.decision.decidedAt)}</p>
        {app.decision.reason && <p className="mt-2 text-sm">{app.decision.reason}</p>}
        {app.outcome && (
          <p className="mt-3 text-sm">Outcome: <strong className={app.outcome === 'REPAID' ? 'text-confirm-deep' : 'text-risk-deep'}>{app.outcome.toLowerCase()}</strong></p>
        )}
      </Card>
    )
  }
  if (!['RISK_SCORED', 'MANUAL_REVIEW'].includes(app.status)) return null

  const manual = app.status === 'MANUAL_REVIEW'
  const override = !manual && choice != null && choice !== app.modelRecommendation
  const needsReason = manual || override
  const tooShort = needsReason && reason.trim().length < 10

  return (
    <Card title="Decision" className="ring-2 ring-steel/60">
      <p className="text-sm">
        {manual ? (
          'No model score is available. Decide on the documents and record your reasoning.'
        ) : (
          <>Model recommendation: <DecisionTag decision={app.modelRecommendation} /></>
        )}
      </p>
      {!canDecide && <p className="mt-2 text-xs text-muted">Only loan officers can record decisions.</p>}
      {canDecide && (
        <form
          className="mt-4 space-y-3"
          onSubmit={(e) => {
            e.preventDefault()
            if (choice && !tooShort) decide.mutate({ decision: choice, reason: reason.trim() || undefined })
          }}
        >
          <fieldset className="grid grid-cols-2 gap-2">
            <legend className="sr-only">Decision</legend>
            {(['APPROVE', 'REJECT'] as const).map((d) => (
              <button
                key={d}
                type="button"
                aria-pressed={choice === d}
                onClick={() => setChoice(d)}
                className={clsx(
                  'rounded px-3 py-2 text-sm font-medium ring-1',
                  choice === d ? (d === 'APPROVE' ? 'bg-confirm text-white ring-confirm' : 'bg-risk text-white ring-risk') : 'ring-paper-rule hover:bg-paper-deep',
                )}
              >
                {d === 'APPROVE' ? 'Approve' : 'Reject'}
              </button>
            ))}
          </fieldset>
          <Field
            label={needsReason ? 'Reason (required)' : 'Note (optional)'}
            htmlFor="reason"
            error={choice && tooShort && reason.length > 0 ? 'at least 10 characters' : undefined}
            hint={override ? 'You are overriding the model. Explain why; this is recorded in the audit trail.' : undefined}
          >
            <Textarea id="reason" rows={3} value={reason} onChange={(e) => setReason(e.target.value)} aria-invalid={!!(choice && tooShort && reason.length > 0)} />
          </Field>
          {decide.isError && <p role="alert" className="text-sm text-risk-deep">{toApiError(decide.error).message}</p>}
          <Button type="submit" className="w-full" disabled={!choice || tooShort} busy={decide.isPending}>
            Record decision
          </Button>
        </form>
      )}
    </Card>
  )
}
