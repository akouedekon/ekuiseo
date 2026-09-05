import { MessageSquare, UserX } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
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
}

/**
 * Passagers d un trajet que je conduis : liste d appel au depart (places, solde a
 * regler a bord) et signalement d absence entre l heure de depart et 48 h apres.
 */
export function TripPassengersSheet({ trip, onOpenChange }: { trip: TripResponse | null; onOpenChange: (open: boolean) => void }) {
  const passengers = useTripPassengers(trip?.id ?? null)
  const markNoShow = useMarkNoShow()
  const [confirming, setConfirming] = useState<TripBookingResponse | null>(null)

  const now = Date.now()
  const departureMs = trip ? new Date(trip.departureAt).getTime() : 0
  const canReportNoShow = trip !== null && now >= departureMs && now <= departureMs + NO_SHOW_WINDOW_MS

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
            <p className="tnum text-[13px] text-muted">
              {seats} place{seats > 1 ? 's' : ''} réservée{seats > 1 ? 's' : ''}
              {cashDue > 0 ? ` · ${formatFcfa(cashDue)} à encaisser à bord` : ''}
            </p>
            <ul className="divide-y divide-rule rounded-[var(--radius-control)] border border-rule">
              {list.map((p) => {
                const status = STATUS[p.status]
                return (
                  <li key={p.id} className="flex items-center gap-3 px-3 py-2.5">
                    <Avatar firstName={p.firstName} lastName={p.lastName ?? ''} photoUrl={p.photoUrl} size={36} />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-[14px] font-semibold">
                        {p.firstName} {p.lastName ? `${p.lastName.charAt(0)}.` : ''}
                      </p>
                      <p className="tnum text-[12px] text-muted">
                        {p.seats} place{p.seats > 1 ? 's' : ''}
                        {p.balanceDueOnBoard > 0 ? ` · ${formatFcfa(p.balanceDueOnBoard)} à bord` : ' · réglé'}
                      </p>
                    </div>
                    <Badge tone={status.tone}>{status.label}</Badge>
                    <Button asChild variant="ghost" size="sm" aria-label="Messages">
                      <Link to={`/bookings/${p.id}/messages`}>
                        <MessageSquare className="size-4" aria-hidden />
                      </Link>
                    </Button>
                    {canReportNoShow && (p.status === 'CONFIRMED' || p.status === 'COMPLETED') ? (
                      <Button variant="ghost" size="sm" className="text-[var(--vermillon)]" onClick={() => setConfirming(p)}>
                        Absent
                      </Button>
                    ) : null}
                  </li>
                )
              })}
            </ul>
            {!canReportNoShow && now < departureMs ? (
              <p className="text-[12px] text-muted">Vous pourrez signaler un passager absent après l'heure de départ.</p>
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
    </>
  )
}
