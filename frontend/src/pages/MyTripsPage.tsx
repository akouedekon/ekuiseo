import { m } from 'motion/react'
import {
  Ban,
  Car,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Clock,
  History,
  Hourglass,
  MessageSquare,
  Pencil,
  PlusCircle,
  Repeat,
  Star,
  Ticket,
  TimerOff,
  Users,
  XCircle,
} from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { ReviewDialog } from '@/components/feedback/ReviewDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Textarea } from '@/components/ui/input'
import { Avatar, Separator } from '@/components/ui/misc'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { EmptyState, ErrorState, ListSkeleton } from '@/components/ui/states'
import { PageContainer, PageHeader } from '@/components/layout/PageContainer'
import { PageMeta } from '@/components/layout/PageMeta'
import { DepositCountdown } from '@/components/booking/Countdown'
import { EditTripSheet } from '@/features/trips/EditTripSheet'
import { LiveSharingControl } from '@/features/trips/LiveSharingControl'
import { TripPassengersSheet } from '@/features/trips/TripPassengersSheet'
import { useCancelBooking, useConfirmTripDone, useMyBookings, useReportDriverNoShow } from '@/hooks/useBookings'
import { useCancelTrip, useMyTrips } from '@/hooks/useTrips'
import { cn } from '@/lib/cn'
import { describeError } from '@/lib/errors'
import { formatDateTime, formatDayShort, formatFcfa, formatRelativeDay, formatTime } from '@/lib/format'
import { BOOKING_STATUS_LABEL } from '@/lib/labels'
import { listContainer, listItem } from '@/lib/motion'
import { isSharingWindowOpen } from '@/lib/liveTracking'
import { tripConfirmationState } from '@/lib/tripConfirmation'
import type { BookingDetailResponse } from '@/api/extended'
import type { BookingStatus, TripResponse } from '@/api/types'

type TabKey = 'upcoming' | 'past' | 'driving'
const TABS: TabKey[] = ['upcoming', 'past', 'driving']
type DrivingScope = 'upcoming' | 'past'

/** Tableau vide partage : reference stable pour les memos tant que la requete n'a pas repondu. */
const NO_TRIPS: TripResponse[] = []

/** Un trajet conduit passe dans l historique 6 h apres son depart (delai de cloture serveur), ou des qu il est termine / annule. */
const COMPLETION_DELAY_MS = 6 * 60 * 60 * 1000

/** Couleur et icone de chaque etat de reservation ; le libelle vient de BOOKING_STATUS_LABEL (une seule source, lib/labels). */
const BOOKING_STATUS: Record<
  BookingStatus,
  { label: string; tone: 'success' | 'warning' | 'danger' | 'neutral'; icon: typeof CheckCircle2 }
> = {
  CONFIRMED: { label: BOOKING_STATUS_LABEL.CONFIRMED, tone: 'success', icon: CheckCircle2 },
  PENDING_PAYMENT: { label: BOOKING_STATUS_LABEL.PENDING_PAYMENT, tone: 'warning', icon: Clock },
  PENDING_DRIVER_APPROVAL: { label: BOOKING_STATUS_LABEL.PENDING_DRIVER_APPROVAL, tone: 'warning', icon: Hourglass },
  // Vue passager : « par vous » est plus juste que « par le passager ».
  CANCELLED_BY_PASSENGER: { label: 'Annulée par vous', tone: 'danger', icon: XCircle },
  CANCELLED_BY_DRIVER: { label: BOOKING_STATUS_LABEL.CANCELLED_BY_DRIVER, tone: 'danger', icon: Ban },
  COMPLETED: { label: BOOKING_STATUS_LABEL.COMPLETED, tone: 'neutral', icon: History },
  NO_SHOW: { label: BOOKING_STATUS_LABEL.NO_SHOW, tone: 'danger', icon: XCircle },
  DRIVER_NO_SHOW: { label: BOOKING_STATUS_LABEL.DRIVER_NO_SHOW, tone: 'danger', icon: Ban },
  EXPIRED: { label: BOOKING_STATUS_LABEL.EXPIRED, tone: 'neutral', icon: TimerOff },
}

/** Reservation close : plus d action possible, et la conversation est fermee (audit F546). */
function isClosedBooking(status: BookingStatus): boolean {
  return status === 'CANCELLED_BY_DRIVER' || status === 'CANCELLED_BY_PASSENGER' || status === 'EXPIRED'
}

