import { useEffect, useRef, useState } from 'react'
import type { LivePositionAck, LivePositionRequest } from '@/api/types'
import { usePostLivePosition } from '@/hooks/useTrips'
import { isNativeApp } from '@/lib/native'
import { DEFAULT_INTERVAL_SECONDS, shouldSendPosition, type LatLng } from '@/lib/liveTracking'

/*
 * Partage de sa position pendant un trajet (V28), pour le conducteur (LiveSharingControl)
 * comme pour un passager confirme (LiveTrackingCard). Suit la position de l appareil
 * (haute precision) et l envoie a la cadence recommandee par le serveur (`intervalSeconds`
 * de chaque accuse), sans jamais descendre sous 2 s. Tout s arrete a la desactivation, au
 * demontage, et quand l application passe en arriere-plan (Capacitor `appStateChange`,
 * `visibilitychange` dans un navigateur) : Android ne livre de toute facon plus de
 * position a un WebView en arriere-plan (docs/MOBILE.md).
 */

/** Etat du GPS ; `paused` quand l application est en arriere-plan. */
export type GeoStatus = 'idle' | 'waiting' | 'ok' | 'denied' | 'unavailable' | 'paused'

export const GEO_SUPPORTED = typeof navigator !== 'undefined' && 'geolocation' in navigator

/** Position du navigateur -> charge utile de l API ; cap et vitesse absents ou NaN deviennent null. */
export function toPositionRequest(position: GeolocationPosition): LivePositionRequest {
  const { latitude, longitude, heading, speed, accuracy } = position.coords
  return {
    lat: latitude,
    lng: longitude,
    heading: heading !== null && Number.isFinite(heading) ? heading : null,
    speedKmh: speed !== null && Number.isFinite(speed) && speed >= 0 ? speed * 3.6 : null,
    accuracyM: Number.isFinite(accuracy) ? accuracy : null,
    recordedAt: new Date(position.timestamp).toISOString(),
  }
}

/** Texte d etat du GPS, une seule source pour le conducteur (LiveSharingControl) et le passager (LiveTrackingCard). */
export function describeGeoStatus(status: GeoStatus, online: boolean): { text: string; tone: 'muted' | 'danger' | 'success' } | null {
  if (!GEO_SUPPORTED) return { text: 'Ce navigateur ne permet pas la géolocalisation.', tone: 'danger' }
  if (!online) return { text: 'Hors ligne : la position repartira au retour du réseau.', tone: 'danger' }
  switch (status) {
    case 'denied':
      return {
        text: 'Accès à la position refusé. Autorisez la localisation pour Ekuiseo dans les réglages du téléphone, puis réessayez.',
        tone: 'danger',
      }
    case 'unavailable':
      return { text: 'Position indisponible pour l’instant. Vérifiez que le GPS est activé.', tone: 'danger' }
    case 'paused':
      return { text: 'Partage en pause : revenez sur l’application pour continuer.', tone: 'muted' }
    case 'waiting':
      return { text: 'Recherche de la position GPS…', tone: 'muted' }
    default:
      return null
  }
}

export interface PositionSharing {
  geoStatus: GeoStatus
  /** Dernier envoi accepte (ISO), null tant que rien n est parti. */
  lastSentAt: string | null
  /** Dernier accuse du serveur (acceptee ou non, flags, cadence). */
  lastAck: LivePositionAck | null
  /** Cadence courante (s), celle du serveur des le premier accuse. */
  intervalSeconds: number
  /** Derniere position lue sur l appareil, envoyee ou non. */
  myPosition: (LatLng & { accuracyM: number | null }) | null
  sendFailed: boolean
  sendError: unknown
}

/**
 * @param active vrai pendant le partage (interrupteur actif ET fenetre du trajet ouverte)
 * @param initialIntervalSeconds cadence connue avant le premier accuse (instantane ou PUT /live)
 */
