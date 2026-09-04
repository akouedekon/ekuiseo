import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import {
  DEMO_IDENTITY,
  DEMO_PAYMENT_METHODS,
  DEMO_PREFERENCES,
  DEMO_VEHICLES,
} from '@/api/demo'
import { resilient, resilientMutation, type Sourced } from '@/api/resilient'
import type {
  IdentityVerificationResponse,
  PaymentMethodResponse,
  PaymentProvider,
  UserPreferencesResponse,
} from '@/api/extended'
import type { UserResponse, VehicleRequest, VehicleResponse } from '@/api/types'

/* ------------------------------------------------------------- Vehicules */

export function useMyVehicles() {
  return useQuery<Sourced<VehicleResponse[]>>({
    queryKey: ['me', 'vehicles'],
    queryFn: () => resilient(() => apiClient.get<VehicleResponse[]>('/api/v1/me/vehicles'), () => DEMO_VEHICLES),
  })
}

export function useAddVehicle() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: VehicleRequest) =>
      resilientMutation(
        () => apiClient.post<VehicleResponse>('/api/v1/me/vehicles', input),
        () => ({
          id: `v-local-${Date.now()}`,
          brand: input.brand,
          model: input.model,
          color: input.color ?? null,
          plate: input.plate,
          seats: input.seats,
          comfortLevel: input.comfortLevel,
          photoUrl: input.photoUrl ?? null,
          verified: false,
        }),
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['me', 'vehicles'] })
    },
  })
}

/** ATTENDU : DELETE /api/v1/me/vehicles/{id} */
export function useDeleteVehicle() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (vehicleId: string) =>
      resilientMutation(() => apiClient.delete<void>(`/api/v1/me/vehicles/${vehicleId}`), () => undefined),
    onMutate: async (vehicleId) => {
      await queryClient.cancelQueries({ queryKey: ['me', 'vehicles'] })
      const previous = queryClient.getQueryData<Sourced<VehicleResponse[]>>(['me', 'vehicles'])
      if (previous) {
        queryClient.setQueryData<Sourced<VehicleResponse[]>>(['me', 'vehicles'], {
          ...previous,
          data: previous.data.filter((v) => v.id !== vehicleId),
        })
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

/** ATTENDU : PATCH /api/v1/me */
export function useUpdateProfile() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: UpdateProfileInput) =>
      resilientMutation(
        () => apiClient.patch<UserResponse>('/api/v1/me', input),
        () => ({ ...(queryClient.getQueryData<UserResponse>(['me']) as UserResponse), ...input }),
      ),
    onSuccess: (user) => {
      queryClient.setQueryData(['me'], user)
    },
  })
}

/* -------------------------------------------------------- Preferences */

/** ATTENDU : GET/PATCH /api/v1/me/preferences */
export function useMyPreferences() {
  return useQuery<Sourced<UserPreferencesResponse>>({
    queryKey: ['me', 'preferences'],
    queryFn: () =>
      resilient(() => apiClient.get<UserPreferencesResponse>('/api/v1/me/preferences'), () => DEMO_PREFERENCES),
  })
}

export function useUpdatePreferences() {
  const queryClient = useQueryClient()
  const key = ['me', 'preferences']
  return useMutation({
    mutationFn: (input: Partial<UserPreferencesResponse>) =>
      resilientMutation(
        () => apiClient.patch<UserPreferencesResponse>('/api/v1/me/preferences', input),
        () => ({ ...DEMO_PREFERENCES, ...queryClient.getQueryData<Sourced<UserPreferencesResponse>>(key)?.data, ...input }),
      ),
    // Les interrupteurs doivent repondre instantanement.
    onMutate: async (input) => {
      await queryClient.cancelQueries({ queryKey: key })
      const previous = queryClient.getQueryData<Sourced<UserPreferencesResponse>>(key)
      if (previous) {
        queryClient.setQueryData<Sourced<UserPreferencesResponse>>(key, {
          ...previous,
          data: { ...previous.data, ...input },
        })
      }
      return { previous }
    },
    onError: (_e, _v, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous)
    },
  })
}

/* ------------------------------------------------ Moyens de paiement */

/** ATTENDU : GET/POST/DELETE /api/v1/me/payment-methods */
export function useMyPaymentMethods() {
  return useQuery<Sourced<PaymentMethodResponse[]>>({
    queryKey: ['me', 'payment-methods'],
    queryFn: () =>
      resilient(() => apiClient.get<PaymentMethodResponse[]>('/api/v1/me/payment-methods'), () => DEMO_PAYMENT_METHODS),
  })
}

export function useAddPaymentMethod() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: { provider: PaymentProvider; phone: string; label?: string }) =>
      resilientMutation(
        () => apiClient.post<PaymentMethodResponse>('/api/v1/me/payment-methods', input),
        () => ({
          id: `pm-local-${Date.now()}`,
          provider: input.provider,
          phone: input.phone,
          label: input.label ?? null,
          isDefault: false,
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['me', 'payment-methods'] }),
  })
}

export function useDeletePaymentMethod() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      resilientMutation(() => apiClient.delete<void>(`/api/v1/me/payment-methods/${id}`), () => undefined),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['me', 'payment-methods'] }),
  })
}

/* ------------------------------------------------ Verification d'identite */

/** ATTENDU : GET /api/v1/me/identity — POST /api/v1/me/identity */
export function useIdentityVerification() {
  return useQuery<Sourced<IdentityVerificationResponse>>({
    queryKey: ['me', 'identity'],
    queryFn: () =>
      resilient(() => apiClient.get<IdentityVerificationResponse>('/api/v1/me/identity'), () => DEMO_IDENTITY),
  })
}

export function useSubmitIdentity() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: { documentType: 'CNI' | 'PASSPORT' | 'DRIVER_LICENSE'; documentNumber: string }) =>
      resilientMutation(
        () => apiClient.post<IdentityVerificationResponse>('/api/v1/me/identity', input),
        () => ({
          status: 'PENDING' as const,
          documentType: input.documentType,
          submittedAt: new Date().toISOString(),
          reviewedAt: null,
          rejectionReason: null,
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['me', 'identity'] }),
  })
}
