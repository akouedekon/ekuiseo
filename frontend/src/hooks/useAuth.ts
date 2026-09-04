import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient, tokenStorage } from '@/api/client'
import { DEMO_ME } from '@/api/demo'
import { resilient, resilientMutation, type Sourced } from '@/api/resilient'
import type { AuthResponse, UserResponse } from '@/api/types'

interface RegisterInput {
  phone: string
  firstName: string
  lastName: string
  password: string
  email?: string
}

interface LoginInput {
  phone: string
  password: string
}

interface OtpVerifyInput {
  phone: string
  code: string
}

function persistAuth(queryClient: ReturnType<typeof useQueryClient>, data: AuthResponse) {
  tokenStorage.setTokens(data.accessToken, data.refreshToken)
  queryClient.setQueryData(['me'], { data: data.user, demo: false } satisfies Sourced<UserResponse>)
}

/** Session de demonstration, utilisee seulement si l'API est injoignable. */
function demoAuth(phone: string): AuthResponse {
  return {
    accessToken: 'demo.access.token',
    refreshToken: 'demo.refresh.token',
    user: { ...DEMO_ME, phone: phone || DEMO_ME.phone },
  }
}

export function useMe() {
  return useQuery<Sourced<UserResponse>>({
    queryKey: ['me'],
    queryFn: () => resilient(() => apiClient.get<UserResponse>('/api/v1/me'), () => DEMO_ME),
    enabled: !!tokenStorage.getAccessToken(),
    retry: 1,
  })
}

export function useRegister() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: RegisterInput) =>
      resilientMutation(
        () => apiClient.post<AuthResponse>('/api/v1/auth/register', input, { auth: false }),
        () => ({
          ...demoAuth(input.phone),
          user: { ...DEMO_ME, phone: input.phone, firstName: input.firstName, lastName: input.lastName },
        }),
      ),
    onSuccess: (data) => persistAuth(queryClient, data),
  })
}

export function useLogin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: LoginInput) =>
      resilientMutation(
        () => apiClient.post<AuthResponse>('/api/v1/auth/login', input, { auth: false }),
        () => demoAuth(input.phone),
      ),
    onSuccess: (data) => persistAuth(queryClient, data),
  })
}

export function useRequestOtp() {
  return useMutation({
    mutationFn: (phone: string) =>
      resilientMutation(
        () => apiClient.post<void>('/api/v1/auth/otp/request', { phone }, { auth: false }),
        () => undefined,
      ),
  })
}

export function useVerifyOtp() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: OtpVerifyInput) =>
      resilientMutation(
        () => apiClient.post<AuthResponse>('/api/v1/auth/otp/verify', input, { auth: false }),
        () => demoAuth(input.phone),
      ),
    onSuccess: (data) => persistAuth(queryClient, data),
  })
}

export function useLogout() {
  const queryClient = useQueryClient()
  return () => {
    tokenStorage.clear()
    queryClient.removeQueries({ queryKey: ['me'] })
    queryClient.clear()
  }
}

export function isAuthenticated(): boolean {
  return !!tokenStorage.getAccessToken()
}
