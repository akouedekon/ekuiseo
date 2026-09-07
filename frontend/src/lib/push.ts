import { apiClient } from '@/api/client'
import { authorizedRawFetch } from '@/api/rawFetch'

/*
 * Web Push cote navigateur (V20) : prise en charge, abonnement aupres du service push
 * du navigateur avec la cle VAPID du serveur, enregistrement de l'abonnement dans
 * l'API, desabonnement. Le service worker (src/sw.ts) affiche les notifications.
 */

export type PushSupport = 'supported' | 'unsupported' | 'ios-not-installed'

/** Environnement minimal interroge par {@link pushSupport} (injectable pour les tests). */
export interface PushEnvironment {
  serviceWorker: boolean
  pushManager: boolean
  notification: boolean
  userAgent: string
  standalone: boolean
}

export function readPushEnvironment(): PushEnvironment {
  const nav = typeof navigator === 'undefined' ? undefined : navigator
  const win = typeof window === 'undefined' ? undefined : window
  return {
    serviceWorker: !!nav && 'serviceWorker' in nav,
    pushManager: !!win && 'PushManager' in win,
    notification: !!win && 'Notification' in win,
    userAgent: nav?.userAgent ?? '',
    standalone:
      (!!win && typeof win.matchMedia === 'function' && win.matchMedia('(display-mode: standalone)').matches) ||
      (nav as (Navigator & { standalone?: boolean }) | undefined)?.standalone === true,
  }
}

/**
 * Sur iPhone et iPad, Safari n'expose Web Push qu'a une application installee sur
 * l'ecran d'accueil (iOS 16.4+) : dans un onglet, PushManager est absent. On le dit
 * tel quel a l'utilisateur plutot qu'un « non pris en charge » sans issue.
 */
export function pushSupport(env: PushEnvironment = readPushEnvironment()): PushSupport {
  if (env.serviceWorker && env.pushManager && env.notification) return 'supported'
  const ios = /iPhone|iPad|iPod/i.test(env.userAgent)
  return ios && !env.standalone ? 'ios-not-installed' : 'unsupported'
}

/** Cle publique VAPID du serveur ; null quand le push y est desactive (204). */
export async function getVapidPublicKey(): Promise<string | null> {
  const response = await apiClient.get<{ publicKey?: string } | undefined>('/api/v1/push/vapid-public-key')
  return response?.publicKey || null
}

/** Cle VAPID base64url -> octets, format attendu par `pushManager.subscribe`. */
export function urlBase64ToUint8Array(base64: string): Uint8Array<ArrayBuffer> {
  const padding = '='.repeat((4 - (base64.length % 4)) % 4)
  const normalized = (base64 + padding).replace(/-/g, '+').replace(/_/g, '/')
  const raw = atob(normalized)
  const bytes = new Uint8Array(raw.length)
  for (let i = 0; i < raw.length; i += 1) bytes[i] = raw.charCodeAt(i)
  return bytes
}

/** Forme envoyee a POST /api/v1/me/push-subscriptions (PushSubscriptionRequest cote serveur). */
export interface PushSubscriptionPayload {
  endpoint: string
  keys: { p256dh: string; auth: string }
}

export function toSubscriptionPayload(subscription: PushSubscription): PushSubscriptionPayload {
  const json = subscription.toJSON()
  const p256dh = json.keys?.p256dh
  const auth = json.keys?.auth
  if (!json.endpoint || !p256dh || !auth) {
    throw new Error("L'abonnement push renvoye par le navigateur est incomplet.")
  }
  return { endpoint: json.endpoint, keys: { p256dh, auth } }
}

async function pushManager(): Promise<PushManager> {
  const registration = await navigator.serviceWorker.ready
  return registration.pushManager
}

/** Abonnement courant de ce navigateur, ou null (non pris en charge, aucun abonnement, service worker absent). */
export async function getPushSubscription(): Promise<PushSubscription | null> {
  if (pushSupport() !== 'supported') return null
  try {
    return await (await pushManager()).getSubscription()
  } catch {
    return null
  }
}

export type SubscribeResult = 'subscribed' | 'denied' | 'disabled' | 'unsupported'

/**
 * Active les notifications sur cet appareil : cle VAPID, permission (demandee au
 * premier appel), abonnement aupres du service push, enregistrement dans l'API. Les
 * erreurs reseau ou API remontent telles quelles (ApiError / NetworkError) pour etre
 * decrites par l'interface ; les refus attendus sont des resultats, pas des erreurs.
 */
export async function subscribePush(): Promise<SubscribeResult> {
  if (pushSupport() !== 'supported') return 'unsupported'
  const key = await getVapidPublicKey()
  if (!key) return 'disabled'
  const permission = await Notification.requestPermission()
  if (permission !== 'granted') return 'denied'
  const manager = await pushManager()
  let subscription = await manager.getSubscription()
  if (!subscription) {
    subscription = await manager.subscribe({ userVisibleOnly: true, applicationServerKey: urlBase64ToUint8Array(key) })
  }
  await apiClient.post<void>('/api/v1/me/push-subscriptions', toSubscriptionPayload(subscription))
  return 'subscribed'
}

/**
 * Retire l'abonnement de cet appareil : cote API (tant que la session est valide),
 * puis cote navigateur. Ne leve jamais : appelee aussi a la deconnexion
 * (`resetSession`), ou rien ne doit bloquer la fermeture de session.
 */
export async function unsubscribePush(): Promise<void> {
  const subscription = await getPushSubscription()
  if (!subscription) return
  try {
    // Corps sur un DELETE : le client JSON standard n'en accepte pas, on passe par l'appel brut.
    await authorizedRawFetch('/api/v1/me/push-subscriptions', {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ endpoint: subscription.endpoint }),
    })
  } catch {
    /* hors ligne ou session deja fermee : l abonnement expirera cote serveur au premier envoi en echec */
  }
  try {
    await subscription.unsubscribe()
  } catch {
    /* ignore */
  }
}
