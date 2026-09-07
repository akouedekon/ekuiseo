import {
  ArrowLeft,
  Ban,
  Car,
  CheckCircle2,
  Contact,
  Eraser,
  MoreHorizontal,
  RotateCcw,
  ShieldCheck,
  ShieldOff,
  Smartphone,
  Star,
  UserX,
} from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { AdminPageHeader } from '@/components/layout/AdminPageHeader'
import { DataTable, type DataTableColumn } from '@/components/tables/DataTable'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Avatar, Skeleton } from '@/components/ui/misc'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { AdminPagination } from '@/features/admin/AdminPagination'
import { AuditTable } from '@/features/admin/AuditTable'
import { ContactCorrectionDialog } from '@/features/admin/ContactCorrectionDialog'
import { SuspendUserDialog, type SuspensionTarget } from '@/features/admin/SuspendUserDialog'
import { UserMotivatedActionDialog, type UserMotivatedAction } from '@/features/admin/UserMotivatedActionDialog'
import {
  useAdminUserBookings,
  useAdminUserDetail,
  useAdminUserPayments,
  useAdminUserTrips,
  useAuditLog,
  useVerifyVehicle,
} from '@/hooks/useAdmin'
import { useMe } from '@/hooks/useAuth'
import { describeError } from '@/lib/errors'
import { formatDateTime, formatDayShort, formatFcfa, formatPhone } from '@/lib/format'
import { COMFORT_LABEL, documentLabel } from '@/lib/labels'
import { providerLabel } from '@/lib/payments'
import type {
  AdminPaymentResponse,
  AdminUserBookingResponse,
  AdminUserDetailResponse,
  AdminUserResponse,
  AdminUserVehicle,
  IdentityVerificationStatus,
  PaymentRecordStatus,
} from '@/api/extended'
import type { TripResponse, TripStatus } from '@/api/types'

const PAGE_SIZE = 10

/* Libelles tolerants (Record<string, …>) : une valeur inconnue s'affiche telle quelle plutot que de casser l'ecran. */
const USER_STATUS_LABEL: Record<string, { label: string; tone: 'success' | 'danger' | 'warning' | 'neutral' }> = {
  ACTIVE: { label: 'Actif', tone: 'success' },
  SUSPENDED: { label: 'Suspendu', tone: 'danger' },
  PENDING_VERIFICATION: { label: 'Jamais connecté', tone: 'warning' },
  DELETED: { label: 'Anonymisé', tone: 'neutral' },
}

const IDENTITY_LABEL: Record<IdentityVerificationStatus, { label: string; tone: 'success' | 'danger' | 'warning' | 'neutral' }> = {
  NOT_SUBMITTED: { label: 'Non déposée', tone: 'neutral' },
  PENDING: { label: 'En attente', tone: 'warning' },
  APPROVED: { label: 'Validée', tone: 'success' },
  REJECTED: { label: 'Refusée', tone: 'danger' },
}

const BOOKING_STATUS_LABEL: Record<string, { label: string; tone: 'success' | 'danger' | 'warning' | 'neutral' | 'indigo' }> = {
  PENDING_PAYMENT: { label: 'Acompte attendu', tone: 'warning' },
  PENDING_DRIVER_APPROVAL: { label: 'Accord conducteur attendu', tone: 'warning' },
  CONFIRMED: { label: 'Confirmée', tone: 'success' },
  COMPLETED: { label: 'Terminée', tone: 'indigo' },
  CANCELLED_BY_PASSENGER: { label: 'Annulée (passager)', tone: 'danger' },
  CANCELLED_BY_DRIVER: { label: 'Annulée (conducteur)', tone: 'danger' },
  NO_SHOW: { label: 'Non présenté', tone: 'danger' },
  EXPIRED: { label: 'Expirée', tone: 'neutral' },
}

const PAYMENT_METHOD_LABEL: Record<string, string> = {
  MOMO_DEPOSIT: 'Acompte MoMo',
  MOMO_FULL: 'MoMo intégral',
  CASH: 'Espèces',
}

