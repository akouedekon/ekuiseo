import { useState } from 'react'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { Textarea } from '@/components/ui/input'
import { useAnonymizeUser, useRevokeIdentity } from '@/hooks/useAdmin'
import { describeError } from '@/lib/errors'

export type UserMotivatedActionKind = 'anonymize' | 'revoke-identity'

export interface UserMotivatedAction {
  kind: UserMotivatedActionKind
  user: { id: string; firstName: string; lastName: string }
}

interface UserMotivatedActionDialogProps {
  action: UserMotivatedAction | null
  onOpenChange: (open: boolean) => void
  onSuccess?: (kind: UserMotivatedActionKind) => void
}

/**
 * Actions motivees et journalisees sur un compte : anonymisation (irreversible)
 * et retrait du badge d'identite. Partage par la liste et la fiche utilisateur.
 */
export function UserMotivatedActionDialog({ action, onOpenChange, onSuccess }: UserMotivatedActionDialogProps) {
  const [reason, setReason] = useState('')
  const anonymize = useAnonymizeUser()
  const revokeIdentity = useRevokeIdentity()

  const close = () => {
    setReason('')
    onOpenChange(false)
  }

  const confirm = () => {
    if (!action || !reason.trim()) return
    const { kind, user } = action
    const name = `${user.firstName} ${user.lastName}`
    const input = { id: user.id, reason: reason.trim() }
    if (kind === 'anonymize') {
      anonymize.mutate(input, {
        onSuccess: () => {
          toast.success(`Le compte de ${name} a été anonymisé`, {
            description: 'Profil remplacé, contacts effacés, sessions révoquées. Réservations et paiements conservés.',
          })
          close()
          onSuccess?.(kind)
        },
        onError: (error) => toast.error(describeError(error, "L'anonymisation n'a pas abouti.")),
      })
    } else {
      revokeIdentity.mutate(input, {
        onSuccess: () => {
          toast.success(`Badge d'identité retiré à ${name}`, { description: "L'utilisateur a été prévenu, avec le motif." })
          close()
          onSuccess?.(kind)
        },
        onError: (error) => toast.error(describeError(error, "Le retrait n'a pas abouti.")),
      })
    }
  }

  return (
    <ConfirmDialog
      open={action !== null}
      onOpenChange={(open) => !open && close()}
      title={
        action?.kind === 'anonymize'
          ? `Anonymiser le compte de ${action.user.firstName} ${action.user.lastName} ?`
          : action
            ? `Retirer le badge d'identité de ${action.user.firstName} ${action.user.lastName} ?`
            : undefined
      }
      description={
        action?.kind === 'anonymize'
          ? "Irréversible. Le profil est remplacé (nom, contacts, photo), les comptes mobile money, alertes et notifications sont effacés et les sessions révoquées. Réservations, paiements et avis sont conservés pour la comptabilité. Refusé si un trajet ou une réservation est en cours."
          : "Le badge « Vérifié » disparaît du profil ; l'utilisateur est prévenu, avec le motif, et peut soumettre un nouveau dossier."
      }
      tone="danger"
      confirmLabel={action?.kind === 'anonymize' ? 'Anonymiser définitivement' : 'Retirer le badge'}
      confirmDisabled={!reason.trim()}
      loading={anonymize.isPending || revokeIdentity.isPending}
      onConfirm={confirm}
    >
      <Textarea
        label="Motif"
        hint="Obligatoire. Conservé dans le journal d'audit."
        placeholder={action?.kind === 'anonymize' ? "Demande d'effacement reçue le…" : 'Document déclaré invalide, usurpation signalée…'}
        rows={3}
        maxLength={500}
        value={reason}
        onChange={(event) => setReason(event.target.value)}
      />
    </ConfirmDialog>
  )
}
