import { m } from 'motion/react'
import { Check, CircleDot, Flag, MessageSquare, Ticket } from 'lucide-react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Separator } from '@/components/ui/misc'
import { SectionTitle } from '@/components/layout/PageContainer'
import type { TripResponse } from '@/api/types'
import { BENIN_TIME_HINT, deviceClockDiffersFromBenin, formatFcfa, formatTime } from '@/lib/format'
import type { BookingFlow } from './useBookingFlow'

/** Issue heureuse : place confirmee (acompte recu, paiement integral, ou demande en especes enregistree). */
export function ConfirmedStep({ flow, trip }: { flow: BookingFlow; trip: TripResponse }) {
  const plan = flow.plan
  const clockDiffers = deviceClockDiffersFromBenin()
  return (
    <m.div
      key="confirmed"
      initial={{ opacity: 0, scale: 0.98 }}
      animate={{ opacity: 1, scale: 1 }}
      transition={{ duration: 0.28 }}
      className="space-y-4"
    >
      <Card className="flex flex-col items-center gap-3 px-5 py-8 text-center">
        <m.span
          initial={{ scale: 0.5, opacity: 0 }}
          animate={{ scale: 1, opacity: 1 }}
          transition={{ type: 'spring', stiffness: 420, damping: 22 }}
          className="flex size-16 items-center justify-center rounded-full bg-success-soft text-success-ink"
        >
          <Check className="size-8" strokeWidth={3} aria-hidden />
        </m.span>
        <div>
          <h2 className="font-display text-display font-extrabold tracking-[-0.03em]">
            {plan.paymentMethod === 'CASH' ? 'Demande enregistrée' : 'Place confirmée'}
          </h2>
          <p className="mt-1 text-body text-ink-2">
            {plan.paymentMethod === 'CASH'
              ? "Aucun paiement en ligne : la place n'est pas garantie tant que le conducteur ne vous a pas pris à bord."
              : plan.balanceAmount === 0
                ? `Paiement de ${formatFcfa(plan.depositAmount)} reçu. Votre place est réservée, rien à régler à bord.`
                : `Acompte de ${formatFcfa(plan.depositAmount)} reçu. Votre place est réservée.`}
          </p>
        </div>
      </Card>

      <Card className="p-4">
        <SectionTitle>À retenir pour le départ</SectionTitle>
        <dl className="space-y-2.5 text-body">
          <Row icon={<CircleDot className="text-primary" />} label="Départ">
            {trip.originLabel} · {formatTime(trip.departureAt)}
            {clockDiffers ? <span className="ml-1 text-caption font-normal text-muted">({BENIN_TIME_HINT})</span> : null}
          </Row>
          <Row icon={<Flag className="text-danger" />} label="Arrivée">
            {flow.selectedStop?.label ?? trip.destLabel}
          </Row>
          <Row icon={<Ticket />} label="Places">
            {flow.booking.data?.seats ?? flow.seats}
          </Row>
          {plan.balanceAmount > 0 ? (
            <>
              <Separator />
              <div className="flex items-baseline justify-between rounded-[var(--radius-control)] bg-accent-soft px-3 py-2.5">
                <dt className="text-body font-semibold text-accent-ink">À payer en espèces à bord</dt>
                <dd className="tnum font-display text-title font-extrabold text-accent-ink">
                  {formatFcfa(plan.balanceAmount)}
                </dd>
              </div>
            </>
          ) : null}
        </dl>
      </Card>

      <div className="flex flex-col gap-2 sm:flex-row">
        <Button asChild size="lg" block>
          <Link to="/bookings">Voir mes réservations</Link>
        </Button>
        {flow.bookingId ? (
          <Button asChild variant="secondary" size="lg" block>
            <Link to={`/bookings/${flow.bookingId}/messages`}>
              <MessageSquare className="size-4" aria-hidden />
              Écrire au conducteur
            </Link>
          </Button>
        ) : null}
      </div>
    </m.div>
  )
}

function Row({ icon, label, children }: { icon: React.ReactNode; label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center gap-2.5">
      <span aria-hidden className="text-muted [&>svg]:size-4">
        {icon}
      </span>
      <dt className="text-muted">{label}</dt>
      <dd className="ml-auto font-semibold">{children}</dd>
    </div>
  )
}
