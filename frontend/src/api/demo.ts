/*
 * Jeu de donnees de demonstration.
 *
 * POURQUOI : l'API est developpee en parallele et plusieurs endpoints
 * utilises par l'interface n'existent pas encore (voir « endpoints attendus
 * du backend »). Sans filet, chaque ecran afficherait une erreur reseau et le
 * design serait impossible a evaluer.
 *
 * COMMENT : `resilientQuery` (api/resilient.ts) n'utilise ces donnees QUE si
 * l'appel reel echoue (reseau injoignable, 404, 501) ET si le mode demo est
 * actif (VITE_DEMO_FALLBACK, actif par defaut hors production). Un bandeau
 * « Données de démonstration » est alors affiche : rien n'est jamais presente
 * comme reel sans l'etre.
 *
 * Les donnees sont deterministes (dates calculees a partir du jour courant)
 * pour que les captures et les revues restent stables.
 */
import { estimatePaymentPlan } from '@/lib/payments'
import type {
  AdminLiquidityHeadline,
  AdminLiquidityResponse,
  AdminPayoutResponse,
  AdminReportResponse,
  AdminStatsResponse,
  AdminUserResponse,
  AdminVerificationResponse,
  BookingDetailResponse,
  ConversationSummary,
  IdentityVerificationResponse,
  PaymentMethodResponse,
  PaymentMode,
  PaymentPlanResponse,
  PublicUserResponse,
  RecurringTripResponse,
  TripStopResponse,
  UserPreferencesResponse,
} from './extended'
import type {
  BookingResponse,
  MessageResponse,
  NotificationResponse,
  Page,
  ReviewResponse,
  TripResponse,
  UserResponse,
  VehicleResponse,
} from './types'

const DAY = 86_400_000
const HOUR = 3_600_000

function at(dayOffset: number, hour: number, minute = 0): string {
  const d = new Date()
  d.setHours(hour, minute, 0, 0)
  return new Date(d.getTime() + dayOffset * DAY).toISOString()
}

function ago(ms: number): string {
  return new Date(Date.now() - ms).toISOString()
}

/* ------------------------------------------------------------ Utilisateurs */

export const DEMO_ME: UserResponse = {
  id: 'u-demo-me',
  phone: '+22997000001',
  email: 'demo@ekuiseo.bj',
  firstName: 'Ariane',
  lastName: 'Dossou',
  photoUrl: null,
  bio: "Je fais Cotonou–Bohicon presque chaque semaine pour le travail.",
  ratingAvg: 4.8,
  ratingCount: 27,
  phoneVerified: true,
  identityVerified: true,
}

const DRIVERS = [
  { id: 'u-01', firstName: 'Koffi', lastName: 'Aholou', ratingAvg: 4.9, ratingCount: 132 },
  { id: 'u-02', firstName: 'Mariam', lastName: 'Bio', ratingAvg: 4.7, ratingCount: 64 },
  { id: 'u-03', firstName: 'Serge', lastName: 'Hounkpatin', ratingAvg: 4.4, ratingCount: 21 },
  { id: 'u-04', firstName: 'Estelle', lastName: 'Zinsou', ratingAvg: 5.0, ratingCount: 9 },
  { id: 'u-05', firstName: 'Rachidi', lastName: 'Yacoubou', ratingAvg: 4.6, ratingCount: 88 },
] as const

const VEHICLES = [
  { id: 'v-01', brand: 'Toyota', model: 'Corolla', color: 'Gris', comfortLevel: 'COMFORT' as const },
  { id: 'v-02', brand: 'Hyundai', model: 'Tucson', color: 'Blanc', comfortLevel: 'PREMIUM' as const },
  { id: 'v-03', brand: 'Peugeot', model: '301', color: 'Bleu', comfortLevel: 'BASIC' as const },
  { id: 'v-04', brand: 'Toyota', model: 'Hiace', color: 'Blanc', comfortLevel: 'BASIC' as const },
  { id: 'v-05', brand: 'Kia', model: 'Sportage', color: 'Noir', comfortLevel: 'COMFORT' as const },
]

interface Seed {
  id: string
  d: number
  v: number
  from: [string, number, number]
  to: [string, number, number]
  dayOffset: number
  hour: number
  minute?: number
  seatsTotal: number
  seatsAvailable: number
  price: number
  instant: boolean
  type: 'INTERURBAIN' | 'QUOTIDIEN'
  description?: string
}

