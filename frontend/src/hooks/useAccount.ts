import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import type {
  DriverBalanceResponse,
  IdentityVerificationResponse,
  PaymentMethodResponse,
  PaymentProvider,
  PayoutResponse,
  SubscriptionResponse,
  UserPreferencesResponse,
} from '@/api/extended'
import type { InitiatePaymentResponse, UserResponse, VehicleRequest, VehicleResponse } from '@/api/types'

/* ------------------------------------------------------------- Vehicules */

/** GET /api/v1/me/vehicles */
export function useMyVehicles(enabled = true) {
  return useQuery<VehicleResponse[]>({
    queryKey: ['me', 'vehicles'],
    queryFn: () => apiClient.get<VehicleResponse[]>('/api/v1/me/vehicles'),
    enabled,
  })
}

/** POST /api/v1/me/vehicles */
export function useAddVehicle() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: VehicleRequest) => apiClient.post<VehicleResponse>('/api/v1/me/vehicles', input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['me', 'vehicles'] })
    },
  })
}

/** DELETE /api/v1/me/vehicles/{id}, retrait optimiste. */
export function useDeleteVehicle() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (vehicleId: string) => apiClient.delete<void>(`/api/v1/me/vehicles/${vehicleId}`),
    onMutate: async (vehicleId) => {
      await queryClient.cancelQueries({ queryKey: ['me', 'vehicles'] })
      const previous = queryClient.getQueryData<VehicleResponse[]>(['me', 'vehicles'])
      if (previous) {
        queryClient.setQueryData<VehicleResponse[]>(
          ['me', 'vehicles'],
          previous.filter((v) => v.id !== vehicleId),
        )
      }
      return { previous }
    },
    onError: (_e, _id, context) => {
      if (context?.previous) queryClient.setQueryData(['me', 'vehicles'], context.previous)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['me', 'vehicles'] }),
  })
}

/* ------------------------------------------------------------- Profil */

export interface UpdateProfileInput {
  firstName?: string
  lastName?: string
  email?: string | null
  bio?: string | null
  photoUrl?: string | null
}

/** PATCH /api/v1/me */
export function useUpdateProfile() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: UpdateProfileInput) => apiClient.patch<UserResponse>('/api/v1/me', input),
    onSuccess: (user) => {
      queryClient.setQueryData<UserResponse>(['me'], user)
    },
  })
}

/* -------------------------------------------------------- Preferences */

/** GET /api/v1/me/preferences */
export function useMyPreferences(enabled = true) {
  return useQuery<UserPreferencesResponse>({
    queryKey: ['me', 'preferences'],
    queryFn: () => apiClient.get<UserPreferencesResponse>('/api/v1/me/preferences'),
    enabled,
  })
}

/** PATCH /api/v1/me/preferences, bascule optimiste (les interrupteurs repondent instantanement). */
export function useUpdatePreferences() {
  const queryClient = useQueryClient()
  const key = ['me', 'preferences']
  return useMutation({
    mutationFn: (input: Partial<UserPreferencesResponse>) =>
      apiClient.patch<UserPreferencesResponse>('/api/v1/me/preferences', input),
    onMutate: async (input) => {
      await queryClient.cancelQueries({ queryKey: key })
      const previous = queryClient.getQueryData<UserPreferencesResponse>(key)
      if (previous) {
        queryClient.setQueryData<UserPreferencesResponse>(key, { ...previous, ...input })
      }
      return { previous }
    },
    onError: (_e, _v, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous)
    },
    onSuccess: (prefs) => {
      queryClient.setQueryData<UserPreferencesResponse>(key, prefs)
    },
  })
}

/* ------------------------------------------------ Moyens de paiement */

/** GET /api/v1/me/payment-methods */
export function useMyPaymentMethods(enabled = true) {
  return useQuery<PaymentMethodResponse[]>({
    queryKey: ['me', 'payment-methods'],
    queryFn: () => apiClient.get<PaymentMethodResponse[]>('/api/v1/me/payment-methods'),
    enabled,
  })
}

/** POST /api/v1/me/payment-methods */
export function useAddPaymentMethod() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: { provider: PaymentProvider; phone: string; label?: string }) =>
      apiClient.post<PaymentMethodResponse>('/api/v1/me/payment-methods', input),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['me', 'payment-methods'] }),
  })
}

/** DELETE /api/v1/me/payment-methods/{id} */
export function useDeletePaymentMethod() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => apiClient.delete<void>(`/api/v1/me/payment-methods/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['me', 'payment-methods'] }),
  })
}

/* ------------------------------------------------ Verification d'identite */

/** GET /api/v1/me/identity */
export function useIdentityVerification(enabled = true) {
  return useQuery<IdentityVerificationResponse>({
    queryKey: ['me', 'identity'],
    queryFn: () => apiClient.get<IdentityVerificationResponse>('/api/v1/me/identity'),
    enabled,
  })
}

/** POST /api/v1/me/identity (type et numero de piece ; le televersement du document n'existe pas encore cote serveur). */
export function useSubmitIdentity() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: { documentType: 'CNI' | 'PASSPORT' | 'DRIVER_LICENSE'; documentNumber: string }) =>
      apiClient.post<IdentityVerificationResponse>('/api/v1/me/identity', input),
    onSuccess: (identity) => {
      queryClient.setQueryData<IdentityVerificationResponse>(['me', 'identity'], identity)
      queryClient.invalidateQueries({ queryKey: ['me'] })
    },
  })
}

/* ------------------------------------------------ Revenus du conducteur */

/** GET /api/v1/me/payouts/balance : solde net en attente et seuil de reversement. */
export function useDriverBalance(enabled = true) {
  return useQuery<DriverBalanceResponse>({
    queryKey: ['me', 'payouts', 'balance'],
    queryFn: () => apiClient.get<DriverBalanceResponse>('/api/v1/me/payouts/balance'),
    enabled,
  })
}

/** GET /api/v1/me/payouts : historique des reversements. */
export function useMyPayouts(enabled = true) {
  return useQuery<PayoutResponse[]>({
    queryKey: ['me', 'payouts'],
    queryFn: () => apiClient.get<PayoutResponse[]>('/api/v1/me/payouts'),
    enabled,
  })
}

/* ------------------------------------------------ Abonnement conducteur */

/** GET /api/v1/me/subscription */
export function useMySubscription(enabled = true) {
  return useQuery<SubscriptionResponse>({
    queryKey: ['me', 'subscription'],
    queryFn: () => apiClient.get<SubscriptionResponse>('/api/v1/me/subscription'),
    enabled,
  })
}

/** POST /api/v1/me/subscription : ouvre un abonnement en attente de paiement et renvoie les donnees du widget. */
export function useSubscribe() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiClient.post<InitiatePaymentResponse>('/api/v1/me/subscription'),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['me', 'subscription'] }),
  })
}