const TRIP_STATUS_LABEL: Record<TripStatus, { label: string; tone: 'success' | 'danger' | 'warning' | 'neutral' | 'indigo' }> = {
  DRAFT: { label: 'Brouillon', tone: 'neutral' },
  TEMPLATE: { label: 'Navette (modèle)', tone: 'indigo' },
  PUBLISHED: { label: 'Publié', tone: 'success' },
  FULL: { label: 'Complet', tone: 'warning' },
  ONGOING: { label: 'En route', tone: 'indigo' },
  COMPLETED: { label: 'Terminé', tone: 'neutral' },
  CANCELLED: { label: 'Annulé', tone: 'danger' },
}

const PAYMENT_STATUS_LABEL: Record<PaymentRecordStatus, { label: string; tone: 'success' | 'danger' | 'warning' | 'neutral' | 'indigo' }> = {
  INITIATED: { label: 'Initié', tone: 'neutral' },
  SUCCEEDED: { label: 'Encaissé', tone: 'success' },
  FAILED: { label: 'Échec', tone: 'neutral' },
  REFUND_PENDING: { label: 'Remboursement en cours', tone: 'warning' },
  REFUND_MANUAL: { label: 'À traiter à la main', tone: 'danger' },
  REFUNDED: { label: 'Remboursé', tone: 'success' },
}

const DASH = <span className="text-muted">—</span>

/* Aucune colonne triable : listes paginees cote serveur. */
const BOOKING_COLUMNS: DataTableColumn<AdminUserBookingResponse>[] = [
  {
    id: 'trip',
    header: 'Trajet',
    mobile: 'title',
    cell: (b) =>
      b.trip?.originLabel || b.trip?.destLabel ? (
        <span className="block min-w-0">
          <span className="block truncate font-semibold text-ink">
            {b.tripId ? (
              <Link to={`/trips/${b.tripId}`} className="underline-offset-4 hover:underline">
                {b.trip.originLabel ?? '?'} → {b.trip.destLabel ?? '?'}
              </Link>
            ) : (
              `${b.trip.originLabel ?? '?'} → ${b.trip.destLabel ?? '?'}`
            )}
          </span>
          <span className="tnum block text-label text-muted">{b.trip.departureAt ? formatDateTime(b.trip.departureAt) : '—'}</span>
        </span>
      ) : (
        DASH
      ),
  },
  { id: 'seats', header: 'Places', align: 'right', mobile: 'value', cell: (b) => (b.seats == null ? DASH : b.seats) },
  {
    id: 'amount',
    header: 'Montant',
    align: 'right',
    mobile: 'value',
    cell: (b) => (b.amount == null ? DASH : <span className="font-semibold">{formatFcfa(b.amount)}</span>),
  },
  {
    id: 'method',
    header: 'Paiement',
    mobile: 'meta',
    className: 'hidden xl:table-cell',
    cell: (b) => (b.paymentMethod ? PAYMENT_METHOD_LABEL[b.paymentMethod] ?? b.paymentMethod : DASH),
  },
  {
    id: 'status',
    header: 'Statut',
    mobile: 'badge',
    cell: (b) =>
      b.status ? <Badge tone={BOOKING_STATUS_LABEL[b.status]?.tone ?? 'neutral'}>{BOOKING_STATUS_LABEL[b.status]?.label ?? b.status}</Badge> : DASH,
  },
  {
    id: 'createdAt',
    header: 'Réservé le',
    align: 'right',
    mobile: 'value',
    className: 'hidden xl:table-cell',
    cell: (b) => (b.createdAt ? <span className="text-ink-2">{formatDayShort(b.createdAt)}</span> : DASH),
  },
]

