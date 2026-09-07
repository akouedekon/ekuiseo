import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Textarea } from '@/components/ui/input'
import { SelectField } from '@/components/forms/SelectField'
import { useCreateReport } from '@/hooks/useReviews'
import { describeError } from '@/lib/errors'
import { REPORT_REASON_OPTIONS } from '@/lib/labels'
import type { ReportReason } from '@/api/extended'

interface ReportDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Exactement une cible : un utilisateur ou un trajet. */
  target: { userId: string; label: string } | { tripId: string; label: string }
}

/**
 * Signalement d'un utilisateur ou d'un trajet (POST /api/v1/reports). Traite
 * par la moderation dans le back-office ; l'auteur reste anonyme pour la
 * personne signalee. Motif et precisions repartent de zero a chaque fermeture
 * (audit F251) ; aucun delai chiffre n'est promis.
 */
export function ReportDialog({ open, onOpenChange, target }: ReportDialogProps) {
  const [reason, setReason] = useState<ReportReason>('OTHER')
  const [details, setDetails] = useState('')
  const report = useCreateReport()

  const close = (next: boolean) => {
    if (report.isPending) return
    if (!next) {
      setReason('OTHER')
      setDetails('')
    }
    onOpenChange(next)
  }

  const submit = () => {
    report.mutate(
      {
        ...('userId' in target ? { reportedUserId: target.userId } : { reportedTripId: target.tripId }),
        reasonCode: reason,
        details: details.trim() || undefined,
      },
      {
        onSuccess: () => {
          close(false)
          toast.success('Signalement transmis', { description: "L'équipe Ekuiseo l'examine dans les meilleurs délais." })
        },
        onError: (error) => toast.error(describeError(error, "Le signalement n'a pas pu être envoyé.")),
      },
    )
  }

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Signaler {target.label}</DialogTitle>
          <DialogDescription>
            Votre signalement est confidentiel : la personne concernée ne saura pas qui l'a envoyé.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <SelectField
            label="Motif"
            value={reason}
            onValueChange={(value) => setReason(value)}
            options={REPORT_REASON_OPTIONS}
          />
          <Textarea
            label="Précisions"
            hint="Faits, date, ce qui s'est passé. 500 caractères maximum."
            maxLength={500}
            rows={4}
            value={details}
            onChange={(event) => setDetails(event.target.value)}
          />
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => close(false)} disabled={report.isPending}>
            Annuler
          </Button>
          <Button variant="danger" onClick={submit} loading={report.isPending}>
            Envoyer le signalement
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
