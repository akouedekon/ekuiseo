import { useMutation, useQuery } from '@tanstack/react-query'
import { useSyncExternalStore } from 'react'
import { apiClient, authStore, restoreSession as restoreSessionFromCookie, type AuthChangeReason } from '@/api/client'
import { TERMS_VERSION } from '@/lib/legal'
import { clearApiCache, clearPersistedCache, queryClient, readCacheOwner, writeCacheOwner } from '@/lib/queryClient'
import { unsubscribePush } from '@/lib/push'
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
  /** Case cochee a l'inscription ; la version acceptee (TERMS_VERSION) est horodatee cote serveur. */
  acceptTerms: true
}

/**
 * Fin de session, quelle qu'en soit la cause : jetons, cache memoire, cache
 * persiste (et son proprietaire) et cache runtime du service worker sont vides
 * ensemble. C'est l'unique chemin : deconnexion volontaire, expiration detectee
 * par le client HTTP, suppression du compte. Sur un appareil partage, rien du
 * compte precedent ne doit survivre au suivant.
 */
export function resetSession(reason: AuthChangeReason = 'logout'): void {
  // L abonnement Web Push de cet appareil ne doit pas survivre a la session (ne leve jamais).
  void unsubscribePush()
  authStore.clear(reason)
  queryClient.clear()
  clearPersistedCache()
  void clearApiCache()
}

/**
 * Adoption du cache par le compte qui vient d'ouvrir (ou de rouvrir) sa session.
 * Si le cache appartenait a un autre compte (session precedente non fermee
 * proprement), il est purge avant d'ecrire le nouveau profil : aucune donnee de
 * l'ancien compte ne s'affiche en attendant le refetch.
 */
function adoptCache(user: UserResponse) {
  const previousOwner = readCacheOwner()
  if (previousOwner !== null && previousOwner !== user.id) {
    queryClient.clear()
    clearPersistedCache()
    void clearApiCache()
  }
  writeCacheOwner(user.id)
  queryClient.setQueryData<UserResponse>(['me'], user)
}

/**
 * Ouverture de session apres verification du code : seul le jeton d'acces est
 * garde, en memoire. Le refresh token n'est pas dans la reponse (`refreshToken`
 * nul) : l'API l'a pose dans le cookie HttpOnly `ekuiseo_refresh`, que le
 * navigateur seul manipule. Rien n'est ecrit dans localStorage.
 */
function persistAuth(data: AuthResponse) {
  adoptCache(data.user)
  authStore.setAccessToken(data.accessToken, 'login')
}

/**
 * Restauration de la session au chargement de la page (a appeler une fois, avant
 * le premier rendu, voir main.tsx) : POST /auth/refresh depuis le cookie HttpOnly.
 * Le jeton d'acces est pose par le client HTTP ; ici on adopte le cache pour le
 * compte retrouve et on garnit `['me']` avec le profil renvoye, comme a la
 * connexion. Silencieux : sans cookie valable, l'utilisateur n'est simplement pas
 * connecte et la garde de route reprend son comportement habituel.
 */
export async function restoreSession(): Promise<boolean> {
  const data = await restoreSessionFromCookie()
  if (!data) return false
  adoptCache(data.user)
  return true
}

/** Etat de session reactif : change des qu'un jeton est pose, retire ou expire. */
export function useIsAuthenticated(): boolean {
  return useSyncExternalStore(
    (onChange) => authStore.subscribe(() => onChange()),
    () => authStore.isAuthenticated(),
    () => false,
  )
}

/**
 * Vrai tant que la restauration de session au chargement n'a pas conclu : la
 * garde de route affiche un ecran de chargement plutot que de renvoyer vers la
 * connexion un utilisateur dont le cookie est peut-etre encore valable.
 */
export function useIsRestoringSession(): boolean {
  return useSyncExternalStore(
    (onChange) => authStore.subscribe(() => onChange()),
    () => authStore.isRestoring(),
    () => false,
  )
}

export function useMe() {
  const authenticated = useIsAuthenticated()
  return useQuery<UserResponse>({
    queryKey: ['me'],
    queryFn: ({ signal }) => apiClient.get<UserResponse>('/api/v1/me', { signal }),
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
        { ...input, phone: normalizePhone(input.phone), termsVersion: TERMS_VERSION },
        { auth: false },
      ),
  })
}

/**
 * PATCH /api/v1/me/terms { termsVersion } (204) : acceptation de la version
 * courante des CGU par un compte existant (ecran bloquant TermsGate quand
 * GET /me renvoie termsAcceptanceRequired).
 */
export function useAcceptTerms() {
  return useMutation({
    mutationFn: () => apiClient.patch<void>('/api/v1/me/terms', { termsVersion: TERMS_VERSION }),
    onSuccess: () => {
      queryClient.setQueryData<UserResponse>(['me'], (current) =>
        current ? { ...current, termsAcceptanceRequired: false } : current,
      )
      queryClient.invalidateQueries({ queryKey: ['me'] })
    },
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
 * Deconnexion : le refresh token est revoque cote serveur (POST /auth/logout lit le
 * cookie HttpOnly, revoque toute sa chaine de rotation et supprime le cookie), puis la
 * session locale est videe (resetSession). La revocation est lancee sans attendre :
 * hors ligne, la session locale disparait quand meme et le jeton expirera seul.
 */
export function useLogout() {
  return () => {
    void apiClient.post<void>('/api/v1/auth/logout', undefined, { auth: false }).catch(() => undefined)
    resetSession('logout')
  }
}
