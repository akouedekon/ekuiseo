import type { Page, Route } from '@playwright/test'

/*
 * API simulee pour les captures d ecran (aucun backend ni Docker) : chaque route de
 * /api/v1 recoit une reponse plausible, dans les formes des DTO du backend. Les
 * identifiants sont fixes pour que les captures soient reproductibles. Les montants
 * respectent les regles metier (acompte = max(1000, 8 %) arrondi aux 5 F).
 */

const NOW = Date.parse('2026-09-10T08:00:00Z')
const iso = (offsetMinutes: number) => new Date(NOW + offsetMinutes * 60_000).toISOString()

export const USER = {
  id: 'u-passager',
  phone: '+2290197000321',
  email: 'awa@example.bj',
  firstName: 'Awa',
  lastName: 'Kossou',
  photoUrl: null,
  bio: null,
  ratingAvg: 4.8,
  ratingCount: 12,
  phoneVerified: true,
  emailVerified: true,
  identityVerified: false,
  role: 'USER',
  termsAcceptanceRequired: false,
}

const DRIVERS = [
  { id: 'd-1', firstName: 'Marcellin', lastName: 'Sagbo', photoUrl: null, ratingAvg: 4.9, ratingCount: 87, identityVerified: true },
  { id: 'd-2', firstName: 'Gildas', lastName: 'Codjo', photoUrl: null, ratingAvg: 4.6, ratingCount: 23, identityVerified: true },
  { id: 'd-3', firstName: 'Wilfried', lastName: 'Tossou', photoUrl: null, ratingAvg: 4.3, ratingCount: 9, identityVerified: false },
]

const VEHICLES = [
  { id: 'v-1', brand: 'Renault', model: 'Logan', color: 'Blanc', comfortLevel: 'COMFORT', vehicleType: 'CAR' },
  { id: 'v-2', brand: 'Haojue', model: 'HJ 125', color: 'Jaune', comfortLevel: 'BASIC', vehicleType: 'MOTO' },
  { id: 'v-3', brand: 'Bajaj', model: 'RE', color: 'Bleu', comfortLevel: 'BASIC', vehicleType: 'TRICYCLE' },
]

function trip(index: number, overrides: Record<string, unknown> = {}) {
  const driver = DRIVERS[index % DRIVERS.length]
  const vehicle = VEHICLES[index % VEHICLES.length]
  return {
    id: `t-${index}`,
    driver,
    vehicle,
    tripType: 'INTERURBAIN',
    originLabel: 'Cotonou, Etoile Rouge',
    originLat: 6.3703,
    originLng: 2.3912,
    destLabel: 'Bohicon, gare routière',
    destLat: 7.1786,
    destLng: 2.0667,
    departureAt: iso(60 * (index + 3)),
    seatsTotal: vehicle.vehicleType === 'MOTO' ? 1 : 4,
    seatsAvailable: vehicle.vehicleType === 'MOTO' ? 1 : 4 - (index % 3),
    pricePerSeat: vehicle.vehicleType === 'MOTO' ? 1500 : vehicle.vehicleType === 'TRICYCLE' ? 800 : 4000,
    instantBooking: index % 4 !== 1,
    luggagePolicy: 'Un sac par passager',
    description: 'Départ ponctuel, climatisation.',
    status: 'PUBLISHED',
    recurrenceRule: null,
    createdAt: iso(-600),
    parentTripId: null,
    ...overrides,
  }
}

export const TRIPS = [
  trip(0),
  trip(1, { originLabel: 'Abomey-Calavi, carrefour IITA', originLat: 6.4485, originLng: 2.3556, destLabel: 'Cotonou, Etoile Rouge', destLat: 6.3703, destLng: 2.3912 }),
  trip(2, { destLabel: 'Porto-Novo, Ouando', destLat: 6.4969, destLng: 2.6289 }),
  trip(3),
  trip(4, { tripType: 'QUOTIDIEN', recurrenceRule: 'FREQ=WEEKLY;COUNT=20;BYDAY=MO,TU,WE,TH,FR' }),
]

const PLACES = [
  { id: 'p-cotonou', name: 'Cotonou', region: 'Littoral', countryCode: 'BJ', kind: 'CITY', lat: 6.3703, lng: 2.3912, parentId: null, parentName: null },
  { id: 'p-bohicon', name: 'Bohicon', region: 'Zou', countryCode: 'BJ', kind: 'CITY', lat: 7.1786, lng: 2.0667, parentId: null, parentName: null },
  { id: 'p-calavi', name: 'Abomey-Calavi', region: 'Atlantique', countryCode: 'BJ', kind: 'CITY', lat: 6.4485, lng: 2.3556, parentId: null, parentName: null },
  { id: 'p-porto', name: 'Porto-Novo', region: 'Ouémé', countryCode: 'BJ', kind: 'CITY', lat: 6.4969, lng: 2.6289, parentId: null, parentName: null },
  { id: 'p-parakou', name: 'Parakou', region: 'Borgou', countryCode: 'BJ', kind: 'CITY', lat: 9.3372, lng: 2.6303, parentId: null, parentName: null },
  { id: 'p-jonquet', name: 'Gare Jonquet', region: 'Littoral', countryCode: 'BJ', kind: 'STATION', lat: 6.3654, lng: 2.4183, parentId: 'p-cotonou', parentName: 'Cotonou' },
]

