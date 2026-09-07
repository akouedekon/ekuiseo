import { MessageSquare, Star, UserX } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { ReviewDialog } from '@/components/feedback/ReviewDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Avatar } from '@/components/ui/misc'
import { Sheet } from '@/components/ui/sheet'
import { EmptyState, ErrorState, ListSkeleton } from '@/components/ui/states'
import { useMarkNoShow, useTripPassengers } from '@/hooks/useTrips'
import { describeError } from '@/lib/errors'
import { formatFcfa, formatRelativeDay, formatTime } from '@/lib/format'
import type { TripBookingResponse, TripResponse } from '@/api/types'

/** Fenetre pendant laquelle le conducteur peut signaler une absence apres le depart (alignee sur le backend). */
const NO_SHOW_WINDOW_MS = 48 * 60 * 60 * 1000

const STATUS: Record<TripBookingResponse['status'], { label: string; tone: 'success' | 'neutral' | 'danger' | 'warning' }> = {
  CONFIRMED: { label: 'Confirmée', tone: 'success' },
  COMPLETED: { label: 'Terminée', tone: 'neutral' },
  NO_SHOW: { label: 'Absent', tone: 'danger' },
  PENDING_PAYMENT: { label: 'Acompte en attente', tone: 'warning' },
  CANCELLED_BY_DRIVER: { label: 'Annulée', tone: 'danger' },
  CANCELLED_BY_PASSENGER: { label: 'Annulée', tone: 'danger' },
  EXPIRED: { label: 'Expirée (acompte non reçu)', tone: 'neutral' },
}

/**
 * Passagers d un trajet que je conduis : liste d appel au depart (places, solde a
 * regler a bord), signalement d absence entre l heure de depart et 48 h apres, et
 * notation du passager une fois le trajet parti (confiance bilaterale, section 5 #20).
 * Le serveur n expose pas « deja note » par passager : l etat est garde localement
 * apres un avis enregistre.
 */
export function TripPassengersSheet({ trip, onOpenChange }: { trip: TripResponse | null; onOpenChange: (open: boolean) => void }) {
  const passengers = useTripPassengers(trip?.id ?? null)
  const markNoShow = useMarkNoShow()
  const [confirming, setConfirming] = useState<TripBookingResponse | null>(null)
  const [reviewing, setReviewing] = useState<TripBookingResponse | null>(null)
  const [reviewed, setReviewed] = useState<Set<string>>(() => new Set())

  const now = Date.now()
  const departureMs = trip ? new Date(trip.departureAt).getTime() : 0
  const departed = trip !== null && now >= departureMs
  const canReportNoShow = departed && now <= departureMs + NO_SHOW_WINDOW_MS

  const confirmNoShow = () => {
    if (!confirming || !trip) return
    markNoShow.mutate(
      { bookingId: confirming.id, tripId: trip.id },
      {
        onSuccess: () => toast.success('Absence signalée', { description: "L'acompte reste acquis et vous sera reversé." }),
        onError: (error) => toast.error(describeError(error, "Le signalement n'a pas pu être enregistré.")),
        onSettled: () => setConfirming(null),
      },
    )
  }

  const list = passengers.data ?? []
  const seats = list.filter((p) => p.status !== 'NO_SHOW').reduce((sum, p) => sum + p.seats, 0)
  const cashDue = list.filter((p) => p.status !== 'NO_SHOW').reduce((sum, p) => sum + p.balanceDueOnBoard, 0)

  return (
    <>
      <Sheet
        open={trip !== null}
        onOpenChange={onOpenChange}
        title="Passagers"
        description={
          trip
            ? `${trip.originLabel} → ${trip.destLabel} · ${formatRelativeDay(trip.departureAt)} à ${formatTime(trip.departureAt)}`
            : undefined
        }
      >
        {passengers.isPending ? (
          <ListSkeleton count={2} />
        ) : passengers.isError ? (
          <ErrorState onRetry={() => passengers.refetch()} />
        ) : list.length === 0 ? (
          <EmptyState icon={UserX} title="Aucun passager" description="Personne n'a encore réservé ce trajet." />
        ) : (
          <div className="space-y-3">
            <p className="tnum text-label text-muted">
              {seats} place{seats > 1 ? 's' : ''} réservée{seats > 1 ? 's' : ''}
              {cashDue > 0 ? ` · ${formatFcfa(cashDue)} à encaisser à bord` : ''}
            </p>
            <ul className="divide-y divide-rule rounded-[var(--radius-control)] border border-rule">
              {list.map((p) => {
                const status = STATUS[p.status]
                const honoured = p.status === 'CONFIRMED' || p.status === 'COMPLETED'
                const canReview = departed && honoured && !reviewed.has(p.id)
                return (
                  <li key={p.id} className="flex flex-wrap items-center gap-x-3 gap-y-2 px-3 py-2.5">
                    <Avatar firstName={p.firstName} lastName={p.lastName ?? ''} photoUrl={p.photoUrl} size={36} />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-body font-semibold">
                        {p.firstName} {p.lastName ? `${p.lastName.charAt(0)}.` : ''}
                      </p>
                      <p className="tnum text-caption text-muted">
                        {p.seats} place{p.seats > 1 ? 's' : ''}
                        {p.balanceDueOnBoard > 0 ? ` · ${formatFcfa(p.balanceDueOnBoard)} à bord` : ' · réglé'}
                      </p>
                    </div>
                    <Badge tone={status.tone}>{status.label}</Badge>
                    <span className="flex items-center gap-1">
                      <Button asChild variant="ghost" size="icon" aria-label={`Écrire à ${p.firstName}`}>
                        <Link to={`/bookings/${p.id}/messages`}>
                          <MessageSquare className="size-4" aria-hidden />
                        </Link>
                      </Button>
                      {canReview ? (
                        <Button variant="ghost" size="sm" onClick={() => setReviewing(p)}>
                          <Star className="size-4" aria-hidden />
                          Noter
                        </Button>
                      ) : reviewed.has(p.id) ? (
                        <Badge tone="neutral">Noté</Badge>
                      ) : null}
                      {canReportNoShow && honoured ? (
                        <Button variant="ghost" size="sm" className="text-danger-ink" onClick={() => setConfirming(p)}>
                          Absent
                        </Button>
                      ) : null}
                    </span>
                  </li>
                )
              })}
            </ul>
            {!departed ? (
              <p className="text-caption text-muted">
                Après le départ, vous pourrez noter chaque passager et signaler une absence pendant 48 h.
              </p>
            ) : null}
          </div>
        )}
      </Sheet>

      <ConfirmDialog
        open={confirming !== null}
        onOpenChange={(open) => !open && setConfirming(null)}
        title={confirming ? `Signaler ${confirming.firstName} absent ?` : 'Signaler une absence ?'}
        description="Le passager ne s'est pas présenté au départ. Son acompte reste acquis et vous sera reversé ; il en sera informé. Cette action est définitive."
        tone="danger"
        confirmLabel="Confirmer l'absence"
        loading={markNoShow.isPending}
        onConfirm={confirmNoShow}
      />

      {reviewing && trip ? (
        <ReviewDialog
          open
          onOpenChange={(open) => !open && setReviewing(null)}
          tripId={trip.id}
          role="PASSENGER"
          target={{ id: reviewing.passengerId, name: reviewing.firstName }}
          onReviewed={() => setReviewed((current) => new Set(current).add(reviewing.id))}
        />
      ) : null}
    </>
  )
}
