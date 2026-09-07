import { fromZonedTime, toZonedTime } from 'date-fns-tz'
import { z } from 'zod'
import { BENIN_TIME_ZONE } from '@/lib/format'

/*
 * Schemas de validation partages (React Hook Form + Zod).
 * Les messages s'adressent a l'utilisateur, jamais au developpeur :
 * « Indiquez votre prénom », pas « firstName is required ».
 */

function phoneDigits(value: string): string {
  return value.replace(/\D/g, '')
}

const LEGACY_BENIN_MESSAGE = 'Depuis 2024, les numéros béninois ont 10 chiffres et commencent par 01'

/**
 * Normalise une saisie en E.164, meme regle que le serveur (PhoneNumbers.java) :
 * espaces, points, tirets et parentheses ignores ; « 00 » initial -> « + » ; un numero
 * sans indicatif n'est accepte que s'il est beninois a 10 chiffres commencant par 01
 * (plan de numerotation du 30/11/2024) ; les anciens numeros a 8 chiffres sont refuses.
 * Renvoie null si la saisie n'est pas un numero valide.
 */
export function toE164(raw: string): string | null {
  let s = raw.trim().replace(/[\s().-]/g, '')
  if (s.startsWith('00')) s = `+${s.slice(2)}`
  const international = s.startsWith('+')
  let digits = international ? s.slice(1) : s
  if (digits.length === 0 || !/^\d+$/.test(digits)) return null
  if (!international) {
    if (digits.length === 10 && digits.startsWith('01')) digits = `229${digits}`
    else return null
  }
  if (digits.startsWith('229')) {
    if (!/^01\d{8}$/.test(digits.slice(3))) return null
  } else if (!/^[1-9]\d{7,14}$/.test(digits)) {
    return null
  }
  return `+${digits}`
}

/** Numero de telephone mobile (Benin +229 a 10 chiffres, Togo, Nigeria... en E.164). */
export const phoneSchema = z
  .string()
  .trim()
  .min(1, 'Indiquez un numéro de téléphone')
  .superRefine((value, ctx) => {
    if (toE164(value)) return
    const compact = value.replace(/[\s().-]/g, '')
    const digits = phoneDigits(compact)
    const benin = compact.startsWith('+229') || compact.startsWith('00229') || !/^(\+|00)/.test(compact)
    const national = compact.startsWith('+229') ? digits.slice(3) : compact.startsWith('00229') ? digits.slice(5) : digits
    let message = 'Numéro de téléphone invalide'
    if (benin && national.length === 8) message = LEGACY_BENIN_MESSAGE
    else if (digits.length < 8) message = 'Numéro de téléphone incomplet'
    else if (!/^(\+|00)/.test(compact)) message = "Ajoutez l'indicatif, ex. +229 01 97 00 00 00"
    ctx.addIssue({ code: z.ZodIssueCode.custom, message })
  })

/** E-mail obligatoire (inscription : le code de connexion y est envoye). */
export const emailSchema = z
  .string()
  .trim()
  .min(1, 'Indiquez votre adresse e-mail : le code de connexion y sera envoyé')
  .email('Adresse e-mail invalide')

/** E-mail facultatif : vide accepte, sinon doit etre valide. */
export const optionalEmailSchema = z.union([z.literal(''), z.string().trim().email('Adresse e-mail invalide')])

/** Profil : l'e-mail n'en fait pas partie, il se change par le parcours verifie (EmailChangeDialog). */
export const profileSchema = z.object({
  firstName: z.string().trim().min(2, 'Indiquez votre prénom').max(60, '60 caractères maximum'),
  lastName: z.string().trim().min(2, 'Indiquez votre nom').max(60, '60 caractères maximum'),
  bio: z.string().trim().max(300, '300 caractères maximum'),
})
export type ProfileValues = z.infer<typeof profileSchema>

const COMFORT_LEVELS = ['BASIC', 'COMFORT', 'PREMIUM'] as const

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

const PAYMENT_PROVIDERS = ['MTN_MOMO', 'MOOV_MONEY', 'CELTIIS_CASH'] as const

export const momoSchema = z.object({
  provider: z.enum(PAYMENT_PROVIDERS),
  phone: phoneSchema,
})
export type MomoValues = z.infer<typeof momoSchema>

const DOCUMENT_TYPES = ['CNI', 'PASSPORT', 'DRIVER_LICENSE'] as const

export const identitySchema = z.object({
  documentType: z.enum(DOCUMENT_TYPES),
  documentNumber: z
    .string()
    .trim()
    .min(4, 'Numéro de document trop court')
    .max(30, '30 caractères maximum'),
})
export type IdentityValues = z.infer<typeof identitySchema>

/* ------------------------------------------------------------ Horaires */

/** Un depart ne se publie ni ne se deplace a moins de 15 minutes (regle F225, alignee sur le serveur). */
export const MIN_DEPARTURE_LEAD_MS = 15 * 60 * 1000

/**
 * Date « AAAA-MM-JJ » + heure « HH:MM » saisies EN HEURE DU BENIN -> instant, ou
 * null si l'un des deux est invalide (audit F424 : jamais le fuseau de l'appareil).
 */
export function departureFromFields(date: string, time: string): Date | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}$/.test(time)) return null
  const value = fromZonedTime(`${date}T${time}:00`, BENIN_TIME_ZONE)
  return Number.isNaN(value.getTime()) ? null : value
}

/** Prochaine demi-heure ronde apres `from` + 15 min, en heure du Benin : valeur initiale honnete du champ heure. */
export function nextHalfHour(from: Date = new Date()): { date: string; time: string } {
  // Horloge murale du Benin : les champs date/heure sont exprimes dans ce fuseau.
  const d = toZonedTime(new Date(from.getTime() + MIN_DEPARTURE_LEAD_MS), BENIN_TIME_ZONE)
  d.setSeconds(0, 0)
  const minutes = d.getMinutes()
  if (minutes === 0 || minutes === 30) {
    /* deja ronde */
  } else if (minutes < 30) d.setMinutes(30)
  else {
    d.setMinutes(0)
    d.setHours(d.getHours() + 1)
  }
  const date = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  const time = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  return { date, time }
}

/* --------------------------------------------------------------- CGU */

/**
 * Acceptation des CGU et de la politique de confidentialite a l'inscription :
 * obligatoire, envoyee au serveur avec la version acceptee (lib/legal.ts).
 */
export const termsAcceptanceSchema = z.literal(true, {
  errorMap: () => ({ message: "Vous devez accepter les conditions générales d'utilisation pour créer un compte" }),
})

export const registerSchema = z.object({
  phone: phoneSchema,
  firstName: z.string().trim().min(1, 'Indiquez votre prénom').max(60, '60 caractères maximum'),
  lastName: z.string().trim().min(1, 'Indiquez votre nom').max(60, '60 caractères maximum'),
  email: emailSchema,
  acceptTerms: termsAcceptanceSchema,
})
export type RegisterValues = z.infer<typeof registerSchema>

