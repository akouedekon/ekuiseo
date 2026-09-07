import { useCallback, useEffect, useState } from 'react'
import { getPushSubscription, pushSupport, subscribePush, unsubscribePush, type PushSupport, type SubscribeResult } from '@/lib/push'

export type PushPermission = NotificationPermission | 'unsupported'

/**
 * Etat des notifications push sur CET appareil (V20) : prise en charge, permission du
 * navigateur, abonnement existant (verifie au montage), activation et desactivation.
 * Distinct de la preference serveur `notifyByPush`, qui vaut pour tous les appareils.
 */
export function usePushSubscription() {
  const [support] = useState<PushSupport>(() => pushSupport())
  const [permission, setPermission] = useState<PushPermission>(() =>
    support === 'supported' ? Notification.permission : 'unsupported',
  )
  /** null tant que l'abonnement courant n'a pas ete lu. */
  const [subscribed, setSubscribed] = useState<boolean | null>(support === 'supported' ? null : false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (support !== 'supported') return
    let cancelled = false
    void getPushSubscription().then((subscription) => {
      if (!cancelled) setSubscribed(subscription !== null)
    })
    return () => {
      cancelled = true
    }
  }, [support])

  const enable = useCallback(async (): Promise<SubscribeResult> => {
    setBusy(true)
    try {
      const result = await subscribePush()
      if (support === 'supported') setPermission(Notification.permission)
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