const SEEDS: Seed[] = [
  { id: 't-01', d: 0, v: 0, from: ['Cotonou', 6.3703, 2.3912], to: ['Bohicon', 7.1782, 2.0667], dayOffset: 0, hour: 6, minute: 30, seatsTotal: 4, seatsAvailable: 2, price: 3500, instant: true, type: 'INTERURBAIN', description: "Départ depuis Étoile Rouge. Bagage cabine accepté, je pars à l'heure." },
  { id: 't-02', d: 1, v: 1, from: ['Cotonou', 6.3703, 2.3912], to: ['Bohicon', 7.1782, 2.0667], dayOffset: 0, hour: 8, seatsTotal: 4, seatsAvailable: 3, price: 4000, instant: false, type: 'INTERURBAIN', description: 'Climatisation, arrêt possible à Allada.' },
  { id: 't-03', d: 2, v: 2, from: ['Cotonou', 6.3703, 2.3912], to: ['Bohicon', 7.1782, 2.0667], dayOffset: 0, hour: 14, minute: 15, seatsTotal: 3, seatsAvailable: 1, price: 3000, instant: true, type: 'INTERURBAIN' },
  { id: 't-04', d: 3, v: 3, from: ['Cotonou', 6.3703, 2.3912], to: ['Parakou', 9.3372, 2.6303], dayOffset: 1, hour: 5, seatsTotal: 6, seatsAvailable: 5, price: 9000, instant: false, type: 'INTERURBAIN', description: 'Long trajet, deux pauses prévues.' },
  { id: 't-05', d: 4, v: 4, from: ['Cotonou', 6.3703, 2.3912], to: ['Porto-Novo', 6.4969, 2.6289], dayOffset: 0, hour: 7, seatsTotal: 4, seatsAvailable: 2, price: 1500, instant: true, type: 'QUOTIDIEN', description: 'Tous les jours ouvrés, départ de Godomey.' },
  { id: 't-06', d: 1, v: 1, from: ['Abomey-Calavi', 6.4489, 2.3556], to: ['Cotonou', 6.3703, 2.3912], dayOffset: 0, hour: 6, seatsTotal: 3, seatsAvailable: 3, price: 1000, instant: true, type: 'QUOTIDIEN' },
  { id: 't-07', d: 0, v: 0, from: ['Cotonou', 6.3703, 2.3912], to: ['Lomé (Togo)', 6.1319, 1.2228], dayOffset: 2, hour: 9, seatsTotal: 4, seatsAvailable: 4, price: 8000, instant: false, type: 'INTERURBAIN', description: 'Passage frontière de Hillacondji, prévoir la pièce d’identité.' },
  { id: 't-08', d: 2, v: 2, from: ['Cotonou', 6.3703, 2.3912], to: ['Natitingou', 10.3042, 1.3792], dayOffset: 3, hour: 5, minute: 30, seatsTotal: 4, seatsAvailable: 2, price: 12500, instant: false, type: 'INTERURBAIN' },
]

function buildTrip(seed: Seed): TripResponse {
  const driver = DRIVERS[seed.d]
  const vehicle = VEHICLES[seed.v]
  return {
    id: seed.id,
    driver: { ...driver, photoUrl: null },
    vehicle,
    tripType: seed.type,
    originLabel: seed.from[0],
    originLat: seed.from[1],
    originLng: seed.from[2],
    destLabel: seed.to[0],
    destLat: seed.to[1],
    destLng: seed.to[2],
    departureAt: at(seed.dayOffset, seed.hour, seed.minute ?? 0),
    seatsTotal: seed.seatsTotal,
    seatsAvailable: seed.seatsAvailable,
    pricePerSeat: seed.price,
    instantBooking: seed.instant,
    luggagePolicy: seed.seatsTotal > 4 ? '1 bagage cabine + 1 sac' : '1 bagage cabine',
    description: seed.description ?? null,
    status: seed.seatsAvailable === 0 ? 'FULL' : 'PUBLISHED',
    recurrenceRule: seed.type === 'QUOTIDIEN' ? 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR' : null,
    createdAt: ago(3 * DAY),
  }
}

export const DEMO_TRIPS: TripResponse[] = SEEDS.map(buildTrip)

export function demoTripSearch(): Page<TripResponse> {
  return { content: DEMO_TRIPS, totalElements: DEMO_TRIPS.length, totalPages: 1, number: 0, size: 20 }
}

export function demoTrip(id: string): TripResponse {
  return DEMO_TRIPS.find((t) => t.id === id) ?? { ...DEMO_TRIPS[0], id }
}

