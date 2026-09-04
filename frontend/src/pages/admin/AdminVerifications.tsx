import { motion } from 'motion/react'
import { BadgeCheck, Check, FileText, X } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Avatar, Skeleton } from '@/components/ui/misc'
import { Input } from '@/components/ui/input'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { AdminPageHeader } from '@/components/layout/AdminPageHeader'
import { useAdminVerifications, useReviewVerification } from '@/hooks/useAdmin'
import { describeError } from '@/lib/errors'
import { formatFromNow, formatPhone } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'
import type { IdentityVerificationStatus } from '@/api/extended'

const DOCUMENT_LABEL: Record<string, string> = {
  CNI: "Carte nationale d'identité",
  PASSPORT: 'Passeport',
  DRIVER_LICENSE: 'Permis de conduire',
}

type Filter = Extract<IdentityVerificationStatus, 'PENDING' | 'APPROVED' | 'REJECTED'>

export function AdminVerifications() {
  const [filter, setFilter] = useState<Filter>('PENDING')
  const verifications = useAdminVerifications(filter)
  const review = useReviewVerification()
  const [rejecting, setRejecting] = useState<{ id: string; name: string } | null>(null)
  const [reason, setReason] = useState('')
  const [busyId, setBusyId] = useState<string | null>(null)

  const list = verifications.data ?? []

  const approve = (id: string, name: string) => {
    setBusyId(id)
    review.mutate(
      { id, approve: true },
      {
        onSuccess: () => toast.success(`Identité de ${name} validée`),
        onError: (error) => toast.error(describeError(error, 'La validation a échoué.')),
        onSettled: () => setBusyId(null),
      },
    )
  }

  const confirmReject = () => {
    if (!rejecting || !reason.trim()) return
    setBusyId(rejecting.id)
    review.mutate(
      { id: rejecting.id, approve: false, reason: reason.trim() },
      {
        onSuccess: () => {
          toast.success('Vérification refusée', { description: "L'utilisateur a été prévenu." })
          setRejecting(null)
          setReason('')
        },
        onError: (error) => toast.error(describeError(error, 'Le refus a échoué.')),
        onSettled: () => setBusyId(null),
      },
    )
  }

  return (
    <div>
      <AdminPageHeader
        title="Vérifications d'identité"
        count={verifications.isSuccess ? list.length : undefined}
        description="Dossiers du plus ancien au plus récent. Un refus doit être motivé : l'utilisateur reçoit le motif."
      />

      <Tabs value={filter} onValueChange={(value) => setFilter(value as Filter)} className="mb-4">
        <TabsList>
          <TabsTrigger value="PENDING">En attente</TabsTrigger>
          <TabsTrigger value="APPROVED">Validées</TabsTrigger>
          <TabsTrigger value="REJECTED">Refusées</TabsTrigger>
        </TabsList>
      </Tabs>

      {/* Le televersement du document n'existe pas encore cote serveur : le
          moderateur controle le type et le numero declares, et peut croiser
          avec le profil public. Dit tel quel, sans faux apercu. */}
      <Card className="mb-4 border-[var(--ocre)] bg-[var(--ocre-soft)] px-4 py-3 text-[13px] leading-relaxed text-[var(--ocre-ink)]">
        Le dépôt de la photo du document n'est pas encore disponible : seuls le type et le numéro déclarés sont
        vérifiables ici. Validez uniquement après contrôle par un autre canal (appel, rendez-vous).
      </Card>

      {verifications.isPending ? (
        <div className="space-y-2">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-24 rounded-[var(--radius-card)]" />
          ))}
        </div>
      ) : verifications.isError ? (
        <ErrorState description={describeError(verifications.error)} onRetry={() => verifications.refetch()} />
      ) : list.length === 0 ? (
        <EmptyState
          icon={BadgeCheck}
          title={filter === 'PENDING' ? 'File de vérification vide' : 'Aucun dossier'}
          description={
            filter === 'PENDING'
              ? "Toutes les demandes d'identité ont été traitées."
              : 'Aucun dossier dans cet état pour le moment.'
          }
        />
      ) : (
        <motion.ul variants={listContainer} initial="hidden" animate="show" className="space-y-3">
          {list.map((item) => (
            <motion.li key={item.id} variants={listItem} layout exit={{ opacity: 0, x: -24 }}>
              <Card>
                <div className="flex items-start gap-3 p-4">
                  <Avatar firstName={item.firstName} lastName={item.lastName} size={44} />
                  <div className="min-w-0 flex-1">
                    <Link to={`/drivers/${item.userId}`} className="font-display text-[16px] font-bold underline-offset-4 hover:underline">
                      {item.firstName} {item.lastName}
                    </Link>
                    <p className="tnum text-[13px] text-muted">{formatPhone(item.phone)}</p>
                    <div className="mt-2 flex flex-wrap items-center gap-2">
                      <Badge tone="neutral">
                        <FileText aria-hidden />
                        {DOCUMENT_LABEL[item.documentType] ?? item.documentType}
                      </Badge>
                      <span className="tnum text-[13px] font-medium text-ink-2">{item.documentNumber}</span>
                      {item.status !== 'PENDING' ? (
                        <Badge tone={item.status === 'APPROVED' ? 'success' : 'danger'}>
                          {item.status === 'APPROVED' ? 'Validée' : 'Refusée'}
                        </Badge>
                      ) : null}
                    </div>
                  </div>
                  <span className="shrink-0 text-[12px] text-muted">{formatFromNow(item.submittedAt)}</span>
                </div>

                {item.status === 'PENDING' ? (
                  <div className="flex gap-2 border-t border-rule px-3 py-2.5">
                    <Button
                      size="sm"
                      variant="success"
                      loading={busyId === item.id && review.isPending}
                      disabled={busyId !== null && busyId !== item.id}
                      onClick={() => approve(item.id, `${item.firstName} ${item.lastName}`)}
                    >
                      <Check className="size-4" aria-hidden />
                      Valider
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      className="text-[var(--vermillon)]"
                      disabled={busyId !== null}
                      onClick={() => setRejecting({ id: item.id, name: `${item.firstName} ${item.lastName}` })}
                    >
                      <X className="size-4" aria-hidden />
                      Refuser
                    </Button>
                  </div>
                ) : null}
              </Card>
            </motion.li>
          ))}
        </motion.ul>
      )}

      <Dialog open={rejecting !== null} onOpenChange={(open) => !open && !review.isPending && setRejecting(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Refuser la vérification</DialogTitle>
            <DialogDescription>{rejecting?.name} sera prévenu et pourra renvoyer un document.</DialogDescription>
          </DialogHeader>
          <Input
            label="Motif du refus"
            placeholder="Document illisible, informations incohérentes…"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
          <DialogFooter>
            <Button variant="ghost" onClick={() => setRejecting(null)} disabled={review.isPending}>
              Annuler
            </Button>
            <Button variant="danger" disabled={!reason.trim()} loading={review.isPending} onClick={confirmReject}>
              Confirmer le refus
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
