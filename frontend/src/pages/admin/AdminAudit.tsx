import { ChevronLeft, ChevronRight, ScrollText } from 'lucide-react'
import { useState } from 'react'
import { AdminPageHeader } from '@/components/layout/AdminPageHeader'
import { DataTable, type DataTableColumn } from '@/components/tables/DataTable'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { useAuditLog } from '@/hooks/useAdmin'
import { formatDateTime } from '@/lib/format'
import type { AuditLogResponse } from '@/api/extended'

const PAGE_SIZE = 25

const COLUMNS: DataTableColumn<AuditLogResponse>[] = [
  {
    id: 'createdAt',
    header: 'Date',
    mobile: 'meta',
    sortValue: (row) => row.createdAt,
    className: 'w-44',
    cell: (row) => <span className="tnum whitespace-nowrap text-ink-2">{formatDateTime(row.createdAt)}</span>,
  },
  {
    id: 'action',
    header: 'Action',
    mobile: 'title',
    sortValue: (row) => row.action,
    cell: (row) => <Badge tone="outline">{row.action}</Badge>,
  },
  {
    id: 'entity',
    header: 'Objet',
    mobile: 'meta',
    cell: (row) =>
      row.entityType ? (
        <span className="tnum text-label text-ink-2">
          {row.entityType}
          {row.entityId ? <span className="text-muted"> · {row.entityId.slice(0, 8)}</span> : null}
        </span>
      ) : (
        <span className="text-muted">—</span>
      ),
  },
  {
    id: 'actor',
    header: 'Acteur',
    mobile: 'meta',
    className: 'hidden xl:table-cell',
    cell: (row) => <span className="tnum text-label text-muted">{row.actorId ? row.actorId.slice(0, 8) : 'système'}</span>,
  },
  {
    id: 'details',
    header: 'Détails',
    mobile: 'value',
    className: 'hidden lg:table-cell max-w-[360px]',
    cell: (row) =>
      row.details && Object.keys(row.details).length > 0 ? (
        <code className="block truncate text-[12px] text-ink-2">{JSON.stringify(row.details)}</code>
      ) : (
        <span className="text-muted">—</span>
      ),
  },
]

/** Journal d'audit (GET /api/v1/admin/audit-log) : qui a fait quoi, pagine cote serveur. */
export function AdminAudit() {
  const [page, setPage] = useState(0)
  const audit = useAuditLog(page, PAGE_SIZE)
  const data = audit.data

  return (
    <div>
      <AdminPageHeader
        title="Journal d'audit"
        count={data?.totalElements}
        description="Actions sensibles du back-office et du système : suspensions, validations, reversements, remboursements."
      />

      {audit.isError ? (
        <ErrorState onRetry={() => audit.refetch()} />
      ) : (
        <>
          <DataTable
            caption="Journal d'audit"
            columns={COLUMNS}
            rows={data?.content ?? []}
            rowKey={(row) => row.id}
            loading={audit.isPending}
            initialSort={{ id: 'createdAt', direction: 'desc' }}
            empty={<EmptyState icon={ScrollText} title="Journal vide" description="Aucune action enregistrée pour le moment." />}
          />
          {data && data.totalPages > 1 ? (
            <nav className="mt-4 flex items-center justify-between gap-3" aria-label="Pagination du journal">
              <Button
                variant="secondary"
                size="sm"
                disabled={page === 0 || audit.isFetching}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                <ChevronLeft className="size-4" aria-hidden />
                Précédent
              </Button>
              <span className="tnum text-label text-muted">
                Page {page + 1} sur {data.totalPages}
              </span>
              <Button
                variant="secondary"
                size="sm"
                disabled={page + 1 >= data.totalPages || audit.isFetching}
                onClick={() => setPage((p) => p + 1)}
              >
                Suivant
                <ChevronRight className="size-4" aria-hidden />
              </Button>
            </nav>
          ) : null}
        </>
      )}
    </div>
  )
}
