import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { apiClient } from '@/api/client'
import type { PaymentStatusResponse } from '@/api/extended'
import type { InitiatePaymentResponse, UserResponse } from '@/api/types'
import { describeError } from '@/lib/errors'
import { isKkiapayClosed, openKkiapay } from '@/lib/kkiapay'

/**
 * Parcours Kkiapay partage par la reservation (acompte) et l'abonnement
 * conducteur (audit F243) : initiation cote serveur -> widget (cle publique) ->
 * POST /payments/{id}/confirm avec le `transactionId` -> invalidations. Une seule
 * traduction des erreurs du widget ; les appelants fournissent l'initiation, le
 * payeur et les cles a invalider.
 *
 * L'evenement « success » du widget ne vaut jamais confirmation : le serveur
 * reverifie statut et montant aupres de Kkiapay avant d'activer quoi que ce soit.
 */
export interface KkiapayCheckoutOptions {
  /** Prepare le paiement cote serveur et renvoie les donnees du widget. */
  initiate: () => Promise<InitiatePaymentResponse>
  /** Payeur : numero pre-rempli dans le widget, nom et e-mail du recu. */
  payer: Pick<UserResponse, 'firstName' | 'lastName' | 'email'> & { phone?: string }
  /** Cles TanStack a invalider apres confirmation (abonnement, reservations…). */
  invalidate: readonly (readonly unknown[])[]
  /** Appele avec le paiement prepare, avant l'ouverture du widget (ex. memoriser paymentId). */
  onInitiated?: (payment: InitiatePaymentResponse) => void
  /** Confirmation serveur obtenue (statut SUCCEEDED ou non : a l'appelant de lire `status`). */
  onConfirmed?: (status: PaymentStatusResponse, payment: InitiatePaymentResponse) => void
  /**
   * Le widget a conclu mais la confirmation serveur a echoue (reseau) : le webhook
   * et le sondage prennent le relais ; l'appelant garde la reference pour le support.
   */
  onUnlinked?: (transactionId: string, payment: InitiatePaymentResponse) => void
  /** Paiement refuse par l'operateur (evenement « failed »). */
  onFailed?: () => void
}

export interface KkiapayCheckout {
  /** Lance tout le parcours ; resout a la fin, jamais de rejet (les erreurs sont traduites en toasts). */
  start: () => Promise<void>
  /** Rouvre le widget pour le DERNIER paiement prepare (fenetre fermee sans conclure). */
  reopen: () => Promise<void>
  /** Une etape est en cours (initiation, widget ouvert, confirmation). */
  busy: boolean
  /** Dernier paiement prepare, s'il existe. */
  payment: InitiatePaymentResponse | null
}

export function useKkiapayCheckout(options: KkiapayCheckoutOptions): KkiapayCheckout {
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [payment, setPayment] = useState<InitiatePaymentResponse | null>(null)
  // Les options changent a chaque rendu (fermetures) : la version courante est lue au moment de l'appel.
  const optionsRef = useRef(options)
  useEffect(() => {
    optionsRef.current = options
  })

  const runWidget = useCallback(
    async (prepared: InitiatePaymentResponse) => {
      const current = optionsRef.current
      try {
        const result = await openKkiapay({
          amount: prepared.amount,
          publicKey: prepared.kkiapayPublicKey,
          sandbox: prepared.sandbox,
          phone: current.payer.phone,
          name: `${current.payer.firstName} ${current.payer.lastName}`.trim() || undefined,
          // Le widget exige un e-mail (recu Kkiapay) : pre-rempli quand le profil en a un.
          email: current.payer.email ?? undefined,
          data: prepared.widgetData,
        })
        try {
          const status = await apiClient.post<PaymentStatusResponse>(`/api/v1/payments/${prepared.paymentId}/confirm`, {
            transactionId: result.transactionId,
          })
          queryClient.setQueryData<PaymentStatusResponse>(['payments', prepared.paymentId], status)
          for (const key of current.invalidate) await queryClient.invalidateQueries({ queryKey: [...key] })
          current.onConfirmed?.(status, prepared)
        } catch {
          current.onUnlinked?.(result.transactionId, prepared)
          toast.message('Paiement signalé, rattachement en cours', {
            description: "Nous vérifions la transaction auprès de l'opérateur. Gardez la référence affichée.",
          })
        }
      } catch (error) {
        // Fenetre fermee sans conclure : rien a signaler, elle peut etre rouverte.
        if (isKkiapayClosed(error)) return
        const message = error instanceof Error ? error.message : ''
        if (message.startsWith('Kkiapay :')) {
          toast.error('La fenêtre de paiement ne peut pas s’ouvrir', {
            description: 'Vérifiez votre connexion ou un éventuel bloqueur de contenu, puis réessayez.',
          })
          return
        }
        toast.error('Le paiement a été refusé par l’opérateur', {
          description: 'Vérifiez votre solde ou changez de numéro, puis réessayez.',
        })
        current.onFailed?.()
      }
    },
    [queryClient],
  )

  const start = useCallback(async () => {
    if (busy) return
    setBusy(true)
    try {
      let prepared: InitiatePaymentResponse
      try {
        prepared = await optionsRef.current.initiate()
      } catch (error) {
        toast.error(describeError(error, "Le paiement n'a pas pu être lancé."))
        return
      }
      setPayment(prepared)
      optionsRef.current.onInitiated?.(prepared)
      await runWidget(prepared)
    } finally {
      setBusy(false)
    }
  }, [busy, runWidget])

  const reopen = useCallback(async () => {
    if (busy || !payment) return
    setBusy(true)
    try {
      await runWidget(payment)
    } finally {
      setBusy(false)
    }
  }, [busy, payment, runWidget])

  return { start, reopen, busy, payment }
}
