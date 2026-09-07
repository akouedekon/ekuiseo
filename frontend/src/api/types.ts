// Types miroirs des DTO exposes par l'API backend (bj.ekuiseo.api). Le serveur omet les
// champs nuls : un champ `X | null` arrive comme `undefined` quand il est vide (tester avec `== null`).
// Les montants sont en FCFA (XOF), toujours des entiers.

export type TripType = 'INTERURBAIN' | 'QUOTIDIEN'
/** TEMPLATE : modele d une navette quotidienne (jamais cherchable), ONGOING : depart passe, plus de reservation ni d annulation. */
export type TripStatus = 'DRAFT' | 'TEMPLATE' | 'PUBLISHED' | 'FULL' | 'ONGOING' | 'COMPLETED' | 'CANCELLED'
export type BookingStatus =
  | 'PENDING_PAYMENT'
  | 'CONFIRMED'
  | 'CANCELLED_BY_PASSENGER'
  | 'CANCELLED_BY_DRIVER'
  | 'COMPLETED'
  | 'NO_SHOW'
  /** Acompte non recu dans les 20 minutes : places liberees par le serveur. Aucune action possible. */
  | 'EXPIRED'
// Alignes sur bj.ekuiseo.api.domain.enums.PaymentMethod : MOMO_DEPOSIT (acompte,
// defaut), MOMO_FULL (paiement integral en ligne), CASH (rien en ligne).
export type PaymentMethod = 'MOMO_DEPOSIT' | 'MOMO_FULL' | 'CASH'
export type ComfortLevel = 'BASIC' | 'COMFORT' | 'PREMIUM'
export type ReviewRole = 'DRIVER' | 'PASSENGER'
// Alignes sur bj.ekuiseo.api.domain.enums.NotificationType (backend). Les 3
// dernieres valeurs manquaient ici : SEARCH_ALERT_MATCH et SUBSCRIPTION_ACTIVATED
// sont deja emises en production (SearchAlertMatchService, PaymentService), ce qui
// faisait planter la page Notifications (Record<NotificationType, ...> non total)
// des qu'une telle notification arrivait. REPORT_RECEIVED n'est pour l'instant
// jamais emise cote serveur, mais reprise ici par completude.
export type NotificationType =
  | 'BOOKING_CONFIRMED'
  | 'BOOKING_CANCELLED'
  | 'PAYMENT_SUCCEEDED'
  | 'PAYMENT_FAILED'
  | 'NEW_MESSAGE'
  | 'TRIP_REMINDER'
  | 'NEW_REVIEW'
  | 'SEARCH_ALERT_MATCH'
  | 'SUBSCRIPTION_ACTIVATED'
  | 'REPORT_RECEIVED'
  /** Horaire de depart deplace par le conducteur (annulation gratuite rouverte 24 h). */
  | 'TRIP_UPDATED'
  /** Le conducteur a signale l absence du passager : acompte retenu. */
  | 'BOOKING_NO_SHOW'
  /* Decisions de moderation, emises par le backend depuis le lot 1.4 (audit F212). */
  | 'IDENTITY_APPROVED'
  | 'IDENTITY_REJECTED'
  | 'IDENTITY_REVOKED'
  | 'ACCOUNT_SUSPENDED'
  | 'REPORT_RESOLVED'
  /* Remboursements (RefundService) et conducteur sans compte mobile money verifie (PayoutService). */
  | 'PAYMENT_REFUND_PENDING'
  | 'PAYMENT_REFUNDED'
  | 'PAYOUT_ACCOUNT_MISSING'
  /* Lot 2 : expiration d'acompte, abonnement, reversements, CGU. */
  | 'BOOKING_EXPIRED'
  | 'SUBSCRIPTION_EXPIRING'
  | 'SUBSCRIPTION_EXPIRED'
  | 'PAYOUT_SETTLED'
  | 'PAYOUT_FAILED'
  | 'TERMS_UPDATED'

/** GET /api/v1/notifications/unread-count : compteur seul, rafraichi chaque minute pour la pastille. */
export interface UnreadCountResponse {
  count: number
}

export interface UserResponse {
  id: string
  phone: string
  email: string | null
  firstName: string
  lastName: string
  photoUrl: string | null
  bio: string | null
  ratingAvg: number
  ratingCount: number
  phoneVerified: boolean
  /** L adresse e-mail a recu et valide un code de connexion. */
  emailVerified: boolean
  identityVerified: boolean
  /** USER par defaut ; ADMIN ouvre le back-office (/api/v1/admin/**). */
  role: 'USER' | 'ADMIN'
  /** Vrai quand les CGU acceptees sont anterieures a la version courante : ecran bloquant (PATCH /me/terms). */
  termsAcceptanceRequired?: boolean
}

/**
 * Reponse de POST /auth/otp/request et /auth/otp/register : ou le code est parti.
 * `destination` est masquee (af***@example.com).
 */
export interface OtpRequestResponse {
  channel: 'EMAIL' | 'SMS'
  destination: string
}

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  user: UserResponse
}

