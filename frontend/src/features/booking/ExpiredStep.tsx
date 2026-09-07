import { m } from 'motion/react'
import { AlertTriangle } from 'lucide-react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { DepositCountdown } from '@/components/booking/Countdown'
import { CONTACT_EMAIL } from '@/lib/legal'
import type { BookingFlow } from './useBookingFlow'

/*
 * Issues decidees par le serveur qui ferment le tunnel : expiration, refus de
 * l'operateur, acompte recu trop tard (rembourse), annulation. Chacune dit ce
 * qui s'est passe, ce qu'il advient de l'argent et l'action suivante.
 */

function SupportLink({ href }: { href: string }) {
  return (
    <p className="text-center text-label text-muted">
      Une question sur un débit ?{' '}
      <a href={href} className="font-semibold text-primary-ink underline-offset-4 hover:underline">
        Écrire au support ({CONTACT_EMAIL})
      </a>
    </p>
  )
}

function OutcomeCard({
  tone,
  title,
  children,
}: {
  tone: 'danger' | 'accent'
  title: string
  children: React.ReactNode
}) {
  return (
    <Card className="flex flex-col items-center gap-3 px-5 py-8 text-center">
      <span
        className={
          tone === 'danger'
            ? 'flex size-16 items-center justify-center rounded-full bg-danger-soft text-danger-ink'
            : 'flex size-16 items-center justify-center rounded-full bg-accent-soft text-accent-ink'
        }
      >
        <AlertTriangle className="size-7" aria-hidden />
      </span>
      <div>
        <h2 className="font-display text-heading font-bold tracking-[-0.02em]">{title}</h2>
        {children}
      </div>
    </Card>
  )
}

function Reference({ value }: { value: string | undefined }) {
  return value ? <p className="tnum mt-2 text-caption text-muted">Référence : {value}</p> : null
}

/** Acompte non recu dans le delai : la place a ete reliberee par le serveur. */
export function ExpiredStep({ flow, supportHref, onBackToTrip }: { flow: BookingFlow; supportHref: string; onBackToTrip: () => void }) {
  return (
    <m.div key="expired" initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="space-y-4">
      <OutcomeCard tone="danger" title="Réservation expirée">
        <p className="mx-auto mt-1 max-w-sm text-body leading-relaxed text-ink-2">
          L'acompte n'a pas été confirmé dans les 20 minutes : la place a été relibérée par le serveur. Si un
          paiement a malgré tout été débité, il vous est remboursé automatiquement et vous en serez prévenu.
        </p>
        <Reference value={flow.paymentStatus.data?.transactionRef} />
      </OutcomeCard>
      <Button size="lg" block onClick={flow.restart}>
        Recommencer la réservation
      </Button>
      <Button variant="ghost" block onClick={onBackToTrip}>
        Revenir au trajet
      </Button>
      <SupportLink href={supportHref} />
    </m.div>
  )
}

/** L'operateur a refuse : la place reste bloquee jusqu'a l'echeance, on peut reessayer. */
export function FailedStep({ flow, supportHref }: { flow: BookingFlow; supportHref: string }) {
  const transactionRef = flow.paymentStatus.data?.transactionRef
  return (
    <m.div key="failed" initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="space-y-4">
      <OutcomeCard tone="danger" title="Paiement refusé">
        <p className="mx-auto mt-1 max-w-sm text-body leading-relaxed text-ink-2">
          L'opérateur n'a pas validé le paiement (solde insuffisant, code erroné ou délai dépassé). Votre place
          reste bloquée jusqu'à la fin du compte à rebours : vous pouvez réessayer avec un autre numéro ou un
          autre opérateur.
        </p>
        <p className="mx-auto mt-2 max-w-sm text-label leading-relaxed text-muted">
          Si votre compte mobile money a malgré tout été débité, écrivez au support avec la référence
          {transactionRef ? ` ${transactionRef}` : ' de la transaction'} : le montant sera vérifié et remboursé.
        </p>
      </OutcomeCard>
      {flow.deadline ? (
        <DepositCountdown
          deadline={flow.deadline}
          totalSeconds={flow.windowSeconds}
          onExpire={() => flow.setLocalExpired(true)}
          compact
          className="justify-center"
        />
      ) : null}
      <Button size="lg" block onClick={flow.retryPayment}>
        Réessayer le paiement
      </Button>
      <SupportLink href={supportHref} />
    </m.div>
  )
}

/** Acompte arrive apres l'expiration : rembourse, la place n'a pas pu etre confirmee. */
export function RefundStep({ flow, supportHref }: { flow: BookingFlow; supportHref: string }) {
  return (
    <m.div key="refund" initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="space-y-4">
      <OutcomeCard tone="accent" title="Paiement reçu trop tard">
        <p className="mx-auto mt-1 max-w-sm text-body leading-relaxed text-ink-2">
          Votre acompte est arrivé après l'expiration de la réservation : la place n'a pas pu être confirmée.
          {flow.paymentStatus.data?.status === 'REFUNDED'
            ? ' Il vous a été intégralement remboursé.'
            : ' Il vous est remboursé automatiquement ; vous serez prévenu.'}
        </p>
        <Reference value={flow.paymentStatus.data?.transactionRef} />
      </OutcomeCard>
      <Button size="lg" block onClick={flow.restart}>
        Réserver à nouveau
      </Button>
      <SupportLink href={supportHref} />
    </m.div>
  )
}

/** Reservation annulee (par le passager ailleurs, ou par le conducteur). */
export function CancelledStep({ flow }: { flow: BookingFlow }) {
  return (
    <m.div key="cancelled" initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="space-y-4">
      <OutcomeCard tone="accent" title="Réservation annulée">
        <p className="mx-auto mt-1 max-w-sm text-body leading-relaxed text-ink-2">
          {flow.bookingStatus === 'CANCELLED_BY_DRIVER'
            ? 'Le conducteur a annulé. Si un acompte avait été versé, il vous est intégralement remboursé.'
            : 'Cette réservation a été annulée. Retrouvez le détail et un éventuel remboursement dans « Mes réservations ».'}
        </p>
      </OutcomeCard>
      <Button asChild size="lg" block>
        <Link to="/bookings">Voir mes réservations</Link>
      </Button>
      <Button variant="ghost" block onClick={flow.restart}>
        Réserver à nouveau
      </Button>
    </m.div>
  )
}
