import { Eye, FileText, Image as ImageIcon, ShieldAlert } from 'lucide-react'
import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import type { IdentityDocumentSummary } from '@/api/extended'
import { fetchAdminIdentityDocument } from '@/api/identityDocuments'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { describeError } from '@/lib/errors'
import { formatFileSize, isImageType, sideLabel, type IdentityDocumentSide } from '@/lib/identityDocuments'

/**
 * Pieces d'un dossier de verification (V20) cote back-office : presence par face et
 * bouton « Voir » qui charge le flux dechiffre avec le JWT (fetch + blob : une URL nue
 * dans <img> ne porterait pas l'en-tete Authorization). Chaque consultation est
 * journalisee cote serveur (ADMIN_IDENTITY_DOCUMENT_VIEWED) et l'interface le dit.
 */
export function VerificationDocuments({ verificationId, documents }: { verificationId: string; documents: IdentityDocumentSummary[] }) {
  const [viewing, setViewing] = useState<{ side: IdentityDocumentSide; contentType: string; url: string } | null>(null)
  const [loadingSide, setLoadingSide] = useState<IdentityDocumentSide | null>(null)

  // L'URL du blob est liberee a la fermeture : rien ne reste en memoire apres consultation.
  useEffect(() => () => (viewing ? URL.revokeObjectURL(viewing.url) : undefined), [viewing])

  const open = async (doc: IdentityDocumentSummary) => {
    setLoadingSide(doc.side)
    try {
      const blob = await fetchAdminIdentityDocument(verificationId, doc.side)
      setViewing({ side: doc.side, contentType: doc.contentType, url: URL.createObjectURL(blob) })
    } catch (error) {
      toast.error(describeError(error, 'La pièce ne peut pas être affichée.'))
    } finally {
      setLoadingSide(null)
    }
  }

  if (documents.length === 0) {
    return (
      <p className="flex items-center gap-1.5 text-caption text-muted">
        <ShieldAlert className="size-3.5" aria-hidden />
        Aucune pièce téléversée : ne validez qu'après contrôle par un autre canal.
      </p>
    )
  }

  return (
    <div>
      <ul className="flex flex-wrap gap-2">
        {documents.map((doc) => (
          <li key={doc.side} className="flex items-center gap-1.5">
            <Badge tone="neutral">
              {isImageType(doc.contentType) ? <ImageIcon aria-hidden /> : <FileText aria-hidden />}
              {sideLabel(doc.side)} · {formatFileSize(doc.sizeBytes)}
            </Badge>
            <Button size="sm" variant="link" loading={loadingSide === doc.side} disabled={loadingSide !== null} onClick={() => open(doc)}>
              <Eye aria-hidden />
              Voir
            </Button>
          </li>
        ))}
      </ul>
      <p className="mt-1 text-caption text-muted">Accès journalisé : chaque consultation est inscrite au journal d'audit.</p>

      <Dialog open={viewing !== null} onOpenChange={(isOpen) => !isOpen && setViewing(null)}>
        <DialogContent className="max-w-3xl">
          <DialogHeader>
            <DialogTitle>{viewing ? sideLabel(viewing.side) : ''}</DialogTitle>
            <DialogDescription>Consultation journalisée. Ne téléchargez ni ne copiez cette pièce.</DialogDescription>
          </DialogHeader>
          {viewing ? (
            isImageType(viewing.contentType) ? (
              <img src={viewing.url} alt={sideLabel(viewing.side)} className="max-h-[70vh] w-full rounded-[var(--radius-control)] object-contain" />
            ) : (
              <iframe src={viewing.url} title={sideLabel(viewing.side)} className="h-[70vh] w-full rounded-[var(--radius-control)] border border-rule" />
            )
          ) : null}
        </DialogContent>
      </Dialog>
    </div>
  )
}