export interface VehicleResponse {
  id: string
  brand: string
  model: string
  color: string | null
  plate: string
  seats: number
  comfortLevel: ComfortLevel
  photoUrl: string | null
  verified: boolean
}

export interface VehicleRequest {
  brand: string
  model: string
  color?: string
  plate: string
  seats: number
  comfortLevel: ComfortLevel
  photoUrl?: string
}

export interface DriverSummary {
  id: string
  firstName: string
  lastName: string
  photoUrl: string | null
  ratingAvg: number
  ratingCount: number
  /** Piece d'identite controlee (envoye par l'API ; optionnel pour tolerer un backend anterieur). */
  identityVerified: boolean
}

export interface VehicleSummary {
  id: string
  brand: string
  model: string
  color: string | null
  comfortLevel: ComfortLevel
}

export interface TripResponse {
  id: string
  driver: DriverSummary
  vehicle: VehicleSummary
  tripType: TripType
  originLabel: string
  originLat: number
  originLng: number
  destLabel: string
  destLat: number
  destLng: number
  departureAt: string
  seatsTotal: number
  seatsAvailable: number
  pricePerSeat: number
  instantBooking: boolean
  luggagePolicy: string | null
  description: string | null
  status: TripStatus
  recurrenceRule: string | null
  createdAt: string
  /** Modele de navette dont ce trajet est une occurrence. */
  parentTripId: string | null
  /** Renseigne uniquement dans la reponse de creation d une navette : occurrences generees. */
  generatedOccurrences?: number | null
  /**
   * Resultat de recherche apparie sur un troncon (arret intermediaire) : arrets de
   * montee / descente et prix du troncon. Absents quand le trajet complet correspond.
   */
  pickupStopId?: string | null
  dropoffStopId?: string | null
  segmentPriceFcfa?: number | null
}

/** Reservation d un trajet vue par son conducteur (GET /api/v1/trips/{id}/bookings). */
export interface TripBookingResponse {
  id: string
  passengerId: string
  firstName: string
  lastName: string | null
  photoUrl: string | null
  ratingAvg: number | null
  seats: number
  status: BookingStatus
  paymentMethod: PaymentMethod
  balanceDueOnBoard: number
  pickupStopId: string | null
  dropoffStopId: string | null
  createdAt: string
}

export interface StopRequest {
  label: string
  lat: number
  lng: number
  /** Heure de passage annoncee par le conducteur (ISO) ; absente, l'arret est affiche sans horaire. */
  plannedAt?: string
  priceFromOrigin: number
}

export interface CreateTripRequest {
  vehicleId: string
  tripType: TripType
  originLabel: string
  originLat: number
  originLng: number
  destLabel: string
  destLat: number
  destLng: number
  departureAt: string
  seatsTotal: number
  pricePerSeat: number
  /**
   * Toujours `true` : l'acceptation par le conducteur n'existe pas cote serveur
   * (audit F213), l'interrupteur a ete retire de l'interface. Le champ reste
   * exige par le contrat de creation.
   */
  instantBooking: boolean
  luggagePolicy?: string
  description?: string
  recurrenceRule?: string
  stops?: StopRequest[]
}

export interface BookingResponse {
  id: string
  tripId: string
  passengerId: string
  seats: number
  amount: number
  serviceFee: number
  status: BookingStatus
  paymentMethod: PaymentMethod
  createdAt: string
}

/** POST /trips/{id}/bookings : le nom JSON attendu par le serveur est `paymentMode`. */
export interface CreateBookingRequest {
  seats: number
  pickupStopId?: string
  dropoffStopId?: string
  paymentMode: PaymentMethod
}

export interface ReviewResponse {
  id: string
  tripId: string
  authorId: string
  targetId: string
  role: ReviewRole
  rating: number
  comment: string | null
  createdAt: string
}

export interface CreateReviewRequest {
  targetId: string
  role: ReviewRole
  rating: number
  comment?: string
}

export interface MessageResponse {
  id: string
  conversationId: string
  senderId: string
  body: string
  readAt: string | null
  createdAt: string
}

export interface NotificationResponse {
  id: string
  type: NotificationType
  payload: Record<string, unknown> | null
  readAt: string | null
  createdAt: string
}

export interface InitiatePaymentResponse {
  paymentId: string
  transactionRef: string
  amount: number
  kkiapayPublicKey: string
  sandbox: boolean
  /** A transmettre tel quel au parametre `data` du widget Kkiapay (contient bookingId). */
  widgetData?: Record<string, string>
}

/** POST /api/v1/payments/{id}/confirm : identifiant remis par l'evenement success du widget. */
export interface ConfirmPaymentRequest {
  transactionId: string
}

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
  /** Derniere page (Page Spring) ; optionnel pour tolerer un serializer qui l'omet. */
  last?: boolean
}

/** Reponse d'erreur RFC 7807 (application/problem+json) renvoyee par l'API. */
export interface ProblemDetail {
  type?: string
  title?: string
  status: number
  detail?: string
  instance?: string
}
