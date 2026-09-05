import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSyncExternalStore } from 'react'
import { apiClient, authStore } from '@/api/client'
import { clearPersistedCache } from '@/lib/queryClient'
import type { AuthResponse, OtpRequestResponse, UserResponse } from '@/api/types'

interface OtpVerifyInput {
  phone: string
  code: string
}

export interface OtpRegisterInput {
  phone: string
  firstName: string
  lastName: string
  /** Obligatoire : le code de connexion y est envoye. */
  email: string
}

function persistAuth(queryClient: ReturnType<typeof useQueryClient>, data: AuthResponse) {
  authStore.setTokens(data.accessToken, data.refreshToken, 'login')
  queryClient.setQueryData<UserResponse>(['me'], data.user)
}

/** Etat de session reactif : change des qu'un jeton est pose, retire ou expire. */
export function useIsAuthenticated(): boolean {
  return useSyncExternalStore(
    (onChange) => authStore.subscribe(() => onChange()),
    () => authStore.isAuthenticated(),
    () => false,
  )
}

/** Lecture ponctuelle hors rendu (gardes, effets). Dans un composant, preferer useIsAuthenticated. */
export function isAuthenticated(): boolean {
  return authStore.isAuthenticated()
}

export function useMe() {
  const authenticated = useIsAuthenticated()
  return useQuery<UserResponse>({
    queryKey: ['me'],
    queryFn: () => apiClient.get<UserResponse>('/api/v1/me'),
    enabled: authenticated,
    staleTime: 5 * 60_000,
  })
}

/**
 * POST /api/v1/auth/otp/request : envoie le code de connexion a l e-mail du compte
 * (ou par SMS en repli) et indique ou il est parti. 404 si le numero est inconnu.
 */
export function useRequestOtp() {
  return useMutation({
    mutationFn: (phone: string) => apiClient.post<OtpRequestResponse>('/api/v1/auth/otp/request', { phone }, { auth: false }),
  })
}

/**
 * POST /api/v1/auth/otp/register : cree le compte (prenom, nom, e-mail
 * obligatoire) puis envoie le code de connexion a cette adresse. La session n'est ouverte qu'a la
 * verification du code, comme pour une connexion.
 */
export function useRegisterOtp() {
  return useMutation({
    mutationFn: (input: OtpRegisterInput) =>
      apiClient.post<OtpRequestResponse>('/api/v1/auth/otp/register', input, { auth: false }),
  })
}

/** POST /api/v1/auth/otp/verify : verifie le code et ouvre la session. */
export function useVerifyOtp() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: OtpVerifyInput) =>
      apiClient.post<AuthResponse>('/api/v1/auth/otp/verify', input, { auth: false }),
    onSuccess: (data) => persistAuth(queryClient, data),
  })
}

/**
 * Deconnexion locale : jetons, cache memoire et cache persiste (donnees
 * personnelles) sont effaces immediatement. Les jetons sont des JWT sans etat
 * cote serveur : il n'existe pas d'appel de revocation, ils expirent seuls.
 */
export function useLogout() {
  const queryClient = useQueryClient()
  return () => {
    authStore.clear('logout')
    queryClient.clear()
    clearPersistedCache()
  }
}
