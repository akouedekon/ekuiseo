import { m } from 'motion/react'
import { LifeBuoy, RefreshCw, Smartphone } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { DepositCountdown } from '@/components/booking/Countdown'
import { formatFcfa, formatPhone } from '@/lib/format'
import { CheckingExpiry } from './PaymentStep'
import type { BookingFlow } from './useBookingFlow'

/** Etape 3 (attente) : le widget est ouvert ou le paiement est en cours de verification cote serveur. */
export function WaitingStep({ flow, supportHref }: { flow: BookingFlow; supportHref: string }) {
  const transactionRef = flow.paymentStatus.data?.transactionRef
  const widgetPayment = flow.checkout.payment
  const unlinked = flow.unlinkedTransactionId

  return (
    <m.div
      key="waiting"
      initial={{ opacity: 0, x: 14 }}
      animate={{ opacity: 1, x: 0 }}
      exit={{ opacity: 0, x: -14 }}
      transition={{ duration: 0.22 }}
      className="space-y-4"
    >
      {flow.deadline ? (
        <DepositCountdown deadline={flow.deadline} totalSeconds={flow.windowSeconds} onExpire={() => flow.setLocalExpired(true)} />
      ) : null}
      {flow.localExpired ? <CheckingExpiry /> : null}

      <Card className="flex flex-col items-center gap-4 px-5 py-8 text-center">
        {/* Anneau de progression en CSS (respecte prefers-reduced-motion), double d'un texte explicite. */}
        <span className="relative flex size-16 items-center justify-center">
          <span aria-hidden className="ek-spin absolute inset-0 rounded-full border-[3px] border-rule border-t-primary" />
          <Smartphone className="size-6 text-primary-ink" aria-hidden />
        </span>

        <div>
          <h2 className="font-display text-heading font-bold tracking-[-0.02em]">
            {unlinked
              ? 'Paiement signalé, rattachement en cours'
              : widgetPayment
                ? 'Réglez dans la fenêtre Kkiapay'
                : 'Validez sur votre téléphone'}
          </h2>
          <p className="mx-auto mt-1.5 max-w-sm text-body leading-relaxed text-ink-2">
            {unlinked
              ? `Kkiapay nous a signalé un paiement (référence ${unlinked}) que nous n'avons pas encore pu rattacher à votre réservation. Nous le vérifions auprès de l'opérateur : la confirmation arrive dès que c'est fait.`
              : widgetPayment
                ? `Choisissez votre opérateur, confirmez ${formatFcfa(flow.plan.depositAmount)} pour le ${formatPhone(flow.phone)}, puis validez avec votre code secret sur votre téléphone.`
                : (flow.paymentStatus.data?.instruction ??
                  `Une demande de ${formatFcfa(flow.plan.depositAmount)} a été envoyée au ${formatPhone(flow.phone)}. Saisissez votre code secret pour la confirmer.`)}
          </p>
        </div>

        <Badge tone="warning">
          <RefreshCw aria-hidden className="ek-spin" />
          {flow.checkout.busy ? 'Vérification du paiement' : "En attente de confirmation de l'opérateur"}
        </Badge>

        {widgetPayment && !unlinked ? (
          <Button
            variant="secondary"
            onClick={() => void flow.checkout.reopen()}
            loading={flow.checkout.busy}
            disabled={flow.localExpired}
          >
            Rouvrir la fenêtre de paiement
          </Button>
        ) : null}

        {transactionRef ? <p className="tnum text-caption text-muted">Référence : {transactionRef}</p> : null}
        {unlinked ? (
          <a href={supportHref} className="inline-flex items-center gap-1.5 text-label font-semibold text-primary-ink underline-offset-4 hover:underline">
            <LifeBuoy className="size-4" aria-hidden />
            Écrire au support avec cette référence
          </a>
        ) : null}
      </Card>

      <Card className="p-4 text-label leading-relaxed text-muted">
        La confirmation arrive automatiquement dès que l'opérateur nous répond — inutile de rafraîchir la page.
        Vous pouvez fermer l'application : la réservation reste valable jusqu'à la fin du compte à rebours, et
        vous recevrez une notification.
      </Card>

      <Button variant="ghost" block disabled={flow.checkout.busy || !!unlinked} onClick={flow.retryPayment}>
        Changer d'opérateur ou de numéro
      </Button>
    </m.div>
  )
}
