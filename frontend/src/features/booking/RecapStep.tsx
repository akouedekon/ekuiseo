import { m } from 'motion/react'
import { ArrowRight, UserCheck } from 'lucide-react'
import { Link } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { RadioGroup, RadioGroupItem, Separator, Stepper } from '@/components/ui/misc'
import { SectionTitle } from '@/components/layout/PageContainer'
import { PaymentSplit } from '@/components/booking/PaymentSplit'
import { RouteTimeline } from '@/components/trip/RouteTimeline'
import type { PaymentMode } from '@/api/extended'
import type { TripResponse } from '@/api/types'
import { describeError } from '@/lib/errors'
import { formatFcfa } from '@/lib/format'
import { DRIVER_APPROVAL_BADGE } from '@/lib/labels'
import { PAYMENT_MODES } from '@/lib/payments'
import { buildRoutePoints, estimateArrival } from '@/lib/route'
import type { BookingFlow } from './useBookingFlow'

/** Etape 1 : places, arret de descente, mode de paiement, montants. */
export function RecapStep({ flow, trip }: { flow: BookingFlow; trip: TripResponse }) {
  const { arrivalIso } = estimateArrival(trip)
  const selectedStop = flow.selectedStop
  // Mode especes propose seulement aux conducteurs a identite verifiee (section 5 #13).
  const modes = PAYMENT_MODES.filter((option) => option.value !== 'CASH' || flow.cashAllowed)

  return (
    <m.div
      key="recap"
      initial={{ opacity: 0, x: 14 }}
      animate={{ opacity: 1, x: 0 }}
      exit={{ opacity: 0, x: -14 }}
      transition={{ duration: 0.22 }}
      className="space-y-4"
    >
      <Card className="p-4">
        <RouteTimeline
          points={buildRoutePoints(
            trip.originLabel,
            selectedStop?.label ?? trip.destLabel,
            trip.departureAt,
            arrivalIso,
            flow.unitPrice,
            selectedStop ? [] : flow.stopList,
          )}
        />
        <p className="mt-2 text-caption text-muted">≈ heure d'arrivée estimée, non garantie par le conducteur.</p>
      </Card>

      <Card className="p-4">
        <SectionTitle>Votre réservation</SectionTitle>
        <div className="flex min-h-11 items-center justify-between gap-4">
          <span className="text-body font-medium">Nombre de places</span>
          <Stepper
            value={flow.seats}
            onChange={flow.setSeats}
            min={1}
            max={trip.seatsAvailable || 1}
            label="places"
            decrementLabel="Une place de moins"
            incrementLabel="Une place de plus"
          />
        </div>
        <p className="mt-1 text-caption text-muted">
          {trip.seatsAvailable} place{trip.seatsAvailable > 1 ? 's' : ''} encore disponible
          {trip.seatsAvailable > 1 ? 's' : ''}.
        </p>
        {flow.stops.isError ? (
          <p className="mt-2 flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-control)] bg-accent-soft px-3 py-2 text-caption text-accent-ink">
            Arrêts intermédiaires indisponibles : réservation jusqu'au terminus uniquement.
            <button
              type="button"
              className="font-semibold underline-offset-4 hover:underline"
              onClick={() => flow.stops.refetch()}
            >
              Réessayer
            </button>
          </p>
        ) : null}

        {flow.stopList.length > 0 ? (
          <>
            <Separator className="my-4" />
            <fieldset>
              <legend className="mb-2 text-body font-medium">Descendre à</legend>
              <RadioGroup
                value={flow.dropoffStopId}
                onValueChange={flow.setDropoffStopId}
                className="divide-y divide-rule overflow-hidden rounded-[var(--radius-control)] border border-rule"
              >
                {[...flow.stopList, null].map((stop) => {
                  const value = stop?.id ?? ''
                  const label = stop?.label ?? trip.destLabel
                  const price = stop?.priceFromOrigin ?? trip.pricePerSeat
                  return (
                    <label key={value || 'terminus'} className="flex min-h-[52px] cursor-pointer items-center gap-3 px-3">
                      <RadioGroupItem value={value} id={`stop-${value || 'terminus'}`} />
                      <span className="flex-1 text-body font-medium">{label}</span>
                      <span className="tnum text-body font-semibold text-ink-2">{formatFcfa(price)}</span>
                    </label>
                  )
                })}
              </RadioGroup>
            </fieldset>
          </>
        ) : null}
      </Card>

      {/* --- Mode de reglement --- */}
      <Card className="p-4">
        <SectionTitle>Comment souhaitez-vous payer ?</SectionTitle>
        <RadioGroup
          value={flow.paymentMode}
          onValueChange={(value) => flow.setPaymentMode(value as PaymentMode)}
          className="divide-y divide-rule overflow-hidden rounded-[var(--radius-control)] border border-rule"
        >
          {modes.map((option) => (
            <label
              key={option.value}
              className="flex cursor-pointer items-start gap-3 px-3 py-3 transition-colors has-[:checked]:bg-primary-soft"
            >
              <RadioGroupItem value={option.value} id={`mode-${option.value}`} className="mt-0.5" />
              <span className="min-w-0 flex-1">
                <span className="flex flex-wrap items-center gap-2">
                  <span className="text-body font-semibold">{option.label}</span>
                  {option.recommended ? <Badge tone="indigo">Recommandé</Badge> : null}
                </span>
                {/* Une phrase, et une seule : ce que le mode implique en cas d'annulation. */}
                <span className="mt-0.5 block text-label leading-snug text-muted">
                  {option.cancellation(flow.plan.freeCancellationHours)}
                </span>
              </span>
            </label>
          ))}
        </RadioGroup>
        {!flow.cashAllowed ? (
          <p className="mt-2 text-caption leading-relaxed text-muted">
            Le paiement tout en espèces à bord est réservé aux conducteurs à identité vérifiée ; ce conducteur ne
            l'est pas encore. L'acompte en ligne garantit votre place.
          </p>
        ) : null}
      </Card>

      {flow.quote.isError ? (
        <Card className="border-danger bg-danger-soft p-4 text-body text-danger-ink" role="alert">
          {describeError(flow.quote.error, "Le devis n'a pas pu être calculé.")}
        </Card>
      ) : null}

      {flow.conflict ? (
        <Card className="space-y-2 border-accent bg-accent-soft p-4 text-body text-accent-ink" role="status">
          <p className="font-semibold">Vous avez déjà une réservation active sur ce trajet.</p>
          {flow.myBookings.isPending ? (
            <p>Recherche de votre réservation…</p>
          ) : flow.existing?.status === 'PENDING_PAYMENT' || flow.existing?.status === 'PENDING_DRIVER_APPROVAL' ? (
            <Button asChild variant="secondary" size="sm">
              <Link to={`/book/${flow.tripId}?booking=${flow.existing.id}`}>
                {flow.existing.status === 'PENDING_PAYMENT' ? 'Reprendre ma réservation en attente' : 'Voir ma demande en attente'}
              </Link>
            </Button>
          ) : (
            <Button asChild variant="secondary" size="sm">
              <Link to="/bookings">Voir mes réservations</Link>
            </Button>
          )}
        </Card>
      ) : null}

      <PaymentSplit plan={flow.plan} estimated={flow.planIsEstimate} />

      {flow.requiresDriverApproval ? (
        <Card className="flex items-start gap-3 border-accent bg-accent-soft p-4 text-body text-accent-ink" role="note">
          <UserCheck className="mt-0.5 size-5 shrink-0" aria-hidden />
          <p>
            <span className="font-semibold">{DRIVER_APPROVAL_BADGE}.</span> {trip.driver.firstName} accepte chaque passager :
            {flow.paymentMode === 'CASH' ? ' votre demande lui est transmise' : " l'acompte est encaissé, puis la demande lui est transmise"}
            . Sans accord de sa part sous 24 h (au plus tard 2 h avant le départ), la place est libérée
            {flow.paymentMode === 'CASH' ? '' : ' et l’acompte remboursé intégralement'}.
          </p>
        </Card>
      ) : null}

      <Button size="lg" block loading={flow.createBooking.isPending} disabled={flow.quote.isError} onClick={flow.goToPayment}>
        {flow.paymentMode === 'CASH'
          ? 'Demander la place'
          : flow.requiresDriverApproval
            ? `Demander ma place pour ${formatFcfa(flow.plan.depositAmount)}`
            : `${flow.paymentMode === 'MOMO_FULL' ? 'Payer' : 'Bloquer ma place pour'} ${formatFcfa(flow.plan.depositAmount)}`}
        <ArrowRight className="size-5" aria-hidden />
      </Button>
      <p className="text-center text-caption text-muted">
        {flow.planIsEstimate
          ? "Montants estimés : le décompte définitif s'affiche à l'étape suivante, avant tout paiement."
          : flow.paymentMode === 'CASH'
            ? 'Aucun paiement en ligne. Le conducteur peut réattribuer la place.'
            : flow.paymentMode === 'MOMO_FULL'
              ? 'Voyage réglé en une fois. Rien à prévoir à bord.'
              : "Vous ne payez que l'acompte maintenant. Aucun débit du solde en ligne."}
      </p>
    </m.div>
  )
}
