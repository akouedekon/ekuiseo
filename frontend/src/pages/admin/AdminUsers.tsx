import { Ban, RotateCcw, Search, ShieldCheck, Star, UserX } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { AdminPageHeader } from '@/components/layout/AdminPageHeader'
import { DataTable, type DataTableColumn } from '@/components/tables/DataTable'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input, Textarea } from '@/components/ui/input'
import { Avatar } from '@/components/ui/misc'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { useAdminUsers, useToggleUserSuspension } from '@/hooks/useAdmin'
import { describeError } from '@/lib/errors'
import { formatDayShort, formatPhone } from '@/lib/format'
import type { AdminUserResponse } from '@/api/extended'

const SEARCH_DEBOUNCE_MS = 350

const COLUMNS: DataTableColumn<AdminUserResponse>[] = [
  {
    id: 'name',
    header: 'Utilisateur',
    mobile: 'title',
    sortValue: (user) => `${user.lastName} ${user.firstName}`,
    className: 'min-w-[220px]',
    cell: (user) => (
      <span className="flex items-center gap-3">
        <Avatar firstName={user.firstName} lastName={user.lastName} size={36} className="hidden lg:inline-flex" />
        <span className="min-w-0">
          <Link to={`/drivers/${user.id}`} className="block truncate font-semibold text-ink underline-offset-4 hover:underline">
            {user.firstName} {user.lastName}
          </Link>
          <span className="tnum block truncate text-label text-muted">
            {formatPhone(user.phone)}
            {user.email ? <span className="hidden 2xl:inline"> · {user.email}</span> : null}
          </span>
        </span>
      </span>
    ),
  },
  {
    id: 'status',
    header: 'Statut',
    mobile: 'badge',
    sortValue: (user) => (user.suspended ? 2 : user.identityVerified ? 0 : 1),
    cell: (user) =>
      user.suspended ? (
        <Badge tone="danger">
          <Ban aria-hidden />
          Suspendu
        </Badge>
      ) : user.identityVerified ? (
        <Badge tone="success">
          <ShieldCheck aria-hidden />
          Vérifié
        </Badge>
      ) : (
        <Badge tone="neutral">Actif</Badge>
      ),
  },
  {
    id: 'trips',
    header: 'Trajets',
    align: 'right',
    mobile: 'value',
    sortValue: (user) => user.tripsPublished,
    cell: (user) => user.tripsPublished.toLocaleString('fr-FR'),
  },
  {
    id: 'bookings',
    header: 'Résas',
    align: 'right',
    mobile: 'value',
    className: 'hidden xl:table-cell',
    sortValue: (user) => user.bookingsMade,
    cell: (user) => user.bookingsMade.toLocaleString('fr-FR'),
  },
  {
    id: 'rating',
    header: 'Note',
    align: 'right',
    mobile: 'value',
    sortValue: (user) => (user.ratingAvg > 0 ? user.ratingAvg : null),
    cell: (user) =>
      user.ratingAvg > 0 ? (
        <span className="inline-flex items-center gap-1 font-semibold">
          <Star className="size-3.5 fill-[var(--ocre)] text-[var(--ocre)]" aria-hidden />
          {user.ratingAvg.toFixed(1).replace('.', ',')}
        </span>
      ) : (
        <span className="text-muted">—</span>
      ),
  },
  {
    id: 'createdAt',
    header: 'Inscrit le',
    align: 'right',
    mobile: 'value',
    className: 'hidden xl:table-cell',
    sortValue: (user) => user.createdAt,
    cell: (user) => <span className="text-ink-2">{formatDayShort(user.createdAt)}</span>,
  },
]

export function AdminUsers() {
  const [input, setInput] = useState('')
  const [query, setQuery] = useState('')
  const [target, setTarget] = useState<AdminUserResponse | null>(null)
  const [reason, setReason] = useState('')
  const users = useAdminUsers(query)
  const toggle = useToggleUserSuspension()

  // Anti-rebond : on n'interroge l'API qu'apres un court silence de saisie.
  useEffect(() => {
    const id = window.setTimeout(() => setQuery(input.trim()), SEARCH_DEBOUNCE_MS)
    return () => window.clearTimeout(id)
  }, [input])

  const list = users.data ?? []

  const close = () => {
    setTarget(null)
    setReason('')
  }

  const confirmToggle = () => {
    if (!target) return
    const user = target
    const suspend = !user.suspended
    if (suspend && !reason.trim()) return
    toggle.mutate(
      { id: user.id, suspend, reason: suspend ? reason.trim() : undefined },
      {
        onSuccess: () => {
          toast.success(
            suspend
              ? `${user.firstName} ${user.lastName} a été suspendu`
              : `${user.firstName} ${user.lastName} a été réactivé`,
          )
          close()
        },
        onError: (error) => toast.error(describeError(error, "L'action n'a pas abouti. Réessayez.")),
      },
    )
  }

  return (
    <div>
      <AdminPageHeader
        title="Utilisateurs"
        count={users.isSuccess ? list.length : undefined}
        description="Recherche par nom, numéro ou e-mail. Une suspension bloque la connexion et les réservations ; elle est motivée et journalisée."
      />

      <Input
        label="Rechercher"
        placeholder="Nom, numéro ou e-mail"
        value={input}
        onChange={(event) => setInput(event.target.value)}
        leading={<Search />}
        className="mb-4"
        aria-describedby={undefined}
      />

      {users.isError ? (
        <ErrorState description={describeError(users.error)} onRetry={() => users.refetch()} />
      ) : (
        <DataTable
          caption="Liste des utilisateurs"
          columns={COLUMNS}
          rows={list}
          rowKey={(user) => user.id}
          loading={users.isPending}
          initialSort={{ id: 'createdAt', direction: 'desc' }}
          rowAccent={(user) => (user.suspended ? 'var(--vermillon)' : undefined)}
          empty={
            <EmptyState
              icon={UserX}
              title="Aucun utilisateur"
              description={query ? `Aucun résultat pour « ${query} ».` : 'Aucun compte enregistré pour le moment.'}
            />
          }
          rowActions={(user) => (
            <Button
              size="sm"
              variant={user.suspended ? 'secondary' : 'ghost'}
              className={user.suspended ? undefined : 'text-[var(--vermillon)]'}
              onClick={() => setTarget(user)}
            >
              {user.suspended ? (
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
          )}
        />
      )}

      <ConfirmDialog
        open={target !== null}
        onOpenChange={(open) => !open && close()}
        title={target?.suspended ? 'Réactiver ce compte ?' : 'Suspendre ce compte ?'}
        description={
          target
            ? target.suspended
              ? `${target.firstName} ${target.lastName} pourra de nouveau se connecter, réserver et publier.`
              : `${target.firstName} ${target.lastName} ne pourra plus se connecter ni réserver. Ses trajets à venir restent visibles jusqu'à leur annulation manuelle.`
            : undefined
        }
        tone={target?.suspended ? 'default' : 'danger'}
        confirmLabel={target?.suspended ? 'Réactiver' : 'Suspendre'}
        confirmDisabled={target ? !target.suspended && !reason.trim() : true}
        loading={toggle.isPending}
        onConfirm={confirmToggle}
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
    </div>
  )
}
