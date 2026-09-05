import { useMutation, useQuery } from '@tanstack/react-query'
import { useSyncExternalStore } from 'react'
import { apiClient, authStore, type AuthChangeReason } from '@/api/client'
import { clearApiCache, clearPersistedCache, queryClient, readCacheOwner, writeCacheOwner } from '@/lib/queryClient'
import { toE164 } from '@/lib/validation'
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

/**
 * Fin de session, quelle qu'en soit la cause : jetons, cache memoire, cache
 * persiste (et son proprietaire) et cache runtime du service worker sont vides
 * ensemble. C'est l'unique chemin : deconnexion volontaire, expiration detectee
 * par le client HTTP, suppression du compte. Sur un appareil partage, rien du
 * compte precedent ne doit survivre au suivant.
 */
export function resetSession(reason: AuthChangeReason = 'logout'): void {
  authStore.clear(reason)
  queryClient.clear()
  clearPersistedCache()
  void clearApiCache()
}

/**
 * Ouverture de session. Si le cache appartenait a un autre compte (session
 * precedente non fermee proprement), il est purge avant d'ecrire le nouveau
 * profil : aucune donnee de l'ancien compte ne s'affiche en attendant le refetch.
 */
function persistAuth(data: AuthResponse) {
  const previousOwner = readCacheOwner()
  if (previousOwner !== null && previousOwner !== data.user.id) {
    queryClient.clear()
    clearPersistedCache()
    void clearApiCache()
  }
  authStore.setTokens(data.accessToken, data.refreshToken, 'login')
  writeCacheOwner(data.user.id)
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
    mutationFn: (phone: string) =>
      apiClient.post<OtpRequestResponse>('/api/v1/auth/otp/request', { phone: normalizePhone(phone) }, { auth: false }),
  })
}

/** Le serveur applique la meme regle (PhoneNumbers.java) ; on envoie deja la forme canonique. */
function normalizePhone(phone: string): string {
  return toE164(phone) ?? phone.trim()
}

/**
 * POST /api/v1/auth/otp/register : cree le compte (prenom, nom, e-mail
 * obligatoire) puis envoie le code de connexion a cette adresse. La session n'est ouverte qu'a la
 * verification du code, comme pour une connexion.
 */
export function useRegisterOtp() {
  return useMutation({
    mutationFn: (input: OtpRegisterInput) =>
      apiClient.post<OtpRequestResponse>(
        '/api/v1/auth/otp/register',
        { ...input, phone: normalizePhone(input.phone) },
        { auth: false },
      ),
  })
}

/** POST /api/v1/auth/otp/verify : verifie le code et ouvre la session. */
export function useVerifyOtp() {
  return useMutation({
    mutationFn: (input: OtpVerifyInput) =>
      apiClient.post<AuthResponse>(
        '/api/v1/auth/otp/verify',
        { ...input, phone: normalizePhone(input.phone) },
        { auth: false },
      ),
    onSuccess: (data) => persistAuth(data),
  })
}

/**
 * Deconnexion : le refresh token est revoque cote serveur (POST /auth/logout, toute sa
 * chaine de rotation), puis la session locale est videe (resetSession). La revocation
 * est lancee sans attendre : hors ligne, la session locale disparait quand meme et le
 * jeton expirera seul.
 */
export function useLogout() {
  return () => {
    const refreshToken = authStore.getRefreshToken()
    if (refreshToken) {
      void apiClient.post<void>('/api/v1/auth/logout', { refreshToken }, { auth: false }).catch(() => undefined)
    }
    resetSession('logout')
  }
}
