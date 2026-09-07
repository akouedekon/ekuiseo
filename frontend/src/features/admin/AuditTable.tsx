import type { UseQueryResult } from '@tanstack/react-query'
import { ScrollText } from 'lucide-react'
import { Link } from 'react-router'
import { DataTable, type DataTableColumn } from '@/components/tables/DataTable'
import { Badge } from '@/components/ui/badge'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { describeError } from '@/lib/errors'
import { formatDateTime } from '@/lib/format'
import type { AuditLogResponse } from '@/api/extended'
import type { Page } from '@/api/types'
import { AdminPagination } from './AdminPagination'

/** Lien vers l'objet vise quand une page existe pour lui ; sinon l'identifiant abrege. */
function entityLink(row: AuditLogResponse) {
  if (!row.entityType) return <span className="text-muted">—</span>
  const short = row.entityId ? row.entityId.slice(0, 8) : null
  const type = row.entityType.toUpperCase()
  const href = row.entityId ? (type === 'USER' ? `/admin/users/${row.entityId}` : type === 'TRIP' ? `/trips/${row.entityId}` : null) : null
  return (
    <span className="tnum text-label text-ink-2">
      {row.entityType}
      {short ? (
        href ? (
          <Link to={href} className="ml-1 text-primary-ink underline-offset-4 hover:underline">
            {short}
          </Link>
        ) : (
          <span className="text-muted"> · {short}</span>
        )
      ) : null}
    </span>
  )
}

/*
 * Aucune colonne n'a de `sortValue` : la table est paginee cote serveur, un tri
 * client ne reordonnerait que la page affichee et tromperait le lecteur.
 */
const COLUMNS: DataTableColumn<AuditLogResponse>[] = [
  {
    id: 'createdAt',
    header: 'Date',
    mobile: 'meta',
    className: 'w-44',
    cell: (row) => <span className="tnum whitespace-nowrap text-ink-2">{formatDateTime(row.createdAt)}</span>,
  },
  {
    id: 'action',
    header: 'Action',
    mobile: 'title',
    cell: (row) => <Badge tone="outline">{row.action}</Badge>,
  },
  {
    id: 'entity',
    header: 'Objet',
    mobile: 'meta',
    cell: entityLink,
  },
  {
    id: 'actor',
    header: 'Acteur',
    mobile: 'meta',
    className: 'hidden xl:table-cell',
    cell: (row) =>
      row.actorId ? (
        <Link to={`/admin/users/${row.actorId}`} className="text-label font-medium text-ink underline-offset-4 hover:underline">
          {row.actorName ?? row.actorId.slice(0, 8)}
        </Link>
      ) : (
        <span className="text-label text-muted">système</span>
      ),
  },
  {
    id: 'details',
    header: 'Détails',
    mobile: 'value',
    className: 'hidden lg:table-cell max-w-[360px]',
    cell: (row) =>
      row.details && Object.keys(row.details).length > 0 ? (
        <code className="block truncate text-caption text-ink-2" title={JSON.stringify(row.details)}>
          {JSON.stringify(row.details)}
        </code>
      ) : (
        <span className="text-muted">—</span>
      ),
  },
]

interface AuditTableProps {
  /** Resultat de `useAuditLog` : le parent possede la requete (il affiche le total, pilote la page). */
  audit: UseQueryResult<Page<AuditLogResponse>>
  page: number
  onPageChange: (page: number) => void
  emptyDescription?: string
}

/**
 * Journal d'audit filtre et pagine cote serveur. Utilise en pleine page
 * (/admin/audit) et dans l'onglet « Audit » d'une fiche utilisateur.
 */
export function AuditTable({ audit, page, onPageChange, emptyDescription }: AuditTableProps) {
  const data = audit.data

  if (audit.isError) return <ErrorState description={describeError(audit.error)} onRetry={() => audit.refetch()} />

  return (
    <>
      <DataTable
        caption="Journal d'audit"
        columns={COLUMNS}
        rows={data?.content ?? []}
        rowKey={(row) => row.id}
        loading={audit.isPending}
        empty={
          <EmptyState
            icon={ScrollText}
            title="Journal vide"
            description={emptyDescription ?? 'Aucune action ne correspond à ces critères.'}
          />
        }
      />
      {data ? (
        <AdminPagination
          page={page}
          totalPages={data.totalPages}
          onPageChange={onPageChange}
          busy={audit.isFetching}
          label="Pagination du journal"
        />
      ) : null}
    </>
  )
}
