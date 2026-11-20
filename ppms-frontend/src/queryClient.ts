import { MutationCache, QueryClient } from '@tanstack/react-query'
import { useToastStore } from './store/toastStore'
import { parseApiError } from './utils/apiError'

function isClientError(error: unknown): boolean {
  const status = (error as { response?: { status?: number } })?.response?.status
  return typeof status === 'number' && status >= 400 && status < 500
}

export const queryClient = new QueryClient({
  mutationCache: new MutationCache({
    onError: (error, _variables, _context, mutation) => {
      if (mutation.options.onError) return
      useToastStore.getState().addToast(parseApiError(error), 'error')
    },
  }),
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => !isClientError(error) && failureCount < 1,
      staleTime: 30_000,
      gcTime: 10 * 60_000,
    },
  },
})
