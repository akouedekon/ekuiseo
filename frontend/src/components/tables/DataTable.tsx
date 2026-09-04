import { ArrowDown, ArrowUp, ArrowUpDown } from 'lucide-react'
import { useMemo, useState, type ReactNode } from 'react'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/misc'
import { cn } from '@/lib/cn'

export type SortDirection = 'asc' | 'desc'

export interface DataTableColumn<T> {
  id: string
  header: ReactNode
  cell: (row: T) => ReactNode
  /** Valeur de tri ; absente, la colonne n'est pas triable. */
  sortValue?: (row: T) => string | number | null | undefined
  align?: 'left' | 'right'
  /**
   * Classes de la cellule desktop, en-tete compris : largeur (`w-40`) ou
   * masquage sous une largeur (`hidden xl:table-cell`) pour eviter le
   * defilement horizontal entre 1024 et 1280 px.
   */
  className?: string
  /**
   * Role de la colonne dans le rendu en carte (sous 768 px) :
   * `title` ligne principale, `meta` sous-ligne, `badge` a droite du titre,
   * `value` paire libelle/valeur, `hidden` non affichee.
   */
  mobile?: 'title' | 'meta' | 'badge' | 'value' | 'hidden'
}

interface DataTableProps<T> {
  columns: DataTableColumn<T>[]
  rows: T[]
  rowKey: (row: T) => string
  loading?: boolean
  /** Etat vide complet (EmptyState), affiche a la place du tableau. */
  empty?: ReactNode
  initialSort?: { id: string; direction: SortDirection }
  /** Actions par ligne : derniere colonne sur desktop, pied de carte sur mobile. */
  rowActions?: (row: T) => ReactNode
  /** Filet lateral colore par ligne (etat), sur desktop comme sur mobile. */
  rowAccent?: (row: T) => string | undefined
  caption: string
  className?: string
}

const SKELETON_ROWS = 4