function isPastBooking(booking: BookingDetailResponse): boolean {
  return (
    booking.status === 'COMPLETED' ||
    booking.status === 'NO_SHOW' ||
    booking.status === 'DRIVER_NO_SHOW' ||
    isClosedBooking(booking.status) ||
    new Date(booking.trip.departureAt).getTime() < Date.now()
  )
}

function isPastTrip(trip: TripResponse): boolean {
  if (trip.status === 'TEMPLATE') return false
  if (trip.status === 'COMPLETED' || trip.status === 'CANCELLED') return true
  return new Date(trip.departureAt).getTime() + COMPLETION_DELAY_MS < Date.now()
}

const RRULE_DAY_LABELS: Record<string, string> = { MO: 'lun', TU: 'mar', WE: 'mer', TH: 'jeu', FR: 'ven', SA: 'sam', SU: 'dim' }

/** « lun, mar, mer » a partir d une regle FREQ=WEEKLY;BYDAY=MO,TU,WE (tous les jours si BYDAY absent). */
function describeRecurrence(rule: string | null): string {
  const byDay = rule?.split(';').find((part) => part.toUpperCase().startsWith('BYDAY='))
  if (!byDay) return 'tous les jours'
  const days = byDay
    .slice('BYDAY='.length)
    .split(',')
    .map((code) => RRULE_DAY_LABELS[code.trim().toUpperCase()])
    .filter(Boolean)
  return days.length === 7 ? 'tous les jours' : days.join(', ')
}

/**
 * Un avis se laisse apres le depart, sur une reservation honoree (confirmee ou terminee),
 * une fois le constat donne ou tacite (V21) : on ne note pas un conducteur avant d avoir
 * dit si le trajet a eu lieu, et jamais un conducteur declare absent.
 */
function canReview(booking: BookingDetailResponse): boolean {
  const state = tripConfirmationState(booking)
  return (state === 'done' || state === 'tacit') && !booking.reviewedByMe
}

/**
 * Trajets conduits regroupes : une navette (modele TEMPLATE) et ses occurrences
 * forment un seul bloc, les trajets ponctuels restent isoles (audit F229). Les
 * occurrences dont le modele n'est pas dans la liste sont regroupees entre elles.
 */
interface DrivingGroup {
  key: string
  template: TripResponse | null
  occurrences: TripResponse[]
}

function groupDriving(trips: TripResponse[]): DrivingGroup[] {
  const byParent = new Map<string, TripResponse[]>()
  const singles: TripResponse[] = []
  const templates: TripResponse[] = []
  for (const trip of trips) {
    if (trip.status === 'TEMPLATE') templates.push(trip)
    else if (trip.parentTripId) {
      const list = byParent.get(trip.parentTripId) ?? []
      list.push(trip)
      byParent.set(trip.parentTripId, list)
    } else singles.push(trip)
  }
  const byTime = (a: TripResponse, b: TripResponse) => a.departureAt.localeCompare(b.departureAt)
  const groups: DrivingGroup[] = []
  for (const template of templates) {
    groups.push({ key: template.id, template, occurrences: (byParent.get(template.id) ?? []).sort(byTime) })
    byParent.delete(template.id)
  }
  for (const [parentId, occurrences] of byParent) {
    groups.push({ key: parentId, template: null, occurrences: occurrences.sort(byTime) })
  }
  for (const trip of singles) groups.push({ key: trip.id, template: null, occurrences: [trip] })
  // Prochain depart en premier ; une navette se classe sur sa premiere occurrence.
  const firstDeparture = (g: DrivingGroup) => g.occurrences[0]?.departureAt ?? g.template?.departureAt ?? ''
  return groups.sort((a, b) => firstDeparture(a).localeCompare(firstDeparture(b)))
}

/**
 * Mes trajets : reservations (a venir / passees) et trajets conduits.
 * L'onglet actif vit dans l'URL (?tab=) : partageable et conserve au rechargement.
 */
