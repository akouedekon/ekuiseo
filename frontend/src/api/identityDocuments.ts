import { apiClient } from './client'
import type { IdentityDocumentSummary } from './extended'
import { fetchAuthorizedBlob, uploadAuthorized } from './rawFetch'
import type { IdentityDocumentSide } from '@/lib/identityDocuments'

/** POST /api/v1/me/identity/documents?side=… (multipart `file`), avec progression d'envoi. */
export function uploadIdentityDocument(
  side: IdentityDocumentSide,
  file: File,
  onProgress?: (fraction: number) => void,
): Promise<IdentityDocumentSummary> {
  const form = new FormData()
  // Le nom d'origine n'est jamais conserve par le serveur : un nom neutre suffit.
  form.append('file', file, `piece-${side.toLowerCase()}`)
  return uploadAuthorized<IdentityDocumentSummary>(`/api/v1/me/identity/documents?side=${side}`, form, onProgress)
}

/** DELETE /api/v1/me/identity/documents/{side} (tant que le dossier est en attente). */
export function deleteIdentityDocument(side: IdentityDocumentSide): Promise<void> {
  return apiClient.delete<void>(`/api/v1/me/identity/documents/${side}`)
}

/**
 * GET /api/v1/admin/verifications/{id}/documents/{side} : flux dechiffre, lu avec le
 * JWT et remis sous forme de blob (jamais une URL nue dans <img>). Chaque appel est
 * journalise cote serveur (ADMIN_IDENTITY_DOCUMENT_VIEWED).
 */
export function fetchAdminIdentityDocument(verificationId: string, side: IdentityDocumentSide): Promise<Blob> {
  return fetchAuthorizedBlob(`/api/v1/admin/verifications/${verificationId}/documents/${side}`)
}
