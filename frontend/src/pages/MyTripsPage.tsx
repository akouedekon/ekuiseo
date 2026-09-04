import { motion } from 'motion/react'
import {
  Ban,
  Car,
  CheckCircle2,
  ChevronRight,
  Clock,
  History,
  MessageSquare,
  PlusCircle,
  Ticket,
  Users,
  XCircle,
} from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Avatar, Separator } from '@/components/ui/misc'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { EmptyState, ErrorState, ListSkeleton } from '@/components/ui/states'
import { PageContainer, PageHeader } from '@/components/layout/PageContainer'
import { DepositCountdown } from '@/components/booking/Countdown'
import { useCancelBooking, useMyBookings } from '@/hooks/useBookings'
import { useCancelTrip, useMyTrips } from '@/hooks/useTrips'
import { formatFcfa, formatRelativeDay, formatTime } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'
import type { BookingDetailResponse } from '@/api/extended'
import type { BookingStatus, TripResponse } from '@/api/types'

type TabKey = 'upcoming' | 'past' | 'driving'

/** Traduction et couleur de chaque etat de reservation — une seule source. */
const BOOKING_STATUS: Record<
  BookingStatus,
  { label: string; tone: 'success' | 'warning' | 'danger' | 'neutral'; icon: typeof CheckCircle2 }
> = {
  CONFIRMED: { label: 'Confirmée', tone: 'success', icon: CheckCircle2 },
  PENDING_PAYMENT: { label: 'Acompte en attente', tone: 'warning', icon: Clock },
  CANCELLED_BY_PASSENGER: { label: 'Annulée par vous', tone: 'danger', icon: XCircle },
  CANCELLED_BY_DRIVER: { label: 'Annulée par le conducteur', tone: 'danger', icon: Ban },
  COMPLETED: { label: 'Terminée', tone: 'neutral', icon: History },
  NO_SHOW: { label: 'Non présenté', tone: 'danger', icon: XCircle },
}

function isPastBooking(booking: BookingDetailResponse): boolean {
  return (
    booking.status === 'COMPLETED' ||
    booking.status === 'NO_SHOW' ||
    booking.status === 'CANCELLED_BY_DRIVER' ||
    booking.status === 'CANCELLED_BY_PASSENGER' ||
    new Date(booking.trip.departureAt).getTime() < Date.now()
  )
}