export function usePositionSharing({
  tripId,
  active,
  initialIntervalSeconds,
}: {
  tripId: string
  active: boolean
  initialIntervalSeconds?: number | null
}): PositionSharing {
  const post = usePostLivePosition()
  const { mutate: send, isError: sendFailed, error: sendError } = post
  const [geoStatus, setGeoStatus] = useState<GeoStatus>('idle')
  const [lastSentAt, setLastSentAt] = useState<string | null>(null)
  const [lastAck, setLastAck] = useState<LivePositionAck | null>(null)
  const [myPosition, setMyPosition] = useState<PositionSharing['myPosition']>(null)
  const [foreground, setForeground] = useState(true)
  // Cadence courante : celle du dernier accuse, sinon celle connue avant le premier envoi. Le
  // rappel de geolocalisation la lit via une ref, synchronisee hors du rendu.
  const intervalSeconds = lastAck?.intervalSeconds ?? initialIntervalSeconds ?? DEFAULT_INTERVAL_SECONDS
  const intervalRef = useRef(intervalSeconds)
  useEffect(() => {
    intervalRef.current = intervalSeconds
  }, [intervalSeconds])

  // Premier plan / arriere-plan : Capacitor dans l application, visibilite dans un navigateur.
  useEffect(() => {
    if (!active) return
    let disposed = false
    let removeNative: (() => void) | null = null
    const onVisibility = () => setForeground(document.visibilityState === 'visible')
    document.addEventListener('visibilitychange', onVisibility)
    if (isNativeApp()) {
      void (async () => {
        try {
          const { App } = await import('@capacitor/app')
          const listener = await App.addListener('appStateChange', ({ isActive }) => setForeground(isActive))
          if (disposed) await listener.remove()
          else removeNative = () => void listener.remove()
        } catch {
          /* greffon absent : la visibilite du document suffit */
        }
      })()
    }
    return () => {
      disposed = true
      document.removeEventListener('visibilitychange', onVisibility)
      removeNative?.()
    }
  }, [active])

  // Suivi de l appareil : l etat n est modifie que depuis les rappels de la geolocalisation.
  useEffect(() => {
    if (!active || !GEO_SUPPORTED) return
    if (!foreground) {
      // Suspendu en arriere-plan : le statut le dit, le suivi reprend au retour.
      const id = window.setTimeout(() => setGeoStatus('paused'), 0)
      return () => window.clearTimeout(id)
    }
    let last: (LatLng & { sentAt: number }) | null = null
    const watchId = navigator.geolocation.watchPosition(
      (position) => {
        const at = Date.now()
        const next = { lat: position.coords.latitude, lng: position.coords.longitude }
        setMyPosition({ ...next, accuracyM: Number.isFinite(position.coords.accuracy) ? position.coords.accuracy : null })
        setGeoStatus('ok')
        if (!shouldSendPosition(last, next, at, intervalRef.current * 1000)) return
        last = { ...next, sentAt: at }
        send(
          { tripId, position: toPositionRequest(position) },
          {
            onSuccess: (ack) => {
              setLastAck(ack)
              if (ack.accepted) setLastSentAt(new Date(at).toISOString())
            },
          },
        )
      },
      (error) => {
        setGeoStatus(error.code === error.PERMISSION_DENIED ? 'denied' : 'unavailable')
      },
      { enableHighAccuracy: true, maximumAge: 2_000, timeout: 20_000 },
    )
    const waiting = window.setTimeout(() => setGeoStatus((s) => (s === 'idle' || s === 'paused' ? 'waiting' : s)), 0)

    // Wake Lock : garde l ecran allume pendant le partage quand le navigateur le permet
    // (Chrome Android) ; refuse ou absent, on continue sans.
    let sentinel: WakeLockSentinel | null = null
    const requestWakeLock = async () => {
      try {
        sentinel = (await navigator.wakeLock?.request('screen')) ?? null
      } catch {
        sentinel = null
      }
    }
    void requestWakeLock()

    return () => {
      window.clearTimeout(waiting)
      navigator.geolocation.clearWatch(watchId)
      void sentinel?.release()
    }
  }, [active, foreground, tripId, send])

  // Fin du partage : on oublie l etat de la session precedente.
  useEffect(() => {
    if (active) return
    const id = window.setTimeout(() => {
      setGeoStatus('idle')
      setLastSentAt(null)
      setLastAck(null)
    }, 0)
    return () => window.clearTimeout(id)
  }, [active])

  return {
    geoStatus,
    lastSentAt,
    lastAck,
    intervalSeconds,
    myPosition,
    sendFailed,
    sendError,
  }
}
