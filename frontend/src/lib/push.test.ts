import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { authStore } from '@/api/client'
import {
  pushSupport,
  subscribePush,
  toSubscriptionPayload,
  unsubscribePush,
  urlBase64ToUint8Array,
  type PushEnvironment,
} from './push'

const full: PushEnvironment = { serviceWorker: true, pushManager: true, notification: true, userAgent: 'Chrome', standalone: false }

describe('pushSupport', () => {
  it('distingue le pris en charge, le non pris en charge et Safari iOS hors ecran d accueil', () => {
    expect(pushSupport(full)).toBe('supported')
    expect(pushSupport({ ...full, pushManager: false })).toBe('unsupported')
    const iphone = { ...full, pushManager: false, userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari' }
    expect(pushSupport(iphone)).toBe('ios-not-installed')
    // Installee sur l ecran d accueil mais sans PushManager (iOS < 16.4) : rien a proposer.
    expect(pushSupport({ ...iphone, standalone: true })).toBe('unsupported')
  })
})

describe('urlBase64ToUint8Array', () => {
  it('decode une cle VAPID base64url sans bourrage', () => {
    // "BAEC_-8" : octets 04 01 02 FF EF (les caracteres - et _ sont ceux de base64url).
    expect(Array.from(urlBase64ToUint8Array('BAEC_-8'))).toEqual([4, 1, 2, 255, 239])
  })
})

/** Double minimal d un PushSubscription du navigateur. */
function fakeSubscription(endpoint: string, keys: Record<string, string> = { p256dh: 'P', auth: 'A' }) {
  return {
    endpoint,
    toJSON: () => ({ endpoint, keys, expirationTime: null }),
    unsubscribe: vi.fn(async () => true),
  }
}

describe('toSubscriptionPayload', () => {
  it('reprend endpoint et cles, et refuse un abonnement incomplet', () => {
    expect(toSubscriptionPayload(fakeSubscription('https://push/x') as unknown as PushSubscription)).toEqual({
      endpoint: 'https://push/x',
      keys: { p256dh: 'P', auth: 'A' },
    })
    expect(() => toSubscriptionPayload(fakeSubscription('https://push/x', {}) as unknown as PushSubscription)).toThrow()
  })
})

describe('subscribePush / unsubscribePush', () => {
  const fetchMock = vi.fn<typeof fetch>()
  const getSubscription = vi.fn()
  const subscribe = vi.fn()
  const requestPermission = vi.fn()

  beforeEach(() => {
    authStore.setTokens('access', 'refresh')
    fetchMock.mockReset()
    getSubscription.mockReset()
    subscribe.mockReset()
    requestPermission.mockReset()
    globalThis.fetch = fetchMock
    // Environnement « pris en charge » : serviceWorker.ready, PushManager, Notification.
    Object.defineProperty(navigator, 'serviceWorker', {
      configurable: true,
      value: { ready: Promise.resolve({ pushManager: { getSubscription, subscribe } }) },
    })
    Object.defineProperty(window, 'PushManager', { configurable: true, value: function PushManager() {} })
    Object.defineProperty(window, 'Notification', {
      configurable: true,
      value: { permission: 'default', requestPermission },
    })
  })

  afterEach(() => {
    authStore.clear()
    delete (window as { PushManager?: unknown }).PushManager
    delete (window as { Notification?: unknown }).Notification
    delete (navigator as { serviceWorker?: unknown }).serviceWorker
  })

  const jsonResponse = (status: number, body?: unknown) =>
    new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    })

  it('recupere la cle VAPID, demande la permission, abonne le navigateur et enregistre l abonnement', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { publicKey: 'BAEC_-8' })).mockResolvedValueOnce(jsonResponse(204))
    requestPermission.mockResolvedValue('granted')
    getSubscription.mockResolvedValue(null)
    subscribe.mockResolvedValue(fakeSubscription('https://push.example/abc'))

    await expect(subscribePush()).resolves.toBe('subscribed')

    expect(requestPermission).toHaveBeenCalledTimes(1)
    expect(subscribe).toHaveBeenCalledWith(expect.objectContaining({ userVisibleOnly: true }))
    const [url, init] = fetchMock.mock.calls[1]
    expect(String(url)).toContain('/api/v1/me/push-subscriptions')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toEqual({ endpoint: 'https://push.example/abc', keys: { p256dh: 'P', auth: 'A' } })
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer access')
  })

  it('renvoie « disabled » sans cle serveur et « denied » si la permission est refusee, sans abonner', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(204))
    await expect(subscribePush()).resolves.toBe('disabled')
    expect(requestPermission).not.toHaveBeenCalled()

    fetchMock.mockResolvedValueOnce(jsonResponse(200, { publicKey: 'BAEC_-8' }))
    requestPermission.mockResolvedValue('denied')
    await expect(subscribePush()).resolves.toBe('denied')
    expect(subscribe).not.toHaveBeenCalled()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('reutilise un abonnement existant plutot que d en creer un second', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { publicKey: 'BAEC_-8' })).mockResolvedValueOnce(jsonResponse(204))
    requestPermission.mockResolvedValue('granted')
    getSubscription.mockResolvedValue(fakeSubscription('https://push.example/existing'))

    await expect(subscribePush()).resolves.toBe('subscribed')
    expect(subscribe).not.toHaveBeenCalled()
    expect(JSON.parse(String(fetchMock.mock.calls[1][1]?.body))).toMatchObject({ endpoint: 'https://push.example/existing' })
  })

  it('unsubscribePush previent le serveur (DELETE avec corps) puis desabonne le navigateur, sans jamais lever', async () => {
    const subscription = fakeSubscription('https://push.example/abc')
    getSubscription.mockResolvedValue(subscription)
    fetchMock.mockResolvedValueOnce(jsonResponse(204))

    await expect(unsubscribePush()).resolves.toBeUndefined()

    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/api/v1/me/push-subscriptions')
    expect(init?.method).toBe('DELETE')
    expect(JSON.parse(String(init?.body))).toEqual({ endpoint: 'https://push.example/abc' })
    expect(subscription.unsubscribe).toHaveBeenCalledTimes(1)

    // Serveur injoignable : le navigateur est quand meme desabonne.
    const other = fakeSubscription('https://push.example/def')
    getSubscription.mockResolvedValue(other)
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await expect(unsubscribePush()).resolves.toBeUndefined()
    expect(other.unsubscribe).toHaveBeenCalledTimes(1)
  })

  it('unsubscribePush ne fait rien sans abonnement', async () => {
    getSubscription.mockResolvedValue(null)
    await unsubscribePush()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
