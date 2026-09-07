import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { BookingDetailResponse } from '@/api/extended'
import { createTestQueryClient, installFakeApi } from '@/test/api'
import { useCancelBooking, useMyBookings } from './useBookings'

function booking(id: string, status: BookingDetailResponse['status']): BookingDetailResponse {
  return {
    id,
    tripId: 't1',
    passengerId: 'u1',
    seats: 1,
    amount: 5000,
    serviceFee: 400,
    status,
    paymentMethod: 'MOMO_DEPOSIT',
    createdAt: '2026-09-01T08:00:00Z',
    paymentPlan: {
      totalAmount: 5000,
      serviceFee: 400,
      depositAmount: 1000,
      balanceAmount: 4000,
      paymentMethod: 'MOMO_DEPOSIT',
      paymentStatus: 'DEPOSIT_PAID',
      depositDueAt: null,
      freeCancellationHours: 24,
    },
    trip: {
      id: 't1',
      tripType: 'INTERURBAIN',
      originLabel: 'Cotonou',
      destLabel: 'Bohicon',
      departureAt: '2026-12-01T07:00:00Z',
      pricePerSeat: 5000,
      driver: { id: 'd1', firstName: 'Koffi', lastName: 'A', photoUrl: null, ratingAvg: 4.5 },
      vehicle: { brand: 'Toyota', model: 'Corolla', color: null, comfortLevel: 'COMFORT' },
    },
    unreadMessages: 0,
    reviewedByMe: false,
  }
}

describe('useCancelBooking', () => {
  afterEach(() => vi.unstubAllGlobals())

  function setup(cancelStatus: number) {
    const client = createTestQueryClient()
    let listCalls = 0
    const api = installFakeApi({
      // Apres un succes, le serveur renvoie la reservation annulee ; apres un refus, elle reste confirmee.
      '/api/v1/bookings': () => {
        listCalls += 1
        return { body: [booking('b1', listCalls > 1 && cancelStatus < 400 ? 'CANCELLED_BY_PASSENGER' : 'CONFIRMED')] }
      },
      'POST /api/v1/bookings/b1/cancel': { status: cancelStatus, body: cancelStatus >= 400 ? { status: cancelStatus, detail: 'Refus' } : { id: 'b1', status: 'CANCELLED_BY_PASSENGER' } },
    })
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
    return { client, api, wrapper, listCalls: () => listCalls }
  }

  it('bascule la liste tout de suite puis la reinvalide au succes', async () => {
    const { wrapper, client, api, listCalls } = setup(200)
    const list = renderHook(() => useMyBookings(), { wrapper })
    await waitFor(() => expect(list.result.current.isSuccess).toBe(true))
    expect(list.result.current.data?.[0].status).toBe('CONFIRMED')

    const cancel = renderHook(() => useCancelBooking(), { wrapper })
    act(() => cancel.result.current.mutate('b1'))
    // Bascule optimiste : sans attendre la reponse, la reservation apparait annulee.
    await waitFor(() =>
      expect(client.getQueryData<BookingDetailResponse[]>(['bookings'])?.[0].status).toBe('CANCELLED_BY_PASSENGER'),
    )
    await waitFor(() => expect(cancel.result.current.isSuccess).toBe(true))
    // Invalidation : la liste est relue depuis le serveur.
    await waitFor(() => expect(listCalls()).toBeGreaterThanOrEqual(2))
    expect(api.calls().some((c) => c.method === 'POST' && c.url.endsWith('/api/v1/bookings/b1/cancel'))).toBe(true)
  })

  it('restaure la liste precedente si le serveur refuse', async () => {
    const { wrapper, client } = setup(409)
    const list = renderHook(() => useMyBookings(), { wrapper })
    await waitFor(() => expect(list.result.current.isSuccess).toBe(true))

    const cancel = renderHook(() => useCancelBooking(), { wrapper })
    act(() => cancel.result.current.mutate('b1'))
    await waitFor(() => expect(cancel.result.current.isError).toBe(true))
    // Retour arriere : la reservation est de nouveau confirmee dans le cache, puis relue telle quelle.
    await waitFor(() => expect(client.getQueryData<BookingDetailResponse[]>(['bookings'])?.[0].status).toBe('CONFIRMED'))
    await waitFor(() => expect(list.result.current.isFetching).toBe(false))
    expect(list.result.current.data?.[0].status).toBe('CONFIRMED')
  })
})
