import { FileText, ImagePlus, Trash2, UploadCloud } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import type { IdentityDocumentSummary } from '@/api/extended'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Progress } from '@/components/ui/misc'
import { useDeleteIdentityDocument, useUploadIdentityDocument } from '@/hooks/useIdentityDocuments'
import { describeError } from '@/lib/errors'
import { formatFromNow } from '@/lib/format'
import {
  IDENTITY_DOCUMENT_ACCEPT,
  IDENTITY_SIDES,
  formatFileSize,
  isImageType,
  validateIdentityDocument,
  type IdentityDocumentSide,
} from '@/lib/identityDocuments'

/** Statuts dans lesquels les pieces se deposent, se remplacent et se suppriment (le serveur exige PENDING). */
export const IDENTITY_DOCUMENTS_ALLOWED: ReadonlySet<string> = new Set(['PENDING'])

/**
 * Depot des pieces d'identite (V20) : une zone par face (recto, verso, selfie), apercu
 * local de l'image choisie, controle du type et de la taille avant envoi, progression,
 * remplacement et suppression tant que le dossier est en attente. Le serveur chiffre
 * les fichiers et les supprime 30 jours apres la decision.
 */
export function IdentityDocumentsForm({ documents, editable }: { documents: IdentityDocumentSummary[]; editable: boolean }) {
  return (
    <div className="space-y-3">
      {IDENTITY_SIDES.map((entry) => (
        <DocumentSlot
          key={entry.side}
          side={entry.side}
          label={entry.label}
          hint={entry.hint}
          existing={documents.find((doc) => doc.side === entry.side) ?? null}
          editable={editable}
        />
      ))}
      <p className="text-caption text-muted">
        Photo (JPEG, PNG, WebP) ou PDF, 5 Mo au maximum par fichier. Vos pièces sont chiffrées sur nos serveurs, visibles
        uniquement par la modération, et supprimées 30 jours après la décision.
      </p>
    </div>
  )
}

function DocumentSlot({
  side,
  label,
  hint,
  existing,
  editable,
}: {
  side: IdentityDocumentSide
  label: string
  hint: string
  existing: IdentityDocumentSummary | null
  editable: boolean
}) {
  const upload = useUploadIdentityDocument()
  const remove = useDeleteIdentityDocument()
  const inputRef = useRef<HTMLInputElement>(null)
  const [preview, setPreview] = useState<{ url: string; name: string } | null>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const inputId = `identity-document-${side.toLowerCase()}`

  // L'URL d'apercu est liberee au demontage et a chaque remplacement.
  useEffect(() => () => (preview ? URL.revokeObjectURL(preview.url) : undefined), [preview])

  const choose = (file: File | undefined) => {
    if (!file) return
    const problem = validateIdentityDocument(file)
    if (problem) {
      toast.error(problem)
      return
    }
    setPreview(file.type.startsWith('image/') ? { url: URL.createObjectURL(file), name: file.name } : null)
    setProgress(0)
    upload.mutate(
      { side, file, onProgress: (fraction) => setProgress(Math.round(fraction * 100)) },
      {
        onSuccess: () => toast.success(`${label} envoyé`),
        onError: (error) => {
          setPreview(null)
          toast.error(describeError(error, "L'envoi n'a pas abouti. Réessayez."))
        },
        onSettled: () => setProgress(null),
      },
    )
  }

  const busy = upload.isPending || remove.isPending
  const Icon = existing ? (isImageType(existing.contentType) ? ImagePlus : FileText) : UploadCloud

  return (
    <div className="rounded-[var(--radius-card)] border border-rule bg-surface p-3">
      <div className="flex items-start gap-3">
        {preview ? (
          <img
            src={preview.url}
            alt=""
            className="size-14 shrink-0 rounded-[var(--radius-control)] border border-rule object-cover"
          />
        ) : (
          <span
            className={
              existing
                ? 'flex size-14 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-success-soft text-success-ink'
                : 'flex size-14 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-surface-2 text-muted'
            }
          >
            <Icon className="size-6" aria-hidden />
          </span>
        )}
        <div className="min-w-0 flex-1">
          <p className="text-body font-medium text-ink">{label}</p>
          {existing ? (
            <p className="text-caption text-muted">
              <Badge tone="success" className="mr-1.5">
                Reçu
              </Badge>
              {formatFileSize(existing.sizeBytes)} · envoyé {formatFromNow(existing.createdAt)}
            </p>
          ) : (
            <p className="text-caption text-muted">{hint}</p>
          )}
          {progress !== null ? (
            <div className="mt-2">
              <Progress value={progress} aria-label={`Envoi de ${label}`} />
              <p className="mt-1 text-caption text-muted">Envoi… {progress} %</p>
            </div>
          ) : null}
        </div>
      </div>

      {editable ? (
        <div className="mt-3 flex flex-wrap gap-2">
          <input
            ref={inputRef}
            id={inputId}
            type="file"
            accept={IDENTITY_DOCUMENT_ACCEPT}
            capture={side === 'SELFIE' ? 'user' : undefined}
            className="sr-only"
            onChange={(event) => {
              choose(event.target.files?.[0])
              // Le meme fichier doit pouvoir etre rechoisi apres une erreur.
              event.target.value = ''
            }}
          />
          <Button size="sm" variant={existing ? 'secondary' : 'primary'} loading={upload.isPending} disabled={busy} onClick={() => inputRef.current?.click()}>
            <UploadCloud aria-hidden />
            {existing ? 'Remplacer' : side === 'SELFIE' ? 'Prendre la photo' : 'Ajouter'}
          </Button>
          {existing ? (
            <Button size="sm" variant="ghost" className="text-danger-ink" disabled={busy} onClick={() => setConfirmDelete(true)}>
              <Trash2 aria-hidden />
              Supprimer
            </Button>
          ) : null}
        </div>
      ) : null}

      <ConfirmDialog
        open={confirmDelete}
        onOpenChange={setConfirmDelete}
        title={`Supprimer ${label.toLowerCase()} ?`}
        description="Le fichier est effacé de nos serveurs. Vous pourrez en envoyer un autre tant que le dossier est en attente."
        confirmLabel="Supprimer"
        tone="danger"
        loading={remove.isPending}
        onConfirm={() =>
          remove.mutate(side, {
            onSuccess: () => {
              setConfirmDelete(false)
              setPreview(null)
              toast.success(`${label} supprimé`)
            },
            onError: (error) => toast.error(describeError(error, "La suppression n'a pas abouti.")),
          })
        }
      />
    </div>
  )
}
