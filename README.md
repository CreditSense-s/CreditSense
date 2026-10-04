# CreditSense

Explainable, compliance-gated credit risk assessment for MSME (micro, small and medium enterprise) lending.

An application first passes a **deterministic compliance gate** (GSTIN check character, KYC completeness, fraud rules).
Only then does a **trained XGBoost model** score it, and every score comes with a **SHAP explanation ledger**.
The ledger is a list of signed line items that add up exactly to the score. A score without a valid explanation is
never shown to anyone. Loan officers decide, with a mandatory reason when they override the model. Administrators
watch the portfolio and the model's live performance and run the champion/challenger retraining loop. Every
state change is written to an append-only audit trail.

## Live demo

> **Please wait 3 to 4 minutes on your first visit.** The free Render servers go to sleep when idle and take about 3 to 4
> minutes to start. The sign-in box shows "Server waking up" and continues by itself; there is nothing to click. Do not refresh
> repeatedly.

| What | Where | Hosted on |
|---|---|---|
| Web app | https://creditsense-seven.vercel.app | Vercel |
| API | https://creditsense-api-asn0.onrender.com (`/actuator/health`, `/api/auth/options`) | Render |
| ML service | https://creditsense-ml.onrender.com (`/health`) | Render |
| Database | PostgreSQL | Supabase |
| Sign-in | Google (admin: `siddhusiddhu1712@gmail.com`) | Google Cloud OAuth |

This is a **research demo**: the risk score comes from a simulated model trained on synthetic data and is not a real lending
decision. Please do not enter real PAN, GSTIN or other personal details; use made-up values (for example PAN `AKTPR4821K`,
GSTIN `36AKTPR4821K1ZH`, Udyam `UDYAM-TS-02-0012345`). The in-app privacy notice is at `/privacy`, and applicants can erase
their data with **Delete my data**.

All three hosts are on free plans. The API and ML service **sleep after about 15 minutes idle**; the first visit afterwards shows
"Server waking up" for a few minutes. Render starts a sleeping service only for a visit from a browser, not for a request from
the API, so every page of the site opens the ML service's `/health` link in a hidden frame: the model wakes while the visitor
signs in and stays awake while the site is open. An application submitted before the model is up waits in manual review and is
scored automatically as soon as the model answers, usually within two minutes; the page updates by itself. A free Supabase project
pauses after a week of no activity.

How it was deployed, and how to redeploy, is in [DEPLOY.md](DEPLOY.md): Google sign-in, Supabase, Render (`render.yaml`) and
Vercel (`frontend/vercel.json`). The website is deployed with the Vercel CLI (`npx vercel --prod` from `frontend/`); the API and ML
service redeploy from Render when this branch is pushed.

## Quick start

You need **Git** and **Docker Desktop** (Windows or macOS) or Docker Engine with the Compose plugin (Linux).
A laptop with 8 GB RAM and about 10 GB of free disk is enough: the running system uses under 1 GB of memory.
On Windows, let Docker Desktop enable WSL 2 when it asks, then restart.

```bash
git clone https://github.com/5ai711/CreditSense.git
cd CreditSense
docker compose up --build
```

The first run downloads the base images and dependencies and trains the model, so it takes 5 to 15 minutes
depending on your connection; later starts take under a minute. The system is ready when the log prints
`demo seed complete: 51 applications` (or when `docker compose ps` shows every service as healthy).
Stop it with `Ctrl+C` or `docker compose down`; `docker compose down -v` also deletes the data for a fresh start.
Ports 3000 and 8080 must be free.

| What | Where |
|---|---|
| Web app | http://localhost:3000 |
| API docs (Swagger UI) | http://localhost:8080/swagger-ui.html |

The first build trains the model inside the ML image (about a minute), and the backend seeds demo data on first start.

**Demo accounts** (password `Demo@1234`):

| Role | Email | Start at |
|---|---|---|
| Applicant | `applicant@creditsense.demo` | My loans: an approved loan, one awaiting a decision, one stopped by the KYC rule |
| Loan officer | `officer@creditsense.demo` | Queue: scored applications waiting for a decision, each with its ledger |
| Admin | `admin@creditsense.demo` | Portfolio: KPIs, risk mix, default trend, champion vs challenger; Audit trail |

The seed pushes about 50 applications through the real pipeline over a simulated year. It includes one example of
each compliance failure: blacklisted PAN, PAN reused on another account, address in a different state than the GST
registration, GSTIN with a typing error, too many applications in 30 days, and incomplete KYC. Older approved loans
are matured with simulated outcomes, and one champion/challenger retrain is run.

To see the manual-review path, stop the model (`docker compose stop ml-service`) and submit an application as the
applicant. The circuit breaker opens and the application goes to `MANUAL_REVIEW` with the reason recorded.
`docker compose start ml-service` brings scoring back.

## Architecture