export function MyTripsPage({ defaultTab = 'upcoming' }: { defaultTab?: TabKey }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const tabParam = searchParams.get('tab')
  const tab: TabKey = TABS.includes(tabParam as TabKey) ? (tabParam as TabKey) : defaultTab
  const [drivingScope, setDrivingScope] = useState<DrivingScope>('upcoming')
  const bookings = useMyBookings()
  const trips = useMyTrips()
  const cancelBooking = useCancelBooking()
  const cancelTrip = useCancelTrip()
  const [confirm, setConfirm] = useState<{
    kind: 'booking' | 'trip'
    id: string
    label: string
    hours?: number
    template?: boolean
    /** Demande encore en attente du conducteur (V19) : retrait gratuit, remboursement integral. */
    awaitingDriver?: boolean
  } | null>(null)
  const [reviewing, setReviewing] = useState<BookingDetailResponse | null>(null)
  // Constat du passager (V21) : confirmation directe, ou declaration d absence apres confirmation.
  const confirmTripDone = useConfirmTripDone()
  const reportDriverNoShow = useReportDriverNoShow()
  const [noShowTarget, setNoShowTarget] = useState<BookingDetailResponse | null>(null)
  const [noShowDetails, setNoShowDetails] = useState('')
  const [editing, setEditing] = useState<TripResponse | null>(null)
  const [viewingPassengers, setViewingPassengers] = useState<TripResponse | null>(null)

  const bookingList = bookings.data ?? []
  const upcoming = bookingList.filter((b) => !isPastBooking(b))
  const past = bookingList.filter(isPastBooking)
  const drivingAll = trips.data ?? NO_TRIPS
  const drivingUpcoming = useMemo(() => groupDriving(drivingAll.filter((t) => !isPastTrip(t))), [drivingAll])
  const drivingPast = useMemo(
    () => groupDriving(drivingAll.filter(isPastTrip)).sort((a, b) => b.occurrences[0].departureAt.localeCompare(a.occurrences[0].departureAt)),
    [drivingAll],
  )
  const drivingGroups = drivingScope === 'upcoming' ? drivingUpcoming : drivingPast

  const markTripDone = (booking: BookingDetailResponse) =>
    confirmTripDone.mutate(booking.id, {
      onSuccess: () => toast.success('Merci, trajet confirmé', { description: 'Vous pouvez maintenant noter le conducteur.' }),
      onError: (error) => toast.error(describeError(error, "La confirmation n'a pas abouti.")),
    })

  const confirmDriverNoShow = () => {
    if (!noShowTarget) return
    reportDriverNoShow.mutate(
      { bookingId: noShowTarget.id, details: noShowDetails },
      {
        onSuccess: () => {
          setNoShowTarget(null)
          setNoShowDetails('')
          toast.success('Absence du conducteur signalée', {
            description: "La modération examine votre signalement et vous tiendra informé du remboursement.",
          })
        },
        onError: (error) => toast.error(describeError(error, "Le signalement n'a pas abouti.")),
      },
    )
  }

  const confirmCancel = () => {
    if (!confirm) return
    const target = confirm
    if (target.kind === 'booking') {
      cancelBooking.mutate(target.id, {
        onSuccess: () => toast.success('Réservation annulée'),
        onError: (error) => toast.error(describeError(error, "L'annulation a échoué.")),
        onSettled: () => setConfirm(null),
      })
    } else {
      cancelTrip.mutate(target.id, {
        onSuccess: () =>
          toast.success(target.template ? 'Navette arrêtée' : 'Trajet annulé', {
            description: 'Les passagers ont été prévenus.',
          }),
        onError: (error) => toast.error(describeError(error, "L'annulation a échoué.")),
        onSettled: () => setConfirm(null),
      })
    }
  }

  const askCancelTrip = (trip: TripResponse) =>
    setConfirm({
      kind: 'trip',
      id: trip.id,
      label: `${trip.originLabel} → ${trip.destLabel}`,
      template: trip.status === 'TEMPLATE',
    })

  const renderBookings = (list: BookingDetailResponse[], pastTab: boolean) => {
    if (bookings.isPending) return <ListSkeleton count={pastTab ? 2 : 3} />
    if (bookings.isError) return <ErrorState onRetry={() => bookings.refetch()} />
    if (list.length === 0) {
      return pastTab ? (
        <EmptyState icon={History} title="Rien dans l'historique" description="Vos trajets passés seront listés ici." />
      ) : (
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
      )
    }
    return (
      // Pas d'AnimatePresence intercalee ici : elle couperait la propagation
      // des variantes et les cartes resteraient invisibles.
      <m.div variants={listContainer} initial="hidden" animate="show" className="space-y-3">
        {list.map((booking) => (
          <BookingCard
            key={booking.id}
            booking={booking}
            past={pastTab}
            onReview={canReview(booking) ? () => setReviewing(booking) : undefined}
            onTripDone={pastTab ? () => markTripDone(booking) : undefined}
            onDriverNoShow={pastTab ? () => setNoShowTarget(booking) : undefined}
            confirming={confirmTripDone.isPending && confirmTripDone.variables === booking.id}
            onCancel={
              pastTab
                ? undefined
                : () =>
                    setConfirm({
                      kind: 'booking',
                      id: booking.id,
                      label: `${booking.trip.originLabel} → ${booking.trip.destLabel}`,
                      hours: booking.paymentPlan.freeCancellationHours,
                      awaitingDriver: booking.status === 'PENDING_DRIVER_APPROVAL',
                    })
            }
          />
        ))}
      </m.div>
    )
  }

  return (
    <PageContainer width="md">
      <PageMeta title="Mes trajets" noindex />
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

      <Tabs
        value={tab}
        onValueChange={(value) => setSearchParams(value === defaultTab ? {} : { tab: value }, { replace: true })}
      >
        <TabsList>
          <TabsTrigger value="upcoming">
            À venir
            {upcoming.length > 0 ? <CountPill>{upcoming.length}</CountPill> : null}
          </TabsTrigger>
          <TabsTrigger value="past">Passés</TabsTrigger>
          <TabsTrigger value="driving">
            Je conduis
            {drivingUpcoming.length > 0 ? <CountPill>{drivingUpcoming.length}</CountPill> : null}
          </TabsTrigger>
        </TabsList>

        <TabsContent value="upcoming">{renderBookings(upcoming, false)}</TabsContent>
        <TabsContent value="past">{renderBookings(past, true)}</TabsContent>

        <TabsContent value="driving">
          {/* Sous-onglets : les trajets passes et annules ne se melangent plus aux prochains departs. */}
          <div className="mb-3 flex gap-1 rounded-[var(--radius-control)] bg-surface-2 p-1" role="tablist" aria-label="Période des trajets conduits">
            {(
              [
                { value: 'upcoming', label: 'À venir', count: drivingUpcoming.length },
                { value: 'past', label: 'Passés', count: drivingPast.length },
              ] as const
            ).map((option) => (
              <button
                key={option.value}
                type="button"
                role="tab"
                aria-selected={drivingScope === option.value}
                onClick={() => setDrivingScope(option.value)}
                className={cn(
                  'inline-flex h-9 flex-1 items-center justify-center gap-1.5 rounded-[7px] px-3 text-label font-semibold transition-colors',
                  drivingScope === option.value ? 'bg-surface text-ink shadow-e1' : 'text-ink-2 hover:text-ink',
                )}
              >
                {option.label}
                {option.count > 0 ? <span className="tnum text-caption text-muted">{option.count}</span> : null}
              </button>
            ))}
          </div>

          {trips.isPending ? (
            <ListSkeleton count={2} />
          ) : trips.isError ? (
            <ErrorState onRetry={() => trips.refetch()} />
          ) : drivingGroups.length === 0 ? (
            drivingScope === 'past' ? (
              <EmptyState icon={History} title="Aucun trajet passé" description="Les trajets que vous avez conduits ou annulés seront listés ici." />
            ) : (
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
            )
          ) : (
            <m.div key={drivingScope} variants={listContainer} initial="hidden" animate="show" className="space-y-3">
              {drivingGroups.map((group) =>
                group.template || group.occurrences.length > 1 ? (
                  <ShuttleGroupCard
                    key={group.key}
                    group={group}
                    onEdit={setEditing}
                    onPassengers={setViewingPassengers}
                    onCancel={askCancelTrip}
                  />
                ) : (
                  <DrivingCard
                    key={group.key}
                    trip={group.occurrences[0]}
                    onEdit={() => setEditing(group.occurrences[0])}
                    onPassengers={() => setViewingPassengers(group.occurrences[0])}
                    onCancel={() => askCancelTrip(group.occurrences[0])}
                  />
                ),
              )}
            </m.div>
          )}
        </TabsContent>
      </Tabs>

      <ConfirmDialog
        open={confirm !== null}
        onOpenChange={(open) => !open && setConfirm(null)}
        title={
          confirm?.kind === 'trip'
            ? confirm.template
              ? 'Arrêter cette navette ?'
              : 'Annuler ce trajet ?'
            : confirm?.awaitingDriver
              ? 'Retirer votre demande ?'
              : 'Annuler cette réservation ?'
        }
        description={
          confirm?.kind === 'trip'
            ? confirm.template
              ? `Tous les départs à venir de ${confirm.label} seront annulés. Les passagers déjà inscrits seront prévenus et intégralement remboursés.`
              : `Les passagers de ${confirm.label} seront prévenus et intégralement remboursés.`
            : confirm?.awaitingDriver
              ? `Trajet ${confirm.label}. Le conducteur n'a pas encore répondu : le retrait est gratuit et tout acompte versé vous est remboursé intégralement.`
              : confirm
                ? `Trajet ${confirm.label}. Annulation gratuite jusqu'à ${confirm.hours ?? 24} h avant le départ ; en deçà, la moitié de l'acompte est retenue, et la totalité après l'heure de départ.`
                : undefined
        }
        tone="danger"
        confirmLabel={confirm?.awaitingDriver ? 'Retirer la demande' : "Confirmer l'annulation"}
        loading={cancelBooking.isPending || cancelTrip.isPending}
        onConfirm={confirmCancel}
      />

      <ConfirmDialog
        open={noShowTarget !== null}
        onOpenChange={(open) => {
          if (!open) {
            setNoShowTarget(null)
            setNoShowDetails('')
          }
        }}
        title="Le conducteur n'est pas venu ?"
        description={
          noShowTarget
            ? `Trajet ${noShowTarget.trip.originLabel} → ${noShowTarget.trip.destLabel}. Votre réservation est mise de côté : le conducteur ne sera pas payé pour votre place, et la modération examinera votre signalement avant de décider du remboursement de votre acompte. Cette déclaration est définitive.`
            : undefined
        }
        tone="danger"
        confirmLabel="Signaler l'absence"
        loading={reportDriverNoShow.isPending}
        onConfirm={confirmDriverNoShow}
      >
        <div className="space-y-1.5">
          <label htmlFor="driver-no-show-details" className="text-label font-medium text-ink">
            Précisions pour la modération (facultatif)
          </label>
          <Textarea
            id="driver-no-show-details"
            value={noShowDetails}
            onChange={(event) => setNoShowDetails(event.target.value.slice(0, 500))}
            rows={3}
            placeholder="Où et combien de temps avez-vous attendu ? Le conducteur a-t-il répondu à vos messages ?"
          />
        </div>
      </ConfirmDialog>

      {reviewing ? (
        <ReviewDialog
          open
          onOpenChange={(open) => !open && setReviewing(null)}
          tripId={reviewing.tripId}
          role="DRIVER"
          target={{ id: reviewing.trip.driver.id, name: `${reviewing.trip.driver.firstName} ${reviewing.trip.driver.lastName}` }}
        />
      ) : null}

      {editing ? <EditTripSheet trip={editing} open onOpenChange={(open) => !open && setEditing(null)} /> : null}
      <TripPassengersSheet trip={viewingPassengers} onOpenChange={(open) => !open && setViewingPassengers(null)} />
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
    <span className="tnum ml-1 flex min-w-[18px] items-center justify-center rounded-full bg-primary px-1 text-micro font-bold leading-[18px] text-on-primary">
      {children}
    </span>
  )
}

