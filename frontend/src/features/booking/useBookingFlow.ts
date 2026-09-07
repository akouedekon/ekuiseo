import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { toast } from 'sonner'
import type { PaymentMode, PaymentProvider } from '@/api/extended'
import { useMe } from '@/hooks/useAuth'
import {
  useBooking,
  useBookingQuote,
  useCreateBooking,
  useInitiateDeposit,
  useMyBookings,
  usePaymentStatus,
} from '@/hooks/useBookings'
import { useKkiapayCheckout } from '@/hooks/useKkiapayCheckout'
import { useTrip, useTripStops } from '@/hooks/useTrips'
import { describeError, errorStatus } from '@/lib/errors'
import { estimatePaymentPlan } from '@/lib/payments'
import { phoneSchema, toE164 } from '@/lib/validation'

/**
 * Etapes du tunnel. Les trois premieres sont pilotees par l'utilisateur ; les
 * suivantes sont des ISSUES decidees par le serveur (statut de la reservation ou
 * du paiement) et l'emportent toujours sur l'etape locale.
 */
export type BookingStep = 'recap' | 'payment' | 'waiting' | 'confirmed' | 'failed' | 'expired' | 'cancelled' | 'refund'

/** Position de chaque etape sur l'indicateur a trois crans (Recapitulatif, Paiement, Confirmation). */
export const STEP_INDEX: Record<BookingStep, number> = {
  recap: 0,
  payment: 1,
  waiting: 1,
  confirmed: 2,
  failed: 1,
  expired: 1,
  cancelled: 1,
  refund: 1,
}

export const BOOKING_STEPS = ['Récapitulatif', 'Paiement', 'Confirmation'] as const

/**
 * Machine a etats du tunnel de reservation (audit F142) : requetes, etat local,
 * issues decidees par le serveur et actions. Les composants d'etape
 * (features/booking/*Step.tsx) ne font que rendre ce qu'elle expose.
 *
 * La reservation creee vit dans l'URL (?booking=) : un rafraichissement, une
 * expiration de session ou un retour depuis « Mes trajets » reprennent le tunnel
 * au lieu de recreer une reservation (409 « deja une reservation active »).
 */
