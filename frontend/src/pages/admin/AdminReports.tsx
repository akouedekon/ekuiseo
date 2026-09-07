import { m } from 'motion/react'
import { Ban, CheckCircle2, Eye, History, MessagesSquare, ShieldQuestion, XCircle } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Textarea } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/misc'
import { Sheet } from '@/components/ui/sheet'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { AdminPageHeader } from '@/components/layout/AdminPageHeader'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { SuspendUserDialog, type SuspensionTarget } from '@/features/admin/SuspendUserDialog'
import { useAdminReports, useReportConversations, useResolveReport, useUpdateReportStatus } from '@/hooks/useAdmin'
import { useMe } from '@/hooks/useAuth'
import { describeError } from '@/lib/errors'
import { formatDateTime, formatFromNow } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'
import type { AdminReportConversationResponse, AdminReportResponse, ReportReason, ReportStatus } from '@/api/extended'

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

function isClosed(status: ReportStatus): boolean {
  return status === 'RESOLVED' || status === 'DISMISSED'
}

/**
 * Signalements : prise en charge (OPEN -> IN_REVIEW), puis cloture motivee
 * (RESOLVED / DISMISSED, note obligatoire). Les echanges lies ne se chargent
 * qu'a la demande : leur lecture est journalisee cote serveur.
 */
export function AdminReports() {
  const [filter, setFilter] = useState<ReportStatus | 'ALL'>('OPEN')
  const [closing, setClosing] = useState<Closing | null>(null)
  const [note, setNote] = useState('')
  const [busyId, setBusyId] = useState<string | null>(null)
  const [conversationsOf, setConversationsOf] = useState<AdminReportResponse | null>(null)
  const [suspension, setSuspension] = useState<SuspensionTarget | null>(null)
  const reports = useAdminReports(filter)
  const update = useUpdateReportStatus()
  const resolve = useResolveReport()
  const me = useMe()

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
        <m.ul variants={listContainer} initial="hidden" animate="show" className="space-y-3">
          {list.map((report) => {
            const priors = report.priorReportsAgainstTarget ?? 0
            const targetIsMe = me.data?.id === report.target.id
            return (
              <m.li key={report.id} variants={listItem}>
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
                      {priors > 0 ? (
                        <Badge tone="danger">
                          <History aria-hidden />
                          {priors} signalement{priors > 1 ? 's' : ''} antérieur{priors > 1 ? 's' : ''} contre cette personne
                        </Badge>
                      ) : null}
                      <span className="ml-auto text-[12px] text-muted">{formatFromNow(report.createdAt)}</span>
                    </div>

                    <p className="mt-2.5 text-[14px] leading-relaxed text-ink">{report.detail || 'Aucune précision fournie.'}</p>

                    <dl className="mt-3 grid gap-1 text-[13px] sm:grid-cols-2">
                      <div className="flex gap-1.5">
                        <dt className="text-muted">Signalé par</dt>
                        <dd className="font-medium">
                          <Link to={`/admin/users/${report.reporter.id}`} className="underline-offset-4 hover:underline">
                            {report.reporter.firstName} {report.reporter.lastName}
                          </Link>
                        </dd>
                      </div>
                      <div className="flex gap-1.5">
                        <dt className="text-muted">Mis en cause</dt>
                        <dd className="font-medium">
                          <Link to={`/admin/users/${report.target.id}`} className="underline-offset-4 hover:underline">
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
                      {report.bookingId ? (
                        <div className="flex gap-1.5">
                          <dt className="text-muted">Réservation</dt>
                          <dd className="tnum font-medium text-ink-2">{report.bookingId.slice(0, 8)}</dd>
                        </div>
                      ) : null}
                    </dl>

                    {/* Dossier clos : la decision, qui l'a prise et quand - ce que le prochain moderateur doit lire en premier. */}
                    {isClosed(report.status) ? (
                      <div className="mt-3 rounded-[var(--radius-control)] bg-[var(--surface-calm)] px-3 py-2.5 text-[13px]">
                        <p className="font-medium text-ink">
                          {report.status === 'RESOLVED' ? 'Mesure prise' : 'Motif du classement'}
                        </p>
                        <p className="mt-0.5 leading-relaxed text-ink-2">{report.resolutionNote ?? '—'}</p>
                        <p className="mt-1 text-[12px] text-muted">
                          {report.resolvedBy ? `Par ${report.resolvedBy.firstName} ${report.resolvedBy.lastName}` : 'Par le système'}
                          {report.resolvedAt ? ` · ${formatDateTime(report.resolvedAt)}` : ''}
                        </p>
                      </div>
                    ) : null}
                  </div>

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
                    {!isClosed(report.status) ? (
                      <Button
                        size="sm"
                        variant="success"
                        disabled={busyId === report.id}
                        onClick={() => setClosing({ report, status: 'RESOLVED' })}
                      >
                        <CheckCircle2 className="size-4" aria-hidden />
                        Résoudre
                      </Button>
                    ) : null}
                    <Button size="sm" variant="ghost" onClick={() => setConversationsOf(report)}>
                      <MessagesSquare className="size-4" aria-hidden />
                      Échanges
                    </Button>
                    {!targetIsMe ? (
                      <Button
                        size="sm"
                        variant="ghost"
                        className="text-[var(--vermillon)]"
                        onClick={() => setSuspension({ ...report.target, suspended: false })}
                      >
                        <Ban className="size-4" aria-hidden />
                        Suspendre
                      </Button>
                    ) : null}
                    {!isClosed(report.status) ? (
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
                    ) : null}
                  </div>
                </Card>
              </m.li>
            )
          })}
        </m.ul>
      )}

      <ConfirmDialog
        open={closing !== null}
        onOpenChange={(open) => !open && closeDialog()}
        title={closing?.status === 'RESOLVED' ? 'Résoudre ce signalement ?' : 'Classer sans suite ?'}
        description={
          closing
            ? closing.status === 'RESOLVED'
              ? "Indiquez la mesure prise (avertissement, suspension, remboursement…). La note est conservée dans le dossier ; l'auteur du signalement est informé de la clôture, sans le détail de la mesure."
              : "Indiquez pourquoi le signalement n'appelle aucune mesure. La note est conservée dans le dossier ; l'auteur du signalement est informé du classement."
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

      <SuspendUserDialog target={suspension} onOpenChange={(open) => !open && setSuspension(null)} />

      <ConversationsSheet report={conversationsOf} onOpenChange={(open) => !open && setConversationsOf(null)} />
    </div>
  )
}

