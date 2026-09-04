import { motion } from 'motion/react'
import { CheckCircle2, ShieldQuestion, XCircle } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Textarea } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/misc'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { AdminPageHeader } from '@/components/layout/AdminPageHeader'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { useAdminReports, useResolveReport, useUpdateReportStatus } from '@/hooks/useAdmin'
import { describeError } from '@/lib/errors'
import { formatFromNow } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'
import type { AdminReportResponse, ReportReason, ReportStatus } from '@/api/extended'

const REASON_LABEL: Record<ReportReason, string> = {
  NO_SHOW: 'Absence au départ',
  DANGEROUS_DRIVING: 'Conduite dangereuse',
  HARASSMENT: 'Harcèlement',
  FRAUD: 'Fraude',
  VEHICLE_MISMATCH: 'Véhicule non conforme',
  OTHER: 'Autre',
}

const REASON_TONE: Record<ReportReason, 'danger' | 'warning' | 'neutral'> = {
  NO_SHOW: 'warning',
  DANGEROUS_DRIVING: 'danger',
  HARASSMENT: 'danger',
  FRAUD: 'danger',
  VEHICLE_MISMATCH: 'warning',
  OTHER: 'neutral',
}

const STATUS_LABEL: Record<ReportStatus, string> = {
  OPEN: 'Ouvert',
  IN_REVIEW: 'En cours',
  RESOLVED: 'Résolu',
  DISMISSED: 'Classé sans suite',
}

type Closing = { report: AdminReportResponse; status: 'RESOLVED' | 'DISMISSED' }

