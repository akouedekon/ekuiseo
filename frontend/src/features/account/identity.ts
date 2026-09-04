import { FileCheck2, ShieldAlert, ShieldCheck, type LucideIcon } from 'lucide-react'
import type { IdentityVerificationStatus } from '@/api/extended'

/** Presentation de chaque etat de verification d'identite - une seule source pour tout le compte. */
export const IDENTITY_PRESENTATION: Record<
  IdentityVerificationStatus,
  { label: string; tone: 'neutral' | 'warning' | 'success' | 'danger'; icon: LucideIcon }
> = {
  NOT_SUBMITTED: { label: 'Identité non vérifiée', tone: 'neutral', icon: ShieldAlert },
  PENDING: { label: 'Vérification en cours', tone: 'warning', icon: FileCheck2 },
  APPROVED: { label: 'Identité vérifiée', tone: 'success', icon: ShieldCheck },
  REJECTED: { label: 'Vérification refusée', tone: 'danger', icon: ShieldAlert },
}

export const DOCUMENT_OPTIONS = [
  { value: 'CNI', label: "Carte nationale d'identité" },
  { value: 'PASSPORT', label: 'Passeport' },
  { value: 'DRIVER_LICENSE', label: 'Permis de conduire' },
] as const

export function documentLabel(type: string | null | undefined): string | undefined {
  return DOCUMENT_OPTIONS.find((option) => option.value === type)?.label
}