export function demoTripStops(tripId: string): TripStopResponse[] {
  const trip = demoTrip(tripId)
  if (trip.destLabel === 'Bohicon') {
    return [
      { id: `${tripId}-s1`, label: 'Allada', lat: 6.6656, lng: 2.1514, plannedAt: null, priceFromOrigin: 1500, position: 1 },
      { id: `${tripId}-s2`, label: 'Abomey', lat: 7.1826, lng: 1.9912, plannedAt: null, priceFromOrigin: 3000, position: 2 },
    ]
  }
  if (trip.destLabel === 'Parakou') {
    return [
      { id: `${tripId}-s1`, label: 'Bohicon', lat: 7.1782, lng: 2.0667, plannedAt: null, priceFromOrigin: 3500, position: 1 },
      { id: `${tripId}-s2`, label: 'Dassa-Zoumè', lat: 7.7503, lng: 2.1836, plannedAt: null, priceFromOrigin: 5500, position: 2 },
      { id: `${tripId}-s3`, label: 'Savè', lat: 8.0342, lng: 2.4864, plannedAt: null, priceFromOrigin: 7000, position: 3 },
    ]
  }
  return []
}

export const DEMO_MY_TRIPS: TripResponse[] = [
  { ...DEMO_TRIPS[0], id: 't-mine-1', driver: { ...DRIVERS[0], id: DEMO_ME.id, firstName: DEMO_ME.firstName, lastName: DEMO_ME.lastName, photoUrl: null, ratingAvg: DEMO_ME.ratingAvg, ratingCount: DEMO_ME.ratingCount } },
  { ...DEMO_TRIPS[4], id: 't-mine-2', driver: { ...DRIVERS[0], id: DEMO_ME.id, firstName: DEMO_ME.firstName, lastName: DEMO_ME.lastName, photoUrl: null, ratingAvg: DEMO_ME.ratingAvg, ratingCount: DEMO_ME.ratingCount }, status: 'PUBLISHED' },
  { ...DEMO_TRIPS[6], id: 't-mine-3', driver: { ...DRIVERS[0], id: DEMO_ME.id, firstName: DEMO_ME.firstName, lastName: DEMO_ME.lastName, photoUrl: null, ratingAvg: DEMO_ME.ratingAvg, ratingCount: DEMO_ME.ratingCount }, departureAt: at(-6, 9), status: 'COMPLETED' },
]

/* ------------------------------------------------------------ Reservations */

/**
 * Plan de paiement de demonstration : applique la meme regle que le serveur
 * (acompte = max(plancher, frais de service), arrondi aux 5 FCFA superieurs,
 * plafonne au total) pour que la demo ne mente pas sur le modele.
 */
export function demoPaymentPlan(total: number, mode: PaymentMode = 'MOMO_DEPOSIT'): PaymentPlanResponse {
  const plan = estimatePaymentPlan(total, mode)
  return {
    ...plan,
    paymentStatus: mode === 'CASH' ? 'CASH_DUE_ON_BOARD' : 'PENDING',
    depositDueAt: mode === 'CASH' ? null : new Date(Date.now() + 18 * 60_000).toISOString(),
  }
}

const BOOKING_SEEDS: { id: string; trip: number; seats: number; status: BookingDetailResponse['status']; created: number; due?: number }[] = [
  { id: 'b-01', trip: 0, seats: 2, status: 'CONFIRMED', created: 2 * DAY },
  { id: 'b-02', trip: 3, seats: 1, status: 'PENDING_PAYMENT', created: 6 * 60_000, due: 14 * 60_000 },
  { id: 'b-03', trip: 6, seats: 1, status: 'CONFIRMED', created: 5 * HOUR },
  { id: 'b-04', trip: 4, seats: 1, status: 'COMPLETED', created: 12 * DAY },
  { id: 'b-05', trip: 2, seats: 3, status: 'CANCELLED_BY_DRIVER', created: 20 * DAY },
]

export const DEMO_BOOKINGS: BookingDetailResponse[] = BOOKING_SEEDS.map((seed) => {
  const trip = DEMO_TRIPS[seed.trip]
  const total = trip.pricePerSeat * seed.seats
  const plan = demoPaymentPlan(total)
  return {
    id: seed.id,
    tripId: trip.id,
    passengerId: DEMO_ME.id,
    seats: seed.seats,
    amount: total,
    serviceFee: plan.serviceFee,
    status: seed.status,
    paymentMethod: 'MOMO_DEPOSIT',
    createdAt: ago(seed.created),
    paymentPlan: {
      ...plan,
      depositDueAt: seed.due ? new Date(Date.now() + seed.due).toISOString() : null,
    },
    trip: {
      id: trip.id,
      tripType: trip.tripType,
      originLabel: trip.originLabel,
      destLabel: trip.destLabel,
      departureAt: seed.status === 'COMPLETED' || seed.status === 'CANCELLED_BY_DRIVER' ? ago(seed.created - DAY) : trip.departureAt,
      pricePerSeat: trip.pricePerSeat,
      driver: { ...trip.driver },
      vehicle: trip.vehicle,
    },
    unreadMessages: seed.id === 'b-01' ? 2 : 0,
  }
})