```
┌──────────────────────┐  REST   ┌───────────────────────────┐  REST (features only)  ┌──────────────────────────┐
│ React + Vite + TS     │◄──────►│ Spring Boot core API       │◄──────────────────────►│ FastAPI ML service        │
│ Tailwind, React Query │ (nginx │ auth · compliance gate ·   │ circuit breaker, retry, │ XGBoost + TreeSHAP ·      │
│ applicant / officer / │  /api) │ orchestration · audit      │ time limiter            │ WOE baseline · registry · │
│ admin dashboards      │        └─────────────┬─────────────┘                         │ champion/challenger       │
└──────────────────────┘                      ▼                                        └────────────┬─────────────┘
                                   PostgreSQL (Flyway schema,                             /data volume: training set,
                                   SHAP ledgers as JSONB,                                 holdout, feedback outcomes,
                                   append-only audit log)                                 versioned model registry
```

* **backend/**: the system of record and the only service the browser talks to. It owns authentication (15-minute JWT
  access tokens, rotating refresh tokens in an `HttpOnly` cookie, BCrypt), role checks with `@PreAuthorize` and ownership checks, the compliance
  gate, calls to the ML service, decisions, portfolio analytics and the audit trail.
* **ml-service/**: stateless from the caller's view. It receives an engineered feature vector (never names, PAN, GSTIN
  or documents) and returns a probability of default, a risk band, the model version and the full ledger. It also
  simulates loan outcomes and runs retraining. See [ml-service/README.md](ml-service/README.md).
* **frontend/**: role-based dashboards built as a risk-desk instrument. The signature element is the hand-built
  ledger waterfall.
* **research/**: the reproducible experiments behind the accompanying paper (same data generator as the ML service).

### Application lifecycle

```
SUBMITTED ─gate─► COMPLIANCE_FAILED            (terminal; the failing rules are shown, it is not a "low score")
    └─passed─► COMPLIANCE_REVIEW ─scored + explained─► RISK_SCORED ─officer─► APPROVED / REJECTED
                    └─ML down or explanation invalid─► MANUAL_REVIEW ─underwriter─► APPROVED / REJECTED
```

## Design decisions that matter

* **Compliance is never learned.** GST validity (structure, state code, Luhn mod-36 check character, PAN match),
  KYC completeness (required PAN, Udyam, address proof and at least 6 months of bank statements, weighted score
  of 0.8 or more) and fraud rules (duplicate PAN, address state mismatch, blacklist, velocity) are pure functions with
  their own unit tests. A failure is terminal and explained.
* **No unexplained scores.** The ML service withholds a prediction (HTTP 503) if the SHAP decomposition does not add
  up. The backend re-validates the contract: every feature present once, contributions finite, base value plus
  contributions equal to the logit of the PD, and the band consistent with the PD. A violation routes the application
  to manual review instead of storing the score.
* **Calibrated scores that stay explainable.** Class weighting inflates raw XGBoost probabilities, so the margin is
  recalibrated with Platt scaling. Because that map is affine, the SHAP ledger stays exact on the calibrated scale.
* **Graceful degradation.** The ML client runs behind Resilience4j `TimeLimiter` (3 s), `CircuitBreaker` and `Retry`.
  Connection errors and 5xx responses are retried; timeouts are not, so no applicant waits longer than one attempt.
  If the model is unreachable, the application moves to `MANUAL_REVIEW` with the reason recorded. Measured on the
  compose stack: about 3 s for the first few submissions, then about 35 ms once the breaker is open, and scoring
  resumes on its own after the model comes back (`research/platform_bench.py`).
* **Auditability.** Every state change writes an audit row with actor, action and before/after state. A database
  trigger rejects `UPDATE` and `DELETE` on `audit_logs`. Automatic pipeline steps are attributed to `system`, and
  officer re-runs to the officer.
* **Model lifecycle.** Matured loans get simulated 12-month outcomes from the same ground-truth process as the
  training data. `/retrain` trains a challenger on base data plus feedback and promotes it only if its AUC on a
  holdout that no model trains on beats the champion's. Every comparison is logged and charted. Training runs off
  the scoring path, so predictions keep flowing during a retrain, and a second retrain request gets `409`. Each
  version records a SHA-256 fingerprint of the exact data it was trained on, shown on the admin portfolio page.

* **Sessions.** The access token lives only in the page's memory. The refresh token is an `HttpOnly`,
  `SameSite=Strict` cookie scoped to `/api/auth`, so page scripts cannot read it and other sites cannot send it. It
  rotates on every use, and presenting a used token revokes all of that user's sessions. After a reload the web app
  restores the session from the cookie. Tabs take turns refreshing (Web Locks API), so several tabs never look like a
  replayed token. Signing out, or signing in as someone else, in one tab carries over to the others, and cached data
  is cleared whenever the signed-in user changes. Expired refresh tokens are purged daily. API clients without
  cookies can still send `refreshToken` in the request body.
* **Sign-in protection.** Five failed sign-ins for one account, or twenty from one address, within 15 minutes
  return `429` with `Retry-After` until the window passes (even with the right password). Unknown emails take as
  long to reject as known ones.

## Deploying to a server

```bash
cp .env.example .env        # then set DB_PASSWORD, JWT_SECRET, ML_SERVICE_TOKEN, PUBLIC_ORIGIN
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build --wait
```

The production override:

* runs the backend with the `prod` profile, which **refuses to start** with a missing secret, a secret published in
  this repository, an empty ML service token or a wildcard CORS origin;
* publishes only the web app (port `HTTP_PORT`, default 80). The API, database and ML service are reachable only on
  the internal network, and Swagger UI is off (`API_DOCS_ENABLED=true` turns it back on);
* does not seed demo data unless `SEED_DEMO_DATA=true`;
* marks the refresh cookie `Secure`, so the site must be served over HTTPS (browsers make an exception for
  `localhost`). `AUTH_COOKIE_SECURE=false` allows a plain-HTTP trial on another host.

Terminate TLS in front of the web container (a load balancer, Caddy, or nginx with certificates). The web container
sends a strict Content-Security-Policy and the other security headers, gzips assets, caches hashed assets for a year
and never caches `index.html`, and re-resolves the backend's address, so restarting the backend alone is safe. All
services restart automatically, have health checks and rotate their logs; the backend shuts down gracefully.

Back up the `pgdata` volume (applications and the audit trail) and the `mldata` volume (model registry, training
data and outcomes).

## Running and testing each part

```bash
# ML service (Python 3.11)
cd ml-service && pip install -r requirements-dev.txt && pytest
ML_DATA_DIR=data uvicorn creditsense_ml.api:app --port 8000

# Backend (Java 21). Integration tests use Testcontainers and need Docker;
# run only the unit tests with -Punit
cd backend && mvn test
ML_SERVICE_TOKEN= SEED_DEMO_DATA=true mvn spring-boot:run   # expects Postgres on localhost:5432

# Frontend (Node 22)
cd frontend && npm ci && npm test && npm run dev            # proxies /api to localhost:8080
```

| Suite | What it covers |
|---|---|
| `ml-service/tests` | generator properties, additivity of every explanation, withheld scores, persisted metrics, feedback de-duplication, champion/challenger promotion, scoring during a retrain, training-data fingerprints, API validation and service token |
| `backend` unit tests | GSTIN (every single-character substitution caught), each compliance rule and its edge cases, the gate service, explanation contract, feature derivation, risk orchestration and fallback, ML client retry, timeout and circuit breaker, decision rules, JWT, sign-in throttling, production secret checks |
| `backend` integration test | real PostgreSQL: applicant to decision with the audit sequence, ownership (404 for another applicant's application), compliance failure blocking scoring, ML outage leading to manual review, field-level validation errors, append-only audit trigger, refresh-token rotation and reuse detection through the `HttpOnly` cookie, purge of expired tokens, sign-in throttling |
| `frontend` | GSTIN rules identical to the backend's, the ledger balancing and labelling, session restore (one refresh for parallel requests, sign-out on a dead cookie, no token in storage), following sign-outs in other tabs, and the error screen |

GitHub Actions (`.github/workflows/ci.yml`) runs all three suites on every push, then builds the images, starts the
whole stack and signs in through the web proxy.

## API

| Method | Path | Role |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout` (refresh token in the `cs_refresh` cookie) | public |
| POST | `/api/applications` (submit; runs the gate, then scoring) | applicant |
| GET | `/api/applications/mine` | applicant |
| GET | `/api/applications/{id}` | owner or staff |
| GET | `/api/applications?status=&band=&q=` | officer, admin |
| POST | `/api/applications/{id}/compliance-check`, `/risk-assessment` | officer, admin |
| POST | `/api/applications/{id}/decision` | officer |
| GET | `/api/portfolio/analytics` | admin |
| GET | `/api/audit-logs` | admin |
| POST | `/api/admin/simulate-maturity`, `/api/admin/retrain`; GET `/api/admin/model` | admin |

## Configuration

Copy `.env.example` to `.env`. The defaults are for your own machine; the production override above rejects them.
Set `SEED_DEMO_DATA=false` for an empty system.

## Honest limitations

* The training data are simulated from a documented generator (see `ml-service/creditsense_ml/generator.py`). The
  model's metrics describe that population, not a real loan book.
* GST and KYC checks validate structure, check characters and internal consistency. They do not call the live GSTN,
  NSDL or Udyam registries. The blacklist holds demo entries.
* Tabs coordinate refreshes with the Web Locks API, which browsers offer only on HTTPS or `localhost`. Over plain
  HTTP on another host, two tabs refreshing at the same instant can end the session and ask the user to sign in again.
* Sign-in throttling counts in memory, per backend instance. Several instances behind a load balancer would each keep
  their own counts; a shared store (for example Redis) would be needed then.

## Team

Developed at the Department of Computer Science and Engineering, Anurag University, Hyderabad.

* C. Saitarun
* Siddardha Chiluveru ([@Siddardha-CH](https://github.com/Siddardha-CH))
* M. Sanjay Reddy
* Guide: Dr. N. Suresh Rao

The accompanying paper, "CreditSense: A Compliance-Gated and Explainable Machine Learning Framework for MSME
Credit Risk Assessment", is by the same authors; the experiments behind it are in [research/](research/).
