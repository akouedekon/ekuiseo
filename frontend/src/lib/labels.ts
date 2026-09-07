import type { ReportReason } from '@/api/extended'
import type { ComfortLevel } from '@/api/types'

/*
 * Libelles partages entre le parcours public et le back-office (audit F239) :
 * une seule traduction par valeur d'enum, pour que « Confortable » ne devienne
 * pas « Confort » d'un ecran a l'autre.
 */

export const COMFORT_LABEL: Record<ComfortLevel, string> = {
  BASIC: 'Confort simple',
  COMFORT: 'Confortable',
  PREMIUM: 'Haut de gamme',
}

/** Options du formulaire vehicule : la mention « climatisé » n'apparait qu'au choix. */
export const COMFORT_OPTIONS = [
  { value: 'BASIC', label: COMFORT_LABEL.BASIC },
  { value: 'COMFORT', label: 'Confortable (climatisé)' },
  { value: 'PREMIUM', label: COMFORT_LABEL.PREMIUM },
] as const

export type DocumentType = 'CNI' | 'PASSPORT' | 'DRIVER_LICENSE'

export const DOCUMENT_LABEL: Record<DocumentType, string> = {
  CNI: "Carte nationale d'identité",
  PASSPORT: 'Passeport',
  DRIVER_LICENSE: 'Permis de conduire',
}

export const DOCUMENT_OPTIONS = [
  { value: 'CNI', label: DOCUMENT_LABEL.CNI },
  { value: 'PASSPORT', label: DOCUMENT_LABEL.PASSPORT },
  { value: 'DRIVER_LICENSE', label: DOCUMENT_LABEL.DRIVER_LICENSE },
] as const

/** Libelle d'une piece, tolerant a une valeur inconnue (affichee telle quelle). */
export function documentLabel(type: string | null | undefined): string | undefined {
  return type && type in DOCUMENT_LABEL ? DOCUMENT_LABEL[type as DocumentType] : type ?? undefined
}

/** Jours de la semaine ISO (1 = lundi … 7 = dimanche), lettre courte, nom et code RRULE (RFC 5545). */
export const WEEKDAYS = [
  { value: 1, letter: 'L', short: 'lun', name: 'lundi', rrule: 'MO' },
  { value: 2, letter: 'M', short: 'mar', name: 'mardi', rrule: 'TU' },
  { value: 3, letter: 'M', short: 'mer', name: 'mercredi', rrule: 'WE' },
  { value: 4, letter: 'J', short: 'jeu', name: 'jeudi', rrule: 'TH' },
  { value: 5, letter: 'V', short: 'ven', name: 'vendredi', rrule: 'FR' },
  { value: 6, letter: 'S', short: 'sam', name: 'samedi', rrule: 'SA' },
  { value: 7, letter: 'D', short: 'dim', name: 'dimanche', rrule: 'SU' },
] as const

export const REPORT_REASON_LABEL: Record<ReportReason, string> = {
  NO_SHOW: 'Absence au départ',
  DANGEROUS_DRIVING: 'Conduite dangereuse',
  HARASSMENT: 'Harcèlement ou comportement déplacé',
  FRAUD: 'Fraude ou arnaque',
  VEHICLE_MISMATCH: 'Véhicule différent de l’annonce',
  OTHER: 'Autre',
}

export const REPORT_REASON_OPTIONS = (Object.keys(REPORT_REASON_LABEL) as ReportReason[]).map((value) => ({
  value,
  label: REPORT_REASON_LABEL[value],
}))

export const TRIP_TYPE_LABEL = {
  INTERURBAIN: 'Interurbain',
  QUOTIDIEN: 'Quotidien',
} as const

/** Libelle « Trajet calme / Discussion selon l’humeur / Aime discuter », partage entre profil public et reglages. */
export const CHATTY_LABEL = {
  QUIET: 'Trajet calme',
  DEPENDS: 'Discussion selon l’humeur',
  TALKATIVE: 'Aime discuter',
} as const
