import { motion } from 'motion/react'
import { CheckCircle2, ShieldQuestion, XCircle } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/misc'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { AdminPageHeader } from '@/components/layout/AdminPageHeader'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { useAdminReports, useUpdateReportStatus } from '@/hooks/useAdmin'
import { formatFromNow } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'
import type { ReportReason, ReportStatus } from '@/api/extended'

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

export function AdminReports() {
  const [filter, setFilter] = useState<ReportStatus | 'ALL'>('OPEN')
  const reports = useAdminReports(filter)
  const update = useUpdateReportStatus()

  const list = reports.data?.data ?? []

  const act = (id: string, status: ReportStatus, message: string) => {
    update.mutate(
      { id, status },
      { onSuccess: () => toast.success(message), onError: () => toast.error("L'action a échoué.") },
    )
  }

  return (
    <div>
      <AdminPageHeader
        title="Signalements"
        count={reports.isSuccess ? list.length : undefined}
        description="Prenez en charge, résolvez ou classez. La personne signalée n'est jamais informée de l'identité de l'auteur."
      />

      <Tabs value={filter} onValueChange={(value) => setFilter(value as ReportStatus | 'ALL')} className="mb-4">
        <TabsList>
          <TabsTrigger value="OPEN">Ouverts</TabsTrigger>
          <TabsTrigger value="IN_REVIEW">En cours</TabsTrigger>
          <TabsTrigger value="RESOLVED">Résolus</TabsTrigger>
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
        <ErrorState onRetry={() => reports.refetch()} />
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
                    <Badge tone={REASON_TONE[report.reason]}>{REASON_LABEL[report.reason]}</Badge>
                    <Badge tone="outline">{STATUS_LABEL[report.status]}</Badge>
                    <span className="ml-auto text-[12px] text-muted">{formatFromNow(report.createdAt)}</span>
                  </div>

                  <p className="mt-2.5 text-[14px] leading-relaxed text-ink">{report.detail}</p>

                  <dl className="mt-3 grid gap-1 text-[13px] sm:grid-cols-2">
                    <div className="flex gap-1.5">
                      <dt className="text-muted">Signalé par</dt>
                      <dd className="font-medium">
                        {report.reporter.firstName} {report.reporter.lastName}
                      </dd>
                    </div>
                    <div className="flex gap-1.5">
                      <dt className="text-muted">Mis en cause</dt>
                      <dd className="font-medium">
                        {report.target.firstName} {report.target.lastName}
                      </dd>
                    </div>
                  </dl>
                </div>

                {report.status === 'OPEN' || report.status === 'IN_REVIEW' ? (
                  <div className="flex flex-wrap gap-2 border-t border-rule px-3 py-2.5">
                    {report.status === 'OPEN' ? (
                      <Button
                        size="sm"
                        variant="secondary"
                        onClick={() => act(report.id, 'IN_REVIEW', 'Signalement pris en charge')}
                      >
                        Prendre en charge
                      </Button>
                    ) : null}
                    <Button
                      size="sm"
                      variant="success"
                      onClick={() => act(report.id, 'RESOLVED', 'Signalement résolu')}
                    >
                      <CheckCircle2 className="size-4" aria-hidden />
                      Résoudre
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      className="ml-auto"
                      onClick={() => act(report.id, 'DISMISSED', 'Signalement classé')}
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
    </div>
  )
}