export function MyTripsPage({ defaultTab = 'upcoming' }: { defaultTab?: TabKey }) {
  const [tab, setTab] = useState<TabKey>(defaultTab)
  const bookings = useMyBookings()
  const trips = useMyTrips()
  const cancelBooking = useCancelBooking()
  const cancelTrip = useCancelTrip()
  const [confirm, setConfirm] = useState<{ kind: 'booking' | 'trip'; id: string; label: string } | null>(null)

  const bookingList = bookings.data?.data ?? []
  const upcoming = bookingList.filter((b) => !isPastBooking(b))
  const past = bookingList.filter(isPastBooking)
  const driving = trips.data?.data ?? []

  const confirmCancel = () => {
    if (!confirm) return
    if (confirm.kind === 'booking') {
      cancelBooking.mutate(confirm.id, {
        onSuccess: () => toast.success('Réservation annulée'),
        onError: () => toast.error("L'annulation a échoué."),
      })
    } else {
      cancelTrip.mutate(confirm.id, {
        onSuccess: () => toast.success('Trajet annulé', { description: 'Les passagers ont été prévenus.' }),
        onError: () => toast.error("L'annulation a échoué."),
      })
    }
    setConfirm(null)
  }

  return (
    <PageContainer width="md">

      <PageHeader
        title="Mes trajets"
        back={false}
        subtitle="Vos réservations et les trajets que vous conduisez"
        actions={
          <Button asChild size="sm" variant="secondary">
            <Link to="/publish">
              <PlusCircle className="size-4" aria-hidden />
              <span className="hidden sm:inline">Publier</span>
            </Link>
          </Button>
        }
      />

      <Tabs value={tab} onValueChange={(value) => setTab(value as TabKey)}>
        <TabsList>
          <TabsTrigger value="upcoming">
            À venir
            {upcoming.length > 0 ? <CountPill>{upcoming.length}</CountPill> : null}
          </TabsTrigger>
          <TabsTrigger value="past">Passés</TabsTrigger>
          <TabsTrigger value="driving">
            Je conduis
            {driving.length > 0 ? <CountPill>{driving.length}</CountPill> : null}
          </TabsTrigger>
        </TabsList>

        <TabsContent value="upcoming">
          {bookings.isPending ? (
            <ListSkeleton count={3} />
          ) : bookings.isError ? (
            <ErrorState onRetry={() => bookings.refetch()} />
          ) : upcoming.length === 0 ? (
            <EmptyState
              icon={Ticket}
              title="Aucun trajet à venir"
              description="Vos prochaines réservations apparaîtront ici."
              action={
                <Button asChild>
                  <Link to="/">Chercher un trajet</Link>
                </Button>
              }
            />
          ) : (
            // Pas d'AnimatePresence intercalee ici : elle couperait la
            // propagation des variantes et les cartes resteraient invisibles.
            <motion.div variants={listContainer} initial="hidden" animate="show" className="space-y-3">
              {upcoming.map((booking) => (
                <BookingCard
                  key={booking.id}
                  booking={booking}
                  onCancel={() =>
                    setConfirm({
                      kind: 'booking',
                      id: booking.id,
                      label: `${booking.trip.originLabel} → ${booking.trip.destLabel}`,
                    })
                  }
                />
              ))}
            </motion.div>
          )}
        </TabsContent>

        <TabsContent value="past">
          {bookings.isPending ? (
            <ListSkeleton count={2} />
          ) : past.length === 0 ? (
            <EmptyState icon={History} title="Rien dans l'historique" description="Vos trajets passés seront listés ici." />
          ) : (
            <motion.div variants={listContainer} initial="hidden" animate="show" className="space-y-3">
              {past.map((booking) => (
                <BookingCard key={booking.id} booking={booking} past />
              ))}
            </motion.div>
          )}
        </TabsContent>

        <TabsContent value="driving">
          {trips.isPending ? (
            <ListSkeleton count={2} />
          ) : trips.isError ? (
            <ErrorState onRetry={() => trips.refetch()} />
          ) : driving.length === 0 ? (
            <EmptyState
              icon={Car}
              title="Vous ne conduisez aucun trajet"
              description="Publiez un trajet et partagez vos frais de route."
              action={
                <Button asChild>
                  <Link to="/publish">Publier un trajet</Link>
                </Button>
              }
            />
          ) : (
            <motion.div variants={listContainer} initial="hidden" animate="show" className="space-y-3">
              {driving.map((trip) => (
                <DrivingCard
                  key={trip.id}
                  trip={trip}
                  onCancel={() =>
                    setConfirm({ kind: 'trip', id: trip.id, label: `${trip.originLabel} → ${trip.destLabel}` })
                  }
                />
              ))}
            </motion.div>
          )}
        </TabsContent>
      </Tabs>

      <Dialog open={confirm !== null} onOpenChange={(open) => !open && setConfirm(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {confirm?.kind === 'trip' ? 'Annuler ce trajet ?' : 'Annuler cette réservation ?'}
            </DialogTitle>
            <DialogDescription>
              {confirm?.kind === 'trip'
                ? `Les passagers de ${confirm?.label} seront prévenus et intégralement remboursés.`
                : `Trajet ${confirm?.label}. Si le départ est dans moins de 24 h, l'acompte reste acquis au conducteur.`}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setConfirm(null)}>
              Revenir
            </Button>
            <Button variant="danger" onClick={confirmCancel}>
              Confirmer l'annulation
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </PageContainer>
  )
}

/** Route /bookings : meme ecran, onglet « À venir » en premier. */
export function MyBookingsPage() {
  return <MyTripsPage defaultTab="upcoming" />
}

/* ---------------------------------------------------------------- Cartes */

function CountPill({ children }: { children: React.ReactNode }) {
  return (
    <span className="tnum ml-1 flex min-w-[18px] items-center justify-center rounded-full bg-[var(--indigo)] px-1 text-[11px] font-bold leading-[18px] text-[var(--indigo-contrast)]">
      {children}
    </span>
  )
}

function BookingCard({
  booking,
  onCancel,
  past = false,
}: {
  booking: BookingDetailResponse
  onCancel?: () => void
  past?: boolean
}) {
  const status = BOOKING_STATUS[booking.status]
  const StatusIcon = status.icon
  const pending = booking.status === 'PENDING_PAYMENT'
  const cancelled = booking.status.startsWith('CANCELLED')
  const deadline = booking.paymentPlan.depositDueAt ? new Date(booking.paymentPlan.depositDueAt).getTime() : null

  return (
    <motion.div variants={listItem} layout>
      <Card
        className={
          // L'etat se lit d'abord au filet lateral, avant meme de lire la puce.
          pending
            ? 'border-l-[3px] border-l-[var(--ocre)]'
            : cancelled
              ? 'border-l-[3px] border-l-[var(--vermillon)] opacity-80'
              : booking.status === 'CONFIRMED'
                ? 'border-l-[3px] border-l-[var(--vert)]'
                : 'border-l-[3px] border-l-rule-strong'
        }
      >
        <div className="p-4">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="truncate font-display text-[16px] font-bold leading-tight">
                {booking.trip.originLabel} → {booking.trip.destLabel}
              </p>
              <p className="tnum mt-0.5 text-[13px] text-muted">
                {formatRelativeDay(booking.trip.departureAt)} · {formatTime(booking.trip.departureAt)} ·{' '}
                {booking.seats} place{booking.seats > 1 ? 's' : ''}
              </p>
            </div>
            <Badge tone={status.tone} className="shrink-0">
              <StatusIcon aria-hidden />
              {status.label}
            </Badge>
          </div>

          {pending && deadline ? <DepositCountdown deadline={deadline} className="mt-3" /> : null}

          <Separator className="my-3" />

          <div className="flex items-center gap-2">
            <Avatar
              firstName={booking.trip.driver.firstName}
              lastName={booking.trip.driver.lastName}
              photoUrl={booking.trip.driver.photoUrl}
              size={28}
            />
            <span className="min-w-0 flex-1 truncate text-[13px] font-medium">
              {booking.trip.driver.firstName} {booking.trip.driver.lastName}
            </span>
            <span className="tnum shrink-0 text-right">
              <span className="block font-display text-[16px] font-bold leading-none">
                {formatFcfa(booking.amount)}
              </span>
              {!past && booking.status === 'CONFIRMED' ? (
                <span className="block text-[11px] text-muted">
                  {booking.paymentPlan.balanceAmount > 0
                    ? `dont ${formatFcfa(booking.paymentPlan.balanceAmount)} à bord`
                    : 'réglé intégralement'}
                </span>
              ) : null}
            </span>
          </div>
        </div>

        {!past ? (
          <div className="flex items-center gap-2 border-t border-rule px-3 py-2">
            {pending ? (
              <Button asChild size="sm" className="flex-1">
                <Link to={`/book/${booking.tripId}`}>Régler l'acompte</Link>
              </Button>
            ) : null}
            <Button asChild variant="ghost" size="sm" className="relative">
              <Link to={`/bookings/${booking.id}/messages`}>
                <MessageSquare className="size-4" aria-hidden />
                Messages
                {booking.unreadMessages > 0 ? (
                  <span className="tnum ml-1 flex size-4 items-center justify-center rounded-full bg-[var(--vermillon)] text-[10px] font-bold text-[var(--vermillon-contrast)]">
                    {booking.unreadMessages}
                  </span>
                ) : null}
              </Link>
            </Button>
            {!cancelled && onCancel ? (
              <Button variant="ghost" size="sm" className="ml-auto text-[var(--vermillon)]" onClick={onCancel}>
                Annuler
              </Button>
            ) : null}
          </div>
        ) : null}
      </Card>
    </motion.div>
  )
}

function DrivingCard({ trip, onCancel }: { trip: TripResponse; onCancel: () => void }) {
  const cancelled = trip.status === 'CANCELLED'
  const completed = trip.status === 'COMPLETED'
  const booked = trip.seatsTotal - trip.seatsAvailable

  return (
    <motion.div variants={listItem} layout>
      <Card
        className={
          cancelled
            ? 'border-l-[3px] border-l-[var(--vermillon)] opacity-80'
            : completed
              ? 'border-l-[3px] border-l-rule-strong'
              : 'border-l-[3px] border-l-[var(--indigo)]'
        }
      >
        <Link to={`/trips/${trip.id}`} className="block p-4 transition-colors hover:bg-[var(--surface-calm)]">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="truncate font-display text-[16px] font-bold leading-tight">
                {trip.originLabel} → {trip.destLabel}
              </p>
              <p className="tnum mt-0.5 text-[13px] text-muted">
                {formatRelativeDay(trip.departureAt)} · {formatTime(trip.departureAt)}
              </p>
            </div>
            <div className="flex shrink-0 items-center gap-2">
              <Badge
                tone={cancelled ? 'danger' : completed ? 'neutral' : booked > 0 ? 'success' : 'warning'}
              >
                <Users aria-hidden />
                {booked}/{trip.seatsTotal}
              </Badge>
              <ChevronRight className="size-4 text-muted" aria-hidden />
            </div>
          </div>
          <p className="tnum mt-2 text-[13px] text-ink-2">
            {formatFcfa(trip.pricePerSeat)} par place · revenu attendu{' '}
            <span className="font-semibold">{formatFcfa(trip.pricePerSeat * booked)}</span>
          </p>
        </Link>
        {!cancelled && !completed ? (
          <div className="flex justify-end border-t border-rule px-3 py-2">
            <Button variant="ghost" size="sm" className="text-[var(--vermillon)]" onClick={onCancel}>
              Annuler le trajet
            </Button>
          </div>
        ) : null}
      </Card>
    </motion.div>
  )
}
