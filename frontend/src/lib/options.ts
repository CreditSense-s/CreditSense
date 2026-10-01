import { useQuery } from '@tanstack/react-query'
import axios from 'axios'

export interface AuthOptions {
  googleClientId: string
  passwordLogin: boolean
}

/**
 * Which sign-in methods the server offers. On a free host the API may be asleep, so this keeps retrying every
 * few seconds instead of failing; `isPending` is then shown as a "server waking up" screen.
 */
export const useAuthOptions = () =>
  useQuery({
    queryKey: ['auth', 'options'],
    queryFn: async () => (await axios.get<AuthOptions>('/api/auth/options', { timeout: 15_000 })).data,
    retry: true,
    retryDelay: 5_000,
    staleTime: 5 * 60_000,
  })