const POPULAR = [
  { originLabel: 'Abomey-Calavi, Godomey-Togoudo', originLat: 6.4, originLng: 2.35, destLabel: 'Cotonou, Etoile Rouge', destLat: 6.3703, destLng: 2.3912, trips: 10, minPrice: 800 },
  { originLabel: 'Cotonou, Etoile Rouge', originLat: 6.3703, originLng: 2.3912, destLabel: 'Bohicon, gare routière', destLat: 7.1786, destLng: 2.0667, trips: 6, minPrice: 4000 },
  { originLabel: 'Cotonou, Vedoko', originLat: 6.37, originLng: 2.38, destLabel: 'Natitingou, centre-ville', destLat: 10.3, destLng: 1.38, trips: 1, minPrice: 14000 },
  { originLabel: 'Porto-Novo, Ouando', originLat: 6.4969, originLng: 2.6289, destLabel: 'Cotonou, Etoile Rouge', destLat: 6.3703, destLng: 2.3912, trips: 4, minPrice: 1500 },
]

function paymentPlan(amount: number, method = 'MOMO_DEPOSIT') {
  const fee = Math.ceil((amount * 0.08) / 5) * 5
  const deposit = method === 'CASH' ? 0 : method === 'MOMO_FULL' ? amount : Math.min(amount, Math.ceil(Math.max(1000, fee) / 5) * 5)
  return {
    totalAmount: amount,
    serviceFee: fee,
    depositAmount: deposit,
    balanceAmount: amount - deposit,
    paymentMethod: method,
    paymentStatus: method === 'CASH' ? 'CASH_DUE_ON_BOARD' : 'DEPOSIT_PAID',
    depositDueAt: null,
    freeCancellationHours: 24,
    approvalDeadlineAt: null,
  }
}

function booking(id: string, t: ReturnType<typeof trip>, status: string, extra: Record<string, unknown> = {}) {
  return {
    id,
    tripId: t.id,
    passengerId: USER.id,
    seats: 1,
    amount: t.pricePerSeat,
    serviceFee: Math.ceil((t.pricePerSeat * 0.08) / 5) * 5,
    status,
    paymentMethod: 'MOMO_DEPOSIT',
    createdAt: iso(-300),
    paymentPlan: paymentPlan(t.pricePerSeat),
    trip: {
      id: t.id,
      tripType: t.tripType,
      originLabel: t.originLabel,
      destLabel: t.destLabel,
      departureAt: t.departureAt,
      pricePerSeat: t.pricePerSeat,
      driver: { id: t.driver.id, firstName: t.driver.firstName, lastName: t.driver.lastName, photoUrl: null, ratingAvg: t.driver.ratingAvg },
      vehicle: { brand: t.vehicle.brand, model: t.vehicle.model, color: t.vehicle.color, comfortLevel: t.vehicle.comfortLevel, vehicleType: t.vehicle.vehicleType },
    },
    unreadMessages: id === 'b-1' ? 2 : 0,
    reviewedByMe: false,
    passengerConfirmation: 'PENDING',
    passengerConfirmedAt: null,
    ...extra,
  }
}

export const BOOKINGS = [
  booking('b-1', TRIPS[0], 'CONFIRMED'),
  booking('b-2', TRIPS[2], 'PENDING_PAYMENT', { paymentPlan: { ...paymentPlan(TRIPS[2].pricePerSeat), paymentStatus: 'PENDING', depositDueAt: iso(15) } }),
  booking('b-3', trip(7, { departureAt: iso(-180), status: 'ONGOING' }), 'CONFIRMED'),
  booking('b-4', trip(8, { departureAt: iso(-60 * 24 * 3), status: 'COMPLETED' }), 'COMPLETED', { passengerConfirmation: 'TRIP_DONE', passengerConfirmedAt: iso(-60 * 24 * 2) }),
  // Conducteur declare absent (V25) : remboursement automatique a l echeance si le conducteur ne conteste pas.
  booking('b-5', trip(9, { departureAt: iso(-60 * 20), status: 'COMPLETED' }), 'DRIVER_NO_SHOW', {
    passengerConfirmation: 'DRIVER_NO_SHOW',
    passengerConfirmedAt: iso(-60 * 18),
    driverNoShowRefundDueAt: iso(60 * 6),
  }),
  // Annulee par le conducteur : acompte rembourse (V25, ligne de remboursement).
  booking('b-6', trip(10, { departureAt: iso(60 * 24 * 2) }), 'CANCELLED_BY_DRIVER', {
    paymentPlan: { ...paymentPlan(TRIPS[0].pricePerSeat), paymentStatus: 'CANCELLED' },
    refund: { status: 'REFUNDED', amountFcfa: 1000, requestedAt: iso(-60 * 5), refundedAt: iso(-60 * 4) },
  }),
]

