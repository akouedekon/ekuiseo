import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import { DEMO_BOOKINGS, demoPaymentPlan, demoTrip } from '@/api/demo'
import { resilient, resilientMutation, type Sourced } from '@/api/resilient'
import { estimatePaymentPlan } from '@/lib/payments'
import type {
  BookingDetailResponse,
  BookingQuoteRequest,
  InitiateDepositRequest,
  PaymentMode,
  PaymentPlanResponse,
  PaymentStatusResponse,
} from '@/api/extended'
import type { BookingResponse, CreateBookingRequest, InitiatePaymentResponse } from '@/api/types'

/**
 * Liste des reservations, enrichie du trajet et du plan de paiement.
 * ATTENDU : GET /api/v1/bookings?expand=trip,paymentPlan
 * (le backend actuel renvoie BookingResponse sans le trajet : l'ecran
 * « Mes réservations » aurait alors besoin d'une requete par ligne.)
 */
export function useMyBookings() {
  return useQuery<Sourced<BookingDetailResponse[]>>({
    queryKey: ['bookings'],
    queryFn: () =>
      resilient(
        () => apiClient.get<BookingDetailResponse[]>('/api/v1/bookings?expand=trip,paymentPlan'),
        () => DEMO_BOOKINGS,
      ),
  })
}

export function useBooking(id: string | undefined) {
  return useQuery<Sourced<BookingDetailResponse>>({
    queryKey: ['bookings', id],
    queryFn: () =>
      resilient(
        () => apiClient.get<BookingDetailResponse>(`/api/v1/bookings/${id}?expand=trip,paymentPlan`),
        () => DEMO_BOOKINGS.find((b) => b.id === id) ?? DEMO_BOOKINGS[0],
      ),
    enabled: !!id,
  })
}

/**
 * Creation de reservation. Le mode de paiement choisi par le passager est
 * transmis au serveur : c'est lui qui recalcule et renvoie le plan de paiement
 * definitif (acompte, solde a bord, echeance).
 * ATTENDU : le champ `paymentMode` sur POST /api/v1/trips/{id}/bookings
 */
export type CreateBookingInput = CreateBookingRequest & { paymentMode: PaymentMode }

export function useCreateBooking(tripId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: CreateBookingInput) =>
      resilientMutation(
        () => apiClient.post<BookingResponse>(`/api/v1/trips/${tripId}/bookings`, input),
        () => {
          const trip = demoTrip(tripId)
          const amount = trip.pricePerSeat * input.seats
          return {
            id: `b-local-${Date.now()}`,
            tripId,
            passengerId: 'u-demo-me',
            seats: input.seats,
            amount,
            serviceFee: demoPaymentPlan(amount, input.paymentMode).serviceFee,
            status: input.paymentMode === 'CASH' ? ('CONFIRMED' as const) : ('PENDING_PAYMENT' as const),
            paymentMethod: input.paymentMethod,
            createdAt: new Date().toISOString(),
          }
        },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['bookings'] })
      queryClient.invalidateQueries({ queryKey: ['trips', tripId] })
    },
  })
}

export function useCancelBooking() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (bookingId: string) =>
      resilientMutation(
        () => apiClient.post<BookingResponse>(`/api/v1/bookings/${bookingId}/cancel`),
        () => ({ ...DEMO_BOOKINGS[0], id: bookingId, status: 'CANCELLED_BY_PASSENGER' as const }),
      ),
    // Bascule optimiste : la carte passe en « annulée » sans attendre le reseau.
    onMutate: async (bookingId) => {
      await queryClient.cancelQueries({ queryKey: ['bookings'] })
      const previous = queryClient.getQueryData<Sourced<BookingDetailResponse[]>>(['bookings'])
      if (previous) {
        queryClient.setQueryData<Sourced<BookingDetailResponse[]>>(['bookings'], {
          ...previous,
          data: previous.data.map((booking) =>
            booking.id === bookingId ? { ...booking, status: 'CANCELLED_BY_PASSENGER' } : booking,
          ),
        })
      }
      return { previous }
    },
    onError: (_error, _id, context) => {
      if (context?.previous) queryClient.setQueryData(['bookings'], context.previous)
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['bookings'] })
    },
  })
}

