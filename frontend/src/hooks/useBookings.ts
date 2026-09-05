import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import { isTransientError } from '@/lib/errors'
import { estimatePaymentPlan } from '@/lib/payments'
import type {
  BookingDetailResponse,
  BookingQuoteRequest,
  InitiateDepositRequest,
  PaymentMode,
  PaymentPlanResponse,
  PaymentStatusResponse,
} from '@/api/extended'
import type { BookingResponse, ConfirmPaymentRequest, CreateBookingRequest, InitiatePaymentResponse } from '@/api/types'

/** GET /api/v1/bookings : reservations de l'utilisateur, enrichies du trajet et du plan de paiement. */
export function useMyBookings(enabled = true) {
  return useQuery<BookingDetailResponse[]>({
    queryKey: ['bookings'],
    queryFn: () => apiClient.get<BookingDetailResponse[]>('/api/v1/bookings'),
    enabled,
  })
}

/** GET /api/v1/bookings/{id} */
export function useBooking(id: string | undefined) {
  return useQuery<BookingDetailResponse>({
    queryKey: ['bookings', id],
    queryFn: () => apiClient.get<BookingDetailResponse>(`/api/v1/bookings/${id}`),
    enabled: !!id,
  })
}

/**
 * POST /api/v1/trips/{id}/bookings : le mode de paiement choisi est transmis au
 * serveur, qui recalcule et renvoie le plan de paiement definitif.
 */
export function useCreateBooking(tripId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: CreateBookingRequest) =>
      apiClient.post<BookingResponse>(`/api/v1/trips/${tripId}/bookings`, input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['bookings'] })
      queryClient.invalidateQueries({ queryKey: ['trips', tripId] })
    },
  })
}

/** POST /api/v1/bookings/{id}/cancel, bascule optimiste. */
export function useCancelBooking() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (bookingId: string) => apiClient.post<BookingResponse>(`/api/v1/bookings/${bookingId}/cancel`),
    onMutate: async (bookingId) => {
      await queryClient.cancelQueries({ queryKey: ['bookings'] })
      const previous = queryClient.getQueryData<BookingDetailResponse[]>(['bookings'])
      if (previous) {
        queryClient.setQueryData<BookingDetailResponse[]>(
          ['bookings'],
          previous.map((booking) =>
            booking.id === bookingId ? { ...booking, status: 'CANCELLED_BY_PASSENGER' } : booking,
          ),
        )
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
 * POST /api/v1/trips/{id}/booking-quote : devis serveur AVANT creation.
 * Si le serveur est injoignable (reseau), on affiche une ESTIMATION locale
 * signalee comme telle. Toute autre erreur (place indisponible, droits...)
 * remonte : elle ne doit pas etre masquee derriere une estimation.
 */
export function useBookingQuote(
  tripId: string | undefined,
  input: { seats: number; dropoffStopId?: string; paymentMode: PaymentMode; unitPrice: number },
  enabled = true,
) {
  const total = input.unitPrice * input.seats
  return useQuery<{ plan: PaymentPlanResponse; estimated: boolean }>({
    queryKey: ['trips', tripId, 'quote', input.seats, input.dropoffStopId ?? '', input.paymentMode, total],
    queryFn: async () => {
      const body: BookingQuoteRequest = {
        seats: input.seats,
        dropoffStopId: input.dropoffStopId,
        paymentMode: input.paymentMode,
      }
      try {
        const plan = await apiClient.post<PaymentPlanResponse>(`/api/v1/trips/${tripId}/booking-quote`, body)
        return { plan, estimated: false }
      } catch (error) {
        if (isTransientError(error)) {
          return { plan: estimatePaymentPlan(total, input.paymentMode), estimated: true }
        }
        throw error
      }
    },
    enabled: enabled && !!tripId && total > 0,
    staleTime: 30_000,
  })
}

/** POST /api/v1/bookings/{id}/payments/deposit : prepare le paiement et renvoie les donnees du widget Kkiapay. */
export function useInitiateDeposit(bookingId: string | undefined) {
  return useMutation({
    mutationFn: (input: InitiateDepositRequest) =>
      apiClient.post<InitiatePaymentResponse>(`/api/v1/bookings/${bookingId}/payments/deposit`, input),
  })
}

/**
 * POST /api/v1/payments/{paymentId}/confirm { transactionId } : confirmation
 * immediate apres l'evenement "success" du widget Kkiapay. Le serveur
 * reverifie la transaction (statut, montant) avant de confirmer ; en cas
 * d'echec reseau ici, le sondage et le webhook prennent le relais.
 */
export function useConfirmPayment(paymentId: string | undefined) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: ConfirmPaymentRequest) =>
      apiClient.post<PaymentStatusResponse>(`/api/v1/payments/${paymentId}/confirm`, input),
    onSuccess: (status) => {
      // Le sondage lit la meme cle : l'ecran bascule sans attendre le prochain tick.
      queryClient.setQueryData<PaymentStatusResponse>(['payments', paymentId], status)
      queryClient.invalidateQueries({ queryKey: ['bookings'] })
    },
  })
}

/** GET /api/v1/payments/{paymentId} : sonde toutes les 3 s tant que le paiement n'est pas tranche. */
export function usePaymentStatus(paymentId: string | undefined) {
  return useQuery<PaymentStatusResponse>({
    queryKey: ['payments', paymentId],
    queryFn: () => apiClient.get<PaymentStatusResponse>(`/api/v1/payments/${paymentId}`),
    enabled: !!paymentId,
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status === 'SUCCEEDED' || status === 'FAILED' || status === 'EXPIRED' || status === 'REFUNDED' || status === 'REFUND_PENDING' ? false : 3000
    },
    staleTime: 0,
  })
}