const TRIP_COLUMNS: DataTableColumn<TripResponse>[] = [
  {
    id: 'route',
    header: 'Trajet',
    mobile: 'title',
    cell: (t) => (
      <span className="block min-w-0">
        <Link to={`/trips/${t.id}`} className="block truncate font-semibold text-ink underline-offset-4 hover:underline">
          {t.originLabel} → {t.destLabel}
        </Link>
        <span className="tnum block text-label text-muted">{formatDateTime(t.departureAt)}</span>
      </span>
    ),
  },
  {
    id: 'type',
    header: 'Mode',
    mobile: 'meta',
    className: 'hidden xl:table-cell',
    cell: (t) => (t.tripType === 'QUOTIDIEN' ? 'Quotidien' : 'Interurbain'),
  },
  {
    id: 'seats',
    header: 'Places',
    align: 'right',
    mobile: 'value',
    cell: (t) => (
      <span className="tnum">
        {t.seatsTotal - t.seatsAvailable} / {t.seatsTotal}
      </span>
    ),
  },
  { id: 'price', header: 'Prix / place', align: 'right', mobile: 'value', cell: (t) => <span className="font-semibold">{formatFcfa(t.pricePerSeat)}</span> },
  {
    id: 'status',
    header: 'Statut',
    mobile: 'badge',
    cell: (t) => <Badge tone={TRIP_STATUS_LABEL[t.status]?.tone ?? 'neutral'}>{TRIP_STATUS_LABEL[t.status]?.label ?? t.status}</Badge>,
  },
]

const PAYMENT_COLUMNS: DataTableColumn<AdminPaymentResponse>[] = [
  {
    id: 'ref',
    header: 'Référence',
    mobile: 'title',
    cell: (p) => (
      <span className="block min-w-0">
        <span className="tnum block truncate font-semibold text-ink" title={p.providerTxId}>
          {p.providerTxId || '—'}
        </span>
        <span className="block text-label text-muted">{p.bookingId ? 'Réservation' : p.subscriptionId ? 'Abonnement' : '—'}</span>
      </span>
    ),
  },
  { id: 'amount', header: 'Montant', align: 'right', mobile: 'value', cell: (p) => <span className="font-semibold">{formatFcfa(p.amount)}</span> },
  {
    id: 'refund',
    header: 'Remboursé',
    align: 'right',
    mobile: 'value',
    className: 'hidden xl:table-cell',
    cell: (p) => (p.refundAmount == null ? DASH : formatFcfa(p.refundAmount)),
  },
  {
    id: 'status',
    header: 'Statut',
    mobile: 'badge',
    cell: (p) => <Badge tone={PAYMENT_STATUS_LABEL[p.status]?.tone ?? 'neutral'}>{PAYMENT_STATUS_LABEL[p.status]?.label ?? p.status}</Badge>,
  },
  {
    id: 'createdAt',
    header: 'Date',
    align: 'right',
    mobile: 'value',
    cell: (p) => <span className="tnum text-ink-2">{formatDateTime(p.createdAt)}</span>,
  },
]

/** Ce que les dialogs partages attendent d'un utilisateur ; la fiche en a davantage. */
function toListShape(user: AdminUserDetailResponse): AdminUserResponse {
  return {
    id: user.id,
    firstName: user.firstName,
    lastName: user.lastName,
    phone: user.phone,
    email: user.email,
    createdAt: user.createdAt,
    identityVerified: user.identityVerified,
    phoneVerified: true,
    suspended: user.status === 'SUSPENDED',
    anonymizedAt: user.anonymizedAt,
    tripsPublished: user.tripsPublished,
    bookingsMade: user.bookingsMade,
    ratingAvg: user.ratingAvg,
    role: user.role,
  }
}

/**
 * Fiche utilisateur du back-office (/admin/users/:id) : tout ce qu'il faut pour
 * instruire un dossier sans changer de page - statut, identite, vehicules,
 * comptes mobile money, historiques pagines, et ce que le journal dit de lui.
 */