/**
 * Devis de paiement AVANT creation de la reservation.
 * ATTENDU : POST /api/v1/trips/{id}/booking-quote  -> PaymentPlanResponse
 *
 * Tant que l'endpoint n'existe pas (ou que le reseau tombe), on affiche une
 * ESTIMATION locale, signalee comme telle a l'ecran par `estimated: true`.
 * Aucun montant n'est jamais presente comme ferme s'il ne vient pas du serveur.
 */
export function useBookingQuote(
  tripId: string | undefined,
  input: { seats: number; dropoffStopId?: string; paymentMode: PaymentMode; unitPrice: number },
) {
  const total = input.unitPrice * input.seats
  return useQuery<{ plan: PaymentPlanResponse; estimated: boolean }>({
    queryKey: ['trips', tripId, 'quote', input.seats, input.dropoffStopId ?? '', input.paymentMode, total],
    queryFn: async () => {
      try {
        const body: BookingQuoteRequest = {
          seats: input.seats,
          dropoffStopId: input.dropoffStopId,
          paymentMode: input.paymentMode,
        }
        const plan = await apiClient.post<PaymentPlanResponse>(`/api/v1/trips/${tripId}/booking-quote`, body)
        return { plan, estimated: false }
      } catch {
        // Le devis n'est pas critique : on degrade en estimation plutot que
        // de bloquer le parcours de reservation.
        return { plan: estimatePaymentPlan(total, input.paymentMode), estimated: true }
      }
    },
    enabled: !!tripId && total > 0,
    staleTime: 30_000,
  })
}

/**
 * Lancement du paiement de l'acompte en mobile money.
 * ATTENDU : POST /api/v1/bookings/{id}/payments/deposit
 */
export function useInitiateDeposit(bookingId: string | undefined, expectedAmount: number) {
  return useMutation({
    mutationFn: (input: InitiateDepositRequest) =>
      resilientMutation(
        () => apiClient.post<InitiatePaymentResponse>(`/api/v1/bookings/${bookingId}/payments/deposit`, input),
        () => ({
          paymentId: `pay-local-${Date.now()}`,
          transactionRef: `EKU-${Math.random().toString(36).slice(2, 8).toUpperCase()}`,
          // Montant issu du plan de paiement en cours, jamais d'un forfait.
          amount: expectedAmount,
          kkiapayPublicKey: 'demo',
          sandbox: true,
        }),
      ),
  })
}

/**
 * Attente de confirmation du webhook operateur.
 * ATTENDU : GET /api/v1/payments/{paymentId}
 * Interrogation toutes les 3 s tant que le paiement n'est pas tranche.
 */
export function usePaymentStatus(
  paymentId: string | undefined,
  bookingId: string | undefined,
  expectedAmount: number,
) {
  return useQuery<Sourced<PaymentStatusResponse>>({
    queryKey: ['payments', paymentId],
    queryFn: () =>
      resilient(
        () => apiClient.get<PaymentStatusResponse>(`/api/v1/payments/${paymentId}`),
        () => ({
          paymentId: paymentId!,
          bookingId: bookingId ?? '',
          transactionRef: 'EKU-DEMO',
          provider: 'MTN_MOMO' as const,
          // En demonstration, le paiement se confirme au bout de ~9 s.
          status: pollElapsed(paymentId!) > 9000 ? ('SUCCEEDED' as const) : ('PROCESSING' as const),
          amount: expectedAmount,
          instruction: 'Composez *880# puis validez avec votre code secret.',
          updatedAt: new Date().toISOString(),
        }),
      ),
    enabled: !!paymentId,
    refetchInterval: (query) => {
      const status = query.state.data?.data.status
      return status === 'SUCCEEDED' || status === 'FAILED' || status === 'EXPIRED' ? false : 3000
    },
    staleTime: 0,
  })
}

/*
 * Instant de depart du sondage, memorise hors du rendu (le calcul a lieu dans
 * queryFn). Sert uniquement a simuler la latence du webhook en mode demo.
 */
const pollStarts = new Map<string, number>()

function pollElapsed(paymentId: string): number {
  const start = pollStarts.get(paymentId)
  if (start === undefined) {
    pollStarts.set(paymentId, Date.now())
    return 0
  }
  return Date.now() - start
}
