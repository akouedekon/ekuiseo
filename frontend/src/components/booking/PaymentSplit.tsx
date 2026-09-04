import { Banknote, Info, Smartphone } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Separator } from '@/components/ui/misc'
import { cn } from '@/lib/cn'
import { formatFcfa } from '@/lib/format'
import type { PaymentPlanResponse } from '@/api/extended'

/**
 * Bloc « paiement en deux temps » — argument differenciant du produit.
 *
 * Tous les montants viennent du `paymentPlan` : aucun forfait n'est ecrit en
 * dur. L'acompte vaut max(1 000 FCFA, frais de service), le serveur en est la
 * seule autorite. Quand la reservation n'existe pas encore, `estimated` est
 * vrai et le bloc l'annonce sans ambiguite.
 */
export function PaymentSplit({
  plan,
  className,
  compact = false,
  estimated = false,
}: {
  plan: PaymentPlanResponse
  className?: string
  compact?: boolean
  /** Le plan vient d'un calcul local, pas encore du serveur. */
  estimated?: boolean
}) {
  const cashOnly = plan.depositAmount === 0
  const fullyOnline = plan.balanceAmount === 0

  return (
    <div className={cn('rounded-[var(--radius-card)] border border-rule bg-surface', className)}>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 px-4 pt-4">
        <span className="text-[14px] text-muted">Prix total du voyage</span>
        <span className="tnum font-display text-[24px] font-extrabold tracking-[-0.03em]">
          {formatFcfa(plan.totalAmount)}
        </span>
        {estimated ? (
          <Badge tone="warning" className="w-full sm:w-auto">
            Estimation — montants confirmés à la réservation
          </Badge>
        ) : null}
      </div>

      <div className={cn('mt-3 grid gap-px overflow-hidden bg-rule', !cashOnly && !fullyOnline && 'sm:grid-cols-2')}>
        {/* Volet 1 : ce qui se paie en ligne, maintenant */}
        {!cashOnly ? (
          <div className="bg-[var(--indigo-soft)] p-4">
            <span className="flex items-center gap-1.5 text-[12px] font-bold uppercase tracking-[0.06em] text-[var(--indigo-deep)]">
              <Smartphone className="size-3.5" aria-hidden />
              {fullyOnline ? 'En ligne — maintenant' : 'Étape 1 — maintenant'}
            </span>
            <p className="tnum mt-1.5 font-display text-[26px] font-extrabold leading-none tracking-[-0.03em] text-[var(--indigo-deep)]">
              {formatFcfa(plan.depositAmount)}
            </p>
            <p className="mt-1.5 text-[13px] leading-snug text-[var(--indigo-deep)]">
              {fullyOnline
                ? 'Voyage réglé intégralement en mobile money. Rien à prévoir à bord.'
                : 'Acompte en mobile money pour bloquer votre place.'}
            </p>
          </div>
        ) : null}

        {/* Volet 2 : ce qui se paie en especes, a bord */}
        {!fullyOnline ? (
          <div className="bg-[var(--surface-calm)] p-4">
            <span className="flex items-center gap-1.5 text-[12px] font-bold uppercase tracking-[0.06em] text-ink-2">
              <Banknote className="size-3.5" aria-hidden />
              {cashOnly ? 'En espèces — à bord' : 'Étape 2 — à bord'}
            </span>
            <p className="tnum mt-1.5 font-display text-[26px] font-extrabold leading-none tracking-[-0.03em]">
              {formatFcfa(plan.balanceAmount)}
            </p>
            <p className="mt-1.5 text-[13px] leading-snug text-ink-2">
              {cashOnly
                ? "Rien n'est réglé en ligne : votre place n'est pas garantie."
                : 'Solde réglé en espèces au conducteur, le jour du départ.'}
            </p>
          </div>
        ) : null}
      </div>

      {!compact ? (
        <div className="px-4 py-3">
          <dl className="space-y-1.5 text-[13px]">
            <div className="flex justify-between gap-4">
              <dt className="text-muted">
                Dont frais de service Ekuiseo
                {!cashOnly && plan.depositAmount <= plan.serviceFee ? ' (montant de l’acompte)' : ''}
              </dt>
              <dd className="tnum shrink-0">{formatFcfa(plan.serviceFee)}</dd>
            </div>
          </dl>
          <Separator className="my-3" />
          <p className="flex items-start gap-2 text-[13px] leading-relaxed text-muted">
            <Info className="mt-0.5 size-4 shrink-0" aria-hidden />
            <span>
              {cashOnly ? (
                <>
                  Sans versement en ligne, la place n'est pas garantie : le conducteur peut la réattribuer jusqu'au
                  départ. Vous pouvez annuler librement et sans frais.
                </>
              ) : fullyOnline ? (
                <>
                  Annulation gratuite jusqu'à {plan.freeCancellationHours} h avant le départ : vous êtes remboursé
                  intégralement. Au-delà, seuls les frais de service restent acquis. Si le conducteur annule, vous
                  êtes remboursé dans tous les cas.
                </>
              ) : (
                <>
                  Annulation gratuite jusqu'à {plan.freeCancellationHours} h avant le départ : l'acompte est
                  remboursé intégralement. Au-delà, il reste acquis au conducteur. Si le conducteur annule, vous êtes
                  remboursé dans tous les cas.
                </>
              )}
            </span>
          </p>
        </div>
      ) : null}
    </div>
  )
}
