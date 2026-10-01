# CreditSense

CreditSense is an explainable MSME lending-risk workflow. Compliance checks are deterministic and execute before scoring; every model score is stored with a SHAP feature ledger.

## Run

Install Docker Desktop, then run:

```bash
docker compose up --build
```

Open `http://localhost:5173`. The API is at `http://localhost:8080`, Swagger at `/swagger-ui/index.html`, and the ML API at `http://localhost:8000/docs`.

Demo accounts are seeded on API startup **only in demo mode** (`APP_DEMO_MODE=true`, which docker compose sets for local use). Public deployments use Google sign-in instead; see [DEPLOY.md](DEPLOY.md).

| Role | Email | Password |
|---|---|---|
| Applicant | applicant@creditsense.demo | `DemoPass123!` |
| Loan officer | officer@creditsense.demo | `DemoPass123!` |
| Admin | admin@creditsense.demo | `DemoPass123!` |

## Architecture

`frontend` is a React/Vite risk desk. `backend` is the system of record: JWT/RBAC, audited application transitions, compliance, and ML orchestration. `ml-service` is a stateless FastAPI service that trains a synthetic-data XGBoost champion and produces SHAP explanations. The frontend never calls ML directly.

The compliance gate validates GSTIN structure/checksum, KYC documents, duplicate PAN, blacklist/velocity and address consistency. A compliance failure cannot be scored. If scoring is unavailable, the API records `MANUAL_REVIEW` rather than returning a score without an explanation.

## Development

Run the ML service with `uvicorn app.main:app --reload` after `pip install -r requirements.txt`. Run the backend with `mvn spring-boot:run`. Run the web app with `npm install && npm run dev`.

The model service creates a documented 20,000-row synthetic training set on first start, persists model metadata, compares a challenger to the champion during retraining, and promotes only on improved ROC-AUC.
