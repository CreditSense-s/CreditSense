import { useQueryClient } from '@tanstack/react-query'
import clsx from 'clsx'
import { ArrowRight, Info } from 'lucide-react'
import { useEffect } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Documents, PipelineStepper, ScoreBlock } from '../components/ApplicationParts'
import { ComplianceChecklist } from '../components/ComplianceChecklist'
import { Ledger } from '../components/Ledger'
import { RiskBadge, StatusBadge } from '../components/badges'
import { Button, Card, Empty, ErrorState, Loading, PageHeader } from '../components/ui'
import { featureMeta } from '../lib/features'
import { date, lakh } from '../lib/format'
import { CHECK_LABEL, purposeLabel } from '../lib/labels'
import { keys, useApplication, useMyApplications } from '../lib/queries'
import type { Detail } from '../lib/types'

const lowerFirst = (s: string) => s.charAt(0).toLowerCase() + s.slice(1)

export function ApplicantDashboard() {
  const mine = useMyApplications()
  const [params, setParams] = useSearchParams()
  const focus = Number(params.get('focus')) || mine.data?.[0]?.id
  const detail = useApplication(focus)
  const queryClient = useQueryClient()
  const focusedStatus = detail.data?.status
  useEffect(() => {
    if (focusedStatus) queryClient.invalidateQueries({ queryKey: keys.mine })
  }, [focusedStatus, queryClient])

  return (
    <>
      <PageHeader
        title="My loans"
        subtitle="Track each application through the compliance gate, risk scoring and the lender's decision."
        action={<Link to="/applicant/apply"><Button variant="ghost" className="border-rule bg-paper">New application</Button></Link>}
      />
      {mine.isLoading && <Loading />}
      {mine.isError && <ErrorState error={mine.error} onRetry={() => mine.refetch()} />}
      {mine.data && mine.data.length === 0 && (
        <Card>
          <Empty>
            You have no applications yet. <Link to="/applicant/apply" className="font-medium text-ink underline">Start one</Link>.
          </Empty>
        </Card>
      )}
      {mine.data && mine.data.length > 0 && (
        <div className="grid gap-5 lg:grid-cols-[18rem_minmax(0,1fr)]">
          <nav aria-label="My applications" className="space-y-2">
            {mine.data.map((a) => (
              <button
                key={a.id}
                onClick={() => setParams({ focus: String(a.id) })}
                aria-current={a.id === focus ? 'true' : undefined}
                className={clsx(
                  'w-full rounded-md p-3 text-left ring-1 transition-colors',
                  a.id === focus ? 'bg-paper text-ink ring-amber' : 'bg-panel-raised text-paper ring-rule hover:ring-fog',
                )}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="num text-xs">{a.reference}</span>
                  <StatusBadge status={a.status} />
                </div>
                <p className="num mt-2 text-lg">{lakh(a.amountRequested)}</p>
                <p className={clsx('text-xs', a.id === focus ? 'text-muted' : 'text-fog')}>
                  {purposeLabel(a.purpose)} · {date(a.submittedAt)}
                </p>
              </button>
            ))}
          </nav>
          <div>
            {detail.isLoading && <Loading />}
            {detail.isError && <ErrorState error={detail.error} onRetry={() => detail.refetch()} />}
            {detail.data && <ApplicationView app={detail.data} />}
          </div>
        </div>
      )}
    </>
  )
}

