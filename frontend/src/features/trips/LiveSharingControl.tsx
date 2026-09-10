import { LocateFixed, Users, WifiOff } from 'lucide-react'
import { useId } from 'react'
import { toast } from 'sonner'
import { Card } from '@/components/ui/card'
import { Switch } from '@/components/ui/misc'
import { ShareTripButton } from '@/components/trip/ShareTripButton'
import { useLiveStream, type LiveView } from '@/hooks/useLiveStream'
import { useNow } from '@/hooks/useNow'
import { useOnlineStatus } from '@/hooks/useNetwork'
import { useSetLiveSharing } from '@/hooks/useTrips'
import { cn } from '@/lib/cn'
import { describeError } from '@/lib/errors'
import { ageSeconds, formatAge, formatDistanceShort, isPositionStale, isSharingWindowOpen, remainingDistanceKm } from '@/lib/liveTracking'
import type { TripResponse } from '@/api/types'
import { describeGeoStatus, usePositionSharing } from './usePositionSharing'

/**
 * Interrupteur « Partager ma position en direct » du conducteur (V23/V28). Active le
 * partage cote serveur, puis suit la position de l appareil et l envoie a la cadence
 * recommandee par le serveur (30 s, 15 s, 5 s a l approche d un passager) ; tout s arrete
 * a la desactivation, au demontage et en arriere-plan. Montre aussi les passagers qui
 * partagent leur position, avec la distance qui les separe du vehicule. N apparait que
 * dans la fenetre du trajet (d une heure avant le depart jusqu a la fin).
 *
 * `live` : vue du suivi deja ouverte par la page (fiche du trajet) ; sans elle, le
 * composant ouvre la sienne (liste « Mes trajets »).
 */
export function LiveSharingControl({
  trip,
  compact = false,
  live,
  showShare = true,
}: {
  trip: TripResponse
  compact?: boolean
  live?: LiveView
  /** Bouton du lien public ; a masquer quand l ecran en porte deja un (LiveTrackingCard). */
  showShare?: boolean
}) {
  const windowOpen = isSharingWindowOpen(trip)
  const ownLive = useLiveStream(trip.id, { enabled: windowOpen && !live, isDriver: true })
  const view = live ?? ownLive
  const setSharing = useSetLiveSharing()
  const online = useOnlineStatus()
  const switchId = useId()
  const sharing = view.sharingEnabled
  const sharingActive = sharing && windowOpen
  const position = usePositionSharing({ tripId: trip.id, active: sharingActive, initialIntervalSeconds: view.intervalSeconds })
  const now = useNow(sharingActive || view.passengers.length > 0)

  if (!windowOpen) return null

  const toggle = (enabled: boolean) => {
    setSharing.mutate(
      { tripId: trip.id, enabled },
      {
        onSuccess: () => {
          if (enabled) {
            toast.success('Partage activé', { description: 'Gardez l’application ouverte pendant le trajet.' })
          } else {
            toast.success('Partage arrêté')
          }
        },
        onError: (error) => toast.error(describeError(error)),
      },
    )
  }

  const shareToken = view.snapshot.data?.shareToken ?? null
  let status: { text: string; tone: 'muted' | 'danger' | 'success' } = {
    text: 'Vos passagers verront votre position sur la carte du trajet. L’application doit rester ouverte.',
    tone: 'muted',
  }
  if (sharing) {
    const geo = describeGeoStatus(position.geoStatus, online)
    if (geo) status = geo
    else if (position.sendFailed) status = { text: describeError(position.sendError), tone: 'danger' }
    else if (position.lastAck && !position.lastAck.accepted)
      status = { text: 'Dernière position non retenue par le serveur (hors zone ou saut improbable). Le suivi continue.', tone: 'danger' }
    else if (position.lastSentAt)
      status = { text: `Position partagée · ${formatAge(ageSeconds(position.lastSentAt, now))} · toutes les ${position.intervalSeconds} s`, tone: 'success' }
    else status = { text: 'Recherche de la position GPS…', tone: 'muted' }
  }

  // Distance de chaque passager au vehicule : ma position GPS d abord, sinon celle que le serveur me connait.
  const origin = position.myPosition ?? view.driver
  const passengers = view.passengers.map((p) => {
    const age = view.ageOf(p, now)
    return {
      key: p.bookingId ?? p.firstName,
      firstName: p.firstName,
      distance: origin ? formatDistanceShort(remainingDistanceKm(origin, p)) : null,
      age,
      stale: isPositionStale(age),
    }
  })

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
        disabled={setSharing.isPending || (view.snapshot.isPending && !view.snapshot.data)}
        onCheckedChange={toggle}
        aria-label="Partager ma position en direct"
      />
    </>
  )

  const share =
    showShare && sharing && shareToken ? (
      <ShareTripButton
        title={`Suivi en direct · ${trip.originLabel} → ${trip.destLabel}`}
        text={`Suivez ma position en direct sur Ekuiseo : ${trip.originLabel} → ${trip.destLabel}.`}
        path={`/live/${shareToken}`}
        size="sm"
        variant="ghost"
      />
    ) : null

  const passengerList =
    passengers.length > 0 ? (
      <ul className="mt-3 space-y-1.5" aria-label="Passagers qui partagent leur position">
        {passengers.map((p) => (
          <li key={p.key} className="flex items-center gap-2 text-label">
            <Users className="size-4 shrink-0 text-accent-ink" aria-hidden />
            <span className="font-medium text-ink">{p.firstName}</span>
            <span className={cn('tnum ml-auto', p.stale ? 'text-danger-ink' : 'text-muted')}>
              {p.distance ? `à ${p.distance} · ` : ''}
              {p.stale ? `dernière position ${formatAge(p.age)}` : formatAge(p.age)}
            </span>
          </li>
        ))}
      </ul>
    ) : null

  if (compact) {
    return (
      <div className="border-t border-rule px-4 py-3">
        <div className="flex flex-wrap items-center gap-3">
          {control}
          {share}
        </div>
        {passengerList}
      </div>
    )
  }

  return (
    <Card className="p-4">
      <div className="flex items-center gap-3">{control}</div>
      {passengerList}
      {share ? <div className="mt-3 flex justify-end">{share}</div> : null}
    </Card>
  )
}
