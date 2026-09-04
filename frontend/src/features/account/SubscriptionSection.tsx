import { BadgeCheck, Sparkles } from 'lucide-react'
import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/misc'
import { ErrorState } from '@/components/ui/states'
import { SectionTitle } from '@/components/layout/PageContainer'
import { apiClient } from '@/api/client'
import { useMySubscription, useSubscribe } from '@/hooks/useAccount'
import { describeError } from '@/lib/errors'
import { formatDayShort, formatFcfa } from '@/lib/format'
import { openKkiapay } from '@/lib/kkiapay'
import type { PaymentStatusResponse } from '@/api/extended'
import type { UserResponse } from '@/api/types'

/**
 * Abonnement conducteur (regle metier n.10) : 2 000 FCFA/mois, commission
 * ramenee a 0 %. Le paiement passe par le widget Kkiapay, puis le serveur
 * reverifie la transaction (POST /payments/{id}/confirm) avant d'activer.
 */
export function SubscriptionSection({ user }: { user: UserResponse }) {
  const subscription = useMySubscription()
  const subscribe = useSubscribe()
  const queryClient = useQueryClient()
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [paying, setPaying] = useState(false)

  const start = async () => {
    setConfirmOpen(false)
    setPaying(true)
    try {
      const payment = await subscribe.mutateAsync()
      const result = await openKkiapay({
        amount: payment.amount,
        publicKey: payment.kkiapayPublicKey,
        sandbox: payment.sandbox,
        phone: user.phone,
        name: `${user.firstName} ${user.lastName}`.trim(),
        email: user.email ?? undefined,
        data: payment.widgetData,
      })
      await apiClient.post<PaymentStatusResponse>(`/api/v1/payments/${payment.paymentId}/confirm`, {
        transactionId: result.transactionId,
      })
      await queryClient.invalidateQueries({ queryKey: ['me', 'subscription'] })
      toast.success('Abonnement activé', { description: 'Plus aucune commission sur vos trajets ce mois-ci.' })
    } catch (error) {
      const message = error instanceof Error ? error.message : ''
      if (message.startsWith('Kkiapay :')) {
        toast.error('La fenêtre de paiement ne peut pas s’ouvrir', {
          description: 'Vérifiez votre connexion ou un éventuel bloqueur de contenu, puis réessayez.',
        })
      } else {
        toast.error(describeError(error, "Le paiement n'a pas abouti. Aucun montant n'a été débité."))
      }
      await queryClient.invalidateQueries({ queryKey: ['me', 'subscription'] })
    } finally {
      setPaying(false)
    }
  }

  if (subscription.isPending) return <Skeleton className="h-32 rounded-[var(--radius-card)]" />
  if (subscription.isError) return <ErrorState onRetry={() => subscription.refetch()} />

  const data = subscription.data
  const active = data.currentlyActive

  return (
    <section aria-labelledby="subscription-title">
      <SectionTitle>
        <span id="subscription-title">Abonnement conducteur</span>
      </SectionTitle>
      <Card className="p-5">
        <div className="flex items-start gap-3">
          <span
            className={
              active
                ? 'flex size-11 shrink-0 items-center justify-center rounded-full bg-[var(--vert-soft)] text-[var(--vert)]'
                : 'flex size-11 shrink-0 items-center justify-center rounded-full bg-primary-soft text-primary-ink'
            }
          >
            {active ? <BadgeCheck className="size-5" aria-hidden /> : <Sparkles className="size-5" aria-hidden />}
          </span>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <p className="font-display text-lead font-bold">
                {active ? 'Abonnement actif' : `${formatFcfa(data.priceFcfa)} par mois, 0 % de commission`}
              </p>
              {active ? <Badge tone="success">0 % de commission</Badge> : null}
              {data.status === 'PENDING_PAYMENT' && !active ? <Badge tone="warning">Paiement en attente</Badge> : null}
            </div>
            <p className="mt-1 text-label text-ink-2">
              {active && data.currentPeriodEnd
                ? `Valable jusqu'au ${formatDayShort(data.currentPeriodEnd)}. Vos passagers paient l'acompte, vous recevez la totalité.`
                : 'Sans abonnement, Ekuiseo retient 8 % de chaque réservation. Avec, la commission tombe à 0 % pendant 30 jours : rentable dès 25 000 FCFA de réservations par mois.'}
            </p>
          </div>
        </div>
        {!active ? (
          <Button block className="mt-4" onClick={() => setConfirmOpen(true)} loading={paying || subscribe.isPending}>
            {data.status === 'PENDING_PAYMENT' ? 'Reprendre le paiement' : `S'abonner pour ${formatFcfa(data.priceFcfa)}`}
          </Button>
        ) : null}
      </Card>

      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="Activer l'abonnement conducteur ?"
        description={`${formatFcfa(data.priceFcfa)} seront débités par mobile money via Kkiapay. L'abonnement dure 30 jours et ne se renouvelle pas automatiquement.`}
        confirmLabel={`Payer ${formatFcfa(data.priceFcfa)}`}
        onConfirm={start}
      />
    </section>
  )
}
