import { ApiError } from './client'

/**
 * Mode demonstration — DESACTIVE PAR DEFAUT.
 *
 * Il ne s'active que si VITE_DEMO_FALLBACK vaut exactement la chaine 'true'.
 * Variable absente, vide, ou toute autre valeur => desactive. Ce choix est
 * volontairement restrictif : un build de production qui oublierait la
 * variable ne doit JAMAIS servir des chiffres inventes a la place d'une
 * erreur reseau. C'est un reglage d'atelier, pas un filet de securite.
 *
 * Quand il est actif, il ne remplace jamais une reponse reelle : il
 * n'intervient que si l'appel echoue (API injoignable, 404, 501, 5xx), la
 * donnee est marquee `demo: true`, et un bandeau permanent le signale.
 */
export const DEMO_FALLBACK_ENABLED =
  (import.meta.env.VITE_DEMO_FALLBACK as string | undefined) === 'true'

/** Enveloppe : la donnee + son origine. */
export interface Sourced<T> {
  data: T
  demo: boolean
}

/*
 * Signal global : des qu'un seul appel est retombe sur la demonstration,
 * toute l'application doit l'annoncer, y compris sur les ecrans qui ne
 * transmettent pas explicitement le drapeau. Un ecran ne peut donc pas
 * « oublier » d'avertir l'utilisateur.
 */
let demoActive = false
const demoListeners = new Set<(active: boolean) => void>()

export function isDemoActive(): boolean {
  return demoActive
}

export function subscribeDemoActive(listener: (active: boolean) => void): () => void {
  demoListeners.add(listener)
  return () => demoListeners.delete(listener)
}

function markDemoActive(): void {
  if (demoActive) return
  demoActive = true
  for (const listener of demoListeners) listener(true)
}

/** Erreurs pour lesquelles la bascule en donnees de demonstration a du sens. */
function isMissingBackend(error: unknown): boolean {
  if (error instanceof ApiError) {
    return error.status === 404 || error.status === 405 || error.status === 501 || error.status >= 502
  }
  // TypeError : fetch a echoue (API non demarree, DNS, CORS).
  return error instanceof TypeError
}

/**
 * Appelle l'API et, si le mode demonstration est explicitement active,
 * retombe sur un jeu de donnees factice au lieu d'echouer.
 * Hors de ce mode, l'erreur remonte telle quelle et l'interface affiche
 * son etat d'erreur. Les vraies erreurs metier (401, 403, 409, 422…)
 * remontent toujours, quel que soit le reglage.
 */
export async function resilient<T>(call: () => Promise<T>, fallback: () => T): Promise<Sourced<T>> {
  try {
    return { data: await call(), demo: false }
  } catch (error) {
    if (DEMO_FALLBACK_ENABLED && isMissingBackend(error)) {
      markDemoActive()
      return { data: fallback(), demo: true }
    }
    throw error
  }
}

/** Variante pour les mutations : simule un succes en mode demonstration. */
export async function resilientMutation<T>(call: () => Promise<T>, fallback: () => T): Promise<T> {
  try {
    return await call()
  } catch (error) {
    if (DEMO_FALLBACK_ENABLED && isMissingBackend(error)) {
      markDemoActive()
      // Latence simulee : les transitions optimistes restent visibles.
      await new Promise((resolve) => setTimeout(resolve, 450))
      return fallback()
    }
    throw error
  }
}