export function AdminUserDetail() {
  const { id } = useParams<{ id: string }>()
  const user = useAdminUserDetail(id)
  const me = useMe()
  const [suspension, setSuspension] = useState<SuspensionTarget | null>(null)
  const [contactTarget, setContactTarget] = useState<AdminUserResponse | null>(null)
  const [action, setAction] = useState<UserMotivatedAction | null>(null)

  if (user.isError) {
    return (
      <div>
        <BackLink />
        <ErrorState description={describeError(user.error)} onRetry={() => user.refetch()} />
      </div>
    )
  }

  if (user.isPending || !user.data) {
    return (
      <div>
        <BackLink />
        <Skeleton className="h-16 w-2/3 rounded-[var(--radius-card)]" />
        <div className="mt-4 grid gap-4 lg:grid-cols-3">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-48 rounded-[var(--radius-card)]" />
          ))}
        </div>
      </div>
    )
  }

  const data = user.data
  const name = `${data.firstName} ${data.lastName}`
  const suspended = data.status === 'SUSPENDED'
  const anonymized = data.anonymizedAt != null || data.status === 'DELETED'
  const status = USER_STATUS_LABEL[data.status] ?? { label: data.status, tone: 'neutral' as const }
  /** Un administrateur ne se suspend pas lui-meme, ni un autre administrateur. */
  const canSuspend = data.role !== 'ADMIN' && data.id !== me.data?.id

  return (
    <div>
      <BackLink />
      <AdminPageHeader
        metaTitle={name}
        title={
          <span className="flex items-center gap-3">
            <Avatar firstName={data.firstName} lastName={data.lastName} size={40} />
            <span className="flex flex-wrap items-center gap-2">
              {name}
              <Badge tone={status.tone}>{status.label}</Badge>
              {data.role === 'ADMIN' ? <Badge tone="indigo">Admin</Badge> : null}
              {data.identityVerified ? (
                <Badge tone="success">
                  <ShieldCheck aria-hidden />
                  Identité vérifiée
                </Badge>
              ) : null}
            </span>
          </span>
        }
        description={
          <span className="tnum">
            {formatPhone(data.phone)}
            {data.email ? ` · ${data.email}` : ''} · inscrit le {formatDayShort(data.createdAt)}
          </span>
        }
        actions={
          anonymized ? (
            <span className="text-label text-muted">Compte anonymisé : aucune action possible</span>
          ) : (
            <>
              <Button size="sm" variant="secondary" onClick={() => setContactTarget(toListShape(data))}>
                <Contact className="size-4" aria-hidden />
                Corriger le contact
              </Button>
              {canSuspend ? (
                <Button
                  size="sm"
                  variant={suspended ? 'secondary' : 'danger'}
                  onClick={() => setSuspension({ id: data.id, firstName: data.firstName, lastName: data.lastName, suspended })}
                >
                  {suspended ? (
                    <>
                      <RotateCcw className="size-4" aria-hidden />
                      Réactiver
                    </>
                  ) : (
                    <>
                      <Ban className="size-4" aria-hidden />
                      Suspendre
                    </>
                  )}
                </Button>
              ) : null}
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button size="sm" variant="ghost" aria-label={`Plus d'actions pour ${name}`}>
                    <MoreHorizontal className="size-4" aria-hidden />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="min-w-56">
                  {data.identityVerified ? (
                    <DropdownMenuItem onSelect={() => setAction({ kind: 'revoke-identity', user: data })}>
                      <ShieldOff aria-hidden />
                      Retirer le badge d'identité
                    </DropdownMenuItem>
                  ) : null}
                  <DropdownMenuItem tone="danger" onSelect={() => setAction({ kind: 'anonymize', user: data })}>
                    <Eraser aria-hidden />
                    Anonymiser le compte
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </>
          )
        }
      />

      {suspended && (data.suspendedReason || data.suspendedAt) ? (
        <Card className="mb-4 border-danger bg-danger-soft px-4 py-3 text-label leading-relaxed text-danger-ink">
          <span className="font-semibold">Suspendu{data.suspendedAt ? ` le ${formatDateTime(data.suspendedAt)}` : ''}.</span>{' '}
          {data.suspendedReason ? `Motif : ${data.suspendedReason}` : 'Motif non renseigné.'}
        </Card>
      ) : null}

      <div className="grid gap-4 lg:grid-cols-3">
        <ProfileCard user={data} />
        <VehiclesCard user={data} />
        <PaymentAccountsCard user={data} />
      </div>

      <Tabs defaultValue="bookings" className="mt-6">
        <TabsList className="w-full sm:w-auto">
          <TabsTrigger value="bookings">Réservations</TabsTrigger>
          <TabsTrigger value="trips">Trajets</TabsTrigger>
          <TabsTrigger value="payments">Paiements</TabsTrigger>
          <TabsTrigger value="audit">Audit</TabsTrigger>
        </TabsList>
        <TabsContent value="bookings">
          <BookingsTab userId={data.id} />
        </TabsContent>
        <TabsContent value="trips">
          <TripsTab userId={data.id} />
        </TabsContent>
        <TabsContent value="payments">
          <PaymentsTab userId={data.id} />
        </TabsContent>
        <TabsContent value="audit">
          <AuditTab userId={data.id} />
        </TabsContent>
      </Tabs>

      <ContactCorrectionDialog user={contactTarget} onOpenChange={(open) => !open && setContactTarget(null)} />
      <SuspendUserDialog target={suspension} onOpenChange={(open) => !open && setSuspension(null)} />
      <UserMotivatedActionDialog action={action} onOpenChange={(open) => !open && setAction(null)} />
    </div>
  )
}