export const DEMO_BOOKINGS_PLAIN: BookingResponse[] = DEMO_BOOKINGS.map((b) => ({
  id: b.id,
  tripId: b.tripId,
  passengerId: b.passengerId,
  seats: b.seats,
  amount: b.amount,
  serviceFee: b.serviceFee,
  status: b.status,
  paymentMethod: b.paymentMethod,
  createdAt: b.createdAt,
}))

/* --------------------------------------------------------------- Messagerie */

export function demoMessages(bookingId: string): MessageResponse[] {
  const driverId = DEMO_BOOKINGS.find((b) => b.id === bookingId)?.trip.driver.id ?? 'u-01'
  return [
    { id: 'm1', conversationId: bookingId, senderId: driverId, body: 'Bonjour ! Je passe par Godomey vers 6 h 15, ça vous va ?', readAt: ago(HOUR), createdAt: ago(3 * HOUR) },
    { id: 'm2', conversationId: bookingId, senderId: DEMO_ME.id, body: 'Parfait. Je serai au carrefour Godomey à 6 h 10.', readAt: ago(HOUR), createdAt: ago(2.6 * HOUR) },
    { id: 'm3', conversationId: bookingId, senderId: driverId, body: "D'accord. Voiture grise, plaque AB 4521 RB.", readAt: null, createdAt: ago(40 * 60_000) },
    { id: 'm4', conversationId: bookingId, senderId: driverId, body: 'À demain !', readAt: null, createdAt: ago(38 * 60_000) },
  ]
}

export const DEMO_CONVERSATIONS: ConversationSummary[] = DEMO_BOOKINGS.filter(
  (b) => b.status === 'CONFIRMED' || b.status === 'PENDING_PAYMENT',
).map((b) => ({
  bookingId: b.id,
  tripId: b.tripId,
  counterpart: {
    id: b.trip.driver.id,
    firstName: b.trip.driver.firstName,
    lastName: b.trip.driver.lastName,
    photoUrl: null,
  },
  originLabel: b.trip.originLabel,
  destLabel: b.trip.destLabel,
  departureAt: b.trip.departureAt,
  lastMessage: b.unreadMessages > 0 ? 'À demain !' : 'Merci, bon voyage.',
  lastMessageAt: ago(38 * 60_000),
  unreadCount: b.unreadMessages,
}))

/* ------------------------------------------------------------ Notifications */

export const DEMO_NOTIFICATIONS: NotificationResponse[] = [
  { id: 'n1', type: 'PAYMENT_SUCCEEDED', payload: { amount: 1000, bookingId: 'b-01' }, readAt: null, createdAt: ago(25 * 60_000) },
  { id: 'n2', type: 'NEW_MESSAGE', payload: { bookingId: 'b-01', from: 'Koffi' }, readAt: null, createdAt: ago(38 * 60_000) },
  { id: 'n3', type: 'BOOKING_CONFIRMED', payload: { bookingId: 'b-01', origin: 'Cotonou', destination: 'Bohicon' }, readAt: ago(2 * HOUR), createdAt: ago(3 * HOUR) },
  { id: 'n4', type: 'TRIP_REMINDER', payload: { tripId: 't-01', departureAt: at(0, 6, 30) }, readAt: ago(5 * HOUR), createdAt: ago(6 * HOUR) },
  { id: 'n5', type: 'NEW_REVIEW', payload: { rating: 5, from: 'Mariam' }, readAt: ago(DAY), createdAt: ago(2 * DAY) },
  { id: 'n6', type: 'BOOKING_CANCELLED', payload: { bookingId: 'b-05', by: 'driver' }, readAt: ago(3 * DAY), createdAt: ago(4 * DAY) },
]

/* ------------------------------------------------------------------- Avis */

export function demoReviews(userId: string): ReviewResponse[] {
  return [
    { id: 'r1', tripId: 't-01', authorId: 'u-09', targetId: userId, role: 'DRIVER', rating: 5, comment: 'Ponctuel, conduite très prudente. Je recommande.', createdAt: ago(4 * DAY) },
    { id: 'r2', tripId: 't-03', authorId: 'u-10', targetId: userId, role: 'DRIVER', rating: 5, comment: 'Voiture propre et climatisée, trajet agréable.', createdAt: ago(11 * DAY) },
    { id: 'r3', tripId: 't-02', authorId: 'u-11', targetId: userId, role: 'DRIVER', rating: 4, comment: 'Un peu de retard au départ mais tout s’est bien passé.', createdAt: ago(19 * DAY) },
    { id: 'r4', tripId: 't-05', authorId: 'u-12', targetId: userId, role: 'DRIVER', rating: 5, comment: null, createdAt: ago(28 * DAY) },
  ]
}

