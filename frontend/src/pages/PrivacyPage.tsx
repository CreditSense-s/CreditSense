import { Link } from 'react-router-dom'
import { useAuth, homeFor } from '../store/auth'

export function PrivacyPage() {
  const user = useAuth((s) => s.user)
  return (
    <main className="mx-auto max-w-2xl p-6 text-paper">
      <h1 className="font-display text-2xl font-semibold">Privacy notice</h1>
      <p className="mt-1 text-sm text-fog">CreditSense research demo</p>
      <div className="mt-6 space-y-5 text-sm leading-relaxed">
        <section>
          <h2 className="font-semibold">What this is</h2>
          <p>
            CreditSense is a research and teaching demonstration of an explainable lending-risk workflow. The risk score comes from a
            simulated model trained on synthetic data. It is not a credit decision, no lender will see it, and nothing here can lead to a loan.
          </p>
        </section>
        <section>
          <h2 className="font-semibold">Please use made-up details</h2>
          <p>
            Do not enter your real PAN, GSTIN, Udyam number, bank figures or address. A valid-looking made-up value works the same.
            The app checks the format, not whether the number belongs to anyone.
          </p>
        </section>
        <section>
          <h2 className="font-semibold">What we store, and why</h2>
          <ul className="list-disc space-y-1 pl-5">
            <li>Your Google account email and name (to sign you in).</li>
            <li>Whatever you type into an application, and the compliance and risk results computed from it.</li>
            <li>An audit log of actions (who did what, and when).</li>
          </ul>
          <p className="mt-2">This is used only to run the demo. It is not sold or shared, and it is visible to you, the demo's loan officers and its administrator.</p>
        </section>
        <section>
          <h2 className="font-semibold">Your choices (DPDP Act, 2023)</h2>
          <ul className="list-disc space-y-1 pl-5">
            <li>You give consent with the tick box when you submit an application, and you can withdraw it at any time.</li>
            <li>
              <strong>Delete my data</strong> (left menu) erases your account and every application you submitted. The audit log is append-only and
              stays as a security record; after erasure it is no longer linked to a live account.
            </li>
          </ul>
        </section>
      </div>
      <Link to={user ? homeFor(user.role) : '/login'} className="mt-8 inline-block text-sm font-medium text-amber underline">
        Back
      </Link>
    </main>
  )
}
