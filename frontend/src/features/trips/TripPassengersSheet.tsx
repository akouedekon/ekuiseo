import { Check, MessageSquare, Star, UserX, X } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { ReviewDialog } from '@/components/feedback/ReviewDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/input'
import { Avatar } from '@/components/ui/misc'
import { Sheet } from '@/components/ui/sheet'
import { EmptyState, ErrorState, ListSkeleton } from '@/components/ui/states'
import { useMarkNoShow, useRespondToBooking, useTripPassengers } from '@/hooks/useTrips'
import { describeError } from '@/lib/errors'
import { formatDateTime, formatFcfa, formatRelativeDay, formatTime } from '@/lib/format'
import { BOOKING_STATUS_LABEL } from '@/lib/labels'
import type { TripBookingResponse, TripResponse } from '@/api/types'

/** Fenetre pendant laquelle le conducteur peut signaler une absence apres le depart (alignee sur le backend). */
const NO_SHOW_WINDOW_MS = 48 * 60 * 60 * 1000

const STATUS_TONE: Record<TripBookingResponse['status'], 'success' | 'neutral' | 'danger' | 'warning'> = {
  CONFIRMED: 'success',
  COMPLETED: 'neutral',
  NO_SHOW: 'danger',
  DRIVER_NO_SHOW: 'danger',
  PENDING_PAYMENT: 'warning',
  PENDING_DRIVER_APPROVAL: 'warning',
  CANCELLED_BY_DRIVER: 'danger',
  CANCELLED_BY_PASSENGER: 'danger',
  EXPIRED: 'neutral',
}

type Decision = { booking: TripBookingResponse; accept: boolean }

/**
 * Passagers d un trajet que je conduis : demandes a accepter ou refuser (trajet sans
 * reservation immediate, V19), liste d appel au depart (places, solde a regler a bord),
 * signalement d absence entre l heure de depart et 48 h apres, et notation du passager
 * une fois le trajet parti (confiance bilaterale, section 5 #20).
 * Le serveur n expose pas « deja note » par passager : l etat est garde localement
 * apres un avis enregistre.
 */
export function TripPassengersSheet({ trip, onOpenChange }: { trip: TripResponse | null; onOpenChange: (open: boolean) => void }) {
  const passengers = useTripPassengers(trip?.id ?? null)
  const markNoShow = useMarkNoShow()
  const respond = useRespondToBooking()
  const [confirming, setConfirming] = useState<TripBookingResponse | null>(null)
  const [deciding, setDeciding] = useState<Decision | null>(null)
  const [declineReason, setDeclineReason] = useState('')
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

  const closeDecision = () => {
    setDeciding(null)
    setDeclineReason('')
  }

  const confirmDecision = () => {
    if (!deciding || !trip) return
    const { booking, accept } = deciding
    respond.mutate(
      { bookingId: booking.id, tripId: trip.id, accept, reason: accept ? undefined : declineReason.trim() || undefined },
      {
        onSuccess: () => {
          toast.success(accept ? `${booking.firstName} est confirmé` : 'Demande refusée', {
            description: accept
              ? 'Le passager est prévenu : sa place est réservée.'
              : 'Le passager est prévenu et remboursé intégralement ; la place est de nouveau disponible.',
          })
          closeDecision()
        },
        onError: (error) => toast.error(describeError(error, "La réponse n'a pas pu être enregistrée.")),
      },
    )
  }

  const list = passengers.data ?? []
  const requests = list.filter((p) => p.status === 'PENDING_DRIVER_APPROVAL')
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
              {requests.length > 0 ? ` · ${requests.length} demande${requests.length > 1 ? 's' : ''} à traiter` : ''}
              {cashDue > 0 ? ` · ${formatFcfa(cashDue)} à encaisser à bord` : ''}
            </p>
            <ul className="divide-y divide-rule rounded-[var(--radius-control)] border border-rule">
              {list.map((p) => {
                const awaiting = p.status === 'PENDING_DRIVER_APPROVAL'
                const honoured = p.status === 'CONFIRMED' || p.status === 'COMPLETED'
                const canReview = departed && honoured && !reviewed.has(p.id)
                return (
                  <li key={p.id} className={awaiting ? 'bg-accent-soft px-3 py-2.5' : 'px-3 py-2.5'}>
                    <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
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
                      <Badge tone={STATUS_TONE[p.status]}>{BOOKING_STATUS_LABEL[p.status]}</Badge>
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
                    </div>
                    {awaiting && !departed ? (
                      <div className="mt-2 flex flex-wrap items-center gap-2">
                        <p className="tnum flex-1 text-caption text-accent-ink">
                          {p.approvalDeadlineAt
                            ? `Répondez avant le ${formatDateTime(p.approvalDeadlineAt)} ; sinon la demande est refusée et le passager remboursé.`
                            : 'Répondez rapidement ; sans réponse, la demande est refusée et le passager remboursé.'}
                        </p>
                        <Button size="sm" variant="ghost" className="text-danger-ink" onClick={() => setDeciding({ booking: p, accept: false })}>
                          <X className="size-4" aria-hidden />
                          Refuser
                        </Button>
                        <Button size="sm" variant="success" onClick={() => setDeciding({ booking: p, accept: true })}>
                          <Check className="size-4" aria-hidden />
                          Accepter
                        </Button>
                      </div>
                    ) : null}
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

      <ConfirmDialog
        open={deciding !== null}
        onOpenChange={(open) => !open && closeDecision()}
        title={
          deciding
            ? deciding.accept
              ? `Accepter ${deciding.booking.firstName} ?`
              : `Refuser la demande de ${deciding.booking.firstName} ?`
            : 'Répondre à la demande ?'
        }
        description={
          deciding
            ? deciding.accept
              ? `${deciding.booking.seats} place${deciding.booking.seats > 1 ? 's' : ''} confirmée${deciding.booking.seats > 1 ? 's' : ''} pour ${deciding.booking.firstName}${
                  deciding.booking.balanceDueOnBoard > 0 ? `, ${formatFcfa(deciding.booking.balanceDueOnBoard)} à encaisser à bord` : ''
                }. Le passager est prévenu immédiatement.`
              : "Les places sont libérées et tout acompte versé est remboursé intégralement au passager. Un refus n'est pas compté comme une annulation tardive."
            : undefined
        }
        tone={deciding?.accept ? 'default' : 'danger'}
        confirmLabel={deciding?.accept ? 'Accepter' : 'Refuser'}
        loading={respond.isPending}
        onConfirm={confirmDecision}
      >
        {deciding && !deciding.accept ? (
          <Textarea
            label="Motif (facultatif)"
            hint="Transmis au passager tel quel. 300 caractères maximum."
            rows={3}
            maxLength={300}
            value={declineReason}
            onChange={(event) => setDeclineReason(event.target.value)}
          />
        ) : null}
      </ConfirmDialog>

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
