/*
 * Vocabulaire du journal d'audit. Fichier sans composant (Fast Refresh exige que
 * les fichiers de composants n'exportent que des composants).
 */

/**
 * Actions journalisees par le backend (AuditService#log). Liste tenue a la main :
 * le serveur n'expose pas de referentiel, et une action inconnue reste lisible
 * dans le tableau meme si elle n'est pas proposee dans le filtre.
 */
export const AUDIT_ACTIONS = [
  'USER_SUSPENDED',
  'USER_REACTIVATED',
  'USER_CONTACT_CHANGED',
  'USER_EMAIL_CHANGED',
  'USER_ANONYMIZED',
  'USER_IDENTITY_VERIFIED',
  'USER_IDENTITY_REVOKED',
  'IDENTITY_SUBMITTED',
  'IDENTITY_DOCUMENT_UPLOADED',
  'IDENTITY_DOCUMENT_DELETED',
  'ADMIN_IDENTITY_DOCUMENT_VIEWED',
  'IDENTITY_VERIFICATION_APPROVED',
  'IDENTITY_VERIFICATION_REJECTED',
  'VEHICLE_VERIFIED',
  'PAYMENT_ACCOUNT_VERIFIED',
  'REPORT_CREATED',
  'REPORT_STATUS_UPDATED',
  'REPORT_RESOLVED',
  'REPORT_CONVERSATIONS_VIEWED',
  'PAYOUT_BATCH_RUN',
  'PAYOUT_SETTLED',
  'PAYOUT_FAILED',
  'PAYOUT_ITEM_REMOVED',
  'PAYOUT_EMPTIED',
  'PAYOUT_ALREADY_INCLUDED',
  'PAYMENT_REFUND_REQUESTED',
  'PAYMENT_REFUND_RETRIED',
  'PAYMENT_REFUNDED',
  'PAYMENT_MARKED_REFUNDED',
  'REFUND_MANUAL_REQUIRED',
  'REFUND_NO_PAYMENT_FOUND',
  'BOOKING_CANCELLED_BY_PASSENGER',
  'BOOKING_NO_SHOW',
] as const

/** Types d'entite rencontres dans `entityType` ; USER et TRIP ont une page cible. */
export const AUDIT_ENTITY_TYPES = [
  'USER',
  'TRIP',
  'BOOKING',
  'PAYMENT',
  'PAYOUT',
  'REPORT',
  'VEHICLE',
  'PAYMENT_ACCOUNT',
  'IDENTITY',
] as const
