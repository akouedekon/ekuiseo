import { Ban, Contact, Eraser, MoreHorizontal, RotateCcw, Search, ShieldCheck, ShieldOff, Star, UserX } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { AdminPageHeader } from '@/components/layout/AdminPageHeader'
import { DataTable, type DataTableColumn } from '@/components/tables/DataTable'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { Avatar } from '@/components/ui/misc'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { ContactCorrectionDialog } from '@/features/admin/ContactCorrectionDialog'
import { SuspendUserDialog, type SuspensionTarget } from '@/features/admin/SuspendUserDialog'
import { UserMotivatedActionDialog, type UserMotivatedAction } from '@/features/admin/UserMotivatedActionDialog'
import { useAdminUsers } from '@/hooks/useAdmin'
import { useMe } from '@/hooks/useAuth'
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
          <Link to={`/admin/users/${user.id}`} className="block truncate font-semibold text-ink underline-offset-4 hover:underline">
            {user.firstName} {user.lastName}
            {user.role === 'ADMIN' ? (
              <Badge tone="indigo" className="ml-2 align-middle">
                Admin
              </Badge>
            ) : null}
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
    sortValue: (user) => (user.anonymizedAt ? 3 : user.suspended ? 2 : user.identityVerified ? 0 : 1),
    cell: (user) =>
      user.anonymizedAt ? (
        <Badge tone="neutral">
          <Eraser aria-hidden />
          Anonymisé
        </Badge>
      ) : user.suspended ? (
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
          <Star className="size-3.5 fill-accent text-accent-ink" aria-hidden />
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

/**
 * Liste des utilisateurs. La recherche vit dans l'URL (?q=) : un lien vers une
 * recherche se partage et survit au retour arriere depuis une fiche.
 */
export function AdminUsers() {
  const [searchParams, setSearchParams] = useSearchParams()
  const query = searchParams.get('q')?.trim() ?? ''
  const [input, setInput] = useState(query)
  const debounce = useRef<number | undefined>(undefined)
  const [suspension, setSuspension] = useState<SuspensionTarget | null>(null)
  const [contactTarget, setContactTarget] = useState<AdminUserResponse | null>(null)
  /* Actions motivees du menu « Plus » : anonymisation (irreversible) et retrait du badge d'identite. */
  const [action, setAction] = useState<UserMotivatedAction | null>(null)
  const users = useAdminUsers(query)
  const me = useMe()

  // Anti-rebond dans le gestionnaire (pas d'effet) : l'URL n'est ecrite qu'apres un court silence de saisie.
  const onInputChange = (value: string) => {
    setInput(value)
    window.clearTimeout(debounce.current)
    debounce.current = window.setTimeout(() => {
      const trimmed = value.trim()
      setSearchParams(trimmed ? { q: trimmed } : {}, { replace: true })
    }, SEARCH_DEBOUNCE_MS)
  }
  useEffect(() => () => window.clearTimeout(debounce.current), [])

  const list = users.data ?? []

  /** Un administrateur ne se suspend pas lui-meme, ni un autre administrateur : cela se regle en base, pas depuis l'interface. */
  const canSuspend = (user: AdminUserResponse) => user.role !== 'ADMIN' && user.id !== me.data?.id

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
        onChange={(event) => onInputChange(event.target.value)}
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
          rowAccent={(user) => (user.suspended ? 'var(--danger)' : undefined)}
          empty={
            <EmptyState
              icon={UserX}
              title="Aucun utilisateur"
              description={query ? `Aucun résultat pour « ${query} ».` : 'Aucun compte enregistré pour le moment.'}
            />
          }
          rowActions={(user) =>
            user.anonymizedAt ? (
              <span className="text-label text-muted">Aucune action</span>
            ) : (
              <span className="flex flex-wrap justify-end gap-1">
                <Button size="sm" variant="ghost" onClick={() => setContactTarget(user)}>
                  <Contact className="size-4" aria-hidden />
                  Contact
                </Button>
                {canSuspend(user) ? (
                  <Button
                    size="sm"
                    variant={user.suspended ? 'secondary' : 'ghost'}
                    className={user.suspended ? undefined : 'text-danger-ink'}
                    onClick={() => setSuspension(user)}
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
                ) : null}
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button size="sm" variant="ghost" aria-label={`Plus d'actions pour ${user.firstName} ${user.lastName}`}>
                      <MoreHorizontal className="size-4" aria-hidden />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end" className="min-w-56">
                    {user.identityVerified ? (
                      <DropdownMenuItem onSelect={() => setAction({ kind: 'revoke-identity', user })}>
                        <ShieldOff aria-hidden />
                        Retirer le badge d'identité
                      </DropdownMenuItem>
                    ) : null}
                    <DropdownMenuItem tone="danger" onSelect={() => setAction({ kind: 'anonymize', user })}>
                      <Eraser aria-hidden />
                      Anonymiser le compte
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </span>
            )
          }
        />
      )}

      <ContactCorrectionDialog user={contactTarget} onOpenChange={(open) => !open && setContactTarget(null)} />
      <SuspendUserDialog target={suspension} onOpenChange={(open) => !open && setSuspension(null)} />
      <UserMotivatedActionDialog action={action} onOpenChange={(open) => !open && setAction(null)} />
    </div>
  )
}
