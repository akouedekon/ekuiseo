import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { authStore } from '@/api/client'
import type { BookingDetailResponse } from '@/api/extended'
import type { TripResponse } from '@/api/types'
import { createTestQueryClient, installFakeApi } from '@/test/api'
import { BookingPage } from './BookingPage'

const TRIP: TripResponse = {
  id: 't1',
  driver: { id: 'd1', firstName: 'Koffi', lastName: 'Aholou', photoUrl: null, ratingAvg: 4.6, ratingCount: 12, identityVerified: true },
  vehicle: { id: 'v1', brand: 'Toyota', model: 'Corolla', color: null, comfortLevel: 'COMFORT' },
  tripType: 'INTERURBAIN',
  originLabel: 'Cotonou',
  originLat: 6.37,
  originLng: 2.39,
  destLabel: 'Parakou',
  destLat: 9.34,
  destLng: 2.63,
  departureAt: '2026-12-01T07:00:00Z',
  seatsTotal: 4,
  seatsAvailable: 3,
  // 20 000 F : l'estimation locale de l'acompte vaudrait 1 600 F (8 %). Le serveur dit autre chose.
  pricePerSeat: 20_000,
  instantBooking: true,
  luggagePolicy: null,
  description: null,
  status: 'PUBLISHED',
  recurrenceRule: null,
  createdAt: '2026-09-01T08:00:00Z',
  parentTripId: null,
}

function bookingOf(
  status: BookingDetailResponse['status'],
  paymentStatus: BookingDetailResponse['paymentPlan']['paymentStatus'],
  approvalDeadlineAt: string | null = null,
): BookingDetailResponse {
  return {
    id: 'b1',
    tripId: 't1',
    passengerId: 'u1',
    seats: 2,
    amount: 40_000,
    serviceFee: 3_200,
    status,
    paymentMethod: 'MOMO_DEPOSIT',
    createdAt: '2026-09-01T08:00:00Z',
    paymentPlan: {
      totalAmount: 40_000,
      serviceFee: 3_200,
      // Montant volontairement different de toute estimation locale : c'est lui qui doit s'afficher.
      depositAmount: 3_205,
      balanceAmount: 36_795,
      paymentMethod: 'MOMO_DEPOSIT',
      paymentStatus,
      depositDueAt: '2026-09-01T08:20:00Z',
      freeCancellationHours: 24,
      approvalDeadlineAt,
    },
    trip: {
      id: 't1',
      tripType: 'INTERURBAIN',
      originLabel: 'Cotonou',
      destLabel: 'Parakou',
      departureAt: '2026-12-01T07:00:00Z',
      pricePerSeat: 20_000,
      driver: { id: 'd1', firstName: 'Koffi', lastName: 'Aholou', photoUrl: null, ratingAvg: 4.6 },
      vehicle: { brand: 'Toyota', model: 'Corolla', color: null, comfortLevel: 'COMFORT' },
    },
    unreadMessages: 0,
    reviewedByMe: false,
  }
}

const ME = { id: 'u1', phone: '+2290197000321', email: 'p@example.com', firstName: 'Ayo', lastName: 'K', role: 'USER' }

function LocationProbe() {
  const location = useLocation()
  return <p data-testid="location">{location.pathname + location.search}</p>
}