export function useBookingFlow(tripId: string | undefined) {
  const [searchParams, setSearchParams] = useSearchParams()
  const bookingId = searchParams.get('booking') ?? undefined
  // Places demandees a la recherche, propagees par la fiche (audit F249) ; bornees par le trajet une fois charge.
  const requestedSeats = Math.max(1, Math.min(8, Number(searchParams.get('seats')) || 1))
  const trip = useTrip(tripId)
  const stops = useTripStops(tripId)
  const me = useMe()

  const [flowStep, setFlowStep] = useState<BookingStep>('recap')
  const [seats, setSeats] = useState(requestedSeats)
  const [dropoffStopId, setDropoffStopId] = useState<string>('')
  const [paymentMode, setPaymentMode] = useState<PaymentMode>('MOMO_DEPOSIT')
  const [provider, setProvider] = useState<PaymentProvider>('MTN_MOMO')
  // `null` = l'utilisateur n'a rien saisi : on retombe sur le numero du compte.
  const [phoneInput, setPhoneInput] = useState<string | null>(null)
  const [phoneError, setPhoneError] = useState<string>()
  const [paymentId, setPaymentId] = useState<string>()
  // Identifiant Kkiapay remis par le widget mais dont le rattachement serveur a echoue (reseau).
  const [unlinkedTransactionId, setUnlinkedTransactionId] = useState<string>()
  // Compte a rebours local ecoule : on interroge le serveur, seul juge de l'expiration.
  const [localExpired, setLocalExpired] = useState(false)
  // 409 a la creation : une reservation active existe deja sur ce trajet.
  const [conflict, setConflict] = useState(false)

  const data = trip.data
  const stopList = stops.data ?? []
  const selectedStop = stopList.find((s) => s.id === dropoffStopId)
  const unitPrice = selectedStop ? selectedStop.priceFromOrigin : (data?.pricePerSeat ?? 0)
  /* Le paiement en especes est reserve aux conducteurs a identite verifiee (le serveur repond 400 sinon). */
  const cashAllowed = data?.driver.identityVerified === true

  /*
   * Montants affiches — ordre de priorite strict :
   *  1. le plan renvoye par le serveur pour la reservation creee (fait foi) ;
   *  2. sinon le devis serveur demande avant reservation ;
   *  3. sinon une estimation locale, explicitement etiquetee comme telle.
   * Aucun montant n'est ecrit en dur nulle part.
   */
  const quote = useBookingQuote(
    tripId,
    { seats, dropoffStopId: dropoffStopId || undefined, paymentMode, unitPrice },
    !bookingId,
  )
  const booking = useBooking(bookingId, {
    refetchInterval: localExpired && bookingId ? 5_000 : false,
  })
  const serverPlan = booking.data?.paymentPlan
  const plan = serverPlan ?? quote.data?.plan ?? estimatePaymentPlan(unitPrice * seats, paymentMode)
  const planIsEstimate = !serverPlan && (quote.data?.estimated ?? true)

  const createBooking = useCreateBooking(tripId ?? '')
  const initiateDeposit = useInitiateDeposit(bookingId)
  const paymentStatus = usePaymentStatus(paymentId)
  // Reservation existante a reprendre, cherchee seulement apres un 409.
  const myBookings = useMyBookings(conflict)
  const existing = conflict
    ? myBookings.data?.find(
        (b) => b.tripId === tripId && (b.status === 'PENDING_PAYMENT' || b.status === 'CONFIRMED'),
      )
    : undefined

  const phone = phoneInput ?? me.data?.phone ?? ''

  /* Places demandees bornees par la disponibilite reelle (audit F249). */
  const seatsAvailable = data?.seatsAvailable
  useEffect(() => {
    if (seatsAvailable !== undefined && seats > Math.max(1, seatsAvailable)) setSeats(Math.max(1, seatsAvailable))
  }, [seatsAvailable, seats])

  /* Le mode especes n'est plus propose : on revient au mode par defaut sans rien envoyer. */
  useEffect(() => {
    if (data && !cashAllowed && paymentMode === 'CASH' && !bookingId) setPaymentMode('MOMO_DEPOSIT')
  }, [data, cashAllowed, paymentMode, bookingId])

  /*
   * Reprise : la reservation existe deja (URL). On aligne places et mode sur ses
   * vraies valeurs et, si l'acompte est encore attendu, on reprend au paiement.
   * Fait une seule fois par reservation (ref), pour ne pas ramener au paiement un
   * utilisateur qui navigue dans le tunnel.
   */
  const initialisedFor = useRef<string>(undefined)
  const loaded = booking.data
  useEffect(() => {
    if (!loaded || initialisedFor.current === loaded.id) return
    initialisedFor.current = loaded.id
    setSeats(loaded.seats)
    setPaymentMode(loaded.paymentPlan.paymentMethod)
    if (loaded.status === 'PENDING_PAYMENT') setFlowStep('payment')
  }, [loaded])

  /* Quand le serveur a tranche, plus besoin de le sonder. */
  const bookingStatus = booking.data?.status
  useEffect(() => {
    if (localExpired && bookingStatus && bookingStatus !== 'PENDING_PAYMENT') setLocalExpired(false)
  }, [localExpired, bookingStatus])

  /*
   * Issue decidee par le serveur : statut de la reservation d'abord (il integre
   * le webhook et l'expiration), statut du paiement ensuite (plus rapide pour
   * l'echec). Aucune de ces transitions ne repose sur l'horloge du client.
   */
  const payStatus = paymentStatus.data?.status
  const outcome: BookingStep | null = (() => {
    if (bookingStatus === 'EXPIRED' || serverPlan?.paymentStatus === 'EXPIRED') return 'expired'
    if (bookingStatus === 'CANCELLED_BY_PASSENGER' || bookingStatus === 'CANCELLED_BY_DRIVER') return 'cancelled'
    if (bookingStatus === 'CONFIRMED' || bookingStatus === 'COMPLETED') return 'confirmed'
    if (payStatus === 'SUCCEEDED') return 'confirmed'
    if (payStatus === 'REFUNDED' || payStatus === 'REFUND_PENDING') return 'refund'
    if (payStatus === 'EXPIRED') return 'expired'
    if (payStatus === 'FAILED') return 'failed'
    return null
  })()
  const step: BookingStep = outcome ?? flowStep

  /*
   * Echeance de l'acompte : celle du serveur (paymentPlan.depositDueAt), et elle
   * seule (audit F154). Tant qu'elle n'est pas connue, pas de compte a rebours :
   * aucune constante locale ne doit contredire le serveur. La duree totale de la
   * fenetre (pour la barre) est echeance - creation.
   */
  const depositDueAt = booking.data?.paymentPlan.depositDueAt ?? null
  const deadline = depositDueAt ? new Date(depositDueAt).getTime() : null
  const windowSeconds =
    booking.data && deadline
      ? Math.max(60, Math.round((deadline - new Date(booking.data.createdAt).getTime()) / 1000))
      : 20 * 60

  /*
   * Parcours Kkiapay partage (audit F243) : initiation -> widget -> confirmation
   * serveur. Le sondage (`usePaymentStatus`) et le webhook restent les juges.
   */
  const checkout = useKkiapayCheckout({
    initiate: () => initiateDeposit.mutateAsync({ provider, phone: toE164(phone) ?? phone }),
    payer: {
      firstName: me.data?.firstName ?? '',
      lastName: me.data?.lastName ?? '',
      email: me.data?.email ?? null,
      phone,
    },
    invalidate: [['bookings']],
    onInitiated: (payment) => {
      setPaymentId(payment.paymentId)
      setUnlinkedTransactionId(undefined)
      setFlowStep('waiting')
    },
    onUnlinked: (transactionId) => setUnlinkedTransactionId(transactionId),
    onFailed: () => setFlowStep('payment'),
  })

  /** « Recommencer » : tout est remis a zero, y compris l'URL (plus de ?booking=). */
  const restart = () => {
    initialisedFor.current = undefined
    setPaymentId(undefined)
    setUnlinkedTransactionId(undefined)
    setLocalExpired(false)
    setConflict(false)
    setPhoneError(undefined)
    setFlowStep('recap')
    setSearchParams({}, { replace: true })
  }

  const goToPayment = () => {
    setConflict(false)
    createBooking.mutate(
      {
        seats,
        dropoffStopId: dropoffStopId || undefined,
        paymentMode,
      },
      {
        onSuccess: (created) => {
          initialisedFor.current = created.id
          setFlowStep(paymentMode === 'CASH' ? 'confirmed' : 'payment')
          // La reservation entre dans l'URL : un rafraichissement reprend ici.
          setSearchParams({ booking: created.id }, { replace: true })
        },
        onError: (error) => {
          if (errorStatus(error) === 409) {
            setConflict(true)
            return
          }
          toast.error(describeError(error, "La demande n'a pas pu être envoyée. Réessayez."))
        },
      },
    )
  }

  const submitDeposit = () => {
    const parsed = phoneSchema.safeParse(phone)
    if (!parsed.success) {
      setPhoneError(parsed.error.issues[0]?.message ?? 'Numéro mobile money incomplet')
      return
    }
    setPhoneError(undefined)
    void checkout.start()
  }

  /** Nouvelle tentative de paiement sur la MEME reservation (encore en attente cote serveur). */
  const retryPayment = () => {
    setPaymentId(undefined)
    setUnlinkedTransactionId(undefined)
    setFlowStep('payment')
  }

  return {
    tripId,
    bookingId,
    trip,
    stops,
    me,
    data,
    stopList,
    selectedStop,
    unitPrice,
    cashAllowed,
    quote,
    booking,
    bookingStatus,
    plan,
    planIsEstimate,
    createBooking,
    initiateDeposit,
    paymentStatus,
    myBookings,
    existing,
    conflict,
    step,
    seats,
    setSeats,
    dropoffStopId,
    setDropoffStopId,
    paymentMode,
    setPaymentMode,
    provider,
    setProvider,
    phone,
    setPhoneInput,
    phoneError,
    unlinkedTransactionId,
    localExpired,
    setLocalExpired,
    deadline,
    windowSeconds,
    checkout,
    restart,
    goToPayment,
    submitDeposit,
    retryPayment,
  }
}

export type BookingFlow = ReturnType<typeof useBookingFlow>