/**
 * Volet des echanges lies a un signalement. La requete ne part qu'a l'ouverture :
 * chaque consultation est journalisee cote serveur (acces a une messagerie privee).
 */
function ConversationsSheet({
  report,
  onOpenChange,
}: {
  report: AdminReportResponse | null
  onOpenChange: (open: boolean) => void
}) {
  const conversations = useReportConversations(report?.id ?? null)
  return (
    <Sheet
      open={report !== null}
      onOpenChange={onOpenChange}
      title="Échanges liés au signalement"
      description={
        report
          ? `${report.reporter.firstName} ${report.reporter.lastName} · ${report.target.firstName} ${report.target.lastName}`
          : undefined
      }
    >
      <p className="mb-3 flex items-start gap-2 rounded-[var(--radius-control)] bg-[var(--ocre-soft)] px-3 py-2 text-[12px] leading-relaxed text-[var(--ocre-ink)]">
        <Eye className="mt-0.5 size-4 shrink-0" aria-hidden />
        Accès journalisé : cette consultation d'une messagerie privée est inscrite au journal d'audit, avec votre identifiant.
      </p>
      {conversations.isPending ? (
        <div className="space-y-2">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-14 rounded-[var(--radius-control)]" />
          ))}
        </div>
      ) : conversations.isError ? (
        <ErrorState description={describeError(conversations.error)} onRetry={() => conversations.refetch()} />
      ) : conversations.data.length === 0 ? (
        <EmptyState
          icon={MessagesSquare}
          title="Aucun échange"
          description="Aucune conversation n'est rattachée à ce signalement."
        />
      ) : (
        <div className="space-y-5">
          {conversations.data.map((conversation) => (
            <ConversationThread key={conversation.conversationId} conversation={conversation} />
          ))}
        </div>
      )}
    </Sheet>
  )
}

function ConversationThread({ conversation }: { conversation: AdminReportConversationResponse }) {
  const names = new Map(conversation.participants.map((p) => [p.id, `${p.firstName} ${p.lastName}`]))
  return (
    <section aria-label={`Conversation ${conversation.conversationId.slice(0, 8)}`}>
      <p className="mb-2 text-caption font-semibold uppercase tracking-[0.08em] text-muted">
        {conversation.participants.map((p) => `${p.firstName} ${p.lastName}`).join(' · ') || 'Participants inconnus'}
        {conversation.tripId ? (
          <>
            {' · '}
            <Link to={`/trips/${conversation.tripId}`} className="normal-case tracking-normal underline-offset-4 hover:underline">
              trajet
            </Link>
          </>
        ) : null}
      </p>
      {conversation.messages.length === 0 ? (
        <p className="text-[13px] text-muted">Aucun message.</p>
      ) : (
        <ol className="space-y-2">
          {conversation.messages.map((message) => (
            <li key={message.id} className="rounded-[var(--radius-control)] border border-rule bg-surface px-3 py-2">
              <p className="flex items-baseline justify-between gap-2 text-[12px]">
                <span className="font-semibold text-ink">{names.get(message.senderId) ?? message.senderId.slice(0, 8)}</span>
                <span className="tnum text-muted">{formatDateTime(message.createdAt)}</span>
              </p>
              <p className="mt-1 whitespace-pre-wrap text-[13px] leading-relaxed text-ink-2">{message.body}</p>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
