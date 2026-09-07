import { m } from 'motion/react'
import { Hourglass, MessageSquare } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useCancelBooking } from '@/hooks/useBookings'
import { describeError } from '@/lib/errors'
import { formatDateTime, formatFcfa } from '@/lib/format'
import type { BookingFlow } from './useBookingFlow'

/**
 * Issue intermediaire (V19) : l'acompte est encaisse (ou la demande en especes enregistree),
 * la place est bloquee, mais le conducteur doit encore accepter. L'echeance vient du serveur
 * (`paymentPlan.approvalDeadlineAt`) ; passe ce delai, le serveur refuse et rembourse de lui-meme.
 * Le passager peut se retirer sans frais tant que le conducteur n'a pas repondu.
 */
export function AwaitingDriverStep({ flow }: { flow: BookingFlow }) {
  const plan = flow.plan
  const cancel = useCancelBooking()
  const [confirming, setConfirming] = useState(false)
  const deadline = flow.booking.data?.paymentPlan.approvalDeadlineAt ?? null
  const driverName = flow.data?.driver.firstName ?? 'Le conducteur'

  const confirmCancel = () => {
    if (!flow.bookingId) return
    cancel.mutate(flow.bookingId, {
      onSuccess: () => {
        toast.success('Demande retirée', {
          description: plan.depositAmount > 0 ? "Votre acompte vous est remboursé intégralement." : undefined,
        })
        setConfirming(false)
        void flow.booking.refetch()
      },
      onError: (error) => toast.error(describeError(error, "Le retrait n'a pas abouti.")),
    })
  }

  return (
    <m.div
      key="awaiting"
      initial={{ opacity: 0, scale: 0.98 }}
      animate={{ opacity: 1, scale: 1 }}
      transition={{ duration: 0.28 }}
      className="space-y-4"
    >
      <Card className="flex flex-col items-center gap-3 px-5 py-8 text-center">
        <span className="flex size-16 items-center justify-center rounded-full bg-accent-soft text-accent-ink">
          <Hourglass className="size-8" aria-hidden />
        </span>
        <div>
          <h2 className="font-display text-display font-extrabold tracking-[-0.03em]">En attente de l'accord du conducteur</h2>
          <p className="mt-1 text-body text-ink-2">
            {plan.paymentMethod === 'CASH'
              ? `Votre demande est transmise à ${driverName}. La place est bloquée pour vous jusqu'à sa réponse.`
              : `${plan.depositAmount > 0 ? `Acompte de ${formatFcfa(plan.depositAmount)} reçu. ` : ''}Votre demande est transmise à ${driverName} ; la place est bloquée pour vous jusqu'à sa réponse.`}
          </p>
        </div>
      </Card>

      <Card className="space-y-2 p-4 text-body">
        <p>
          <span className="font-semibold">Réponse attendue{deadline ? ' avant le ' : ''}</span>
          {deadline ? <span className="tnum font-semibold">{formatDateTime(deadline)}</span> : null}
          {deadline ? '.' : ' sous 24 h.'}
        </p>
        <p className="text-ink-2">
          {plan.depositAmount > 0
            ? "S'il refuse ou ne répond pas dans ce délai, votre acompte vous est remboursé intégralement et la place est libérée."
            : "S'il refuse ou ne répond pas dans ce délai, la place est libérée."}{' '}
          Vous serez prévenu par e-mail et dans l'application.
        </p>
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
      <Button variant="ghost" block className="text-danger-ink" onClick={() => setConfirming(true)}>
        Retirer ma demande
      </Button>

      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title="Retirer votre demande ?"
        description={
          plan.depositAmount > 0
            ? `Tant que le conducteur n'a pas répondu, le retrait est gratuit : votre acompte de ${formatFcfa(plan.depositAmount)} vous est remboursé intégralement.`
            : "Tant que le conducteur n'a pas répondu, le retrait est gratuit."
        }
        tone="danger"
        confirmLabel="Retirer la demande"
        loading={cancel.isPending}
        onConfirm={confirmCancel}
      />
    </m.div>
  )
}