const NOTIFICATIONS = [
  { id: 'n-1', type: 'BOOKING_CONFIRMED', payload: { tripId: 't-0', route: 'Cotonou → Bohicon', forPassenger: true }, readAt: null, createdAt: iso(-30) },
  { id: 'n-2', type: 'NEW_MESSAGE', payload: { bookingId: 'b-1', senderName: 'Marcellin' }, readAt: null, createdAt: iso(-120) },
  { id: 'n-3', type: 'TRIP_REMINDER', payload: { tripId: 't-0', route: 'Cotonou → Bohicon' }, readAt: iso(-1000), createdAt: iso(-1400) },
]

const CONVERSATIONS = [
  {
    bookingId: 'b-1',
    tripId: 't-0',
    counterpart: { id: 'd-1', firstName: 'Marcellin', lastName: 'Sagbo', photoUrl: null },
    originLabel: 'Cotonou, Etoile Rouge',
    destLabel: 'Bohicon, gare routière',
    departureAt: TRIPS[0].departureAt,
    lastMessage: 'Je serai à la gare à 7 h 15, appelez-moi en arrivant.',
    lastMessageAt: iso(-90),
    unreadCount: 2,
  },
]

function page<T>(content: T[]) {
  return { content, totalElements: content.length, totalPages: 1, number: 0, size: 20, last: true }
}

export interface MockOptions {
  /** Session ouverte (cookie simule) : /auth/refresh renvoie un jeton et les ecrans prives se chargent. */
  authed?: boolean
}

/**
 * Installe l API simulee sur la page. Toute route inconnue renvoie 404 RFC 7807 pour que
 * le manque se voie dans la capture plutot que de bloquer silencieusement.
 */
