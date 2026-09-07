import { m } from 'motion/react'
import { Banknote, Wallet } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import { Progress, Skeleton } from '@/components/ui/misc'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { SectionTitle } from '@/components/layout/PageContainer'
import { useDriverBalance, useMyPayouts } from '@/hooks/useAccount'
import { formatDayShort, formatFcfa, formatPhone } from '@/lib/format'
import { CONTACT_EMAIL } from '@/lib/legal'
import { listContainer, listItem } from '@/lib/motion'
import type { PayoutStatus } from '@/api/extended'

/*
 * Libelles honnetes (audit F233) : le virement mobile money est fait a la main
 * par l'equipe Ekuiseo, il n'y a ni automate ni « relance en cours ». Un echec
 * appelle un contact avec le support, pas une attente.
 */
const STATUS: Record<PayoutStatus, { label: string; tone: 'warning' | 'indigo' | 'success' | 'danger' }> = {
  PENDING: { label: 'À verser', tone: 'warning' },
  PROCESSING: { label: 'Virement en préparation', tone: 'indigo' },
  PAID: { label: 'Versé', tone: 'success' },
  SETTLED: { label: 'Versé', tone: 'success' },
  FAILED: { label: 'Échec du virement', tone: 'danger' },
}

/**
 * Revenus du conducteur : solde net en attente (reservations payees en mobile
 * money, commission deduite) et lots de reversement. Les montants viennent du
 * serveur (regle metier n.4) ; rien n'est recalcule ici.
 */
export function EarningsSection() {
  const balance = useDriverBalance()
  const payouts = useMyPayouts()
  const list = payouts.data ?? []
  const hasFailed = list.some((p) => p.status === 'FAILED')

  return (
    <div>
      <SectionTitle>Solde à reverser</SectionTitle>
      {balance.isPending ? (
        <Skeleton className="h-28 rounded-[var(--radius-card)]" />
      ) : balance.isError ? (
        <ErrorState onRetry={() => balance.refetch()} />
      ) : (
        <Card className="p-5">
          <div className="flex items-start gap-3">
            <span className="flex size-11 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-primary-soft text-primary-ink">
              <Wallet className="size-5" aria-hidden />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-label text-muted">Encaissé en ligne, commission déduite</p>
              <p className="tnum font-display text-display font-extrabold leading-none tracking-[-0.03em]">
                {formatFcfa(balance.data.pendingBalanceFcfa)}
              </p>
            </div>
          </div>
          <Progress
            className="mt-4"
            value={Math.min(
              100,
              Math.round((balance.data.pendingBalanceFcfa / Math.max(1, balance.data.minimumPayoutThresholdFcfa)) * 100),
            )}
            aria-label="Progression vers le seuil de reversement"
          />
          <p className="mt-2 text-label leading-relaxed text-ink-2">
            {balance.data.pendingBalanceFcfa >= balance.data.minimumPayoutThresholdFcfa
              ? "Seuil atteint : ce solde sera inclus dans le prochain lot constitué par l'équipe Ekuiseo, puis viré à la main sur votre compte mobile money vérifié."
              : `Les reversements sont déclenchés par l'équipe Ekuiseo, en général chaque semaine, dès ${formatFcfa(balance.data.minimumPayoutThresholdFcfa)} de solde et sur un compte mobile money vérifié. Le solde en espèces réglé à bord ne transite pas par Ekuiseo.`}
          </p>
        </Card>
      )}

      <SectionTitle className="mt-5">Reversements</SectionTitle>
      {payouts.isPending ? (
        <div className="space-y-2">
          <Skeleton className="h-[72px] rounded-[var(--radius-card)]" />
          <Skeleton className="h-[72px] rounded-[var(--radius-card)]" />
        </div>
      ) : payouts.isError ? (
        <ErrorState onRetry={() => payouts.refetch()} />
      ) : list.length === 0 ? (
        <Card>
          <EmptyState
            icon={Banknote}
            title="Aucun reversement pour l'instant"
            description="Votre premier lot apparaîtra ici lorsque l'équipe Ekuiseo l'aura constitué, une fois le seuil atteint."
            className="py-8"
          />
        </Card>
      ) : (
        <>
          <m.ul variants={listContainer} initial="hidden" animate="show" className="space-y-2">
            {list.map((payout) => (
              <m.li key={payout.id} variants={listItem}>
                <Card className="flex items-center gap-3 p-4">
                  <div className="min-w-0 flex-1">
                    <p className="tnum font-display text-lead font-bold">{formatFcfa(payout.amount)}</p>
                    <p className="tnum text-label text-muted">
                      {formatDayShort(payout.periodStart)} → {formatDayShort(payout.periodEnd)}
                      {payout.destinationMsisdn ? ` · ${formatPhone(payout.destinationMsisdn)}` : ''}
                    </p>
                    {/* Date reelle du virement (audit F504) : c'est elle que le conducteur rapproche de son releve. */}
                    {payout.settledAt ? (
                      <p className="tnum text-label font-medium text-success-ink">Versé le {formatDayShort(payout.settledAt)}</p>
                    ) : null}
                  </div>
                  <Badge tone={STATUS[payout.status]?.tone ?? 'neutral'}>{STATUS[payout.status]?.label ?? payout.status}</Badge>
                </Card>
              </m.li>
            ))}
          </m.ul>
          {hasFailed ? (
            <p className="mt-3 rounded-[var(--radius-control)] bg-danger-soft px-3 py-2 text-label leading-relaxed text-danger-ink">
              Un virement n'a pas abouti (numéro invalide, plafond de compte, opérateur en panne). Vérifiez votre compte
              mobile money dans « Paiement », puis écrivez à{' '}
              <a href={`mailto:${CONTACT_EMAIL}`} className="font-semibold underline underline-offset-2">
                {CONTACT_EMAIL}
              </a>{' '}
              pour qu'il soit refait.
            </p>
          ) : null}
        </>
      )}
    </div>
  )
}