export function demoPublicUser(id: string): PublicUserResponse {
  const driver = DRIVERS.find((d) => d.id === id) ?? DRIVERS[0]
  return {
    id,
    firstName: driver.firstName,
    lastName: driver.lastName,
    photoUrl: null,
    bio: "Je relie Cotonou et l'intérieur du pays depuis trois ans. Départs à l'heure, pas de surcharge.",
    ratingAvg: driver.ratingAvg,
    ratingCount: driver.ratingCount,
    identityVerified: true,
    phoneVerified: true,
    memberSince: ago(430 * DAY),
    tripsCompleted: driver.ratingCount * 3,
    reliabilityRate: 97,
    responseTimeMinutes: 12,
    vehicles: [VEHICLES[0], VEHICLES[2]],
    preferences: { smoking: false, music: true, pets: false, chatty: 'DEPENDS' },
  }
}

/* --------------------------------------------------------------- Mon compte */

export const DEMO_VEHICLES: VehicleResponse[] = [
  { id: 'v-01', brand: 'Toyota', model: 'Corolla', color: 'Gris', plate: 'AB 4521 RB', seats: 4, comfortLevel: 'COMFORT', photoUrl: null, verified: true },
  { id: 'v-03', brand: 'Peugeot', model: '301', color: 'Bleu', plate: 'BF 1180 RB', seats: 4, comfortLevel: 'BASIC', photoUrl: null, verified: false },
]

export const DEMO_PAYMENT_METHODS: PaymentMethodResponse[] = [
  { id: 'pm-1', provider: 'MTN_MOMO', phone: '+22997000001', label: 'Numéro principal', isDefault: true },
  { id: 'pm-2', provider: 'MOOV_MONEY', phone: '+22995110022', label: null, isDefault: false },
]

export const DEMO_PREFERENCES: UserPreferencesResponse = {
  notifyByPush: true,
  notifyBySms: true,
  notifyByEmail: false,
  language: 'fr',
  smoking: false,
  music: true,
  pets: false,
  chatty: 'DEPENDS',
}

export const DEMO_IDENTITY: IdentityVerificationResponse = {
  status: 'APPROVED',
  documentType: 'CNI',
  submittedAt: ago(120 * DAY),
  reviewedAt: ago(118 * DAY),
  rejectionReason: null,
}

export const DEMO_RECURRING: RecurringTripResponse[] = [
  {
    id: 'rt-1',
    originLabel: 'Abomey-Calavi',
    originLat: 6.4489,
    originLng: 2.3556,
    destLabel: 'Cotonou',
    destLat: 6.3703,
    destLng: 2.3912,
    weekdays: [1, 2, 3, 4, 5],
    departureTime: '06:30',
    seats: 1,
    matchesAvailable: 6,
    nextDepartureAt: at(1, 6, 30),
  },
]

/* -------------------------------------------------------------- Back-office */

export function demoAdminStats(days = 30): AdminStatsResponse {
  const series = Array.from({ length: days }).map((_, i) => {
    const offset = days - 1 - i
    const d = new Date(Date.now() - offset * DAY)
    // Saisonnalite hebdomadaire marquee (pics le vendredi et le dimanche).
    const weekday = d.getDay()
    const seasonal = weekday === 5 ? 1.6 : weekday === 0 ? 1.35 : weekday === 6 ? 1.15 : 1
    const growth = 1 + (i / days) * 0.45
    const trips = Math.round(38 * seasonal * growth)
    const bookings = Math.round(trips * 2.4)
    const gmv = bookings * 3850
    return {
      date: d.toISOString().slice(0, 10),
      trips,
      bookings,
      gmv,
      revenue: Math.round(gmv * 0.07),
    }
  })

  const sum = (key: 'trips' | 'bookings' | 'gmv' | 'revenue') => series.reduce((acc, row) => acc + row[key], 0)

  return {
    series,
    totals: {
      trips: sum('trips'),
      bookings: sum('bookings'),
      gmv: sum('gmv'),
      revenue: sum('revenue'),
      activeUsers: 4820,
      newUsers: 612,
    },
    deltas: { trips: 12.4, bookings: 18.1, gmv: 15.7, revenue: 16.2, activeUsers: 8.3, newUsers: -4.1 },
    bookingsByStatus: [
      { status: 'CONFIRMED', count: 1842 },
      { status: 'PENDING_PAYMENT', count: 214 },
      { status: 'COMPLETED', count: 3310 },
      { status: 'CANCELLED_BY_PASSENGER', count: 176 },
      { status: 'CANCELLED_BY_DRIVER', count: 88 },
      { status: 'NO_SHOW', count: 31 },
    ],
    topRoutes: [
      { origin: 'Cotonou', destination: 'Bohicon', trips: 412, gmv: 5_640_000 },
      { origin: 'Cotonou', destination: 'Porto-Novo', trips: 388, gmv: 1_940_000 },
      { origin: 'Abomey-Calavi', destination: 'Cotonou', trips: 355, gmv: 1_070_000 },
      { origin: 'Cotonou', destination: 'Parakou', trips: 194, gmv: 6_790_000 },
      { origin: 'Cotonou', destination: 'Lomé (Togo)', trips: 121, gmv: 3_630_000 },
    ],
  }
}

