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

export function errorStatus(error: unknown): number | undefined {
  return error instanceof ApiError ? error.status : undefined
}
