import { formatDistanceToNowStrict, parseISO } from 'date-fns'
import { fr } from 'date-fns/locale'
import { formatInTimeZone, getTimezoneOffset } from 'date-fns-tz'

/**
 * Fuseau de reference du produit (audit F424) : toutes les heures affichees et
 * saisies sont celles du Benin (`Africa/Porto-Novo`, UTC+1 toute l'annee, meme
 * regle que `common/Tz` cote serveur), jamais celles du navigateur. Un membre de
 * la diaspora ou un telephone mal regle voit donc la meme heure que le conducteur.
 */
export const BENIN_TIME_ZONE = 'Africa/Porto-Novo'

/** Mention a afficher a cote d'une heure quand l'horloge de l'appareil ne suit pas le Benin. */
export const BENIN_TIME_HINT = 'heure du Bénin'

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

/** Formatage dans le fuseau du Benin, locale francaise. */
function fmt(value: string | Date, pattern: string): string {
  return formatInTimeZone(toDate(value), BENIN_TIME_ZONE, pattern, { locale: fr })
}

export function formatDateTime(value: string | Date): string {
  return fmt(value, "EEE d MMM 'à' HH:mm")
}

export function formatTime(value: string | Date): string {
  return fmt(value, 'HH:mm')
}

export function formatDayShort(value: string | Date): string {
  return fmt(value, 'EEE d MMM')
}

/** Jour civil au Benin, « AAAA-MM-JJ » : la cle de comparaison entre deux instants. */
export function toInputDate(value: string | Date): string {
  return fmt(value, 'yyyy-MM-dd')
}

/** Heure au Benin, « HH:MM », pour un champ `<input type="time">`. */
export function toInputTime(value: string | Date): string {
  return fmt(value, 'HH:mm')
}

/** "Aujourd'hui", "Demain", sinon la date courte — pour les en-tetes de groupe. Jours comptes au Benin. */
export function formatRelativeDay(value: string | Date): string {
  const day = toInputDate(value)
  const now = Date.now()
  if (day === toInputDate(new Date(now))) return "Aujourd'hui"
  if (day === toInputDate(new Date(now + 24 * 60 * 60 * 1000))) return 'Demain'
  return fmt(value, 'EEEE d MMMM')
}

export function formatFromNow(value: string | Date): string {
  return formatDistanceToNowStrict(toDate(value), { locale: fr, addSuffix: true })
}

/**
 * Vrai quand l'horloge de l'appareil ne suit pas l'heure du Benin (diaspora,
 * telephone regle sur un autre fuseau) : les ecrans ajoutent alors la mention
 * « heure du Benin » a cote des horaires. Compare les decalages, pas les noms :
 * Lagos ou Niamey (UTC+1 aussi) n'appellent aucune mention.
 */
export function deviceClockDiffersFromBenin(at: Date = new Date()): boolean {
  return -at.getTimezoneOffset() * 60_000 !== getTimezoneOffset(BENIN_TIME_ZONE, at)
}

/** Duree en minutes -> "2 h 15" / "45 min". */
export function formatDuration(minutes: number): string {
  const h = Math.floor(minutes / 60)
  const m = Math.round(minutes % 60)
  if (h === 0) return `${m} min`
  return m === 0 ? `${h} h` : `${h} h ${String(m).padStart(2, '0')}`
}

/** Distance courte en km -> « 800 m » sous 1 km, « 2,4 km » au-dela (au dixieme, sans decimale a partir de 10 km). */
export function formatDistanceKm(km: number): string {
  const safe = Math.max(0, km)
  if (safe < 1) return `${Math.max(10, Math.round(safe * 100) * 10)} m`
  if (safe < 10) return `${(Math.round(safe * 10) / 10).toLocaleString('fr-FR')} km`
  return `${Math.round(safe)} km`
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
