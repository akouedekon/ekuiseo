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

interface KkiapaySuccess {
  transactionId: string
  /** Ce que nous avons passe dans `data`, renvoye tel quel par certains widgets. */
  requestData?: unknown
  [key: string]: unknown
}

interface KkiapayFailure {
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
function loadKkiapayScript(): Promise<void> {
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

interface OpenKkiapayInput {
  amount: number
  publicKey: string
  sandbox: boolean
  phone?: string
  name?: string
  /** Exige par le widget (envoi du recu) ; laisse vide, il le demande a l'utilisateur. */
  email?: string
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

/** Fenetre fermee par l'utilisateur sans conclure : ni succes ni refus, rien a afficher. */
class KkiapayClosedError extends Error {
  constructor() {
    super('Kkiapay : fenetre fermee')
    this.name = 'KkiapayClosedError'
  }
}

export function isKkiapayClosed(error: unknown): boolean {
  return error instanceof KkiapayClosedError
}

/*
 * Le SDK n'expose pas de retrait pour l'ecouteur de fermeture : on en pose un
 * seul, une fois pour toutes, qui delegue a l'ouverture en cours. Sans cela,
 * chaque ouverture empilait un ecouteur de plus (audit F136).
 */
let currentClose: (() => void) | null = null
let closeListenerInstalled = false

function ensureCloseListener(w: Window) {
  if (closeListenerInstalled || !w.addKkiapayCloseListener) return
  closeListenerInstalled = true
  w.addKkiapayCloseListener(() => currentClose?.())
}

/** Reserve aux tests : oublie l'ecouteur de fermeture pose sur une fenetre precedente. */
export function resetKkiapayListenersForTests(): void {
  currentClose = null
  closeListenerInstalled = false
  loader = undefined
}

/**
 * Ouvre le widget et se resout a l'evenement "success" (avec `transactionId`).
 * Rejette a l'evenement "failed" (paiement refuse) ou a la fermeture de la fenetre
 * sans conclure (`KkiapayClosedError`, a ignorer cote appelant). La promesse est
 * TOUJOURS reglee, et les ecouteurs sont retires dans un `settle()` unique :
 * chaque ouverture est independante (plusieurs paiements possibles par session).
 */
export async function openKkiapay(input: OpenKkiapayInput): Promise<KkiapaySuccess> {
  await loadKkiapayScript()
  const w = window
  if (!w.openKkiapayWidget || !w.addKkiapayListener) {
    throw new Error('Kkiapay : widget indisponible')
  }

  return new Promise<KkiapaySuccess>((resolve, reject) => {
    let settled = false
    const settle = (outcome: { ok: true; value: KkiapaySuccess } | { ok: false; error: Error }) => {
      if (settled) return
      settled = true
      w.removeKkiapayListener?.('success', onSuccess)
      w.removeKkiapayListener?.('failed', onFailed)
      if (currentClose === onClose) currentClose = null
      if (outcome.ok) resolve(outcome.value)
      else reject(outcome.error)
    }
    const onSuccess = (payload: unknown) => {
      const res = (payload ?? {}) as KkiapaySuccess
      if (!res.transactionId) {
        settle({ ok: false, error: new Error('Kkiapay : succes sans transactionId') })
        return
      }
      settle({ ok: true, value: res })
    }
    const onFailed = (payload: unknown) => {
      const failure = (payload ?? {}) as KkiapayFailure
      settle({
        ok: false,
        error: Object.assign(new Error(failure.message ?? failure.reason ?? 'Paiement refuse'), { failure }),
      })
    }
    const onClose = () => {
      input.onClose?.()
      settle({ ok: false, error: new KkiapayClosedError() })
    }
    w.addKkiapayListener?.('success', onSuccess)
    w.addKkiapayListener?.('failed', onFailed)
    ensureCloseListener(w)
    currentClose = onClose

    try {
      w.openKkiapayWidget?.({
        amount: Math.round(input.amount),
        key: input.publicKey,
        api_key: input.publicKey,
        sandbox: input.sandbox,
        phone: toKkiapayPhone(input.phone),
        name: input.name,
        email: input.email,
        data: input.data ? JSON.stringify(input.data) : undefined,
        theme: '#0e7c4a',
        position: 'center',
        countries: ['BJ'],
      })
    } catch (error) {
      settle({ ok: false, error: error instanceof Error ? error : new Error('Kkiapay : ouverture impossible') })
    }
  })
}
