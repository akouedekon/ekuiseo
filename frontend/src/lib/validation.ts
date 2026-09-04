import { z } from 'zod'

/*
 * Schemas de validation partages (React Hook Form + Zod).
 * Les messages s'adressent a l'utilisateur, jamais au developpeur :
 * « Indiquez votre prénom », pas « firstName is required ».
 */

const PHONE_MIN_DIGITS = 8
const PHONE_MAX_DIGITS = 15

export function phoneDigits(value: string): string {
  return value.replace(/\D/g, '')
}

/** Numero de telephone mobile (Benin +229, Togo, Nigeria acceptes). */
export const phoneSchema = z
  .string()
  .trim()
  .min(1, 'Indiquez un numéro de téléphone')
  .refine((value) => phoneDigits(value).length >= PHONE_MIN_DIGITS, 'Numéro de téléphone incomplet')
  .refine((value) => phoneDigits(value).length <= PHONE_MAX_DIGITS, 'Numéro de téléphone trop long')

/** E-mail facultatif : vide accepte, sinon doit etre valide. */
export const optionalEmailSchema = z.union([z.literal(''), z.string().trim().email('Adresse e-mail invalide')])

export const profileSchema = z.object({
  firstName: z.string().trim().min(2, 'Indiquez votre prénom').max(60, '60 caractères maximum'),
  lastName: z.string().trim().min(2, 'Indiquez votre nom').max(60, '60 caractères maximum'),
  email: optionalEmailSchema,
  bio: z.string().trim().max(300, '300 caractères maximum'),
})
export type ProfileValues = z.infer<typeof profileSchema>

export const COMFORT_LEVELS = ['BASIC', 'COMFORT', 'PREMIUM'] as const

export const vehicleSchema = z.object({
  brand: z.string().trim().min(1, 'Indiquez la marque').max(40, '40 caractères maximum'),
  model: z.string().trim().min(1, 'Indiquez le modèle').max(40, '40 caractères maximum'),
  color: z.string().trim().max(30, '30 caractères maximum'),
  plate: z.string().trim().min(4, "Indiquez l'immatriculation").max(15, '15 caractères maximum'),
  seats: z
    .number({ invalid_type_error: 'Indiquez un nombre de places' })
    .int('Nombre entier attendu')
    .min(1, 'Au moins 1 place')
    .max(8, '8 places maximum'),
  comfortLevel: z.enum(COMFORT_LEVELS),
})
export type VehicleValues = z.infer<typeof vehicleSchema>

export const PAYMENT_PROVIDERS = ['MTN_MOMO', 'MOOV_MONEY', 'CELTIIS_CASH'] as const

export const momoSchema = z.object({
  provider: z.enum(PAYMENT_PROVIDERS),
  phone: phoneSchema,
})
export type MomoValues = z.infer<typeof momoSchema>

export const DOCUMENT_TYPES = ['CNI', 'PASSPORT', 'DRIVER_LICENSE'] as const

export const identitySchema = z.object({
  documentType: z.enum(DOCUMENT_TYPES),
  documentNumber: z
    .string()
    .trim()
    .min(4, 'Numéro de document trop court')
    .max(30, '30 caractères maximum'),
})
export type IdentityValues = z.infer<typeof identitySchema>

export const loginPhoneSchema = z.object({ phone: phoneSchema })
export type LoginPhoneValues = z.infer<typeof loginPhoneSchema>