function BackLink() {
  return (
    <Link
      to="/admin/users"
      className="mb-3 inline-flex items-center gap-1 text-label font-medium text-ink-2 underline-offset-4 hover:text-ink hover:underline"
    >
      <ArrowLeft className="size-4" aria-hidden />
      Utilisateurs
    </Link>
  )
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-3 text-label">
      <dt className="shrink-0 text-muted">{label}</dt>
      <dd className="tnum min-w-0 text-right font-medium text-ink">{children}</dd>
    </div>
  )
}

function CardTitle({ children, count }: { children: ReactNode; count?: number }) {
  return (
    <h3 className="mb-3 flex items-center gap-2 font-display text-base font-bold">
      {children}
      {count !== undefined ? (
        <span className="tnum rounded-full bg-surface-2 px-2 py-0.5 text-caption font-semibold text-ink-2">{count}</span>
      ) : null}
    </h3>
  )
}

function ProfileCard({ user }: { user: AdminUserDetailResponse }) {
  const identity = user.identity
  const identityStatus = identity ? IDENTITY_LABEL[identity.status] : IDENTITY_LABEL.NOT_SUBMITTED
  return (
    <Card className="p-4">
      <CardTitle>Compte</CardTitle>
      <dl className="space-y-1.5">
        <Fact label="Identité">
          <Badge tone={identityStatus.tone}>{identityStatus.label}</Badge>
        </Fact>
        {identity?.documentType ? (
          <Fact label="Pièce">
            {documentLabel(identity.documentType)}
            {identity.documentLast4 ? <span className="text-muted"> · …{identity.documentLast4}</span> : null}
          </Fact>
        ) : null}
        {/* Contrat phase 3 : e-mail confirme et derniere connexion, optionnels tant que le backend ne les envoie pas. */}
        {user.emailVerified !== undefined ? (
          <Fact label="E-mail">
            <Badge tone={user.emailVerified ? 'success' : 'warning'}>{user.emailVerified ? 'Confirmé' : 'Non confirmé'}</Badge>
          </Fact>
        ) : null}
        {user.lastLoginAt !== undefined ? (
          <Fact label="Dernière connexion">{user.lastLoginAt ? formatDateTime(user.lastLoginAt) : 'Jamais'}</Fact>
        ) : null}
        <Fact label="Trajets publiés">{user.tripsPublished.toLocaleString('fr-FR')}</Fact>
        <Fact label="Réservations">{user.bookingsMade.toLocaleString('fr-FR')}</Fact>
        <Fact label="Note">
          {user.ratingAvg > 0 ? (
            <span className="inline-flex items-center gap-1">
              <Star className="size-3.5 fill-accent text-accent-ink" aria-hidden />
              {user.ratingAvg.toFixed(1).replace('.', ',')}
            </span>
          ) : (
            DASH
          )}
        </Fact>
        <Fact label="Annulations tardives">
          <span className={user.lateCancellationsCount > 0 ? 'text-danger-ink' : undefined}>
            {user.lateCancellationsCount.toLocaleString('fr-FR')}
          </span>
        </Fact>
        {user.anonymizedAt ? <Fact label="Anonymisé le">{formatDateTime(user.anonymizedAt)}</Fact> : null}
      </dl>
      {user.lateCancellationsCount >= 3 ? (
        <p className="mt-3 text-caption leading-relaxed text-muted">
          Trois annulations à moins de 24 h du départ ou plus : un passager qui fait perdre des places aux conducteurs.
        </p>
      ) : null}
    </Card>
  )
}