function ApplicationView({ app }: { app: Detail }) {
  const failures = app.complianceChecks.filter((c) => !c.passed)
  const r = app.riskAssessment
  const top = r ? [...r.contributions].sort((a, b) => b.shapContribution - a.shapContribution) : []
  const raises = top.filter((c) => c.shapContribution >= 0.005).slice(0, 2)
  const lowers = [...top].reverse().filter((c) => c.shapContribution <= -0.005).slice(0, 2)

  return (
    <div className="space-y-5">
      <Card>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <p className="num text-xs text-muted">{app.reference}</p>
            <h2 className="mt-1 text-xl font-semibold">
              <span className="num">{lakh(app.amountRequested)}</span> for {purposeLabel(app.purpose).toLowerCase()}
            </h2>
            <p className="text-sm text-muted">{app.tenureMonths} months · submitted {date(app.submittedAt)}</p>
          </div>
          <StatusBadge status={app.status} />
        </div>
        <div className="mt-4 border-t border-paper-rule pt-4">
          <PipelineStepper app={app} />
        </div>
      </Card>

      {app.status === 'COMPLIANCE_FAILED' && (
        <Card title="Why this application stopped" className="ring-2 ring-risk/60">
          <p className="mb-3 text-sm">
            This is not a credit score. The application did not pass the mandatory compliance checks, so it was not sent to the risk model.
          </p>
          <ul className="space-y-2">
            {failures.map((f) => (
              <li key={f.checkType} className="rounded bg-risk/10 p-3 text-sm">
                <p className="font-medium text-risk-deep">{CHECK_LABEL[f.checkType]}</p>
                <p>{f.reason}</p>
              </li>
            ))}
          </ul>
          <p className="mt-3 text-sm text-muted">Correct the details or documents and submit a new application.</p>
        </Card>
      )}

      {app.status === 'MANUAL_REVIEW' && (
        <Card>
          <p role="status" className="flex items-start gap-2 text-sm">
            <Info aria-hidden className="mt-0.5 size-4 shrink-0 text-amber" />
            {app.awaitingModel
              ? 'Your application passed the compliance checks. The risk model is starting up (the free demo server sleeps when nobody uses it); your score will appear here by itself, usually within two minutes. You can leave this page open.'
              : 'Your application passed the compliance checks and is with an underwriter for a manual review. No action is needed from you.'}
          </p>
        </Card>
      )}

      {r && (
        <Card title="Your risk score, explained">
          <ScoreBlock app={app} />
          {(raises.length > 0 || lowers.length > 0) && (
            <p className="mt-4 text-sm">
              {raises.length > 0 && <>Main factors raising risk: <strong>{raises.map((c) => lowerFirst(featureMeta(c.feature).says(c.featureValue))).join('; ')}</strong>. </>}
              {lowers.length > 0 && <>Working in your favour: <strong>{lowers.map((c) => lowerFirst(featureMeta(c.feature).says(c.featureValue))).join('; ')}</strong>.</>}
            </p>
          )}
          <div className="mt-5">
            <Ledger baseValue={r.baseValue} contributions={r.contributions} probabilityOfDefault={r.probabilityOfDefault} audience="applicant" />
          </div>
        </Card>
      )}

      {app.decision && (
        <Card title="Decision">
          <div className="flex flex-wrap items-center gap-3">
            <span className={clsx('text-lg font-semibold', app.decision.decision === 'APPROVE' ? 'text-confirm-deep' : 'text-risk-deep')}>
              {app.decision.decision === 'APPROVE' ? 'Approved' : 'Not approved'}
            </span>
            <span className="text-sm text-muted">on {date(app.decision.decidedAt)}</span>
            {r && <RiskBadge band={r.riskBand} />}
          </div>
          {app.decision.reason && <p className="mt-2 text-sm">Lender's note: {app.decision.reason}</p>}
          {app.outcome && (
            <p className="mt-2 text-sm text-muted">
              Loan outcome recorded: <strong className={app.outcome === 'REPAID' ? 'text-confirm-deep' : 'text-risk-deep'}>{app.outcome === 'REPAID' ? 'repaid on schedule' : 'defaulted'}</strong>
            </p>
          )}
        </Card>
      )}

      <div className="grid gap-5 md:grid-cols-2">
        <Card title="Compliance checks">
          <ComplianceChecklist checks={app.complianceChecks} />
        </Card>
        <Card title="Documents">
          <Documents app={app} />
        </Card>
      </div>
      {app.status === 'COMPLIANCE_FAILED' && (
        <Link to="/applicant/apply" className="inline-flex items-center gap-1 text-sm font-medium text-amber hover:underline">
          Start a corrected application <ArrowRight aria-hidden className="size-4" />
        </Link>
      )}
    </div>
  )
}
