/**
 * Public health-check address of the ML service. On a free host the service sleeps when idle, and Render only
 * starts it for a visit from a browser, not for requests from our own API, so every page opens this address in
 * a hidden frame (see `MlWaker`). `VITE_ML_WAKE_URL` overrides it; an empty value turns the wake-up off, which
 * the local Docker build does because its ML service never sleeps.
 */
export const ML_WAKE_URL: string =
  import.meta.env.VITE_ML_WAKE_URL ?? (import.meta.env.PROD ? 'https://creditsense-ml.onrender.com/health' : '')
