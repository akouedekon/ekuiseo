import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'

interface AdminPaginationProps {
  /** Page courante, indexee a 0 (convention Spring `Page.number`). */
  page: number
  totalPages: number
  onPageChange: (page: number) => void
  /** Desactive les boutons pendant un rechargement pour eviter les doubles sauts. */
  busy?: boolean
  label: string
}

/**
 * Pagination serveur des listes du back-office (journal, sous-listes d'une fiche).
 * Ne s'affiche pas quand une seule page suffit.
 */
export function AdminPagination({ page, totalPages, onPageChange, busy = false, label }: AdminPaginationProps) {
  if (totalPages <= 1) return null
  return (
    <nav className="mt-4 flex items-center justify-between gap-3" aria-label={label}>
      <Button variant="secondary" size="sm" disabled={page === 0 || busy} onClick={() => onPageChange(Math.max(0, page - 1))}>
        <ChevronLeft className="size-4" aria-hidden />
        Précédent
      </Button>
      <span className="tnum text-label text-muted">
        Page {page + 1} sur {totalPages}
      </span>
      <Button variant="secondary" size="sm" disabled={page + 1 >= totalPages || busy} onClick={() => onPageChange(page + 1)}>
        Suivant
        <ChevronRight className="size-4" aria-hidden />
      </Button>
    </nav>
  )
}