export function AdminReports() {
  const [filter, setFilter] = useState<ReportStatus | 'ALL'>('OPEN')
  const [closing, setClosing] = useState<Closing | null>(null)
  const [note, setNote] = useState('')
  const [busyId, setBusyId] = useState<string | null>(null)
  const reports = useAdminReports(filter)
  const update = useUpdateReportStatus()
  const resolve = useResolveReport()

  const list = reports.data ?? []

  const takeOver = (report: AdminReportResponse) => {
    setBusyId(report.id)
    update.mutate(
      { id: report.id, status: 'IN_REVIEW' },
      {
        onSuccess: () => toast.success('Signalement pris en charge'),
        onError: (error) => toast.error(describeError(error, "L'action a échoué.")),
        onSettled: () => setBusyId(null),
      },
    )
  }

  const closeDialog = () => {
    setClosing(null)
    setNote('')
  }

  const confirmClose = () => {
    if (!closing || !note.trim()) return
    resolve.mutate(
      { id: closing.report.id, status: closing.status, resolutionNote: note.trim() },
      {
        onSuccess: () => {
          toast.success(closing.status === 'RESOLVED' ? 'Signalement résolu' : 'Signalement classé sans suite')
          closeDialog()
        },
        onError: (error) => toast.error(describeError(error, "L'action a échoué.")),
      },
    )
  }

  return (
    <div>
      <AdminPageHeader
        title="Signalements"
        count={reports.isSuccess ? list.length : undefined}
        description="Prenez en charge, résolvez ou classez avec une note de résolution. La personne signalée n'est jamais informée de l'identité de l'auteur."
      />

      <Tabs value={filter} onValueChange={(value) => setFilter(value as ReportStatus | 'ALL')} className="mb-4">
        <TabsList>
          <TabsTrigger value="OPEN">Ouverts</TabsTrigger>
          <TabsTrigger value="IN_REVIEW">En cours</TabsTrigger>
          <TabsTrigger value="RESOLVED">Résolus</TabsTrigger>
          <TabsTrigger value="DISMISSED">Classés</TabsTrigger>
          <TabsTrigger value="ALL">Tous</TabsTrigger>
        </TabsList>
      </Tabs>

      {reports.isPending ? (
        <div className="space-y-2">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-32 rounded-[var(--radius-card)]" />
          ))}
        </div>
      ) : reports.isError ? (
        <ErrorState description={describeError(reports.error)} onRetry={() => reports.refetch()} />
      ) : list.length === 0 ? (
        <EmptyState
          icon={ShieldQuestion}
          title="Aucun signalement"
          description="Rien à modérer dans cette file pour le moment."
        />
      ) : (
        <motion.ul variants={listContainer} initial="hidden" animate="show" className="space-y-3">
          {list.map((report) => (
            <motion.li key={report.id} variants={listItem} layout>
              <Card
                className={
                  report.status === 'OPEN'
                    ? 'border-l-[3px] border-l-[var(--vermillon)]'
                    : report.status === 'IN_REVIEW'
                      ? 'border-l-[3px] border-l-[var(--ocre)]'
                      : 'border-l-[3px] border-l-rule-strong'
                }
              >
                <div className="p-4">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={REASON_TONE[report.reason] ?? 'neutral'}>{REASON_LABEL[report.reason] ?? report.reason}</Badge>
                    <Badge tone="outline">{STATUS_LABEL[report.status]}</Badge>
                    <span className="ml-auto text-[12px] text-muted">{formatFromNow(report.createdAt)}</span>
                  </div>

                  <p className="mt-2.5 text-[14px] leading-relaxed text-ink">{report.detail || 'Aucune précision fournie.'}</p>

                  <dl className="mt-3 grid gap-1 text-[13px] sm:grid-cols-2">
                    <div className="flex gap-1.5">
                      <dt className="text-muted">Signalé par</dt>
                      <dd className="font-medium">
                        <Link to={`/drivers/${report.reporter.id}`} className="underline-offset-4 hover:underline">
                          {report.reporter.firstName} {report.reporter.lastName}
                        </Link>
                      </dd>
                    </div>
                    <div className="flex gap-1.5">
                      <dt className="text-muted">Mis en cause</dt>
                      <dd className="font-medium">
                        <Link to={`/drivers/${report.target.id}`} className="underline-offset-4 hover:underline">
                          {report.target.firstName} {report.target.lastName}
                        </Link>
                      </dd>
                    </div>
                    {report.tripId ? (
                      <div className="flex gap-1.5">
                        <dt className="text-muted">Trajet</dt>
                        <dd className="font-medium">
                          <Link to={`/trips/${report.tripId}`} className="underline-offset-4 hover:underline">
                            Voir le trajet
                          </Link>
                        </dd>
                      </div>
                    ) : null}
                  </dl>
                </div>

                {report.status === 'OPEN' || report.status === 'IN_REVIEW' ? (
                  <div className="flex flex-wrap gap-2 border-t border-rule px-3 py-2.5">
                    {report.status === 'OPEN' ? (
                      <Button
                        size="sm"
                        variant="secondary"
                        loading={busyId === report.id && update.isPending}
                        onClick={() => takeOver(report)}
                      >
                        Prendre en charge
                      </Button>
                    ) : null}
                    <Button
                      size="sm"
                      variant="success"
                      disabled={busyId === report.id}
                      onClick={() => setClosing({ report, status: 'RESOLVED' })}
                    >
                      <CheckCircle2 className="size-4" aria-hidden />
                      Résoudre
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      className="ml-auto"
                      disabled={busyId === report.id}
                      onClick={() => setClosing({ report, status: 'DISMISSED' })}
                    >
                      <XCircle className="size-4" aria-hidden />
                      Classer sans suite
                    </Button>
                  </div>
                ) : null}
              </Card>
            </motion.li>
          ))}
        </motion.ul>
      )}

      <ConfirmDialog
        open={closing !== null}
        onOpenChange={(open) => !open && closeDialog()}
        title={closing?.status === 'RESOLVED' ? 'Résoudre ce signalement ?' : 'Classer sans suite ?'}
        description={
          closing
            ? closing.status === 'RESOLVED'
              ? 'Indiquez la mesure prise (avertissement, suspension, remboursement…). La note est conservée dans le dossier.'
              : "Indiquez pourquoi le signalement n'appelle aucune mesure. La note est conservée dans le dossier."
            : undefined
        }
        tone={closing?.status === 'DISMISSED' ? 'danger' : 'default'}
        confirmLabel={closing?.status === 'RESOLVED' ? 'Marquer résolu' : 'Classer'}
        confirmDisabled={!note.trim()}
        loading={resolve.isPending}
        onConfirm={confirmClose}
      >
        <Textarea
          label="Note de résolution"
          hint="Obligatoire."
          rows={3}
          maxLength={500}
          value={note}
          onChange={(event) => setNote(event.target.value)}
        />
      </ConfirmDialog>
    </div>
  )
}
