# Putting CreditSense online (Vercel + Render + Supabase)

```
Browser -> Vercel (website) -> Render "creditsense-api" (Java) -> Render "creditsense-ml" (Python model)
                                       |
                                       +-> Supabase (Postgres)
```

All three have a free plan. Do the steps in this order. Local use is unchanged: `docker compose up --build` still gives the
seeded demo accounts on http://localhost:3000 (see the README).

## What changes on a public site

- **Sign in with Google only.** Demo accounts and password sign-in are off (`PASSWORD_LOGIN_ENABLED=false`, the `prod` default),
  and the API refuses to start if demo data is switched on.
- **Admin / officers by email.** `ADMIN_EMAILS` (already `siddhusiddhu1712@gmail.com` in `render.yaml`) opens the admin dashboard.
  `OFFICER_EMAILS` lists loan officers. Everyone else who signs in is an applicant and sees only their own applications.
  Roles are re-checked at each sign-in, so removing an address demotes that person.
- **Instant result.** After submitting, the applicant sees the compliance checks, the risk band and the explanation of each factor.
  An officer or admin still records the final decision.
- **Privacy.** A research-demo notice on every screen, a privacy notice page, a required consent checkbox on every application,
  and **Delete my data**, which erases the account and all its applications (DPDP Act, 2023). The append-only audit log is kept.

## 1. Supabase (database)

1. supabase.com -> New project; save the **database password**.
2. Project -> **Connect** -> **Session pooler**. Note the host (`aws-0-<region>.pooler.supabase.com`), port `5432` and user
   `postgres.<project-ref>`.
3. The three values for Render:
   - `DB_URL` = `jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require` (encrypted connection)
   - `DB_USER` = `postgres.<project-ref>`
   - `DB_PASSWORD` = the database password

The API opens at most 3 connections (`DB_POOL_SIZE`), which fits the free tier. Tables are created on first start.
A free Supabase project pauses after a week without activity; unpause it from the dashboard.

## 2. Google sign-in

1. console.cloud.google.com -> new project -> **APIs & Services -> OAuth consent screen**: External, fill the app name and your
   email, then **Publish app** so any Gmail account can sign in.
2. **Credentials -> Create credentials -> OAuth client ID -> Web application.**
3. **Authorized JavaScript origins**: your Vercel address (step 4) and `http://localhost:3000` for local tests. No redirect URI.
4. Copy the **Client ID** (`...apps.googleusercontent.com`). It is public, not a secret.

## 3. Render (API and ML)

1. render.com -> New -> **Blueprint** -> connect the GitHub repo `CreditSense-s/CreditSense`. It reads `render.yaml` and creates
   `creditsense-ml` and `creditsense-api`. The first build trains the model and takes several minutes.
2. When asked, enter `DB_URL`, `DB_USER`, `DB_PASSWORD`, `GOOGLE_CLIENT_ID`, and `CORS_ALLOWED_ORIGINS` (your Vercel address,
   e.g. `https://creditsense.vercel.app`; without it the API rejects the site's requests).
3. If the services get URLs other than `creditsense-api.onrender.com` / `creditsense-ml.onrender.com`, update `ML_BASE_URL` on the API
   and the `destination` in `frontend/vercel.json`.
4. Free services sleep after about 15 minutes idle. The first visit afterwards takes a few minutes; the sign-in page shows
   "Server waking up" and continues by itself. Add officers in `OFFICER_EMAILS` any time (comma-separated).

## 4. Vercel (website)

1. vercel.com -> Add New -> Project -> import the repo, set **Root Directory** to `frontend` (Vite is detected).
2. Deploy, then add the Vercel address to the Google client's Authorized JavaScript origins (step 2.3) and to
   `CORS_ALLOWED_ORIGINS` on Render.

`frontend/vercel.json` forwards `/api/*` to Render (so the refresh cookie stays same-origin), routes every page to the app and sets
the security headers, including the exceptions Google's button needs.

## 5. Check it

1. Open the site and sign in as `siddhusiddhu1712@gmail.com`: you get the admin dashboard (portfolio, queue, audit).
2. Sign in with a different Gmail: you get only the application form and your own applications.
3. Submit an application with made-up values. You should see the compliance checks, the risk band and the per-factor explanation straight away.
4. Try **Delete my data** with that second account.

## Notes

- Free Render has no disk: the model is trained into the image at build time, and retraining results are lost on restart.
- The ML service is public but needs `ML_SERVICE_TOKEN`, which Render generates and shares with the API.
- A server of your own instead of Render: use `docker-compose.prod.yml` (see the README) with `GOOGLE_CLIENT_ID`, `ADMIN_EMAILS`
  and `PUBLIC_ORIGIN` set in `.env`.
