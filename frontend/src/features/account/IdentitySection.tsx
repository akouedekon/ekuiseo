import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/misc'
import { Sheet } from '@/components/ui/sheet'
import { ErrorState } from '@/components/ui/states'
import { useIdentityVerification, useSubmitIdentity } from '@/hooks/useAccount'
import { describeError } from '@/lib/errors'
import { formatFromNow } from '@/lib/format'
import { IDENTITY_FORM_ID, IdentityForm } from './forms/IdentityForm'
import { IDENTITY_PRESENTATION, documentLabel } from './identity'

export function IdentitySection() {
  const identity = useIdentityVerification()
  const submit = useSubmitIdentity()
  const [open, setOpen] = useState(false)

  if (identity.isPending) {
    return <Skeleton className="h-28 rounded-[var(--radius-card)]" />
  }
  if (identity.isError) {
    return (
      <ErrorState
        title="Statut d'identité indisponible"
        description="Impossible de vérifier l'état de votre dossier pour l'instant."
        onRetry={() => identity.refetch()}
      />
    )
  }

  const data = identity.data
  const status = data.status
  const presentation = IDENTITY_PRESENTATION[status]
  const Icon = presentation.icon
  const canSubmit = status === 'NOT_SUBMITTED' || status === 'REJECTED'

  return (
    <section aria-labelledby="identity-title">
      <Card className="p-5">
        <div className="flex items-start gap-3">
          <span
            className={
              status === 'APPROVED'
                ? 'flex size-11 shrink-0 items-center justify-center rounded-full bg-[var(--vert-soft)] text-[var(--vert)]'
                : status === 'REJECTED'
                  ? 'flex size-11 shrink-0 items-center justify-center rounded-full bg-[var(--vermillon-soft)] text-[var(--vermillon)]'
                  : 'flex size-11 shrink-0 items-center justify-center rounded-full bg-[var(--ocre-soft)] text-[var(--ocre-ink)]'
            }
          >
            <Icon className="size-5" aria-hidden />
          </span>
          <div className="min-w-0 flex-1">
            <p id="identity-title" className="font-display text-lead font-bold">
              {presentation.label}
            </p>
            {data?.documentType ? (
              <p className="text-label text-muted">
                {documentLabel(data.documentType) ?? data.documentType}
                {data.submittedAt ? ` · envoyé ${formatFromNow(data.submittedAt)}` : ''}
              </p>
            ) : (
              <p className="text-label text-muted">
                Une identité vérifiée rassure les passagers et augmente nettement vos réservations.
              </p>
            )}
            {data?.rejectionReason ? (
              <p className="mt-2 rounded-[var(--radius-control)] bg-[var(--vermillon-soft)] px-3 py-2 text-label text-[var(--vermillon)]">
                {data.rejectionReason}
              </p>
            ) : null}
            {status === 'PENDING' ? (
              <p className="mt-2 text-label text-ink-2">
                Un agent Ekuiseo contrôle votre dossier. Vous serez prévenu par notification.
              </p>
            ) : null}
          </div>
        </div>

        {canSubmit ? (
          <Button block className="mt-4" onClick={() => setOpen(true)}>
            {status === 'REJECTED' ? 'Renvoyer un document' : 'Vérifier mon identité'}
          </Button>
        ) : null}
      </Card>

      <Sheet
        open={open}
        onOpenChange={setOpen}
        title="Vérifier mon identité"
        description="Vos données servent uniquement à la vérification et ne sont jamais publiées."
        footer={
          <Button type="submit" form={IDENTITY_FORM_ID} size="lg" block loading={submit.isPending}>
            Envoyer pour vérification
          </Button>
        }
      >
        <IdentityForm
          onSubmit={(values) =>
            submit.mutate(values, {
              onSuccess: () => {
                setOpen(false)
                toast.success('Dossier envoyé', { description: 'Vous serez prévenu dès la vérification.' })
              },
              onError: (error) => toast.error(describeError(error, "L'envoi n'a pas abouti. Réessayez.")),
            })
          }
        />
      </Sheet>
    </section>
  )
}