/**
 * Tableau de donnees du back-office : tri par colonne (clavier compris,
 * `aria-sort`), etats de chargement et vide, et un vrai rendu en cartes sous
 * 1024 px (mobile et tablette) plutot qu'un tableau compresse qui defile de
 * cote. Sans dependance : les listes
 * admin restent courtes et filtrees cote serveur. Si un jour la pagination et
 * les colonnes configurables s'imposent, TanStack Table se branche sur la meme
 * definition de colonnes.
 */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  loading = false,
  empty,
  initialSort,
  rowActions,
  rowAccent,
  caption,
  className,
}: DataTableProps<T>) {
  const [sort, setSort] = useState<{ id: string; direction: SortDirection } | null>(initialSort ?? null)

  const sorted = useMemo(() => {
    if (!sort) return rows
    const column = columns.find((c) => c.id === sort.id)
    if (!column?.sortValue) return rows
    const factor = sort.direction === 'asc' ? 1 : -1
    return rows
      .map((row, index) => ({ row, index }))
      .sort((a, b) => {
        const va = column.sortValue!(a.row)
        const vb = column.sortValue!(b.row)
        if (va == null && vb == null) return a.index - b.index
        if (va == null) return 1
        if (vb == null) return -1
        const result =
          typeof va === 'number' && typeof vb === 'number'
            ? va - vb
            : String(va).localeCompare(String(vb), 'fr', { sensitivity: 'base', numeric: true })
        return result !== 0 ? result * factor : a.index - b.index
      })
      .map((entry) => entry.row)
  }, [rows, sort, columns])

  const toggleSort = (id: string) => {
    setSort((current) => {
      if (current?.id !== id) return { id, direction: 'asc' }
      if (current.direction === 'asc') return { id, direction: 'desc' }
      return null
    })
  }

  if (!loading && rows.length === 0 && empty) {
    return <Card className={className}>{empty}</Card>
  }

  const titleColumns = columns.filter((c) => c.mobile === 'title')
  const badgeColumns = columns.filter((c) => c.mobile === 'badge')
  const metaColumns = columns.filter((c) => c.mobile === 'meta')
  const valueColumns = columns.filter((c) => c.mobile === 'value' || c.mobile === undefined)

  return (
    <div className={className}>
      {/* ------------------------------------- Desktop (>= 1024 px) : tableau */}
      <Card className="hidden overflow-hidden lg:block">
        <div className="overflow-x-auto">
          <table className="w-full text-body">
            <caption className="sr-only">{caption}</caption>
            <thead>
              <tr className="border-b border-rule bg-[var(--surface-calm)] text-left text-caption uppercase tracking-wide text-muted">
                {columns.map((column) => {
                  const active = sort?.id === column.id
                  const sortable = Boolean(column.sortValue)
                  const SortIcon = !active ? ArrowUpDown : sort?.direction === 'asc' ? ArrowUp : ArrowDown
                  return (
                    <th
                      key={column.id}
                      scope="col"
                      aria-sort={active ? (sort?.direction === 'asc' ? 'ascending' : 'descending') : undefined}
                      className={cn(
                        'whitespace-nowrap px-3 py-2.5 font-semibold first:pl-4',
                        column.align === 'right' && 'text-right',
                        column.className,
                      )}
                    >
                      {sortable ? (
                        <button
                          type="button"
                          onClick={() => toggleSort(column.id)}
                          className={cn(
                            'inline-flex min-h-8 items-center gap-1 rounded-[var(--radius-chip)] uppercase transition-colors hover:text-ink',
                            active && 'text-ink',
                            column.align === 'right' && 'flex-row-reverse',
                          )}
                        >
                          {column.header}
                          <SortIcon className={cn('size-3.5', !active && 'opacity-50')} aria-hidden />
                        </button>
                      ) : (
                        column.header
                      )}
                    </th>
                  )
                })}
                {rowActions ? (
                  <th scope="col" className="px-3 py-2.5 text-right font-semibold">
                    <span className="sr-only">Actions</span>
                  </th>
                ) : null}
              </tr>
            </thead>
            <tbody className="divide-y divide-rule" aria-busy={loading || undefined}>
              {loading
                ? Array.from({ length: SKELETON_ROWS }).map((_, index) => (
                    <tr key={index}>
                      {columns.map((column) => (
                        <td key={column.id} className="px-4 py-3.5">
                          <Skeleton className={cn('h-4', column.align === 'right' ? 'ml-auto w-16' : 'w-32')} />
                        </td>
                      ))}
                      {rowActions ? <td className="px-4 py-3.5" /> : null}
                    </tr>
                  ))
                : sorted.map((row) => {
                    const accent = rowAccent?.(row)
                    return (
                      <tr
                        key={rowKey(row)}
                        className="transition-colors hover:bg-[var(--surface-calm)]"
                        style={accent ? { boxShadow: `inset 3px 0 0 ${accent}` } : undefined}
                      >
                        {columns.map((column) => (
                          <td
                            key={column.id}
                            className={cn(
                              'px-3 py-3 align-middle first:pl-4',
                              column.align === 'right' && 'tnum whitespace-nowrap text-right',
                              column.className,
                            )}
                          >
                            {column.cell(row)}
                          </td>
                        ))}
                        {rowActions ? (
                          <td className="whitespace-nowrap px-3 py-2 text-right align-middle">
                            <div className="flex justify-end gap-1">{rowActions(row)}</div>
                          </td>
                        ) : null}
                      </tr>
                    )
                  })}
            </tbody>
          </table>
        </div>
      </Card>

      {/* ------------------------------ Mobile et tablette (< 1024 px) : cartes */}
      <ul className="space-y-2 lg:hidden" aria-busy={loading || undefined} aria-label={caption}>
        {loading
          ? Array.from({ length: SKELETON_ROWS }).map((_, index) => (
              <li key={index}>
                <Card className="space-y-2 p-4">
                  <Skeleton className="h-4 w-40" />
                  <Skeleton className="h-3 w-56" />
                  <Skeleton className="h-3 w-24" />
                </Card>
              </li>
            ))
          : sorted.map((row) => {
              const accent = rowAccent?.(row)
              return (
                <li key={rowKey(row)}>
                  <Card style={accent ? { borderLeft: `3px solid ${accent}` } : undefined}>
                    <div className="p-4">
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0 flex-1">
                          {titleColumns.map((column) => (
                            <div key={column.id} className="font-display text-lead font-bold leading-tight">
                              {column.cell(row)}
                            </div>
                          ))}
                          {metaColumns.map((column) => (
                            <div key={column.id} className="mt-0.5 text-label text-muted">
                              {column.cell(row)}
                            </div>
                          ))}
                        </div>
                        {badgeColumns.length > 0 ? (
                          <div className="flex shrink-0 flex-col items-end gap-1">
                            {badgeColumns.map((column) => (
                              <div key={column.id}>{column.cell(row)}</div>
                            ))}
                          </div>
                        ) : null}
                      </div>
                      {valueColumns.length > 0 ? (
                        <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-1.5 text-label">
                          {valueColumns.map((column) => (
                            <div key={column.id} className="flex min-w-0 flex-wrap items-baseline justify-between gap-x-2">
                              <dt className="shrink-0 text-muted">{column.header}</dt>
                              <dd className="tnum min-w-0 text-right font-medium">{column.cell(row)}</dd>
                            </div>
                          ))}
                        </dl>
                      ) : null}
                    </div>
                    {rowActions ? (
                      <div className="flex flex-wrap justify-end gap-1 border-t border-rule px-3 py-2">
                        {rowActions(row)}
                      </div>
                    ) : null}
                  </Card>
                </li>
              )
            })}
      </ul>
    </div>
  )
}
