/**
 * Widget de paiement Kkiapay (mobile money MTN / Moov / Celtiis, carte).
 *
 * Contrat public (docs.kkiapay.me, "SDK Javascript") :
 *  - script `https://cdn.kkiapay.me/k.js` (deja autorise par la CSP, voir Caddyfile) ;
 *  - `openKkiapayWidget({ amount, key, sandbox, phone, data, theme, position, ... })` ;
 *  - `addKkiapayListener('success' | 'failed', cb)` / `removeKkiapayListener(...)` ;
 *  - la reponse "success" contient `transactionId`, l'identifiant Kkiapay de la transaction.
 *
 * Le paiement est OUVERT par ce widget (cle publique), jamais par notre serveur : le
 * serveur ne fait que preparer la reference (POST /bookings/{id}/payments/deposit), puis
 * reverifie la transaction aupres de Kkiapay - a l'arrivee du webhook, et immediatement
 * via POST /payments/{id}/confirm avec le `transactionId` remis ici. Le parametre `data`
 * est renvoye par Kkiapay dans `stateData` du webhook : c'est lui qui porte `bookingId`.
 */

const SCRIPT_URL = 'https://cdn.kkiapay.me/k.js'

type KkiapayEvent = 'success' | 'failed' | 'pending'

export interface KkiapaySuccess {
  transactionId: string
  /** Ce que nous avons passe dans `data`, renvoye tel quel par certains widgets. */
  requestData?: unknown
  [key: string]: unknown
}

export interface KkiapayFailure {
  transactionId?: string
  reason?: string
  message?: string
  [key: string]: unknown
}

interface KkiapayWidgetOptions {
  amount: number
  key: string
  /** Certaines versions du script lisent `api_key` plutot que `key` : on passe les deux. */
  api_key?: string
  sandbox?: boolean
  phone?: string
  name?: string
  email?: string
  data?: string
  theme?: string
  position?: 'left' | 'right' | 'center'
  callback?: string
  countries?: string[]
  paymentmethod?: ('momo' | 'card' | 'wallet')[]
}

declare global {
  interface Window {
    openKkiapayWidget?: (options: KkiapayWidgetOptions) => void
    addKkiapayListener?: (event: KkiapayEvent, cb: (payload: unknown) => void) => void
    removeKkiapayListener?: (event: KkiapayEvent, cb?: (payload: unknown) => void) => void
    addKkiapayCloseListener?: (cb: () => void) => void
  }
}

let loader: Promise<void> | undefined

/** Charge k.js une seule fois ; rejette si le script est bloque (CSP, hors ligne, bloqueur). */
export function loadKkiapayScript(): Promise<void> {
  if (typeof window === 'undefined') return Promise.reject(new Error('Kkiapay : pas de navigateur'))
  if (window.openKkiapayWidget) return Promise.resolve()
  if (loader) return loader
  loader = new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = SCRIPT_URL
    script.async = true
    script.onload = () => {
      if (window.openKkiapayWidget) resolve()
      else reject(new Error('Kkiapay : script charge mais openKkiapayWidget absent'))
    }
    script.onerror = () => {
      loader = undefined
      script.remove()
      reject(new Error('Kkiapay : impossible de charger le widget de paiement'))
    }
    document.head.appendChild(script)
  })
  return loader
}

export interface OpenKkiapayInput {
  amount: number
  publicKey: string
  sandbox: boolean
  phone?: string
  name?: string
  /** Donnees de correlation (bookingId...) echoees dans stateData du webhook. */
  data?: Record<string, string>
  /** Appele si l'utilisateur ferme la fenetre sans conclure (quand le widget le signale). */
  onClose?: () => void
}

/** Numero au format attendu par Kkiapay : chiffres seuls, indicatif sans "+". */
export function toKkiapayPhone(phone: string | undefined): string | undefined {
  if (!phone) return undefined
  const digits = phone.replace(/\D/g, '')
  return digits.length >= 8 ? digits : undefined
}

/**
 * Ouvre le widget et se resout a l'evenement "success" (avec `transactionId`), rejette
 * a l'evenement "failed". Les ecouteurs sont retires des qu'un evenement est recu :
 * chaque ouverture est independante (plusieurs paiements possibles par session).
 */
export async function openKkiapay(input: OpenKkiapayInput): Promise<KkiapaySuccess> {
  await loadKkiapayScript()
  const w = window
  if (!w.openKkiapayWidget || !w.addKkiapayListener) {
    throw new Error('Kkiapay : widget indisponible')
  }

  return new Promise<KkiapaySuccess>((resolve, reject) => {
    const cleanup = () => {
      w.removeKkiapayListener?.('success', onSuccess)
      w.removeKkiapayListener?.('failed', onFailed)
    }
    const onSuccess = (payload: unknown) => {
      cleanup()
      const res = (payload ?? {}) as KkiapaySuccess
      if (!res.transactionId) {
        reject(new Error('Kkiapay : succes sans transactionId'))
        return
      }
      resolve(res)
    }
    const onFailed = (payload: unknown) => {
      cleanup()
      const failure = (payload ?? {}) as KkiapayFailure
      reject(Object.assign(new Error(failure.message ?? failure.reason ?? 'Paiement refuse'), { failure }))
    }
    w.addKkiapayListener?.('success', onSuccess)
    w.addKkiapayListener?.('failed', onFailed)
    if (input.onClose) w.addKkiapayCloseListener?.(input.onClose)

    w.openKkiapayWidget?.({
      amount: Math.round(input.amount),
      key: input.publicKey,
      api_key: input.publicKey,
      sandbox: input.sandbox,
      phone: toKkiapayPhone(input.phone),
      name: input.name,
      data: input.data ? JSON.stringify(input.data) : undefined,
      theme: '#0e7c4a',
      position: 'center',
      countries: ['BJ'],
    })
  })
}
