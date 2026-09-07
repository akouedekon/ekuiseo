import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { IdentityDocumentSummary, IdentityVerificationResponse } from '@/api/extended'
import { deleteIdentityDocument, uploadIdentityDocument } from '@/api/identityDocuments'
import type { IdentityDocumentSide } from '@/lib/identityDocuments'

const IDENTITY_KEY = ['me', 'identity'] as const

function replaceDocument(
  current: IdentityVerificationResponse | undefined,
  side: IdentityDocumentSide,
  summary: IdentityDocumentSummary | null,
): IdentityVerificationResponse | undefined {
  if (!current) return current
  const others = (current.documents ?? []).filter((doc) => doc.side !== side)
  return { ...current, documents: summary ? [...others, summary] : others }
}

/** Televersement d'une face (V20) ; la reponse remplace l'entree correspondante du dossier en cache. */
export function useUploadIdentityDocument() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationKey: ['identity-document', 'upload'],
    mutationFn: (input: { side: IdentityDocumentSide; file: File; onProgress?: (fraction: number) => void }) =>
      uploadIdentityDocument(input.side, input.file, input.onProgress),
    onSuccess: (summary, input) => {
      queryClient.setQueryData<IdentityVerificationResponse>(IDENTITY_KEY, (current) => replaceDocument(current, input.side, summary))
    },
  })
}

/** Retrait d'une face (V20). */
export function useDeleteIdentityDocument() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationKey: ['identity-document', 'delete'],
    mutationFn: (side: IdentityDocumentSide) => deleteIdentityDocument(side),
    onSuccess: (_result, side) => {
      queryClient.setQueryData<IdentityVerificationResponse>(IDENTITY_KEY, (current) => replaceDocument(current, side, null))
    },
  })
}
