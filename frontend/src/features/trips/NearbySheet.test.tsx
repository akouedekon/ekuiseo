import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { NearbyTripResponse, TripResponse, VehicleType } from '@/api/types'
import { NearbySheet, type NearbySheetState } from './NearbySheet'

function trip(id: string, vehicleType: VehicleType, overrides: Partial<TripResponse> = {}): TripResponse {
  return {
    id,
    driver: { id: 'd1', firstName: 'Koffi', lastName: 'A.', photoUrl: null, ratingAvg: 4.6, ratingCount: 12, identityVerified: true },
    vehicle: { id: 'v1', brand: 'Toyota', model: 'Corolla', color: null, comfortLevel: 'COMFORT', vehicleType },
    tripType: 'QUOTIDIEN',
    originLabel: 'Abomey-Calavi',
    originLat: 6.4489,
    originLng: 2.3556,
    destLabel: 'Cotonou',
    destLat: 6.3703,
    destLng: 2.3912,
    // Loin dans le futur : le libelle relatif (« dans … ») ne depend pas de l heure du test.
    departureAt: '2030-01-01T06:30:00Z',
    seatsTotal: 3,
    seatsAvailable: 2,
    pricePerSeat: 500,
    instantBooking: true,
    luggagePolicy: null,
    description: null,
    status: 'PUBLISHED',
    recurrenceRule: null,
    createdAt: '2026-09-10T08:00:00Z',
    parentTripId: null,
    ...overrides,
  }
}

const NEAR: NearbyTripResponse = { trip: trip('t1', 'MOTO'), distanceKm: 0.8, boardingLabel: 'Abomey-Calavi', boardingLat: 6.4489, boardingLng: 2.3556 }
const VIA_STOP: NearbyTripResponse = {
  trip: trip('t2', 'CAR', { originLabel: 'Cotonou', destLabel: 'Parakou', pricePerSeat: 6000, seatsAvailable: 0 }),
  distanceKm: 2.4,
  boardingLabel: 'Gare de Bohicon',
  boardingLat: 7.1783,
  boardingLng: 2.0667,
}

function renderSheet(state: NearbySheetState, props: Partial<Parameters<typeof NearbySheet>[0]> = {}) {
  const onSelect = vi.fn()
  render(
    <MemoryRouter>
      <NearbySheet state={state} aroundLabel="vous" selectedId={null} onSelect={onSelect} {...props} />
    </MemoryRouter>,
  )
  return { onSelect }
}

describe('NearbySheet', () => {
  it('liste les departs du plus proche au plus lointain avec type, heure, axe, prix, places et distance', () => {
    renderSheet({ kind: 'ready', trips: [NEAR, VIA_STOP] })

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('2 départs près de vous')
    const items = within(screen.getByRole('list', { name: /départs à proximité/i })).getAllByRole('link')
    expect(items).toHaveLength(2)

    expect(items[0]).toHaveAttribute('href', '/trips/t1')
    expect(items[0]).toHaveTextContent('Moto')
    expect(items[0]).toHaveTextContent('Abomey-Calavi')
    expect(items[0]).toHaveTextContent('Cotonou')
    expect(items[0]).toHaveTextContent('500')
    expect(items[0]).toHaveTextContent('2 pl.')
    expect(items[0]).toHaveTextContent('à 800 m')
    // Montee a l origine : pas de mention d arret.
    expect(items[0]).not.toHaveTextContent('montée à')

    expect(items[1]).toHaveAttribute('href', '/trips/t2')
    expect(items[1]).toHaveTextContent('Voiture')
    expect(items[1]).toHaveTextContent('Complet')
    expect(items[1]).toHaveTextContent('à 2,4 km')
    expect(items[1]).toHaveTextContent('montée à Gare de Bohicon')
  })

  it('met en avant le depart selectionne et remonte la selection au survol', async () => {
    const user = userEvent.setup()
    const { onSelect } = renderSheet({ kind: 'ready', trips: [NEAR, VIA_STOP] }, { selectedId: 't2' })

    const links = screen.getAllByRole('link', { name: /vers/ })
    expect(links[1]).toHaveAttribute('aria-current', 'true')
    expect(links[0]).not.toHaveAttribute('aria-current')

    await user.hover(links[0])
    expect(onSelect).toHaveBeenCalledWith('t1')
  })

  it('sans depart : message, recherche classique et alerte', () => {
    renderSheet({ kind: 'ready', trips: [] }, { aroundLabel: 'Bohicon' })

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Aucun départ à proximité')
    expect(screen.getByText(/Aucun départ à moins de 10 km de Bohicon/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /recherche classique/i })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: /créer une alerte/i })).toHaveAttribute('href', '/me?tab=alerts')
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
  })

  it('position refusee : explication et champ de repli', () => {
    renderSheet(
      { kind: 'position-error', message: 'Vous avez refusé l’accès à votre position.' },
      { fallback: <p>Champ de repli</p> },
    )

    expect(screen.getByText('Vous avez refusé l’accès à votre position.')).toBeInTheDocument()
    expect(screen.getByText('Champ de repli')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Autour de moi')
  })

  it('recherche de position, chargement, hors ligne et erreur', async () => {
    const user = userEvent.setup()
    const { unmount } = render(
      <MemoryRouter>
        <NearbySheet state={{ kind: 'locating' }} aroundLabel="vous" selectedId={null} onSelect={() => undefined} />
      </MemoryRouter>,
    )
    expect(screen.getByRole('status')).toHaveTextContent('Recherche de votre position')
    unmount()

    const onRetry = vi.fn()
    renderSheet({ kind: 'offline', onRetry })
    expect(screen.getByText('Hors ligne')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /réessayer/i }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('erreur definitive : pas de bouton de reessai', () => {
    renderSheet({ kind: 'error', message: 'Rayon invalide.' })
    expect(screen.getByText('Départs indisponibles')).toBeInTheDocument()
    expect(screen.getByText('Rayon invalide.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /réessayer/i })).not.toBeInTheDocument()
  })
})