function BookingCard({
  booking,
  onCancel,
  onReview,
  onTripDone,
  onDriverNoShow,
  confirming = false,
  past = false,
}: {
  booking: BookingDetailResponse
  onCancel?: () => void
  onReview?: () => void
  /** Constat du passager (V21) : le trajet a eu lieu / le conducteur n'est pas venu. */
  onTripDone?: () => void
  onDriverNoShow?: () => void
  confirming?: boolean
  past?: boolean
}) {
  const status = BOOKING_STATUS[booking.status]
  const confirmation = tripConfirmationState(booking)
  const StatusIcon = status.icon
  const pending = booking.status === 'PENDING_PAYMENT'
  const awaitingDriver = booking.status === 'PENDING_DRIVER_APPROVAL'
  const closed = isClosedBooking(booking.status)
  const deadline = booking.paymentPlan.depositDueAt ? new Date(booking.paymentPlan.depositDueAt).getTime() : null
  const approvalDeadline = awaitingDriver ? (booking.paymentPlan.approvalDeadlineAt ?? null) : null

  return (
    <m.div variants={listItem}>
      <Card
        className={
          // L'etat se lit d'abord au filet lateral, avant meme de lire la puce.
          pending || awaitingDriver
            ? 'border-l-[3px] border-l-accent'
            : closed
              ? 'border-l-[3px] border-l-danger'
              : booking.status === 'CONFIRMED'
                ? 'border-l-[3px] border-l-success'
                : 'border-l-[3px] border-l-rule-strong'
        }
      >
        <div className="p-4">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0 flex-1">
              {/* Deux lignes au plus : un axe long ne doit ni deborder sous la puce ni etre coupe a la premiere ville. */}
              <Link
                to={`/trips/${booking.tripId}`}
                className="line-clamp-2 block font-display text-lead font-bold leading-tight hover:underline"
              >
                {booking.trip.originLabel} → {booking.trip.destLabel}
              </Link>
              <p className="tnum mt-0.5 text-label text-muted">
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
          {awaitingDriver ? (
            <p className="mt-3 rounded-[var(--radius-control)] bg-accent-soft px-3 py-2 text-caption leading-relaxed text-accent-ink">
              Le conducteur doit accepter votre demande
              {approvalDeadline ? (
                <>
                  {' '}
                  avant le <span className="tnum font-semibold">{formatDateTime(approvalDeadline)}</span>
                </>
              ) : null}
              . Sans réponse, {booking.paymentPlan.depositAmount > 0 ? "l'acompte est remboursé intégralement et " : ''}la place est
              libérée.
            </p>
          ) : null}

          <Separator className="my-3" />

          <div className="flex items-center gap-2">
            <Link to={`/drivers/${booking.trip.driver.id}`} className="flex min-w-0 flex-1 items-center gap-2">
              <Avatar
                firstName={booking.trip.driver.firstName}
                lastName={booking.trip.driver.lastName}
                photoUrl={booking.trip.driver.photoUrl}
                size={28}
              />
              <span className="min-w-0 flex-1 truncate text-label font-medium">
                {booking.trip.driver.firstName} {booking.trip.driver.lastName}
              </span>
            </Link>
            <span className="tnum shrink-0 text-right">
              <span className="block font-display text-lead font-bold leading-none">
                {formatFcfa(booking.amount)}
              </span>
              {!past && booking.status === 'CONFIRMED' ? (
                <span className="block text-micro text-muted">
                  {booking.paymentPlan.balanceAmount > 0
                    ? `dont ${formatFcfa(booking.paymentPlan.balanceAmount)} à bord`
                    : 'réglé intégralement'}
                </span>
              ) : null}
            </span>
          </div>
        </div>

        {/* Reservation close (annulee, expiree) : plus de messagerie ni d'annulation - la conversation est fermee. */}
        {!past && !closed ? (
          <div className="flex items-center gap-2 border-t border-rule px-3 py-2">
            {pending ? (
              <Button asChild size="sm" className="flex-1">
                <Link to={`/book/${booking.tripId}?booking=${booking.id}`}>Régler l'acompte</Link>
              </Button>
            ) : null}
            <Button asChild variant="ghost" size="sm" className="relative">
              <Link to={`/bookings/${booking.id}/messages`}>
                <MessageSquare className="size-4" aria-hidden />
                Messages
                {booking.unreadMessages > 0 ? (
                  <span className="tnum ml-1 flex size-4 items-center justify-center rounded-full bg-danger text-micro font-bold text-on-danger">
                    {booking.unreadMessages}
                  </span>
                ) : null}
              </Link>
            </Button>
            {onCancel ? (
              <Button variant="ghost" size="sm" className="ml-auto text-danger-ink" onClick={onCancel}>
                {awaitingDriver ? 'Retirer' : 'Annuler'}
              </Button>
            ) : null}
          </div>
        ) : confirmation === 'ask' && onTripDone && onDriverNoShow ? (
          /* Constat du passager (V21) : pose une fois, dans les 24 h qui suivent le depart. */
          <div className="border-t border-rule px-3 py-3">
            <p className="text-label font-medium text-ink">Ce trajet a-t-il eu lieu ?</p>
            <p className="mt-0.5 text-caption text-muted">
              Sans réponse de votre part sous 24 h, le trajet est considéré comme effectué.
            </p>
            <div className="mt-2 flex flex-wrap gap-2">
              <Button size="sm" onClick={onTripDone} loading={confirming}>
                <CheckCircle2 className="size-4" aria-hidden />
                Oui, le trajet a eu lieu
              </Button>
              <Button size="sm" variant="ghost" className="text-danger-ink" onClick={onDriverNoShow} disabled={confirming}>
                <Ban className="size-4" aria-hidden />
                Le conducteur n'est pas venu
              </Button>
            </div>
          </div>
        ) : confirmation === 'driver-no-show' ? (
          <p className="border-t border-rule px-3 py-2 text-caption text-muted">
            Absence du conducteur signalée{booking.passengerConfirmedAt ? ` le ${formatDateTime(booking.passengerConfirmedAt)}` : ''} :
            la modération examine votre dossier et vous informera du remboursement.
          </p>
        ) : onReview ? (
          <div className="flex items-center gap-2 border-t border-rule px-3 py-2">
            <Button asChild variant="ghost" size="sm">
              <Link to={`/bookings/${booking.id}/messages`}>
                <MessageSquare className="size-4" aria-hidden />
                Messages
              </Link>
            </Button>
            <Button size="sm" variant="secondary" className="ml-auto" onClick={onReview}>
              <Star className="size-4" aria-hidden />
              Noter le conducteur
            </Button>
          </div>
        ) : null}
      </Card>
    </m.div>
  )
}

