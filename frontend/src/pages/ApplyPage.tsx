import { zodResolver } from '@hookform/resolvers/zod'
import clsx from 'clsx'
import { AlertTriangle, CheckCircle2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useForm, useWatch, type FieldPath } from 'react-hook-form'
import { Link, useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { Button, Card, Field, Input, PageHeader, Select } from '../components/ui'
import { toApiError } from '../lib/api'
import { lakh } from '../lib/format'
import { GSTIN_SHAPE, PAN_SHAPE, STATES, UDYAM_SHAPE, gstinStatus, stateName } from '../lib/gstin'
import { PURPOSES, SECTORS, purposeLabel, sectorLabel } from '../lib/labels'
import { useApplication, useMyApplications, useSubmitApplication } from '../lib/queries'
import type { LoanPurpose, Sector } from '../lib/types'

const num = (min: number, max: number) =>
  z.number({ error: 'enter a number' }).min(min, `at least ${min}`).max(max, `at most ${max}`)
const int = (min: number, max: number) => num(min, max).int('whole number')
const upper = (re: RegExp, msg: string) => z.string().trim().transform((s) => s.toUpperCase()).pipe(z.string().regex(re, msg))

/** Mirrors the backend's request validation; the compliance gate still has the final word. */
const schema = z.object({
  business: z.object({
    businessName: z.string().trim().min(2, 'enter the business name').max(200),
    ownerName: z.string().trim().min(2, 'enter the owner name').max(120),
    sector: z.enum(SECTORS.map((s) => s.value) as [Sector, ...Sector[]]),
    pan: upper(PAN_SHAPE, 'PAN looks like ABCPE1234F'),
    gstin: upper(GSTIN_SHAPE, 'GSTIN has 15 characters: state code, PAN, entity number, Z, check character'),
    udyamNumber: z.string().trim().transform((s) => s.toUpperCase()).pipe(z.string().regex(UDYAM_SHAPE, 'looks like UDYAM-TS-02-0012345')),
    addressLine: z.string().trim().min(5, 'enter the business address').max(300),
    city: z.string().trim().min(2, 'enter the city').max(100),
    stateCode: z.string().regex(/^\d{2}$/, 'choose a state'),
    pincode: z.string().regex(/^[1-9]\d{5}$/, 'six-digit PIN code'),
    businessStartDate: z.string().min(1, 'choose a date').refine((d) => new Date(d) <= new Date(), 'cannot be in the future'),
  }),
  loan: z.object({
    amount: num(0.5, 5000),
    purpose: z.enum(PURPOSES.map((p) => p.value) as [LoanPurpose, ...LoanPurpose[]]),
    tenureMonths: int(3, 120),
  }),
  financials: z.object({
    monthlyRevenues: z.array(num(0.01, 100000)).length(6),
    existingDebt: num(0, 1_000_000),
    avgMonthlyInflow: num(0.01, 1_000_000),
    avgMonthlyOutflow: num(0.01, 1_000_000),
    avgBankBalance: num(0, 1_000_000),
    gstOnTimeFilingPct: num(0, 100),
    tradeReferences: int(0, 500),
    delinquencyEvents: int(0, 100),
    digitalTxnPerMonth: int(0, 1_000_000),
  }),
  documents: z.object({
    addressProofRef: z.string().trim().regex(/^[A-Za-z0-9/-]{6,30}$/, '6–30 letters, digits, / or -'),
    bankStatementRef: z.string().trim().min(3, 'enter the statement reference').max(50),
    bankMonths: int(1, 60),
    includeGstCertificate: z.boolean(),
  }),
  consent: z.boolean().refine((v) => v, 'tick the box to continue'),
})
type FormIn = z.input<typeof schema>
type FormOut = z.output<typeof schema>

const STEPS: { title: string; fields: FieldPath<FormIn>[] }[] = [
  { title: 'Business', fields: ['business'] },
  { title: 'Loan', fields: ['loan'] },
  { title: 'Financials', fields: ['financials'] },
  { title: 'Documents', fields: ['documents'] },
  { title: 'Review', fields: ['consent'] },
]

const MONTHS = (() => {
  const out: string[] = []
  const d = new Date()
  for (let i = 6; i >= 1; i--) {
    const m = new Date(d.getFullYear(), d.getMonth() - i, 1)
    out.push(m.toLocaleDateString('en-IN', { month: 'short', year: '2-digit' }))
  }
  return out
})()

export function ApplyPage() {
  const [step, setStep] = useState(0)
  const navigate = useNavigate()
  const submit = useSubmitApplication()
  const mine = useMyApplications()
  const previous = useApplication(mine.data?.[0]?.id)

  const form = useForm<FormIn, unknown, FormOut>({
    resolver: zodResolver(schema),
    mode: 'onTouched',
    defaultValues: {
      business: { sector: 'MANUFACTURING', stateCode: '36', udyamNumber: '', businessStartDate: '' },
      loan: { purpose: 'WORKING_CAPITAL', tenureMonths: 24 },
      documents: { includeGstCertificate: true, bankMonths: 12 },
      consent: false,
    },
  })
  const { register, formState, trigger, control, reset, getValues } = form
  const e = formState.errors

  // Returning applicants start from their saved business profile.
  useEffect(() => {
    const b = previous.data?.business
    if (b && !formState.isDirty) {
      reset({ ...getValues(), business: { ...b, udyamNumber: b.udyamNumber ?? '' } })
    }
  }, [previous.data, formState.isDirty, reset, getValues])

  const [pan, gstin, stateCode] = useWatch({ control, name: ['business.pan', 'business.gstin', 'business.stateCode'] })
  const gst = gstinStatus(gstin ?? '', pan, stateCode)

  const next = async () => {
    if (await trigger(STEPS[step].fields)) setStep((s) => Math.min(s + 1, STEPS.length - 1))
  }

  const onSubmit = form.handleSubmit(async (v) => {
    const docs = [
      { type: 'PAN', documentNumber: v.business.pan },
      { type: 'UDYAM', documentNumber: v.business.udyamNumber },
      { type: 'ADDRESS_PROOF', documentNumber: v.documents.addressProofRef.toUpperCase() },
      { type: 'BANK_STATEMENT', documentNumber: v.documents.bankStatementRef.toUpperCase(), monthsCovered: v.documents.bankMonths },
      ...(v.documents.includeGstCertificate ? [{ type: 'GST_CERTIFICATE', documentNumber: v.business.gstin }] : []),
    ]
    const body = { business: v.business, loan: v.loan, financials: v.financials, documents: docs, consent: v.consent }
    const result = await submit.mutateAsync(body).catch(() => null)
    if (result) navigate(`/applicant/dashboard?focus=${result.id}`)
  })

  const apiError = submit.error ? toApiError(submit.error) : null
  const values = useWatch({ control }) as FormIn

  return (
    <>
      <PageHeader title="Apply for a business loan" subtitle="Five short steps. Compliance checks run first; then the risk model scores the application and explains why." />
      <ol className="mb-5 grid grid-cols-5 gap-2" aria-label="Progress">
        {STEPS.map((s, i) => (
          <li key={s.title}>
            <button
              type="button"
              onClick={() => i < step && setStep(i)}
              disabled={i > step}
              aria-current={i === step ? 'step' : undefined}
              className={clsx(
                'w-full border-t-2 pt-2 text-left text-xs font-medium',
                i < step && 'border-confirm text-paper',
                i === step && 'border-amber text-paper',
                i > step && 'border-rule text-fog',
              )}
            >
              <span className="num mr-1">{i + 1}</span>
              <span className="hidden sm:inline">{s.title}</span>
            </button>
          </li>
        ))}
      </ol>

      <form onSubmit={onSubmit} noValidate>
        <Card title={STEPS[step].title}>
          {step === 0 && (
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Business name" htmlFor="bn" error={e.business?.businessName?.message}>
                <Input id="bn" {...register('business.businessName')} aria-invalid={!!e.business?.businessName} />
              </Field>
              <Field label="Owner / proprietor" htmlFor="on" error={e.business?.ownerName?.message}>
                <Input id="on" {...register('business.ownerName')} aria-invalid={!!e.business?.ownerName} />
              </Field>
              <Field label="Sector" htmlFor="sec" error={e.business?.sector?.message}>
                <Select id="sec" {...register('business.sector')}>
                  {SECTORS.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
                </Select>
              </Field>
              <Field label="Business start date" htmlFor="bsd" error={e.business?.businessStartDate?.message}>
                <Input id="bsd" type="date" {...register('business.businessStartDate')} aria-invalid={!!e.business?.businessStartDate} />
              </Field>
              <Field label="PAN" htmlFor="pan" error={e.business?.pan?.message}>
                <Input id="pan" className="num uppercase" maxLength={10} {...register('business.pan')} aria-invalid={!!e.business?.pan} />
              </Field>
              <Field label="Udyam registration" htmlFor="ud" error={e.business?.udyamNumber?.message}>
                <Input id="ud" className="num uppercase" placeholder="UDYAM-TS-02-0012345" {...register('business.udyamNumber')} aria-invalid={!!e.business?.udyamNumber} />
              </Field>
              <div className="sm:col-span-2">
                <Field label="GSTIN" htmlFor="gst" error={e.business?.gstin?.message} hint={<GstinHint status={gst} stateCode={stateCode} />}>
                  <Input id="gst" className="num uppercase" maxLength={15} {...register('business.gstin')} aria-invalid={!!e.business?.gstin} />
                </Field>
              </div>
              <div className="sm:col-span-2">
                <Field label="Business address" htmlFor="addr" error={e.business?.addressLine?.message}>
                  <Input id="addr" {...register('business.addressLine')} aria-invalid={!!e.business?.addressLine} />
                </Field>
              </div>
              <Field label="City" htmlFor="city" error={e.business?.city?.message}>
                <Input id="city" {...register('business.city')} aria-invalid={!!e.business?.city} />
              </Field>
              <div className="grid grid-cols-[minmax(0,1fr)_7rem] gap-3">
                <Field label="State" htmlFor="st" error={e.business?.stateCode?.message}>
                  <Select id="st" {...register('business.stateCode')}>
                    {STATES.map((s) => <option key={s.code} value={s.code}>{s.name}</option>)}
                  </Select>
                </Field>
                <Field label="PIN" htmlFor="pin" error={e.business?.pincode?.message}>
                  <Input id="pin" className="num" inputMode="numeric" maxLength={6} {...register('business.pincode')} aria-invalid={!!e.business?.pincode} />
                </Field>
              </div>
            </div>
          )}

          {step === 1 && (
            <div className="grid gap-4 sm:grid-cols-3">
              <Field label="Amount (₹ lakh)" htmlFor="amt" error={e.loan?.amount?.message} hint="1 lakh = ₹1,00,000">
                <Input id="amt" type="number" step="0.01" className="num" {...register('loan.amount', { valueAsNumber: true })} aria-invalid={!!e.loan?.amount} />
              </Field>
              <Field label="Purpose" htmlFor="pur" error={e.loan?.purpose?.message}>
                <Select id="pur" {...register('loan.purpose')}>
                  {PURPOSES.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
                </Select>
              </Field>
              <Field label="Tenure (months)" htmlFor="ten" error={e.loan?.tenureMonths?.message}>
                <Input id="ten" type="number" className="num" {...register('loan.tenureMonths', { valueAsNumber: true })} aria-invalid={!!e.loan?.tenureMonths} />
              </Field>
            </div>
          )}

          {step === 2 && (
            <div className="space-y-5">
              <fieldset>
                <legend className="mb-2 text-xs font-medium tracking-wide text-muted uppercase">Revenue for each of the last six months (₹ lakh)</legend>
                <div className="grid grid-cols-3 gap-3 sm:grid-cols-6">
                  {MONTHS.map((m, i) => (
                    <Field key={m} label={m} htmlFor={`rev${i}`} error={e.financials?.monthlyRevenues?.[i]?.message}>
                      <Input id={`rev${i}`} type="number" step="0.01" className="num" {...register(`financials.monthlyRevenues.${i}`, { valueAsNumber: true })} aria-invalid={!!e.financials?.monthlyRevenues?.[i]} />
                    </Field>
                  ))}
                </div>
              </fieldset>
              <div className="grid gap-4 sm:grid-cols-3">
                <NumField id="debt" label="Existing debt (₹ lakh)" path="financials.existingDebt" form={form} error={e.financials?.existingDebt?.message} step="0.01" />
                <NumField id="inf" label="Avg monthly bank credits (₹ lakh)" path="financials.avgMonthlyInflow" form={form} error={e.financials?.avgMonthlyInflow?.message} step="0.01" />
                <NumField id="outf" label="Avg monthly bank debits (₹ lakh)" path="financials.avgMonthlyOutflow" form={form} error={e.financials?.avgMonthlyOutflow?.message} step="0.01" />
                <NumField id="bal" label="Avg bank balance (₹ lakh)" path="financials.avgBankBalance" form={form} error={e.financials?.avgBankBalance?.message} step="0.01" />
                <NumField id="gstp" label="GST returns filed on time (%)" path="financials.gstOnTimeFilingPct" form={form} error={e.financials?.gstOnTimeFilingPct?.message} step="1" />
                <NumField id="upi" label="Digital payments per month" path="financials.digitalTxnPerMonth" form={form} error={e.financials?.digitalTxnPerMonth?.message} step="1" />
                <NumField id="refs" label="Active trade references" path="financials.tradeReferences" form={form} error={e.financials?.tradeReferences?.message} step="1" />
                <NumField id="dpd" label="Past repayment delays" path="financials.delinquencyEvents" form={form} error={e.financials?.delinquencyEvents?.message} step="1" />
              </div>
            </div>
          )}

          {step === 3 && (
            <div className="grid gap-4 sm:grid-cols-2">
              <p className="text-sm text-muted sm:col-span-2">
                Your PAN card and Udyam certificate are matched against the numbers you entered. Every mandatory document must verify, and the
                weighted completeness score must reach 80%, before the application can be scored.
              </p>
              <Field label="PAN card number" htmlFor="dpan">
                <Input id="dpan" className="num" value={values.business?.pan?.toUpperCase() ?? ''} readOnly />
              </Field>
              <Field label="Udyam certificate number" htmlFor="dud">
                <Input id="dud" className="num" value={values.business?.udyamNumber?.toUpperCase() ?? ''} readOnly />
              </Field>
              <Field label="Address proof reference" htmlFor="apr" error={e.documents?.addressProofRef?.message} hint="e.g. electricity bill account number">
                <Input id="apr" className="num uppercase" {...register('documents.addressProofRef')} aria-invalid={!!e.documents?.addressProofRef} />
              </Field>
              <div className="grid grid-cols-[minmax(0,1fr)_7rem] gap-3">
                <Field label="Bank statement reference" htmlFor="bsr" error={e.documents?.bankStatementRef?.message}>
                  <Input id="bsr" className="num uppercase" {...register('documents.bankStatementRef')} aria-invalid={!!e.documents?.bankStatementRef} />
                </Field>
                <Field label="Months" htmlFor="bm" error={e.documents?.bankMonths?.message}>
                  <Input id="bm" type="number" className="num" {...register('documents.bankMonths', { valueAsNumber: true })} aria-invalid={!!e.documents?.bankMonths} />
                </Field>
              </div>
              <label className="flex items-center gap-2 text-sm sm:col-span-2">
                <input type="checkbox" className="size-4 accent-ink" {...register('documents.includeGstCertificate')} />
                Attach GST registration certificate (optional, adds to completeness)
              </label>
            </div>
          )}

          {step === 4 && (
            <>
              <Review v={values} />
              <label className="mt-4 flex items-start gap-2 rounded bg-paper-deep p-3 text-sm">
                <input type="checkbox" className="mt-0.5 size-4 accent-ink" {...register('consent')} aria-invalid={!!e.consent} />
                <span>
                  I understand this is a <strong>research demo</strong> with a simulated model, I have <strong>not</strong> entered real personal or
                  business identifiers, and I consent to CreditSense storing what I submit. I can delete it at any time with "Delete my data".{' '}
                  <Link to="/privacy" target="_blank" className="underline">Privacy notice</Link>
                </span>
              </label>
              {e.consent && <p role="alert" className="mt-1 text-xs text-risk-deep">{e.consent.message}</p>}
            </>
          )}

          {apiError && (
            <div role="alert" className="mt-4 rounded bg-risk/10 p-3 text-sm text-risk-deep">
              <p className="font-medium">{apiError.message}</p>
              {apiError.fieldErrors?.map((f) => <p key={f.field} className="text-xs">{f.field}: {f.message}</p>)}
            </div>
          )}

          <div className="mt-6 flex justify-between border-t border-paper-rule pt-4">
            <Button type="button" variant="ghost" onClick={() => setStep((s) => Math.max(0, s - 1))} disabled={step === 0}>
              Back
            </Button>
            {step < STEPS.length - 1 ? (
              <Button type="button" onClick={next}>Continue</Button>
            ) : (
              <Button type="submit" busy={submit.isPending}>Submit application</Button>
            )}
          </div>
        </Card>
      </form>
    </>
  )
}

function NumField({ id, label, path, form, error, step }: {
  id: string
  label: string
  path: FieldPath<FormIn>
  form: ReturnType<typeof useForm<FormIn, unknown, FormOut>>
  error?: string
  step: string
}) {
  return (
    <Field label={label} htmlFor={id} error={error}>
      <Input id={id} type="number" step={step} className="num" {...form.register(path, { valueAsNumber: true })} aria-invalid={!!error} />
    </Field>
  )
}

function GstinHint({ status, stateCode }: { status: ReturnType<typeof gstinStatus>; stateCode?: string }) {
  const msg: Record<string, [boolean, string]> = {
    ok: [true, `Check character valid, matches the PAN, registered in ${stateName(stateCode ?? '')}`],
    checksum: [false, "The check character doesn't match: probably a typing error. The compliance gate will reject this GSTIN."],
    'pan-mismatch': [false, 'This GSTIN belongs to a different PAN. The compliance gate will reject it.'],
    'state-mismatch': [false, 'The GSTIN is registered in another state than your address. The fraud rules will flag this.'],
  }
  const m = msg[status]
  if (!m) return <span>15 characters, e.g. 36AKTPR4821K1ZH</span>
  return (
    <span className={clsx('inline-flex items-center gap-1', m[0] ? 'text-confirm-deep' : 'text-risk-deep')}>
      {m[0] ? <CheckCircle2 aria-hidden className="size-3.5" /> : <AlertTriangle aria-hidden className="size-3.5" />}
      {m[1]}
    </span>
  )
}

function Review({ v }: { v: FormIn }) {
  const revenues = (v.financials?.monthlyRevenues ?? []).map(Number)
  const avg = revenues.length ? revenues.reduce((a, b) => a + b, 0) / revenues.length : 0
  const rows: [string, string][] = [
    ['Business', `${v.business?.businessName} (${sectorLabel(v.business?.sector as Sector)})`],
    ['PAN / GSTIN', `${v.business?.pan?.toUpperCase()} / ${v.business?.gstin?.toUpperCase()}`],
    ['Address', `${v.business?.addressLine}, ${v.business?.city}, ${stateName(v.business?.stateCode ?? '')} ${v.business?.pincode}`],
    ['Loan', `${lakh(Number(v.loan?.amount))} for ${purposeLabel(v.loan?.purpose as LoanPurpose)}, ${v.loan?.tenureMonths} months`],
    ['Average monthly revenue', lakh(avg)],
    ['Existing debt', lakh(Number(v.financials?.existingDebt))],
    ['GST filed on time', `${v.financials?.gstOnTimeFilingPct}%`],
    ['Bank statements', `${v.documents?.bankMonths} months`],
  ]
  return (
    <div>
      <dl className="divide-y divide-paper-rule">
        {rows.map(([k, val]) => (
          <div key={k} className="grid gap-1 py-2 sm:grid-cols-[14rem_minmax(0,1fr)]">
            <dt className="text-xs font-medium tracking-wide text-muted uppercase">{k}</dt>
            <dd className="num text-sm break-words">{val}</dd>
          </div>
        ))}
      </dl>
      <p className="mt-4 text-sm text-muted">
        On submission, the compliance gate checks your GSTIN, KYC documents and fraud rules. If everything passes, the risk model scores the
        application and you will see which factors raised or lowered the score.
      </p>
    </div>
  )
}