/**
 * Indicateurs de liquidite (GET /api/v1/admin/stats/liquidity). Les volumes sont
 * proportionnels a la periode ; les taux racontent une situation plausible de
 * lancement : la recherche aboutit deux fois sur trois, le quotidien se remplit
 * mieux que l'interurbain, et le Nord (Natitingou, Djougou) manque de conducteurs.
 */
export function demoAdminLiquidity(days = 30): AdminLiquidityResponse {
  const scale = days / 30
  const headline = (factor: number): AdminLiquidityHeadline => {
    const searches = Math.round(2140 * scale * factor)
    const searchesWithResults = Math.round(searches * (factor > 1 ? 0.68 : 0.61))
    const searchesByUsers = Math.round(searches * 0.42)
    const searchesConverted = Math.round(searchesByUsers * (factor > 1 ? 0.19 : 0.16))
    const trips = Math.round(560 * scale * factor)
    const seatsPublished = trips * 4
    const seatsBooked = Math.round(seatsPublished * (factor > 1 ? 0.57 : 0.52))
    const orphanTrips = Math.round(trips * (factor > 1 ? 0.21 : 0.26))
    const pct = (num: number, den: number) => (den === 0 ? 0 : Math.round((num / den) * 1000) / 10)
    return {
      searches,
      searchesWithResults,
      searchSuccessRate: pct(searchesWithResults, searches),
      searchesByUsers,
      searchesConverted,
      searchToBookingRate: pct(searchesConverted, searchesByUsers),
      trips,
      seatsPublished,
      seatsBooked,
      fillRate: pct(seatsBooked, seatsPublished),
      orphanTrips,
      orphanRate: pct(orphanTrips, trips),
      medianHoursToFirstBooking: factor > 1 ? 9.4 : 12.8,
      firstBookingSampleSize: trips - orphanTrips,
    }
  }
  const current = headline(1.12)
  const previous = headline(1)
  const confirmedSeats = current.seatsBooked
  const monthlyPace = Math.round((confirmedSeats * 30) / days)
  const weeks = Math.max(1, Math.round(days / 7))
  const now = Date.now()
  const weekly = Array.from({ length: weeks }).map((_, i) => {
    const d = new Date(now - (weeks - 1 - i) * 7 * DAY)
    // Lundi de la semaine.
    d.setDate(d.getDate() - ((d.getDay() + 6) % 7))
    return { weekStart: d.toISOString().slice(0, 10), seats: Math.round((confirmedSeats / weeks) * (0.85 + (i / weeks) * 0.3)) }
  })
  return {
    period: { days, from: new Date(now - days * DAY).toISOString(), to: new Date(now).toISOString() },
    northStar: {
      confirmedSeats,
      previousConfirmedSeats: previous.seatsBooked,
      monthlyPace,
      monthlyTarget: 2000,
      progressPercent: Math.round((monthlyPace / 2000) * 1000) / 10,
      weekly,
    },
    current,
    previous,
    fillByMode: [
      { tripType: 'INTERURBAIN', trips: Math.round(current.trips * 0.55), seatsPublished: Math.round(current.seatsPublished * 0.6), seatsBooked: Math.round(current.seatsBooked * 0.5), fillRate: 47.3, orphanTrips: Math.round(current.orphanTrips * 0.7), orphanRate: 26.8 },
      { tripType: 'QUOTIDIEN', trips: Math.round(current.trips * 0.45), seatsPublished: Math.round(current.seatsPublished * 0.4), seatsBooked: Math.round(current.seatsBooked * 0.5), fillRate: 71.2, orphanTrips: Math.round(current.orphanTrips * 0.3), orphanRate: 14.1 },
    ],
    fillByRoute: [
      { origin: 'Abomey-Calavi, Godomey-Togoudo', destination: 'Cotonou, Etoile Rouge', tripType: 'QUOTIDIEN', trips: Math.round(120 * scale), seatsPublished: Math.round(480 * scale), seatsBooked: Math.round(352 * scale), fillRate: 73.3, orphanTrips: Math.round(11 * scale) },
      { origin: 'Cotonou, gare Jonquet', destination: 'Bohicon, gare routiere', tripType: 'INTERURBAIN', trips: Math.round(96 * scale), seatsPublished: Math.round(384 * scale), seatsBooked: Math.round(221 * scale), fillRate: 57.6, orphanTrips: Math.round(14 * scale) },
      { origin: 'Cotonou, Etoile Rouge', destination: 'Porto-Novo, Ouando', tripType: 'INTERURBAIN', trips: Math.round(88 * scale), seatsPublished: Math.round(352 * scale), seatsBooked: Math.round(184 * scale), fillRate: 52.3, orphanTrips: Math.round(19 * scale) },
      { origin: 'Cotonou, gare de Parakou', destination: 'Parakou, gare routiere', tripType: 'INTERURBAIN', trips: Math.round(41 * scale), seatsPublished: Math.round(287 * scale), seatsBooked: Math.round(129 * scale), fillRate: 44.9, orphanTrips: Math.round(12 * scale) },
      { origin: 'Cotonou, Cadjehoun', destination: 'Lomé, gare routiere', tripType: 'INTERURBAIN', trips: Math.round(27 * scale), seatsPublished: Math.round(108 * scale), seatsBooked: Math.round(39 * scale), fillRate: 36.1, orphanTrips: Math.round(10 * scale) },
    ],
    shortageRoutes: [
      { origin: 'Cotonou', destination: 'Natitingou', searches: Math.round(146 * scale), searchesWithoutResults: Math.round(118 * scale), lastSearchedAt: ago(2 * HOUR) },
      { origin: 'Cotonou', destination: 'Djougou', searches: Math.round(74 * scale), searchesWithoutResults: Math.round(69 * scale), lastSearchedAt: ago(7 * HOUR) },
      { origin: 'Parakou', destination: 'Cotonou', searches: Math.round(121 * scale), searchesWithoutResults: Math.round(58 * scale), lastSearchedAt: ago(40 * 60_000) },
      { origin: 'Porto-Novo', destination: 'Abomey-Calavi', searches: Math.round(63 * scale), searchesWithoutResults: Math.round(41 * scale), lastSearchedAt: ago(1 * DAY) },
      { origin: 'Cotonou', destination: 'Kandi', searches: Math.round(29 * scale), searchesWithoutResults: Math.round(29 * scale), lastSearchedAt: ago(3 * DAY) },
      { origin: 'Lokossa', destination: 'Cotonou', searches: Math.round(38 * scale), searchesWithoutResults: Math.round(22 * scale), lastSearchedAt: ago(5 * HOUR) },
    ],
  }
}

