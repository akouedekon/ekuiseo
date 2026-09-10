import { apiClient } from '@/api/client'
import { authorizedRawFetch } from '@/api/rawFetch'

/*
 * Notifications natives de l application Android/iOS (V24) : le WebView n a pas de Web
 * Push, l application s enregistre aupres de Firebase Cloud Messaging via le greffon
 * @capacitor/push-notifications et confie son jeton a l API (kind = FCM). Le jeton est
 * garde localement pour pouvoir le retirer a la deconnexion. Les listeners (reception,
 * toucher) sont branches par lib/native.ts au demarrage.
 */

const TOKEN_KEY = 'ekuiseo.push.fcmToken'
const REGISTRATION_TIMEOUT_MS = 15_000

export function readStoredToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

function storeToken(token: string | null): void {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* stockage indisponible : l abonnement vaut pour la session */
  }
}

/** Le serveur sait-il envoyer des notifications natives (compte de service Firebase renseigne) ? */
export async function isNativePushEnabledOnServer(): Promise<boolean> {
  const config = await apiClient.get<{ webPush: boolean; nativePush: boolean } | undefined>('/api/v1/push/config')
  return config?.nativePush === true
}

export type NativePermission = 'granted' | 'denied' | 'prompt'

export async function nativePushPermission(): Promise<NativePermission> {
  const { PushNotifications } = await import('@capacitor/push-notifications')
  const status = await PushNotifications.checkPermissions()
  return status.receive === 'granted' ? 'granted' : status.receive === 'denied' ? 'denied' : 'prompt'
}

/** Demande la permission puis le jeton FCM ; null si refusee ou si l enregistrement echoue. */
export async function registerNativePush(): Promise<string | null> {
  const { PushNotifications } = await import('@capacitor/push-notifications')
  let status = await PushNotifications.checkPermissions()
  if (status.receive === 'prompt' || status.receive === 'prompt-with-rationale') {
    status = await PushNotifications.requestPermissions()
  }
  if (status.receive !== 'granted') return null

  const token = await new Promise<string | null>((resolve) => {
    let settled = false
    const done = (value: string | null) => {
      if (settled) return
      settled = true
      window.clearTimeout(timer)
      void registration.then((h) => h.remove())
      void failure.then((h) => h.remove())
      resolve(value)
    }
    const timer = window.setTimeout(() => done(null), REGISTRATION_TIMEOUT_MS)
    const registration = PushNotifications.addListener('registration', (result) => done(result.value || null))
    const failure = PushNotifications.addListener('registrationError', () => done(null))
    void PushNotifications.register().catch(() => done(null))
  })
  storeToken(token)
  return token
}

/** Enregistre le jeton aupres de l API (POST /me/push-subscriptions, kind FCM). */
export async function saveNativeToken(token: string): Promise<void> {
  await apiClient.post<void>('/api/v1/me/push-subscriptions', { endpoint: token, kind: 'FCM' })
}

/** Retire le jeton de cet appareil : API (si la session est valide), puis stockage local et greffon. Ne leve jamais. */
export async function unregisterNativePush(): Promise<void> {
  const token = readStoredToken()
  if (token) {
    try {
      await authorizedRawFetch('/api/v1/me/push-subscriptions', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ endpoint: token }),
      })
    } catch {
      /* hors ligne ou session fermee : le jeton expirera cote serveur au premier envoi en echec */
    }
  }
  storeToken(null)
  try {
    const { PushNotifications } = await import('@capacitor/push-notifications')
    await PushNotifications.unregister()
  } catch {
    /* greffon absent */
  }
}