function renderBooking(path: string) {
  const client = createTestQueryClient()
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route
            path="/book/:tripId"
            element={
              <>
                <LocationProbe />
                <BookingPage />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('BookingPage', () => {
  beforeEach(() => authStore.setTokens('access', 'refresh'))
  afterEach(() => {
    authStore.clear('logout')
    vi.unstubAllGlobals()
  })

  it('affiche le plan de paiement lu de l API pour une reservation confirmee, jamais l estimation locale', async () => {
    installFakeApi({
      '/api/v1/trips/t1': { body: TRIP },
      '/api/v1/trips/t1/stops': { body: [] },
      '/api/v1/me': { body: ME },
      '/api/v1/bookings/b1': { body: bookingOf('CONFIRMED', 'DEPOSIT_PAID') },
    })
    renderBooking('/book/t1?booking=b1')

    expect(await screen.findByText('Place confirmée')).toBeInTheDocument()
    // 3 205 F vient du serveur ; 1 600 F ou 3 200 F seraient des estimations locales.
    expect(screen.getByText(/Acompte de 3\s205 FCFA reçu/)).toBeInTheDocument()
    expect(screen.getByText(/36\s795 FCFA/)).toBeInTheDocument()
    expect(screen.queryByText(/1\s600 FCFA/)).not.toBeInTheDocument()
  })

  it('affiche l attente de l accord du conducteur avec l echeance du serveur, jamais « Place confirmee »', async () => {
    installFakeApi({
      '/api/v1/trips/t1': { body: { ...TRIP, instantBooking: false } },
      '/api/v1/trips/t1/stops': { body: [] },
      '/api/v1/me': { body: ME },
      '/api/v1/bookings/b1': { body: bookingOf('PENDING_DRIVER_APPROVAL', 'AWAITING_DRIVER', '2026-11-30T05:00:00Z') },
    })
    renderBooking('/book/t1?booking=b1')

    expect(await screen.findByText("En attente de l'accord du conducteur")).toBeInTheDocument()
    expect(screen.getByText(/Acompte de 3\s205 FCFA reçu/)).toBeInTheDocument()
    // L echeance vient de paymentPlan.approvalDeadlineAt : 05:00 UTC = 06:00 au Benin, le 30 novembre.
    expect(screen.getByText(/30 nov\. à 06:00/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retirer ma demande' })).toBeInTheDocument()
    expect(screen.queryByText('Place confirmée')).not.toBeInTheDocument()
  })

  it('annonce « sur accord du conducteur » au recapitulatif d un trajet sans reservation immediate', async () => {
    installFakeApi({
      '/api/v1/trips/t1': { body: { ...TRIP, instantBooking: false } },
      '/api/v1/trips/t1/stops': { body: [] },
      '/api/v1/me': { body: ME },
      'POST /api/v1/trips/t1/booking-quote': {
        body: {
          totalAmount: 20_000,
          serviceFee: 1_600,
          depositAmount: 1_600,
          balanceAmount: 18_400,
          paymentMethod: 'MOMO_DEPOSIT',
          paymentStatus: 'PENDING',
          depositDueAt: null,
          freeCancellationHours: 24,
        },
      },
    })
    renderBooking('/book/t1')

    expect(await screen.findByText('Votre réservation')).toBeInTheDocument()
    expect(screen.getByRole('note')).toHaveTextContent('Sur accord du conducteur')
    expect(await screen.findByRole('button', { name: /Demander ma place pour 1\s600 FCFA/ })).toBeInTheDocument()
  })

  it('montre « expiree » sur decision du serveur et « Recommencer » remet tout a zero, URL comprise', async () => {
    const user = userEvent.setup()
    installFakeApi({
      '/api/v1/trips/t1': { body: TRIP },
      '/api/v1/trips/t1/stops': { body: [] },
      '/api/v1/me': { body: ME },
      '/api/v1/bookings/b1': { body: bookingOf('EXPIRED', 'EXPIRED') },
      // Devis serveur, demande seulement une fois la reservation oubliee.
      'POST /api/v1/trips/t1/booking-quote': {
        body: {
          totalAmount: 20_000,
          serviceFee: 1_600,
          depositAmount: 1_600,
          balanceAmount: 18_400,
          paymentMethod: 'MOMO_DEPOSIT',
          paymentStatus: 'PENDING',
          depositDueAt: null,
          freeCancellationHours: 24,
        },
      },
    })
    renderBooking('/book/t1?booking=b1')

    expect(await screen.findByText('Réservation expirée')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/book/t1?booking=b1')

    await user.click(screen.getByRole('button', { name: 'Recommencer la réservation' }))

    // Retour au recapitulatif, sans ?booking= : plus rien de la reservation expiree ne s'affiche.
    expect(await screen.findByText('Votre réservation')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/book/t1')
    expect(screen.getByTestId('location')).not.toHaveTextContent('booking=')
    expect(screen.queryByText('Réservation expirée')).not.toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText(/3\s205 FCFA/)).not.toBeInTheDocument())
  })
})