export const DEMO_ADMIN_REPORTS: AdminReportResponse[] = [
  { id: 'rp-1', reason: 'NO_SHOW', status: 'OPEN', detail: "Le conducteur n'est jamais venu au point de rendez-vous, aucun message.", createdAt: ago(3 * HOUR), reporter: { id: 'u-21', firstName: 'Chantal', lastName: 'Agbo' }, target: { id: 'u-03', firstName: 'Serge', lastName: 'Hounkpatin' }, tripId: 't-03' },
  { id: 'rp-2', reason: 'VEHICLE_MISMATCH', status: 'OPEN', detail: "Le véhicule n'était pas celui annoncé sur l'annonce (berline au lieu du SUV).", createdAt: ago(9 * HOUR), reporter: { id: 'u-22', firstName: 'Bertin', lastName: 'Kpogli' }, target: { id: 'u-05', firstName: 'Rachidi', lastName: 'Yacoubou' }, tripId: 't-08' },
  { id: 'rp-3', reason: 'DANGEROUS_DRIVING', status: 'IN_REVIEW', detail: 'Dépassements répétés sur la RNIE2, plusieurs passagers inquiets.', createdAt: ago(2 * DAY), reporter: { id: 'u-23', firstName: 'Nadège', lastName: 'Sossou' }, target: { id: 'u-03', firstName: 'Serge', lastName: 'Hounkpatin' }, tripId: 't-04' },
  { id: 'rp-4', reason: 'FRAUD', status: 'OPEN', detail: "Demande de paiement du solde hors plateforme avant le départ.", createdAt: ago(3 * DAY), reporter: { id: 'u-24', firstName: 'Ismaël', lastName: 'Adjovi' }, target: { id: 'u-07', firstName: 'Patrice', lastName: 'Gnonlonfoun' }, tripId: null },
  { id: 'rp-5', reason: 'HARASSMENT', status: 'RESOLVED', detail: 'Messages insistants après le trajet. Compte suspendu 30 jours.', createdAt: ago(9 * DAY), reporter: { id: 'u-25', firstName: 'Reine', lastName: 'Ahouandjinou' }, target: { id: 'u-08', firstName: 'Casimir', lastName: 'Toko' }, tripId: 't-02' },
]

