import { BadgeCheck, Sparkles } from 'lucide-react'
import { useEffect, useState } from 'react'
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
import { useKkiapayCheckout } from '@/hooks/useKkiapayCheckout'
import { formatDayShort, formatFcfa } from '@/lib/format'
import type { PaymentStatusResponse } from '@/api/extended'
import type { UserResponse } from '@/api/types'

const PENDING_CONFIRM_KEY = 'ekuiseo.subscription.pendingConfirm'
const SUBSCRIPTION_KEY = ['me', 'subscription'] as const

function readPendingConfirm(): { paymentId: string; transactionId: string } | null {
  try {
    const raw = sessionStorage.getItem(PENDING_CONFIRM_KEY)
    return raw ? (JSON.parse(raw) as { paymentId: string; transactionId: string }) : null
  } catch {
    return null
  }
}

function savePendingConfirm(value: { paymentId: string; transactionId: string }) {
  try {
    sessionStorage.setItem(PENDING_CONFIRM_KEY, JSON.stringify(value))
  } catch {
    // stockage indisponible : la reprise ne sera pas possible, le paiement reste verifiable par le webhook
  }
}

function clearPendingConfirm() {
  try {
    sessionStorage.removeItem(PENDING_CONFIRM_KEY)
  } catch {
    // ignore
  }
}

/**
 * Abonnement conducteur (regle metier n.10) : 2 000 FCFA/mois, commission
 * ramenee a 0 %. Le paiement passe par le parcours Kkiapay partage avec la
 * reservation (useKkiapayCheckout, audit F243) : le serveur reverifie la
 * transaction (POST /payments/{id}/confirm) avant d'activer.
 */
export function SubscriptionSection({ user }: { user: UserResponse }) {
  const subscription = useMySubscription()
  const subscribe = useSubscribe()
  const queryClient = useQueryClient()
  const [confirmOpen, setConfirmOpen] = useState(false)

  const checkout = useKkiapayCheckout({
    initiate: () => subscribe.mutateAsync(),
    payer: user,
    invalidate: [SUBSCRIPTION_KEY],
    onConfirmed: (status) => {
      clearPendingConfirm()
      if (status.status === 'SUCCEEDED') {
        toast.success('Abonnement activé', { description: 'Plus aucune commission sur vos trajets ce mois-ci.' })
      } else {
        toast.message('Paiement en cours de vérification', {
          description: "L'abonnement s'activera dès que l'opérateur aura confirmé. Vous serez prévenu.",
        })
      }
    },
    /*
     * Reprise : si le widget a reussi mais que la confirmation serveur a echoue (reseau,
     * onglet ferme), paymentId + transactionId sont conserves pour rejouer /confirm avec
     * le MEME paiement au lieu d'en creer un nouveau (constat F206).
     */
    onUnlinked: (transactionId, payment) => savePendingConfirm({ paymentId: payment.paymentId, transactionId }),
    onFailed: () => void queryClient.invalidateQueries({ queryKey: SUBSCRIPTION_KEY }),
  })

  useEffect(() => {
    const raw = readPendingConfirm()
    if (!raw) return
    apiClient
      .post<PaymentStatusResponse>(`/api/v1/payments/${raw.paymentId}/confirm`, { transactionId: raw.transactionId })
      .then(async (status) => {
        if (status.status === 'SUCCEEDED') {
          clearPendingConfirm()
          await queryClient.invalidateQueries({ queryKey: SUBSCRIPTION_KEY })
          toast.success('Abonnement activé', { description: 'Votre paiement précédent a été retrouvé et confirmé.' })
        } else if (status.status === 'FAILED') {
          clearPendingConfirm()
        }
      })
      .catch(() => undefined)
  }, [queryClient])

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
                ? 'flex size-11 shrink-0 items-center justify-center rounded-full bg-success-soft text-success-ink'
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
                ? `Valable jusqu'au ${formatDayShort(data.currentPeriodEnd)} — vos passagers paient l'acompte, vous recevez la totalité.`
                : 'Sans abonnement, Ekuiseo retient 8 % de chaque réservation. Avec, la commission tombe à 0 % pendant 30 jours : rentable dès 25 000 FCFA de réservations par mois.'}
            </p>
          </div>
        </div>
        {!active ? (
          <Button block className="mt-4" onClick={() => setConfirmOpen(true)} loading={checkout.busy || subscribe.isPending}>
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
        onConfirm={() => {
          setConfirmOpen(false)
          void checkout.start()
        }}
      />
    </section>
  )
}
