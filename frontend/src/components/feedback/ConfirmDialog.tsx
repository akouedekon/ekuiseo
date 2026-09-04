import type { ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

interface ConfirmDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: ReactNode
  description?: ReactNode
  /** Contenu additionnel (ex. un champ « motif ») entre la description et les boutons. */
  children?: ReactNode
  confirmLabel?: string
  cancelLabel?: string
  /** `danger` pour une action destructrice ou irreversible. */
  tone?: 'default' | 'danger'
  loading?: boolean
  confirmDisabled?: boolean
  onConfirm: () => void
}

/**
 * Confirmation d'une action qui engage (suppression, annulation, versement).
 * Une seule question, une seule action principale, et le bouton de retour en
 * premier a gauche pour que le geste reflexe ne soit jamais destructeur.
 */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  children,
  confirmLabel = 'Confirmer',
  cancelLabel = 'Revenir',
  tone = 'default',
  loading = false,
  confirmDisabled = false,
  onConfirm,
}: ConfirmDialogProps) {
  return (
    <Dialog open={open} onOpenChange={(next) => !loading && onOpenChange(next)}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description ? <DialogDescription>{description}</DialogDescription> : null}
        </DialogHeader>
        {children}
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)} disabled={loading}>
            {cancelLabel}
          </Button>
          <Button
            variant={tone === 'danger' ? 'danger' : 'primary'}
            onClick={onConfirm}
            loading={loading}
            disabled={confirmDisabled}
          >
            {confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