/** Ce qu un trajet conduit autorise encore : modification, liste d appel, annulation. */
function drivingState(trip: TripResponse) {
  const cancelled = trip.status === 'CANCELLED'
  const completed = trip.status === 'COMPLETED'
  const template = trip.status === 'TEMPLATE'
  // Le statut ONGOING est pose par le serveur toutes les 5 min ; l heure locale couvre l intervalle.
  const departed = trip.status === 'ONGOING' || (!template && new Date(trip.departureAt).getTime() < Date.now())
  const editable = !cancelled && !completed && !departed
  const booked = trip.seatsTotal - trip.seatsAvailable
  // La liste d appel sert des qu il y a des passagers, et jusqu a 48 h apres le depart (no-show).
  const showPassengers =
    !template && !cancelled && (booked > 0 || departed) && new Date(trip.departureAt).getTime() + 48 * 3600 * 1000 > Date.now()
  return { cancelled, completed, template, departed, editable, booked, showPassengers }
}

function DrivingCard({
  trip,
  onEdit,
  onCancel,
  onPassengers,
}: {
  trip: TripResponse
  onEdit: () => void
  onCancel: () => void
  onPassengers: () => void
}) {
  const { cancelled, completed, template, departed, editable, booked, showPassengers } = drivingState(trip)

  return (
    <m.div variants={listItem}>
      <Card
        className={
          cancelled
            ? 'border-l-[3px] border-l-danger'
            : completed
              ? 'border-l-[3px] border-l-rule-strong'
              : 'border-l-[3px] border-l-primary'
        }
      >
        <Link to={`/trips/${trip.id}`} className="block p-4 transition-colors hover:bg-surface-2">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="truncate font-display text-lead font-bold leading-tight">
                {trip.originLabel} → {trip.destLabel}
              </p>
              <p className="tnum mt-0.5 text-label text-muted">
                {template
                  ? `Navette · ${describeRecurrence(trip.recurrenceRule)} à ${formatTime(trip.departureAt)}`
                  : `${formatRelativeDay(trip.departureAt)} · ${formatTime(trip.departureAt)}`}
              </p>
            </div>
            <div className="flex shrink-0 items-center gap-2">
              <TripStatusBadge trip={trip} />
              <ChevronRight className="size-4 text-muted" aria-hidden />
            </div>
          </div>
          <p className="tnum mt-2 text-label text-ink-2">
            {template
              ? `${formatFcfa(trip.pricePerSeat)} par place · ${trip.seatsTotal} place${trip.seatsTotal > 1 ? 's' : ''} par départ`
              : `${formatFcfa(trip.pricePerSeat)} par place · ${booked} place${booked > 1 ? 's' : ''} réservée${booked > 1 ? 's' : ''}`}
          </p>
        </Link>
        {editable || showPassengers ? (
          <div className="flex items-center gap-2 border-t border-rule px-3 py-2">
            {showPassengers ? (
              <Button variant="ghost" size="sm" onClick={onPassengers}>
                <Users className="size-4" aria-hidden />
                Passagers
              </Button>
            ) : null}
            {editable ? (
              <>
                {!departed ? (
                  <Button variant="ghost" size="sm" onClick={onEdit}>
                    <Pencil className="size-4" aria-hidden />
                    Modifier
                  </Button>
                ) : null}
                <Button variant="ghost" size="sm" className="ml-auto text-danger-ink" onClick={onCancel}>
                  {template ? 'Arrêter la navette' : 'Annuler le trajet'}
                </Button>
              </>
            ) : null}
          </div>
        ) : null}
        {/* Suivi en direct (V23) : d une heure avant le depart jusqu a la fin du trajet. */}
        {!template && isSharingWindowOpen(trip) ? <LiveSharingControl trip={trip} compact /> : null}
      </Card>
    </m.div>
  )
}

