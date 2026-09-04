import { format, formatDistanceToNowStrict, isToday, isTomorrow, parseISO } from 'date-fns'
import { fr } from 'date-fns/locale'

/** Formate un montant entier FCFA avec separateur de milliers, ex: 12500 -> "12 500 FCFA". */
export function formatFcfa(amount: number): string {
  return `${new Intl.NumberFormat('fr-FR').format(Math.round(amount))} FCFA`
}

/** Variante compacte pour les graphiques et les tuiles de statistiques. */
export function formatFcfaCompact(amount: number): string {
  if (Math.abs(amount) >= 1_000_000) return `${(amount / 1_000_000).toFixed(1).replace('.', ',')} M`
  if (Math.abs(amount) >= 1_000) return `${Math.round(amount / 1_000)} k`
  return String(amount)
}

function toDate(value: string | Date): Date {
  return typeof value === 'string' ? parseISO(value) : value
}

export function formatDateTime(value: string | Date): string {
  return format(toDate(value), "EEE d MMM 'à' HH:mm", { locale: fr })
}

export function formatTime(value: string | Date): string {
  return format(toDate(value), 'HH:mm', { locale: fr })
}

export function formatDayLong(value: string | Date): string {
  return format(toDate(value), 'EEEE d MMMM yyyy', { locale: fr })
}

export function formatDayShort(value: string | Date): string {
  return format(toDate(value), 'EEE d MMM', { locale: fr })
}

/** "Aujourd'hui", "Demain", sinon la date courte — pour les en-tetes de groupe. */
export function formatRelativeDay(value: string | Date): string {
  const d = toDate(value)
  if (isToday(d)) return "Aujourd'hui"
  if (isTomorrow(d)) return 'Demain'
  return format(d, 'EEEE d MMMM', { locale: fr })
}

export function formatFromNow(value: string | Date): string {
  return formatDistanceToNowStrict(toDate(value), { locale: fr, addSuffix: true })
}

/** Duree en minutes -> "2 h 15" / "45 min". */
export function formatDuration(minutes: number): string {
  const h = Math.floor(minutes / 60)
  const m = Math.round(minutes % 60)
  if (h === 0) return `${m} min`
  return m === 0 ? `${h} h` : `${h} h ${String(m).padStart(2, '0')}`
}

/** Compte a rebours mm:ss (acompte, renvoi d'OTP). */
export function formatCountdown(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds))
  const mm = String(Math.floor(s / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return `${mm}:${ss}`
}

export function initials(firstName: string, lastName: string): string {
  return `${firstName.charAt(0)}${lastName.charAt(0)}`.toUpperCase()
}

/** Telephone beninois : +229 01 97 12 34 56 -> groupes de 2. */
export function formatPhone(phone: string): string {
  const digits = phone.replace(/\D/g, '')
  const national = digits.startsWith('229') ? digits.slice(3) : digits
  const grouped = national.replace(/(\d{2})(?=\d)/g, '$1 ').trim()
  return digits.startsWith('229') ? `+229 ${grouped}` : grouped
}

export function formatRating(value: number): string {
  return value.toFixed(1).replace('.', ',')
}
