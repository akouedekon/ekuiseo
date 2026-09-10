import { useCallback, useEffect, useState } from 'react'
import { hasPushSubscription, pushSupport, subscribePush, type PushSupport, type SubscribeResult, unsubscribePush } from '@/lib/push'

export type PushPermission = NotificationPermission | 'unsupported'

/**
 * Etat des notifications push sur CET appareil (V20) : prise en charge, permission du
 * navigateur, abonnement existant (verifie au montage), activation et desactivation.
 * Distinct de la preference serveur `notifyByPush`, qui vaut pour tous les appareils.
 */
export function usePushSubscription() {
  const [support] = useState<PushSupport>(() => pushSupport())
  const [permission, setPermission] = useState<PushPermission>(() =>
    support === 'supported' ? (typeof Notification === 'undefined' ? 'default' : Notification.permission) : 'unsupported',
  )
  /** null tant que l'abonnement courant n'a pas ete lu. */
  const [subscribed, setSubscribed] = useState<boolean | null>(support === 'supported' ? null : false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (support !== 'supported') return
    let cancelled = false
    void hasPushSubscription().then((subscribed) => {
      if (!cancelled) setSubscribed(subscribed)
    })
    return () => {
      cancelled = true
    }
  }, [support])

  const enable = useCallback(async (): Promise<SubscribeResult> => {
    setBusy(true)
    try {
      const result = await subscribePush()
      if (support === 'supported') {
        setPermission(typeof Notification === 'undefined' ? (result === 'denied' ? 'denied' : 'granted') : Notification.permission)
      }
      setSubscribed(result === 'subscribed')
      return result
    } finally {
      setBusy(false)
    }
  }, [support])

  const disable = useCallback(async (): Promise<void> => {
    setBusy(true)
    try {
      await unsubscribePush()
      setSubscribed(false)
    } finally {
      setBusy(false)
    }
  }, [])

  return { support, permission, subscribed, busy, enable, disable }
}
