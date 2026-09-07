import { ApiError, NetworkError } from '@/api/client'

/**
 * Traduit une erreur technique en phrase affichable. Les messages metier du
 * backend (RFC 7807, rediges en francais) sont repris tels quels pour les
 * statuts 4xx ; tout le reste est resume sans jargon. Le detail technique reste
 * dans la console pour l'equipe.
 */
export function describeError(error: unknown, fallback = "L'opération n'a pas abouti. Réessayez."): string {
  if (error instanceof NetworkError) {
    switch (error.kind) {
      case 'offline':
        return 'Vous êtes hors ligne. Vérifiez votre connexion puis réessayez.'
      case 'timeout':
        return 'Le serveur met trop de temps à répondre. Réessayez dans un instant.'
      default:
        return 'Impossible de joindre le serveur. Réessayez dans un instant.'
    }
  }
  if (error instanceof ApiError) {
    if (error.status === 401) return 'Votre session a expiré. Reconnectez-vous.'
    if (error.status === 403) return "Vous n'avez pas les droits nécessaires pour cette action."
    if (error.status === 404) return error.problem?.detail ?? 'Cet élément est introuvable.'
    if (error.status === 429) return 'Trop de tentatives. Patientez quelques minutes.'
    if (error.status >= 500) return 'Le service est momentanément indisponible. Réessayez dans un instant.'
    return error.problem?.detail ?? error.problem?.title ?? fallback
  }
  return fallback
}

/** Vrai si l'erreur vient du reseau ou d'un serveur momentanement en panne : reessayer a du sens. */
export function isTransientError(error: unknown): boolean {
  if (error instanceof NetworkError) return true
  if (error instanceof ApiError) return error.status === 502 || error.status === 503 || error.status === 504
  return false
}

/**
 * Erreur definitive (audit F246) : une requete invalide, interdite ou introuvable
 * ne changera pas en la rejouant. L'ecran d'erreur n'affiche alors pas de bouton
 * « Reessayer », qui promettrait un resultat different.
 */
export function isDefinitiveError(error: unknown): boolean {
  const status = errorStatus(error)
  return status === 400 || status === 403 || status === 404 || status === 410 || status === 422
}

export function errorStatus(error: unknown): number | undefined {
  return error instanceof ApiError ? error.status : undefined
}

/** Type RFC 7807 d'un compte suspendu (403 renvoye par l'API sur toute requete authentifiee). */
export const ACCOUNT_SUSPENDED_TYPE = 'account-suspended'

/** Vrai pour le 403 « compte suspendu » : un ecran dedie, pas une redirection vers la connexion. */
export function isAccountSuspendedError(error: unknown): boolean {
  if (!(error instanceof ApiError) || error.status !== 403) return false
  const problem = error.problem
  if (!problem) return false
  return [problem.type, problem.title].some((value) => typeof value === 'string' && value.includes(ACCOUNT_SUSPENDED_TYPE))
}