function TripStatusBadge({ trip }: { trip: TripResponse }) {
  const { cancelled, completed, template, departed, booked } = drivingState(trip)
  if (template) return <Badge tone={cancelled ? 'danger' : 'neutral'}>Navette</Badge>
  if (departed && !completed && !cancelled) return <Badge tone="warning">En cours</Badge>
  return (
    <Badge tone={cancelled ? 'danger' : completed ? 'neutral' : booked > 0 ? 'success' : 'warning'}>
      <Users aria-hidden />
      {booked}/{trip.seatsTotal}
    </Badge>
  )
}

/**
 * Navette et ses occurrences : un seul bloc au lieu d'une carte par jour. Le
 * modele porte l'action « arrêter » ; chaque occurrence garde ses propres
 * actions (passagers, modification, annulation d'un seul depart).
 */
function ShuttleGroupCard({
  group,
  onEdit,
  onPassengers,
  onCancel,
}: {
  group: DrivingGroup
  onEdit: (trip: TripResponse) => void
  onPassengers: (trip: TripResponse) => void
  onCancel: (trip: TripResponse) => void
}) {
  const [open, setOpen] = useState(false)
  const template = group.template
  const head = template ?? group.occurrences[0]
  const active = group.occurrences.filter((t) => t.status !== 'CANCELLED')
  const bookedSeats = active.reduce((sum, t) => sum + (t.seatsTotal - t.seatsAvailable), 0)
  const cancelledTemplate = template?.status === 'CANCELLED'

  return (
    <m.div variants={listItem}>
      <Card className={cn('border-l-[3px]', cancelledTemplate ? 'border-l-danger' : 'border-l-primary')}>
        <div className="p-4">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="truncate font-display text-lead font-bold leading-tight">
                {head.originLabel} → {head.destLabel}
              </p>
              <p className="tnum mt-0.5 text-label text-muted">
                Navette · {describeRecurrence(head.recurrenceRule)} à {formatTime(head.departureAt)}
              </p>
            </div>
            <Badge tone={cancelledTemplate ? 'danger' : 'neutral'}>
              <Repeat aria-hidden />
              Navette
            </Badge>
          </div>
          <p className="tnum mt-2 text-label text-ink-2">
            {formatFcfa(head.pricePerSeat)} par place · {active.length} départ{active.length > 1 ? 's' : ''}
            {group.occurrences.length > 0 ? ` · ${bookedSeats} place${bookedSeats > 1 ? 's' : ''} réservée${bookedSeats > 1 ? 's' : ''}` : ''}
          </p>
        </div>

        {group.occurrences.length > 0 ? (
          <>
            <button
              type="button"
              onClick={() => setOpen((v) => !v)}
              aria-expanded={open}
              className="flex w-full items-center justify-between gap-2 border-t border-rule px-4 py-2.5 text-label font-semibold text-ink-2 transition-colors hover:bg-surface-2"
            >
              {open ? 'Masquer les départs' : `Voir les ${group.occurrences.length} départ${group.occurrences.length > 1 ? 's' : ''}`}
              <ChevronDown className={cn('size-4 transition-transform', open && 'rotate-180')} aria-hidden />
            </button>
            {open ? (
              <ul className="divide-y divide-rule border-t border-rule">
                {group.occurrences.map((trip) => {
                  const state = drivingState(trip)
                  return (
                    <li key={trip.id} className="flex flex-wrap items-center gap-2 px-4 py-2.5">
                      <Link to={`/trips/${trip.id}`} className="tnum min-w-0 flex-1 text-body font-medium underline-offset-4 hover:underline">
                        {formatDayShort(trip.departureAt)} · {formatTime(trip.departureAt)}
                      </Link>
                      <TripStatusBadge trip={trip} />
                      {state.showPassengers ? (
                        <Button variant="ghost" size="iconSm" aria-label="Passagers" onClick={() => onPassengers(trip)}>
                          <Users className="size-4" aria-hidden />
                        </Button>
                      ) : null}
                      {state.editable && !state.departed ? (
                        <Button variant="ghost" size="iconSm" aria-label="Modifier ce départ" onClick={() => onEdit(trip)}>
                          <Pencil className="size-4" aria-hidden />
                        </Button>
                      ) : null}
                      {state.editable ? (
                        <Button
                          variant="ghost"
                          size="iconSm"
                          aria-label="Annuler ce départ"
                          className="text-danger-ink"
                          onClick={() => onCancel(trip)}
                        >
                          <XCircle className="size-4" aria-hidden />
                        </Button>
                      ) : null}
                    </li>
                  )
                })}
              </ul>
            ) : null}
          </>
        ) : null}

        {template && !cancelledTemplate ? (
          <div className="flex items-center gap-2 border-t border-rule px-3 py-2">
            <Button variant="ghost" size="sm" onClick={() => onEdit(template)}>
              <Pencil className="size-4" aria-hidden />
              Modifier la navette
            </Button>
            <Button variant="ghost" size="sm" className="ml-auto text-danger-ink" onClick={() => onCancel(template)}>
              Arrêter la navette
            </Button>
          </div>
        ) : null}
      </Card>
    </m.div>
  )
}
