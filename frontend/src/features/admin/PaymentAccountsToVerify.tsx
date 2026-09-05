import { ShieldCheck, Smartphone } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/misc'
import { SectionTitle } from '@/components/layout/PageContainer'
import { useAdminPaymentAccounts, useVerifyPaymentAccount } from '@/hooks/useAdmin'
import { describeError } from '@/lib/errors'
import { formatPhone } from '@/lib/format'
import { providerLabel } from '@/lib/payments'
import type { AdminPaymentAccountResponse } from '@/api/extended'

/**
 * Comptes mobile money enregistres avec un numero different du numero de connexion :
 * la possession doit etre etablie avant qu ils ne recoivent des reversements (constat
 * F605). L admin verifie hors ligne (appel au numero connu, piece), puis atteste ici.
 */
export function PaymentAccountsToVerify() {
  const accounts = useAdminPaymentAccounts(false)
  const verify = useVerifyPaymentAccount()
  const [target, setTarget] = useState<AdminPaymentAccountResponse | null>(null)
  const list = accounts.data ?? []

  const confirm = () => {
    if (!target) return
    const account = target
    verify.mutate(account.id, {
      onSuccess: () => {
        toast.success('Compte vérifié', { description: `${account.userName} pourra recevoir ses reversements sur ce numéro.` })
        setTarget(null)
      },
      onError: (error) => toast.error(describeError(error, "La vérification n'a pas abouti.")),
    })
  }

  if (accounts.isPending) return <Skeleton className="h-20 rounded-[var(--radius-card)]" />
  if (accounts.isError || list.length === 0) return null

  return (
    <section aria-labelledby="accounts-to-verify" className="mb-6">
      <SectionTitle>
        <span id="accounts-to-verify">Comptes mobile money à vérifier ({list.length})</span>
      </SectionTitle>
      <ul className="space-y-2">
        {list.map((account) => (
          <li key={account.id}>
            <Card className="flex flex-wrap items-center gap-3 p-4">
              <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-[var(--ocre-soft)] text-[var(--ocre-ink)]">
                <Smartphone className="size-5" aria-hidden />
              </span>
              <div className="min-w-0 flex-1">
                <p className="font-semibold text-ink">
                  {account.userName}{' '}
                  <span className="tnum font-normal text-muted">· compte {formatPhone(account.userPhone)}</span>
                </p>
                <p className="tnum text-label text-ink-2">
                  {providerLabel(account.provider)} {formatPhone(account.phone)}
                  {account.isDefault ? <Badge tone="indigo" className="ml-2">Par défaut</Badge> : null}
                </p>
              </div>
              <Button size="sm" onClick={() => setTarget(account)}>
                <ShieldCheck className="size-4" aria-hidden />
                Vérifier
              </Button>
            </Card>
          </li>
        ))}
      </ul>
      <p className="mt-2 text-[12px] leading-relaxed text-muted">
        Un numéro différent du numéro de connexion ne reçoit aucun reversement tant qu'il n'est pas vérifié : appelez
        l'utilisateur au numéro connu, confirmez qu'il possède bien ce compte, puis attestez ici (journalisé).
      </p>
      <ConfirmDialog
        open={target !== null}
        onOpenChange={(open) => !open && setTarget(null)}
        title="Attester la possession du compte ?"
        description={
          target
            ? `${target.userName} recevra ses reversements sur ${providerLabel(target.provider)} ${formatPhone(target.phone)}. Ne confirmez qu'après vérification hors ligne : cette attestation est journalisée.`
            : undefined
        }
        confirmLabel="Oui, vérifié"
        loading={verify.isPending}
        onConfirm={confirm}
      />
    </section>
  )
}
