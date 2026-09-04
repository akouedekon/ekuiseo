import { motion } from 'motion/react'
import { BadgeCheck, Check, FileText, X } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Avatar, Skeleton } from '@/components/ui/misc'
import { Input } from '@/components/ui/input'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { useAdminVerifications, useReviewVerification } from '@/hooks/useAdmin'
import { formatFromNow, formatPhone } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'

const DOCUMENT_LABEL: Record<string, string> = {
  CNI: "Carte nationale d'identité",
  PASSPORT: 'Passeport',
  DRIVER_LICENSE: 'Permis de conduire',
}

export function AdminVerifications() {
  const verifications = useAdminVerifications()
  const review = useReviewVerification()
  const [rejecting, setRejecting] = useState<{ id: string; name: string } | null>(null)
  const [reason, setReason] = useState('')

  const list = verifications.data?.data ?? []

  const approve = (id: string, name: string) => {
    review.mutate(
      { id, approve: true },
      {
        onSuccess: () => toast.success(`Identité de ${name} validée`),
        onError: () => toast.error('La validation a échoué.'),
      },
    )
  }

  const confirmReject = () => {
    if (!rejecting) return
    review.mutate(
      { id: rejecting.id, approve: false, reason },
      {
        onSuccess: () => toast.success('Vérification refusée', { description: "L'utilisateur a été prévenu." }),
        onError: () => toast.error('Le refus a échoué.'),
      },
    )
    setRejecting(null)
    setReason('')
  }

  return (
    <div>

      {verifications.isPending ? (
        <div className="space-y-2">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-24 rounded-[var(--radius-card)]" />
          ))}
        </div>
      ) : verifications.isError ? (
        <ErrorState onRetry={() => verifications.refetch()} />
      ) : list.length === 0 ? (
        <EmptyState
          icon={BadgeCheck}
          title="File de vérification vide"
          description="Toutes les demandes d'identité ont été traitées."
        />
      ) : (
        <motion.ul variants={listContainer} initial="hidden" animate="show" className="space-y-3">
          {list.map((item) => (
            <motion.li key={item.id} variants={listItem} layout exit={{ opacity: 0, x: -24 }}>
              <Card>
                <div className="flex items-start gap-3 p-4">
                  <Avatar firstName={item.firstName} lastName={item.lastName} size={44} />
                  <div className="min-w-0 flex-1">
                    <p className="font-display text-[16px] font-bold">
                      {item.firstName} {item.lastName}
                    </p>
                    <p className="tnum text-[13px] text-muted">{formatPhone(item.phone)}</p>
                    <div className="mt-2 flex flex-wrap items-center gap-2">
                      <Badge tone="neutral">
                        <FileText aria-hidden />
                        {DOCUMENT_LABEL[item.documentType] ?? item.documentType}
                      </Badge>
                      <span className="tnum text-[13px] font-medium text-ink-2">{item.documentNumber}</span>
                    </div>
                  </div>
                  <span className="shrink-0 text-[12px] text-muted">{formatFromNow(item.submittedAt)}</span>
                </div>

                {/* TODO(backend) : afficher l'image du document une fois le
                    stockage securise disponible (GET /api/v1/admin/verifications/{id}/document). */}
                <div className="mx-4 mb-3 flex h-24 items-center justify-center rounded-[var(--radius-control)] border border-dashed border-rule-strong bg-[var(--surface-calm)] text-[13px] text-muted">
                  Aperçu du document indisponible
                </div>

                <div className="flex gap-2 border-t border-rule px-3 py-2.5">
                  <Button
                    size="sm"
                    variant="success"
                    onClick={() => approve(item.id, `${item.firstName} ${item.lastName}`)}
                  >
                    <Check className="size-4" aria-hidden />
                    Valider
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    className="text-[var(--vermillon)]"
                    onClick={() => setRejecting({ id: item.id, name: `${item.firstName} ${item.lastName}` })}
                  >
                    <X className="size-4" aria-hidden />
                    Refuser
                  </Button>
                </div>
              </Card>
            </motion.li>
          ))}
        </motion.ul>
      )}

      <Dialog open={rejecting !== null} onOpenChange={(open) => !open && setRejecting(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Refuser la vérification</DialogTitle>
            <DialogDescription>
              {rejecting?.name} sera prévenu et pourra renvoyer un document.
            </DialogDescription>
          </DialogHeader>
          <Input
            label="Motif du refus"
            placeholder="Document illisible, informations incohérentes…"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
          <DialogFooter>
            <Button variant="ghost" onClick={() => setRejecting(null)}>
              Annuler
            </Button>
            <Button variant="danger" disabled={!reason.trim()} onClick={confirmReject}>
              Confirmer le refus
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