export const DEMO_ADMIN_VERIFICATIONS: AdminVerificationResponse[] = [
  { id: 'vf-1', userId: 'u-31', firstName: 'Alphonse', lastName: 'Djossou', phone: '+22997221100', documentType: 'CNI', documentNumber: 'BJ-0294-9911', submittedAt: ago(4 * HOUR), status: 'PENDING' },
  { id: 'vf-2', userId: 'u-32', firstName: 'Félicité', lastName: 'Amoussou', phone: '+22996440233', documentType: 'DRIVER_LICENSE', documentNumber: 'PC-118-4420', submittedAt: ago(11 * HOUR), status: 'PENDING' },
  { id: 'vf-3', userId: 'u-33', firstName: 'Ganiou', lastName: 'Salami', phone: '+22995010877', documentType: 'PASSPORT', documentNumber: 'BJ7714238', submittedAt: ago(DAY), status: 'PENDING' },
  { id: 'vf-4', userId: 'u-34', firstName: 'Léontine', lastName: 'Vodounon', phone: '+22991338800', documentType: 'CNI', documentNumber: 'BJ-1188-4402', submittedAt: ago(2 * DAY), status: 'REJECTED' },
]

export const DEMO_ADMIN_PAYOUTS: AdminPayoutResponse[] = [
  { id: 'po-1', driverId: 'u-01', driverName: 'Koffi Aholou', provider: 'MTN_MOMO', phone: '+22997001122', amount: 184_500, tripCount: 21, periodStart: ago(14 * DAY), periodEnd: ago(7 * DAY), status: 'PENDING', paidAt: null },
  { id: 'po-2', driverId: 'u-02', driverName: 'Mariam Bio', provider: 'MOOV_MONEY', phone: '+22995223311', amount: 96_000, tripCount: 12, periodStart: ago(14 * DAY), periodEnd: ago(7 * DAY), status: 'PENDING', paidAt: null },
  { id: 'po-3', driverId: 'u-05', driverName: 'Rachidi Yacoubou', provider: 'CELTIIS_CASH', phone: '+22990887744', amount: 232_000, tripCount: 28, periodStart: ago(14 * DAY), periodEnd: ago(7 * DAY), status: 'PROCESSING', paidAt: null },
  { id: 'po-4', driverId: 'u-04', driverName: 'Estelle Zinsou', provider: 'MTN_MOMO', phone: '+22997554400', amount: 41_500, tripCount: 5, periodStart: ago(21 * DAY), periodEnd: ago(14 * DAY), status: 'PAID', paidAt: ago(6 * DAY) },
  { id: 'po-5', driverId: 'u-03', driverName: 'Serge Hounkpatin', provider: 'MOOV_MONEY', phone: '+22996112200', amount: 73_000, tripCount: 9, periodStart: ago(21 * DAY), periodEnd: ago(14 * DAY), status: 'FAILED', paidAt: null },
]

export const DEMO_ADMIN_USERS: AdminUserResponse[] = [
  ...DRIVERS.map((d, i) => ({
    id: d.id,
    firstName: d.firstName,
    lastName: d.lastName,
    phone: `+2299700${String(1000 + i * 137).slice(0, 4)}`,
    email: i % 2 === 0 ? `${d.firstName.toLowerCase()}@example.bj` : null,
    createdAt: ago((60 + i * 45) * DAY),
    identityVerified: i !== 2,
    phoneVerified: true,
    suspended: i === 2,
    tripsPublished: d.ratingCount * 2,
    bookingsMade: Math.round(d.ratingCount / 3),
    ratingAvg: d.ratingAvg,
  })),
  {
    id: DEMO_ME.id,
    firstName: DEMO_ME.firstName,
    lastName: DEMO_ME.lastName,
    phone: DEMO_ME.phone,
    email: DEMO_ME.email,
    createdAt: ago(300 * DAY),
    identityVerified: true,
    phoneVerified: true,
    suspended: false,
    tripsPublished: 14,
    bookingsMade: 31,
    ratingAvg: DEMO_ME.ratingAvg,
  },
]
