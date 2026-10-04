import '@fontsource/ibm-plex-mono/400.css'
import '@fontsource/ibm-plex-mono/500.css'
import '@fontsource/ibm-plex-sans/400.css'
import '@fontsource/ibm-plex-sans/500.css'
import '@fontsource/ibm-plex-sans/600.css'
import '@fontsource/space-grotesk/500.css'
import '@fontsource/space-grotesk/600.css'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { App } from './App'
import { ErrorBoundary } from './components/ErrorBoundary'
import { MlWaker } from './components/MlWaker'
import './index.css'
import { followOtherTabs, useAuth } from './store/auth'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      retry: (count, error) => {
        const status = (error as { response?: { status?: number } }).response?.status
        return status != null && status < 500 ? false : count < 2
      },
    },
  },
})

// A different person (or nobody) is signed in now: never show the previous user's cached data.
useAuth.subscribe((state, previous) => {
  if (state.user?.id !== previous.user?.id) queryClient.clear()
})
followOtherTabs()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
        <MlWaker />
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>,
)