export async function mockApi(pageObj: Page, options: MockOptions = {}): Promise<void> {
  const authed = options.authed ?? true
  await pageObj.route('**/api/v1/**', async (route: Route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace(/^.*\/api\/v1/, '')
    const method = request.method()
    const json = (body: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    const empty = (status = 204) => route.fulfill({ status, body: '' })
    const problem = (status: number, detail: string) =>
      route.fulfill({ status, contentType: 'application/problem+json', body: JSON.stringify({ title: detail, status, detail }) })

    if (path === '/auth/refresh') {
      return authed ? json({ accessToken: 'jeton-de-test', refreshToken: null, user: USER }) : problem(400, 'Aucune session')
    }
    if (path === '/auth/logout') return empty()
    if (path === '/me' && method === 'GET') return authed ? json(USER) : problem(401, 'Authentification requise')
    if (path === '/me/preferences') {
      return json({ notifyByPush: false, notifyBySms: false, notifyByEmail: true, language: 'fr', smoking: false, music: true, pets: false, chatty: 'DEPENDS' })
    }
    if (path === '/me/vehicles') return json([{ id: 'v-1', brand: 'Renault', model: 'Logan', color: 'Blanc', plate: 'AG 3216 RB', seats: 4, vehicleType: 'CAR', comfortLevel: 'COMFORT', photoUrl: null, verified: true }])
    if (path === '/me/identity') return json({ status: 'NOT_SUBMITTED', documentType: null, submittedAt: null, reviewedAt: null, rejectionReason: null, documents: [] })
    if (path === '/me/payment-methods') return json([])
    if (path === '/me/payouts/balance') return json({ balanceFcfa: 0, pendingFcfa: 0 })
    if (path === '/me/payouts') return json([])
    if (path === '/me/subscription') return json({ id: null, priceFcfa: 2000, status: 'NONE', currentlyActive: false, startedAt: null, endsAt: null, autoRenew: false })
    if (path === '/me/conversations') return json(authed ? CONVERSATIONS : [])
    if (path === '/me/trips') return json([])
    if (path === '/me/recurring-trips') return json([])
    if (path === '/me/trip-alerts' || path === '/trip-alerts') return json([])
    if (path === '/me/export') return empty(202)
    if (path === '/notifications/unread-count') return json({ count: authed ? 2 : 0 })
    if (path === '/notifications') return json(page(NOTIFICATIONS))
    if (path === '/bookings' && method === 'GET') return json(authed ? BOOKINGS : [])
    if (/^\/bookings\/[^/]+$/.test(path)) return json(BOOKINGS.find((b) => path.endsWith(b.id)) ?? BOOKINGS[0])
    if (/^\/bookings\/[^/]+\/messages$/.test(path)) {
      return json([
        { id: 'm-1', conversationId: 'c-1', senderId: 'd-1', body: 'Bonjour Awa, départ confirmé demain 7 h 30.', createdAt: iso(-200), readAt: iso(-190) },
        { id: 'm-2', conversationId: 'c-1', senderId: USER.id, body: 'Parfait, merci ! Je serai à Etoile Rouge.', createdAt: iso(-150), readAt: iso(-140) },
        { id: 'm-3', conversationId: 'c-1', senderId: 'd-1', body: 'Je serai à la gare à 7 h 15, appelez-moi en arrivant.', createdAt: iso(-90), readAt: null },
      ])
    }
    if (path === '/trips/popular') return json(POPULAR)
    if (path === '/trips/search') return json(page(TRIPS))
    if (path === '/trips/nearby') {
      return json(TRIPS.slice(0, 4).map((t, i) => ({ trip: t, distanceKm: 0.4 + i * 0.9, boardingLabel: t.originLabel, boardingLat: t.originLat, boardingLng: t.originLng })))
    }
    if (/^\/trips\/[^/]+\/stops$/.test(path)) return json([])
    if (/^\/trips\/[^/]+\/live$/.test(path)) return json({ enabled: false, position: null, staleSeconds: null, tripStatus: 'PUBLISHED', departureAt: TRIPS[0].departureAt, shareToken: null })
    if (/^\/trips\/[^/]+\/booking-quote$/.test(path)) {
      const body = request.postDataJSON() as { seats?: number; paymentMode?: string } | null
      return json(paymentPlan(4000 * (body?.seats ?? 1), body?.paymentMode ?? 'MOMO_DEPOSIT'))
    }
    if (/^\/trips\/[^/]+$/.test(path)) return json(TRIPS.find((t) => path.endsWith(t.id)) ?? TRIPS[0])
    if (/^\/users\/[^/]+\/reviews$/.test(path)) {
      return json([
        { id: 'r-1', authorFirstName: 'Awa', rating: 5, comment: 'Conducteur ponctuel et prudent.', createdAt: iso(-60 * 24 * 10), role: 'PASSENGER' },
        { id: 'r-2', authorFirstName: 'Koffi', rating: 4, comment: 'Bon trajet, voiture propre.', createdAt: iso(-60 * 24 * 30), role: 'PASSENGER' },
      ])
    }
    if (/^\/users\/[^/]+$/.test(path)) {
      return json({
        id: 'd-1', firstName: 'Marcellin', lastName: 'Sagbo', photoUrl: null, bio: 'Conducteur depuis 2019, trajets Cotonou-Bohicon chaque semaine.',
        ratingAvg: 4.9, ratingCount: 87, identityVerified: true, memberSince: '2024-03-01T00:00:00Z', tripsCompleted: 142,
        reliabilityRate: 98, responseTimeMinutes: 12, preferences: { smoking: false, music: true, pets: false, chatty: 'DEPENDS' },
        vehicles: [{ id: 'v-1', brand: 'Renault', model: 'Logan', color: 'Blanc', comfortLevel: 'COMFORT', vehicleType: 'CAR' }],
      })
    }
    if (path === '/geo/places') return json(PLACES)
    if (path === '/geo/search') {
      const q = (url.searchParams.get('q') ?? '').toLowerCase()
      return json(PLACES.filter((p) => p.name.toLowerCase().includes(q)))
    }
    if (path === '/push/vapid-public-key') return empty()
    if (path === '/push/config') return json({ webPush: false, nativePush: false })
    if (path === '/client-errors') return empty(202)
    if (path.startsWith('/live/')) return problem(404, 'Suivi terminé')
    return problem(404, `Route simulée absente : ${method} ${path}`)
  })
}

/**
 * Pont Capacitor factice : l application se croit dans l APK (classe app-native, greffons
 * neutres). @capacitor/core detecte Android par la presence de `window.androidBridge`
 * (il recalcule lui-meme `isNativePlatform`) ; sans greffon natif enregistre, chaque appel
 * (barre d etat, bouton retour, clavier) est rejete « not implemented » et rattrape par
 * lib/native.ts. L invite de permissions du premier lancement est marquee comme deja vue.
 */
export async function fakeNativeBridge(pageObj: Page): Promise<void> {
  await pageObj.addInitScript(() => {
    ;(window as unknown as { androidBridge: unknown }).androidBridge = { postMessage: () => undefined }
    try {
      localStorage.setItem('ekuiseo.native.permissionsAsked', '1')
    } catch {
      /* stockage indisponible */
    }
  })
}
