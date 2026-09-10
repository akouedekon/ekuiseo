import { LocateFixed, WifiOff } from 'lucide-react'
import { useEffect, useId, useState } from 'react'
import { toast } from 'sonner'
import { Card } from '@/components/ui/card'
import { Switch } from '@/components/ui/misc'
import { ShareTripButton } from '@/components/trip/ShareTripButton'
import { usePostLivePosition, useSetLiveSharing, useTripLive } from '@/hooks/useTrips'
import { useNow } from '@/hooks/useNow'
import { useOnlineStatus } from '@/hooks/useNetwork'
import { cn } from '@/lib/cn'
import { describeError } from '@/lib/errors'
import { ageSeconds, formatAge, isSharingWindowOpen, shouldSendPosition } from '@/lib/liveTracking'
import type { LivePositionRequest, TripResponse } from '@/api/types'

/** Etat du GPS pendant le partage ; `waiting` tant qu aucune position n est arrivee. */
type GeoStatus = 'waiting' | 'ok' | 'denied' | 'unavailable'

/** Position du navigateur -> charge utile de l API ; cap et vitesse absents ou NaN deviennent null. */
function toRequest(position: GeolocationPosition): LivePositionRequest {
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

const GEO_SUPPORTED = typeof navigator !== 'undefined' && 'geolocation' in navigator

/**
 * Interrupteur « Partager ma position en direct » du conducteur (V23). Active le partage
 * cote serveur, puis suit la position du navigateur (haute precision) et l envoie au
 * plus toutes les 10 s ou tous les 50 m ; tout s arrete a la desactivation et au
 * demontage. Le Wake Lock est demande quand il existe, sans en dependre : le texte dit
 * clairement que le navigateur doit rester ouvert. N apparait que dans la fenetre du
 * trajet (d une heure avant le depart jusqu a la fin).
 */
export function LiveSharingControl({ trip, compact = false }: { trip: TripResponse; compact?: boolean }) {
  const windowOpen = isSharingWindowOpen(trip)
  const live = useTripLive(trip.id, { enabled: windowOpen, live: true })
  const setSharing = useSetLiveSharing()
  const postPosition = usePostLivePosition()
  const { mutate: sendPosition, isError: sendFailed, error: sendError } = postPosition
  const online = useOnlineStatus()
  const switchId = useId()
  const [geoStatus, setGeoStatus] = useState<GeoStatus>('waiting')
  const [lastSentAt, setLastSentAt] = useState<string | null>(null)

  const sharing = live.data?.enabled ?? false
  const now = useNow(sharing)

  // Suivi du navigateur pendant le partage : l etat n est modifie que depuis les rappels
  // asynchrones de la geolocalisation (jamais dans le corps de l effet).
  useEffect(() => {
    if (!sharing || !windowOpen || !GEO_SUPPORTED) return
    let last: { lat: number; lng: number; sentAt: number } | null = null
    const watchId = navigator.geolocation.watchPosition(
      (position) => {
        const at = Date.now()
        const next = { lat: position.coords.latitude, lng: position.coords.longitude }
        if (!shouldSendPosition(last, next, at)) return
        last = { ...next, sentAt: at }
        setGeoStatus('ok')
        sendPosition(
          { tripId: trip.id, position: toRequest(position) },
          { onSuccess: () => setLastSentAt(new Date(at).toISOString()) },
        )
      },
      (error) => {
        setGeoStatus(error.code === error.PERMISSION_DENIED ? 'denied' : 'unavailable')
      },
      { enableHighAccuracy: true, maximumAge: 5_000, timeout: 20_000 },
    )

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
    const onVisible = () => {
      if (document.visibilityState === 'visible') void requestWakeLock()
    }
    void requestWakeLock()
    document.addEventListener('visibilitychange', onVisible)

    return () => {
      navigator.geolocation.clearWatch(watchId)
      document.removeEventListener('visibilitychange', onVisible)
      void sentinel?.release()
    }
  }, [sharing, windowOpen, trip.id, sendPosition])

  if (!windowOpen) return null

  const toggle = (enabled: boolean) => {
    setSharing.mutate(
      { tripId: trip.id, enabled },
      {
        onSuccess: () => {
          // Reinitialise l etat du GPS a chaque (re)activation, depuis l evenement et non un effet.
          setGeoStatus('waiting')
          setLastSentAt(null)
          if (enabled) {
            toast.success('Partage activé', { description: 'Gardez cette page ouverte pendant le trajet.' })
          } else {
            toast.success('Partage arrêté')
          }
        },
        onError: (error) => toast.error(describeError(error)),
      },
    )
  }

  const shareToken = live.data?.shareToken ?? null
  let status: { text: string; tone: 'muted' | 'danger' | 'success' } = {
    text: 'Vos passagers verront votre position sur la carte du trajet. Le navigateur doit rester ouvert.',
    tone: 'muted',
  }
  if (sharing) {
    if (!GEO_SUPPORTED) status = { text: 'Ce navigateur ne permet pas la géolocalisation.', tone: 'danger' }
    else if (!online) status = { text: 'Hors ligne : la position repartira au retour du réseau.', tone: 'danger' }
    else if (geoStatus === 'denied')
      status = {
        text: 'Accès à la position refusé. Autorisez la localisation pour ce site dans les réglages du navigateur.',
        tone: 'danger',
      }
    else if (geoStatus === 'unavailable')
      status = { text: 'Position indisponible pour l’instant. Vérifiez que le GPS est activé.', tone: 'danger' }
    else if (sendFailed) status = { text: describeError(sendError), tone: 'danger' }
    else if (lastSentAt) status = { text: `Position partagée · ${formatAge(ageSeconds(lastSentAt, now))}`, tone: 'success' }
    else status = { text: 'Recherche de la position GPS…', tone: 'muted' }
  }

  const control = (
    <>
      <label htmlFor={switchId} className="flex min-w-0 flex-1 cursor-pointer items-center gap-3">
        <span
          className={cn(
            'flex size-9 shrink-0 items-center justify-center rounded-full',
            sharing ? 'bg-primary-soft text-primary-ink' : 'bg-surface-2 text-muted',
          )}
          aria-hidden
        >
          {sharing && !online ? <WifiOff className="size-4" /> : <LocateFixed className="size-4" />}
        </span>
        <span className="min-w-0">
          <span className="block text-body font-medium text-ink">Partager ma position en direct</span>
          <span
            className={cn(
              'block text-caption',
              status.tone === 'danger' ? 'text-danger-ink' : status.tone === 'success' ? 'text-success-ink' : 'text-muted',
            )}
            role="status"
          >
            {status.text}
          </span>
        </span>
      </label>
      <Switch
        id={switchId}
        checked={sharing}
        disabled={setSharing.isPending || live.isPending}
        onCheckedChange={toggle}
        aria-label="Partager ma position en direct"
      />
    </>
  )

  const share =
    sharing && shareToken ? (
      <ShareTripButton
        title={`Suivi en direct · ${trip.originLabel} → ${trip.destLabel}`}
        text={`Suivez ma position en direct sur Ekuiseo : ${trip.originLabel} → ${trip.destLabel}.`}
        path={`/live/${shareToken}`}
        size="sm"
        variant="ghost"
      />
    ) : null

  if (compact) {
    return (
      <div className="flex flex-wrap items-center gap-3 border-t border-rule px-4 py-3">
        {control}
        {share}
      </div>
    )
  }

  return (
    <Card className="p-4">
      <div className="flex items-center gap-3">{control}</div>
      {share ? <div className="mt-3 flex justify-end">{share}</div> : null}
    </Card>
  )
}