function VehiclesCard({ user }: { user: AdminUserDetailResponse }) {
  const verify = useVerifyVehicle()
  const [target, setTarget] = useState<AdminUserVehicle | null>(null)

  const confirm = () => {
    if (!target) return
    const vehicle = target
    verify.mutate(vehicle.id, {
      onSuccess: () => {
        toast.success('Véhicule validé', { description: `${vehicle.brand} ${vehicle.model} · ${vehicle.plate}` })
        setTarget(null)
      },
      onError: (error) => toast.error(describeError(error, "La validation n'a pas abouti.")),
    })
  }

  return (
    <Card className="p-4">
      <CardTitle count={user.vehicles.length}>Véhicules</CardTitle>
      {user.vehicles.length === 0 ? (
        <p className="text-label text-muted">Aucun véhicule enregistré : ce compte ne publie pas de trajet.</p>
      ) : (
        <ul className="space-y-2">
          {user.vehicles.map((vehicle) => (
            <li key={vehicle.id} className="flex items-start gap-3 rounded-[var(--radius-control)] border border-rule px-3 py-2">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-surface-2 text-ink-2">
                <Car className="size-4" aria-hidden />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-label font-semibold text-ink">
                  {vehicle.brand} {vehicle.model}
                  {vehicle.color ? <span className="font-normal text-muted"> · {vehicle.color}</span> : null}
                </span>
                <span className="tnum block text-caption text-muted">
                  {vehicle.plate} · {vehicle.seats} places · {COMFORT_LABEL[vehicle.comfortLevel]}
                </span>
                <span className="mt-1 block">
                  {vehicle.verified ? (
                    <Badge tone="success">
                      <CheckCircle2 aria-hidden />
                      Validé
                    </Badge>
                  ) : (
                    <Button size="sm" variant="secondary" className="h-8" onClick={() => setTarget(vehicle)}>
                      Valider le véhicule
                    </Button>
                  )}
                </span>
              </span>
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog
        open={target !== null}
        onOpenChange={(open) => !open && setTarget(null)}
        title="Valider ce véhicule ?"
        description={
          target
            ? `${target.brand} ${target.model}, ${target.plate}. Ne validez qu'après contrôle de la carte grise et de la plaque par un autre canal : la validation est journalisée et visible des passagers.`
            : undefined
        }
        confirmLabel="Oui, validé"
        loading={verify.isPending}
        onConfirm={confirm}
      />
    </Card>
  )
}

function PaymentAccountsCard({ user }: { user: AdminUserDetailResponse }) {
  return (
    <Card className="p-4">
      <CardTitle count={user.paymentAccounts.length}>Comptes mobile money</CardTitle>
      {user.paymentAccounts.length === 0 ? (
        <p className="text-label text-muted">Aucun compte : ce conducteur ne peut recevoir aucun reversement.</p>
      ) : (
        <ul className="space-y-2">
          {user.paymentAccounts.map((account) => (
            <li key={account.id} className="flex items-start gap-3 rounded-[var(--radius-control)] border border-rule px-3 py-2">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-surface-2 text-ink-2">
                <Smartphone className="size-4" aria-hidden />
              </span>
              <span className="min-w-0 flex-1">
                <span className="tnum block text-label font-semibold text-ink">
                  {providerLabel(account.provider)} {formatPhone(account.phone)}
                </span>
                <span className="mt-1 flex flex-wrap gap-1">
                  {account.verified ? <Badge tone="success">Vérifié</Badge> : <Badge tone="warning">Possession à vérifier</Badge>}
                  {account.isDefault ? <Badge tone="indigo">Par défaut</Badge> : null}
                </span>
              </span>
            </li>
          ))}
        </ul>
      )}
      {user.paymentAccounts.some((a) => !a.verified) ? (
        <p className="mt-3 text-caption leading-relaxed text-muted">
          Un compte non vérifié ne reçoit aucun reversement. L'attestation se fait depuis{' '}
          <Link to="/admin/payouts" className="font-medium text-ink underline-offset-4 hover:underline">
            Reversements
          </Link>
          .
        </p>
      ) : null}
    </Card>
  )
}

function BookingsTab({ userId }: { userId: string }) {
  const [page, setPage] = useState(0)
  const bookings = useAdminUserBookings(userId, page, PAGE_SIZE)
  if (bookings.isError) return <ErrorState description={describeError(bookings.error)} onRetry={() => bookings.refetch()} />
  return (
    <>
      <DataTable
        caption="Réservations de l'utilisateur"
        columns={BOOKING_COLUMNS}
        rows={bookings.data?.content ?? []}
        rowKey={(b) => b.id}
        loading={bookings.isPending}
        empty={<EmptyState icon={UserX} title="Aucune réservation" description="Cet utilisateur n'a encore rien réservé." />}
      />
      {bookings.data ? (
        <AdminPagination page={page} totalPages={bookings.data.totalPages} onPageChange={setPage} busy={bookings.isFetching} label="Pagination des réservations" />
      ) : null}
    </>
  )
}

function TripsTab({ userId }: { userId: string }) {
  const [page, setPage] = useState(0)
  const trips = useAdminUserTrips(userId, page, PAGE_SIZE)
  if (trips.isError) return <ErrorState description={describeError(trips.error)} onRetry={() => trips.refetch()} />
  return (
    <>
      <DataTable
        caption="Trajets publiés par l'utilisateur"
        columns={TRIP_COLUMNS}
        rows={trips.data?.content ?? []}
        rowKey={(t) => t.id}
        loading={trips.isPending}
        empty={<EmptyState icon={Car} title="Aucun trajet" description="Cet utilisateur n'a encore rien publié." />}
      />
      {trips.data ? (
        <AdminPagination page={page} totalPages={trips.data.totalPages} onPageChange={setPage} busy={trips.isFetching} label="Pagination des trajets" />
      ) : null}
    </>
  )
}

function PaymentsTab({ userId }: { userId: string }) {
  const [page, setPage] = useState(0)
  const payments = useAdminUserPayments(userId, page, PAGE_SIZE)
  if (payments.isError) return <ErrorState description={describeError(payments.error)} onRetry={() => payments.refetch()} />
  return (
    <>
      <DataTable
        caption="Paiements de l'utilisateur"
        columns={PAYMENT_COLUMNS}
        rows={payments.data?.content ?? []}
        rowKey={(p) => p.id}
        loading={payments.isPending}
        rowAccent={(p) => (p.status === 'REFUND_MANUAL' ? 'var(--danger)' : p.status === 'REFUND_PENDING' ? 'var(--accent)' : undefined)}
        empty={<EmptyState icon={Smartphone} title="Aucun paiement" description="Aucun paiement mobile money pour ce compte." />}
      />
      {payments.data ? (
        <AdminPagination page={page} totalPages={payments.data.totalPages} onPageChange={setPage} busy={payments.isFetching} label="Pagination des paiements" />
      ) : null}
    </>
  )
}

/**
 * Journal filtre sur l'utilisateur : ce qui lui a ete fait (entityId) par
 * defaut, ou ce qu'il a fait lui-meme (actorId) - utile pour un administrateur.
 */
function AuditTab({ userId }: { userId: string }) {
  const [scope, setScope] = useState<'entity' | 'actor'>('entity')
  const [page, setPage] = useState(0)
  const audit = useAuditLog(page, PAGE_SIZE, scope === 'entity' ? { entityId: userId } : { actorId: userId })
  return (
    <>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <Tabs
          value={scope}
          onValueChange={(value) => {
            setScope(value as 'entity' | 'actor')
            setPage(0)
          }}
        >
          <TabsList>
            <TabsTrigger value="entity">Le concernant</TabsTrigger>
            <TabsTrigger value="actor">Effectué par lui</TabsTrigger>
          </TabsList>
        </Tabs>
        <Link to={`/admin/audit?${scope === 'entity' ? 'entityId' : 'actorId'}=${userId}`} className="text-label font-medium text-ink-2 underline-offset-4 hover:underline">
          Ouvrir dans le journal
        </Link>
      </div>
      <AuditTable
        audit={audit}
        page={page}
        onPageChange={setPage}
        emptyDescription={scope === 'entity' ? 'Aucune action enregistrée sur ce compte.' : 'Aucune action enregistrée par ce compte.'}
      />
    </>
  )
}
