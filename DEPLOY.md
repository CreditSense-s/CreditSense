# Deploying CreditSense (Vercel + Render + Supabase)

```
Browser -> Vercel (website) -> Render "creditsense-api" (Java) -> Render "creditsense-ml" (Python)
                                          |
                                          +-> Supabase (Postgres)
```

Everything below is free. Do the steps in this order.

## 1. Supabase (the database)
1. supabase.com -> New project. Save the **database password** you choose.
2. Project -> **Connect** -> *Session pooler*. Note the host (`aws-0-<region>.pooler.supabase.com`), port `5432`, and user (`postgres.<project-ref>`).
3. Build the JDBC URL (encrypted connection is required):
   `jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require`
   Username: `postgres.<project-ref>`. Password: your database password.
   The API keeps at most 3 connections open (`DB_POOL_SIZE`) so it stays inside the free tier. Tables are created automatically on first start.

## 2. Google sign-in
1. console.cloud.google.com -> create a project -> **APIs & Services -> OAuth consent screen** (External, add your name/email, publish to *Production* so anyone with Gmail can sign in).
2. **Credentials -> Create credentials -> OAuth client ID -> Web application.**
3. *Authorized JavaScript origins*: your Vercel URL (e.g. `https://creditsense.vercel.app`) and `http://localhost:5173` for local testing. Leave redirect URIs empty.
4. Copy the **Client ID** (ends in `.apps.googleusercontent.com`). It is not a secret.

## 3. Render (Java API + ML)
1. render.com -> New -> **Blueprint** -> connect the GitHub repo. It reads `render.yaml` and creates `creditsense-ml` and `creditsense-api`.
2. When asked, fill in: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` (from step 1) and `GOOGLE_CLIENT_ID` (step 2).
3. `ADMIN_EMAILS` is already `siddhusiddhu1712@gmail.com`. Add officers (comma-separated) in `OFFICER_EMAILS` if you want any.
4. If Render gives the services different URLs than `creditsense-api.onrender.com` / `creditsense-ml.onrender.com` (name already taken), update `ML_SERVICE_URL` on the API service and the `destination` in `frontend/vercel.json`.
5. Free services sleep after ~15 min idle. The first request after that takes a few minutes; the site shows "Server waking up…" and continues on its own. The model retrains on every cold start of the ML service because the free plan has no disk.

## 4. Vercel (the website)
1. vercel.com -> Add New Project -> import the repo -> set **Root Directory** to `frontend`. Framework preset: Vite (build `npm run build`, output `dist`).
2. Deploy, then put the resulting URL into the Google client's *Authorized JavaScript origins* (step 2.3).
`frontend/vercel.json` forwards `/api/*` to Render, routes all pages to the app, and sets security headers that allow Google's sign-in button.

## 5. Check it
- Open the site -> "Sign in with Google" -> sign in as siddhusiddhu1712@gmail.com -> you see the admin dashboard (queue, portfolio, audit).
- Sign in with any other Gmail -> you see only the application form and your own applications.
- Submit a test application with made-up values (GSTIN `27AAPFU0939F1ZV` passes the checksum) -> you immediately see the compliance checks, risk level and per-factor explanation.

## Security model
- Public deployments run with `APP_DEMO_MODE` off: no demo accounts, no password sign-in. The API refuses to start without `GOOGLE_CLIENT_ID`, a real `JWT_SECRET` and at least one admin.
- Only emails in `ADMIN_EMAILS` / `OFFICER_EMAILS` get elevated roles. Everyone else is an applicant and sees only their own applications.
- The ML service requires `X-API-Key`, known only to the API.

## Privacy (DPDP Act)
The app shows a research-demo notice and privacy notice, requires a consent checkbox on every application, asks users not to enter real identifiers, and offers **Delete my data**, which erases the account and all its applications.

## Local development
`docker compose up --build` runs everything with `APP_DEMO_MODE=true` (seeded demo accounts, password sign-in) at http://localhost:5173. Demo mode must never be enabled on a public server.
