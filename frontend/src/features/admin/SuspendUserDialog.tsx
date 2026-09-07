import { useState } from 'react'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { Textarea } from '@/components/ui/input'
import { useToggleUserSuspension } from '@/hooks/useAdmin'
import { describeError } from '@/lib/errors'

/** Ce qu'il faut savoir d'un compte pour le suspendre ou le reintegrer. */
export interface SuspensionTarget {
  id: string
  firstName: string
  lastName: string
  suspended: boolean
}

interface SuspendUserDialogProps {
  target: SuspensionTarget | null
  onOpenChange: (open: boolean) => void
  onSuccess?: () => void
}

/**
 * Suspension motivee (journalisee, utilisateur prevenu avec le motif) ou
 * reintegration d'un compte. Partage par la liste des utilisateurs, la fiche
 * utilisateur et les signalements.
 */
export function SuspendUserDialog({ target, onOpenChange, onSuccess }: SuspendUserDialogProps) {
  const [reason, setReason] = useState('')
  const toggle = useToggleUserSuspension()

  const close = () => {
    setReason('')
    onOpenChange(false)
  }

  const confirm = () => {
    if (!target) return
    const user = target
    const suspend = !user.suspended
    if (suspend && !reason.trim()) return
    toggle.mutate(
      { id: user.id, suspend, reason: suspend ? reason.trim() : undefined },
      {
        onSuccess: () => {
          toast.success(
            suspend ? `${user.firstName} ${user.lastName} a été suspendu` : `${user.firstName} ${user.lastName} a été réactivé`,
          )
          close()
          onSuccess?.()
        },
        onError: (error) => toast.error(describeError(error, "L'action n'a pas abouti. Réessayez.")),
      },
    )
  }

  return (
    <ConfirmDialog
      open={target !== null}
      onOpenChange={(open) => !open && close()}
      title={target?.suspended ? 'Réactiver ce compte ?' : 'Suspendre ce compte ?'}
      description={
        target
          ? target.suspended
            ? `${target.firstName} ${target.lastName} pourra de nouveau se connecter, réserver et publier.`
            : `${target.firstName} ${target.lastName} ne pourra plus se connecter ni réserver ; il en sera informé, avec le motif. Ses trajets à venir restent visibles jusqu'à leur annulation manuelle.`
          : undefined
      }
      tone={target?.suspended ? 'default' : 'danger'}
      confirmLabel={target?.suspended ? 'Réactiver' : 'Suspendre'}
      confirmDisabled={target ? !target.suspended && !reason.trim() : true}
      loading={toggle.isPending}
      onConfirm={confirm}
    >
      {target && !target.suspended ? (
        <Textarea
          label="Motif de la suspension"
          hint="Obligatoire. Conservé dans le journal d'audit."
          placeholder="Signalements répétés, fraude à l'acompte…"
          rows={3}
          maxLength={500}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
        />
      ) : null}
    </ConfirmDialog>
  )
}
