/*
 * Types miroirs des DTO de l'API (suite de types.ts) : reservations enrichies,
 * paiement, compte, back-office. Tous ces endpoints existent cote backend
 * (bj.ekuiseo.api.web.controller). Rappel : le serveur omet les champs nuls
 * (`non_null`), un champ type `X | null` arrive donc comme `undefined` quand il
 * est vide - tester avec `== null` ou `??`, jamais avec `=== null`.
 */
import type { BookingStatus, ComfortLevel, PaymentMethod, TripType, VehicleSummary } from './types'

/* --------------------------------------------------------- Trajet detaille */

/** Arret intermediaire avec prix depuis l'origine (tarif par troncon). */
export interface TripStopResponse {
  id: string
  label: string
  lat: number
  lng: number
  plannedAt: string | null
  priceFromOrigin: number
  /** Rang dans l'itineraire, 1..n (l'origine est 0). */
  position: number
}

/** Profil public d'un conducteur — GET /api/v1/users/{id}. */
export interface PublicUserResponse {
  id: string
  firstName: string
  lastName: string
  photoUrl: string | null
  bio: string | null
  ratingAvg: number
  ratingCount: number
  identityVerified: boolean
  phoneVerified: boolean
  memberSince: string
  tripsCompleted: number
  /** Taux de trajets honores, 0..100. */
  /** null tant que le conducteur a moins de 5 trajets : "pas encore d'historique", pas "mauvais historique". */
  reliabilityRate: number | null
  /** Delai median de reponse aux messages, en minutes. */
  responseTimeMinutes: number | null
  vehicles: VehicleSummary[]
  preferences: DriverPreferences | null
}

export interface DriverPreferences {
  smoking: boolean
  music: boolean
  pets: boolean
  chatty: 'QUIET' | 'DEPENDS' | 'TALKATIVE'
}

/* ---------------------------------------------------------------- Paiement */

export type PaymentProvider = 'MTN_MOMO' | 'MOOV_MONEY' | 'CELTIIS_CASH'
/** Vocabulaire client d un paiement (PaymentStatusResponse) : REFUND_PENDING / REFUNDED = acompte recu trop tard, rembourse. */
export type PaymentStatus = 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED' | 'REFUND_PENDING' | 'REFUNDED'

/** Mode de reglement choisi par le passager a la reservation. */
/** Valeurs telles que stockees en base par le backend. */
export type PaymentMode = 'MOMO_DEPOSIT' | 'MOMO_FULL' | 'CASH'

/**
 * Etat du plan de paiement. 'ESTIMATED' n'existe que cote client ; les autres
 * valeurs sont exactement celles renvoyees par BookingService#paymentPlanStatus
 * cote serveur (voir bj.ekuiseo.api.service.BookingService).
 */
export type PaymentPlanStatus =
  | 'ESTIMATED'
  | 'PENDING'
  /** Acompte encaisse (ou especes), place bloquee, en attente de l'accord du conducteur (V19). */
  | 'AWAITING_DRIVER'
  | 'CANCELLED'
  | 'DEPOSIT_PAID'
  | 'PAID_IN_FULL'
  | 'CASH_DUE_ON_BOARD'
  /** Acompte non recu a l'echeance : la reservation est EXPIRED, les places liberees. */
  | 'EXPIRED'

/**
 * Decomposition du paiement, calculee et renvoyee par le serveur.
 *
 * Regle : `depositAmount` = max(1 000 FCFA, `serviceFee`), arrondi aux 5 FCFA
 * superieurs et plafonne a `totalAmount`. Le front N'APPLIQUE PAS cette regle
 * pour afficher une reservation existante : il lit les montants tels quels.
 * (Elle n'est reimplementee, dans lib/payments.ts, que pour l'estimation
 * affichee avant la creation de la reservation.)
 *
 * Noms de champs alignes sur bj.ekuiseo.api.dto.payment.PaymentPlanResponse
 * (record Java) : `paymentMethod` (pas `paymentMode`) et `paymentStatus` (pas
 * `status`) sont les noms JSON reellement renvoyes par le serveur.
 */
export interface PaymentPlanResponse {
  /** Prix total du voyage (places x prix du troncon). */
  totalAmount: number
  /** Frais de service Ekuiseo, inclus dans l'acompte. */
  serviceFee: number
  /** Montant a regler en ligne maintenant. 0 en mode especes. */
  depositAmount: number
  /** Solde a regler en especes a bord. 0 en paiement integral. */
  balanceAmount: number
  paymentMethod: PaymentMode
  paymentStatus: PaymentPlanStatus
  /** Echeance de l'acompte (ISO). Passe ce delai, la place est reliberee. */
  depositDueAt: string | null
  /** Delai d'annulation sans frais, en heures avant le depart. */
  freeCancellationHours: number
  /** Echeance de la reponse du conducteur (ISO), renseignee seulement en AWAITING_DRIVER (V19). */
  approvalDeadlineAt?: string | null
}

/** Devis demande avant reservation : POST /api/v1/trips/{id}/booking-quote */
export interface BookingQuoteRequest {
  seats: number
  dropoffStopId?: string
  paymentMode: PaymentMode
}

export interface PaymentStatusResponse {
  paymentId: string
  /** Reservation reglee ; null pour un paiement d'abonnement. */
  bookingId: string | null
  /** Abonnement regle ; null pour un acompte de reservation. */
  subscriptionId?: string | null
  transactionRef: string
  provider: PaymentProvider | null
  status: PaymentStatus
  amount: number
  /** Instructions renvoyees par l'operateur (ex. « composez *880# »). */
  instruction: string | null
  updatedAt: string
}

export interface InitiateDepositRequest {
  provider: PaymentProvider
  /** Numero mobile money debite, au format international. */
  phone: string
}

/* --------------------------------------------- Reservation enrichie (vue UI) */

/** Reservation + trajet, pour eviter N+1 requetes sur « Mes reservations ». */
export interface BookingDetailResponse {
  id: string
  tripId: string
  passengerId: string
  seats: number
  amount: number
  serviceFee: number
  status: BookingStatus
  paymentMethod: PaymentMethod
  createdAt: string
  paymentPlan: PaymentPlanResponse
  trip: {
    id: string
    tripType: TripType
    originLabel: string
    destLabel: string
    departureAt: string
    pricePerSeat: number
    driver: { id: string; firstName: string; lastName: string; photoUrl: string | null; ratingAvg: number }
    vehicle: { brand: string; model: string; color: string | null; comfortLevel: ComfortLevel }
  }
  /** Nombre de messages non lus dans la conversation liee. */
  unreadMessages: number
  /** Vrai si j'ai deja note le conducteur pour ce trajet (un seul avis par trajet). */
  reviewedByMe: boolean
}

/* ------------------------------------------------------------- Preferences */

export interface UserPreferencesResponse {
  notifyByPush: boolean
  notifyBySms: boolean
  notifyByEmail: boolean
  language: 'fr' | 'en'
  smoking: boolean
  music: boolean
  pets: boolean
  chatty: 'QUIET' | 'DEPENDS' | 'TALKATIVE'
}

export interface PaymentMethodResponse {
  id: string
  provider: PaymentProvider
  phone: string
  label: string | null
  isDefault: boolean
  /** Possession du numero etablie (numero de connexion, ou validation par l administration) : seul un compte verifie recoit des reversements. */
  verified: boolean
}

export type IdentityVerificationStatus = 'NOT_SUBMITTED' | 'PENDING' | 'APPROVED' | 'REJECTED'

export type IdentityDocumentSide = 'FRONT' | 'BACK' | 'SELFIE'

/** Presence d une piece televersee (V20) : jamais le contenu ni le nom du fichier. */
export interface IdentityDocumentSummary {
  side: IdentityDocumentSide
  contentType: string
  sizeBytes: number
  createdAt: string
}

export interface IdentityVerificationResponse {
  status: IdentityVerificationStatus
  documentType: 'CNI' | 'PASSPORT' | 'DRIVER_LICENSE' | null
  submittedAt: string | null
  reviewedAt: string | null
  rejectionReason: string | null
  /** Pieces televersees (recto, verso, selfie), V20. */
  documents: IdentityDocumentSummary[]
}

/* ------------------------------------------------------------ Geocodage */

/**
 * Lieu du referentiel serveur (GET /api/v1/geo/places, liste complete publique
 * mise en cache 24 h ; GET /api/v1/geo/search pour une recherche). Le referentiel
 * `geo_places` est la seule source des villes (audit F411) ; `parentName` porte la
 * ville de rattachement d'un quartier ou d'une gare (« Agla — Cotonou », audit F422).
 */
export interface GeoPlaceResponse {
  id: string
  name: string
  region: string | null
  countryCode?: string
  kind: 'CITY' | 'DISTRICT' | 'STATION'
  lat: number
  lng: number
  parentId?: string | null
  parentName?: string | null
}

/** Axe le plus propose en ce moment (GET /api/v1/trips/popular), raccourcis de l'accueil. */
export interface PopularRouteResponse {
  originLabel: string
  originLat: number
  originLng: number
  destLabel: string
  destLat: number
  destLng: number
  trips: number
  minPrice: number
}

/* ------------------------------------------------------ Trajets recurrents */

/** « Votre trajet de la semaine » — trajet quotidien memorise du passager. */
export interface RecurringTripResponse {
  id: string
  originLabel: string
  originLat: number
  originLng: number
  destLabel: string
  destLat: number
  destLng: number
  /** Jours actifs : 1 = lundi … 7 = dimanche (ISO-8601). */
  weekdays: number[]
  departureTime: string
  seats: number
  /** Nombre d'offres disponibles pour la prochaine occurrence. */
  matchesAvailable: number
  nextDepartureAt: string | null
}

/* ------------------------------------------------------------ Alerte trajet */

export interface TripAlertRequest {
  originLabel: string
  originLat: number
  originLng: number
  destLabel: string
  destLat: number
  destLng: number
  date: string | null
  seats: number
  /** null = tous les modes (recherche lancee sans `type`). */
  tripType: TripType | null
}

/** GET /api/v1/trip-alerts (liste), POST (200 si une alerte identique est reutilisee, 422 au-dela de 10), DELETE /{id} → 204. */
export interface TripAlertResponse extends TripAlertRequest {
  id: string
  createdAt: string
  active: boolean
  radiusKm: number
}

/* -------------------------------------------------------------- Messagerie */

export interface ConversationSummary {
  bookingId: string
  tripId: string
  counterpart: { id: string; firstName: string; lastName: string; photoUrl: string | null }
  originLabel: string
  destLabel: string
  departureAt: string
  lastMessage: string | null
  lastMessageAt: string | null
  unreadCount: number
}

/* ------------------------------------------------------------- Back-office */

export interface AdminStatsResponse {
  /** Serie journaliere sur la periode demandee. */
  series: {
    date: string
    trips: number
    bookings: number
    gmv: number
    revenue: number
  }[]
  totals: {
    trips: number
    bookings: number
    gmv: number
    revenue: number
    activeUsers: number
    newUsers: number
  }
  /** Variation en pourcentage par rapport a la periode precedente. */
  deltas: {
    trips: number
    bookings: number
    gmv: number
    revenue: number
    activeUsers: number
    newUsers: number
  }
  /** Repartition des reservations par statut. */
  bookingsByStatus: { status: BookingStatus; count: number }[]
  /** Axes les plus frequentes. */
  topRoutes: { origin: string; destination: string; trips: number; gmv: number }[]
}

/**
 * Indicateurs de liquidite, GET /api/v1/admin/stats/liquidity?days=N.
 * `current` et `previous` ont la meme forme : le front affiche la variation en
 * points (un taux seul ne s'interprete pas). Tous les taux sont en pourcentage,
 * arrondis au dixieme, cote serveur - jamais recalcules ici.
 */
export interface AdminLiquidityHeadline {
  searches: number
  searchesWithResults: number
  /** Recherches ayant renvoye au moins un trajet / total, en %. */
  searchSuccessRate: number
  searchesByUsers: number
  searchesConverted: number
  /** Recherches d'utilisateurs connectes suivies d'une reservation sous 24 h / recherches connectees, en %. */
  searchToBookingRate: number
  /** Trajets partis sur la periode (hors brouillons et annules). */
  trips: number
  seatsPublished: number
  seatsBooked: number
  fillRate: number
  orphanTrips: number
  orphanRate: number
  /** Delai median publication -> premiere reservation, en heures ; null si aucun trajet reserve. */
  medianHoursToFirstBooking: number | null
  firstBookingSampleSize: number
}

export interface AdminLiquidityResponse {
  period: { days: number; from: string; to: string }
  /** Metrique nord : places reellement vendues, rapportees au seuil de 2 000 par mois. */
  northStar: {
    confirmedSeats: number
    previousConfirmedSeats: number
    /** Extrapolation de la periode a 30 jours. */
    monthlyPace: number
    monthlyTarget: number
    /** monthlyPace / monthlyTarget, en %. */
    progressPercent: number
    weekly: { weekStart: string; seats: number }[]
  }
  current: AdminLiquidityHeadline
  previous: AdminLiquidityHeadline
  fillByMode: {
    tripType: TripType
    trips: number
    seatsPublished: number
    seatsBooked: number
    fillRate: number
    orphanTrips: number
    orphanRate: number
  }[]
  fillByRoute: {
    origin: string
    destination: string
    tripType: TripType
    trips: number
    seatsPublished: number
    seatsBooked: number
    fillRate: number
    orphanTrips: number
  }[]
  /** Corridors recherches sans resultat : la liste a demarcher en priorite. */
  shortageRoutes: {
    origin: string
    destination: string
    searches: number
    searchesWithoutResults: number
    lastSearchedAt: string | null
  }[]
}

/**
 * Retention, paiement et panier : GET /api/v1/admin/stats/retention?days=N
 * (export CSV sur /retention/export). Les taux sont des fractions 0..1 (ou null
 * quand la cohorte est vide) calcules en SQL agrege cote serveur, jamais ici ;
 * `previous` porte les memes scalaires sur la periode precedente pour la variation.
 */
export interface AdminRetentionScalars {
  /** Conducteurs ayant republie la semaine suivant leur premiere publication / cohorte. */
  driverRetentionW1: number | null
  /** Idem, quatre semaines apres. */
  driverRetentionW4: number | null
  /** Passagers ayant reserve a nouveau sous 30 jours. */
  passengerRetention30d: number | null
  /** Part des reservations en mode quotidien. */
  dailyModeShare: number | null
  activeRecurringTemplates: number
  /** Places reellement remplies par occurrence de navette, en moyenne. */
  avgFilledSeatsPerOccurrence: number | null
  /** Reservations dont l'acompte a ete encaisse / reservations en mobile money. */
  bookingToDepositRate: number | null
  /** Reservations expirees faute d'acompte sous 20 min / reservations en mobile money. */
  expiredBookingShare: number | null
  averageBasketFcfa: number | null
  seatsPerBooking: number | null
}

export interface AdminRetentionResponse extends AdminRetentionScalars {
  days: number
  /** Tentatives et echecs Kkiapay par operateur : une panne MTN doit se voir en minutes. */
  kkiapayFailureByOperator: { operator: PaymentProvider | string; attempts: number; failures: number }[]
  /** Repartition des modes de paiement ; la part CASH echappe a la commission. */
  paymentMethodShare: { method: PaymentMethod; count: number; amountFcfa: number }[]
  previous: AdminRetentionScalars
}

export type ReportStatus = 'OPEN' | 'IN_REVIEW' | 'RESOLVED' | 'DISMISSED'
export type ReportReason = 'NO_SHOW' | 'DANGEROUS_DRIVING' | 'HARASSMENT' | 'FRAUD' | 'VEHICLE_MISMATCH' | 'OTHER'

/** Personne citee dans un signalement (auteur, mis en cause, moderateur). */
export interface ReportPersonRef {
  id: string
  firstName: string
  lastName: string
}

export interface AdminReportResponse {
  id: string
  reason: ReportReason
  status: ReportStatus
  detail: string
  createdAt: string
  reporter: ReportPersonRef
  target: ReportPersonRef
  tripId: string | null
  bookingId: string | null
  /** Note de cloture (RESOLVED / DISMISSED), obligatoire cote serveur ; null tant que le dossier est ouvert. */
  resolutionNote: string | null
  resolvedBy: ReportPersonRef | null
  resolvedAt: string | null
  /** Signalements deja clos ou en cours contre la meme personne, celui-ci exclu : un recidiviste se traite autrement. */
  priorReportsAgainstTarget: number
}

/**
 * GET /admin/reports/{id}/conversations : echanges lies au signalement (reservation ou
 * trajet). Lecture journalisee cote serveur - c'est une intrusion dans la messagerie,
 * reservee a l'instruction d'un dossier.
 */
export interface AdminReportConversationResponse {
  conversationId: string
  bookingId: string | null
  tripId: string | null
  participants: ReportPersonRef[]
  messages: { id: string; senderId: string; body: string; createdAt: string }[]
}

export interface AdminVerificationResponse {
  id: string
  userId: string
  firstName: string
  lastName: string
  phone: string
  documentType: 'CNI' | 'PASSPORT' | 'DRIVER_LICENSE'
  documentNumber: string
  submittedAt: string
  status: IdentityVerificationStatus
  /** Date de la decision (validation ou refus) ; null tant que le dossier est en attente. */
  reviewedAt: string | null
  /** Motif transmis a l'utilisateur en cas de refus ; null sinon. */
  rejectionReason: string | null
  /** Pieces televersees (V20) ; le contenu se lit face par face, consultation journalisee. */
  documents: IdentityDocumentSummary[]
}

/** Valeurs de l'enum backend PayoutStatus ; la vue admin expose `PAID` pour `SETTLED`. */
export type PayoutStatus = 'PENDING' | 'PROCESSING' | 'PAID' | 'SETTLED' | 'FAILED'

export interface AdminPayoutResponse {
  id: string
  driverId: string
  driverName: string
  provider: PaymentProvider | null
  phone: string | null
  amount: number
  tripCount: number
  periodStart: string
  periodEnd: string
  status: PayoutStatus
  paidAt: string | null
  /** Reservations remboursees apres inclusion dans un lot deja traite : montant a deduire du prochain virement. */
  reversedCount: number
  reversedAmount: number
  /** Reference du virement mobile money saisie au reglement (identifiant operateur). */
  externalReference: string | null
  /** Motif saisi quand le virement a echoue (numero invalide, plafond, operateur en panne). */
  failureReason: string | null
  settledAt: string | null
}

/**
 * POST /admin/payouts/{id}/settle : marque un lot regle apres virement manuel. La
 * reference externe permet de retrouver l'operation chez l'operateur ; le montant
 * n'est renseigne que s'il differe du montant du lot (frais retenus).
 */
export interface SettlePayoutRequest {
  externalReference: string
  settledAmountFcfa?: number
}

/** POST /admin/payouts/{id}/fail : virement impossible, motif obligatoire. */
export interface FailPayoutRequest {
  reason: string
}

/** POST /admin/payouts/{id}/settle et GET /me/payouts : lot de reversement brut (dto.payout.PayoutResponse). */
export interface PayoutResponse {
  id: string
  driverId: string
  amount: number
  status: PayoutStatus
  destinationMsisdn: string | null
  periodStart: string
  periodEnd: string
  requestedAt: string
  settledAt: string | null
}

/** POST /admin/payouts/run */
export interface PayoutBatchResultResponse {
  payoutsCreated: number
  totalAmountFcfa: number
  payouts: PayoutResponse[]
  /** Conducteurs eligibles mais exclus du lot (aucun compte mobile money verifie). */
  skipped: { driverId: string; driverName: string; amountFcfa: number; reason: string }[]
}

/** Statut interne d un paiement (dto.payment.AdminPaymentResponse). */
export type PaymentRecordStatus = 'INITIATED' | 'SUCCEEDED' | 'FAILED' | 'REFUND_PENDING' | 'REFUNDED' | 'REFUND_MANUAL'

export type AdminPaymentsFilter = 'TODO' | 'REFUNDED' | 'ALL'

/** GET /admin/payments : paiements a suivre (remboursements). */
export interface AdminPaymentResponse {
  id: string
  bookingId: string | null
  subscriptionId: string | null
  passengerId: string | null
  passengerName: string | null
  passengerPhone: string | null
  providerTxId: string
  amount: number
  status: PaymentRecordStatus
  refundAmount: number | null
  refundReason: string | null
  refundRequestedAt: string | null
  refundAttempts: number
  refundLastError: string | null
  refundedAt: string | null
  createdAt: string
}

/** GET /admin/payment-accounts : comptes mobile money a verifier avant reversement. */
export interface AdminPaymentAccountResponse {
  id: string
  userId: string
  userName: string
  userPhone: string
  provider: PaymentProvider
  phone: string
  label: string | null
  isDefault: boolean
  verifiedAt: string | null
  createdAt: string
}

/** GET /me/payouts/balance */
export interface DriverBalanceResponse {
  pendingBalanceFcfa: number
  minimumPayoutThresholdFcfa: number
}

export type SubscriptionStatus = 'PENDING_PAYMENT' | 'ACTIVE' | 'EXPIRED' | 'CANCELLED'

/** GET /me/subscription : abonnement conducteur (2 000 FCFA/mois, commission a 0 %). */
export interface SubscriptionResponse {
  id: string | null
  priceFcfa: number
  status: SubscriptionStatus | null
  currentlyActive: boolean
  startedAt: string | null
  currentPeriodEnd: string | null
}

/** POST /reports : exactement une cible (utilisateur ou trajet). */
export interface CreateReportRequest {
  reportedUserId?: string
  reportedTripId?: string
  reasonCode: ReportReason
  details?: string
}

export interface ReportResponse {
  id: string
  reporterId: string
  reportedUserId: string | null
  reportedTripId: string | null
  reasonCode: string
  details: string | null
  status: ReportStatus
  resolutionNote: string | null
  createdAt: string
  resolvedAt: string | null
}

/** GET /admin/audit-log (pagine). */
export interface AuditLogResponse {
  id: string
  actorId: string | null
  /** Nom de l'acteur au moment de la lecture ; null pour le systeme ou un compte anonymise. */
  actorName: string | null
  action: string
  entityType: string | null
  entityId: string | null
  details: Record<string, unknown> | null
  createdAt: string
}

/** Filtres de GET /admin/audit-log ; chaque champ vide est omis de la requete. */
export interface AuditLogFilters {
  action?: string
  actorId?: string
  entityType?: string
  entityId?: string
  /** Bornes ISO (date ou date-heure), inclusives. */
  from?: string
  to?: string
}

/** PATCH /trips/{id} : chaque champ absent est laisse inchange. */
export interface UpdateTripRequest {
  originLabel?: string
  originLat?: number
  originLng?: number
  destLabel?: string
  destLat?: number
  destLng?: number
  departureAt?: string
  seatsTotal?: number
  pricePerSeat?: number
  instantBooking?: boolean
  luggagePolicy?: string | null
  description?: string | null
}

export interface AdminUserResponse {
  id: string
  firstName: string
  lastName: string
  phone: string
  email: string | null
  createdAt: string
  identityVerified: boolean
  phoneVerified: boolean
  /** L'adresse a recu et valide un code de connexion. */
  emailVerified?: boolean
  /** Derniere ouverture de session ; null pour un compte jamais connecte. */
  lastLoginAt?: string | null
  suspended: boolean
  /**
   * Compte anonymise (droit a l'effacement) : profil remplace, connexion impossible.
   * Optionnel tant que le backend du lot 1.4 n'expose pas le champ : absent = compte vivant.
   */
  anonymizedAt?: string | null
  tripsPublished: number
  bookingsMade: number
  ratingAvg: number
  /** ADMIN : compte du back-office, jamais suspendable depuis l'interface. */
  role: 'USER' | 'ADMIN'
}

/**
 * GET /admin/overview : les files d'attente du back-office, rafraichies chaque
 * minute. Chaque chiffre repond a « qu'est-ce qui attend une decision ? ».
 */
export interface AdminOverviewResponse {
  openReports: number
  inReviewReports: number
  pendingVerifications: number
  /** Date de depot du plus vieux dossier en attente ; null si la file est vide. */
  oldestPendingVerificationAt: string | null
  pendingPayouts: number
  pendingPayoutsAmountFcfa: number
  /** Paiements REFUND_PENDING + REFUND_MANUAL. */
  refundsToHandle: number
}

/** Statut de compte, valeurs de l'enum backend UserStatus. */
export type AdminUserStatus = 'PENDING_VERIFICATION' | 'ACTIVE' | 'SUSPENDED' | 'DELETED'

export interface AdminUserVehicle {
  id: string
  brand: string
  model: string
  color: string | null
  plate: string
  seats: number
  comfortLevel: ComfortLevel
  verified: boolean
}

export interface AdminUserPaymentAccount {
  id: string
  provider: PaymentProvider
  phone: string
  isDefault: boolean
  verified: boolean
}

/** GET /admin/users/{id} : fiche complete d'un utilisateur, pour instruire un dossier. */
export interface AdminUserDetailResponse {
  id: string
  firstName: string
  lastName: string
  phone: string
  email: string | null
  role: 'USER' | 'ADMIN'
  /** Enum backend ; tolere une valeur inconnue plutot que de casser l'ecran. */
  status: AdminUserStatus | string
  suspendedReason: string | null
  suspendedAt: string | null
  createdAt: string
  identity: {
    status: IdentityVerificationStatus
    documentType: 'CNI' | 'PASSPORT' | 'DRIVER_LICENSE' | null
    /** Quatre derniers caracteres du numero de piece : jamais le numero complet a l'ecran. */
    documentLast4: string | null
  } | null
  identityVerified: boolean
  emailVerified?: boolean
  lastLoginAt?: string | null
  vehicles: AdminUserVehicle[]
  paymentAccounts: AdminUserPaymentAccount[]
  tripsPublished: number
  bookingsMade: number
  ratingAvg: number
  /** Annulations a moins de 24 h du depart : le signal d'un passager peu fiable. */
  lateCancellationsCount: number
  anonymizedAt: string | null
}

/**
 * GET /admin/users/{id}/bookings : reservations d'un utilisateur, vue minimale.
 * Champs optionnels : le contrat est tolerant, l'ecran affiche « — » quand absent.
 */
export interface AdminUserBookingResponse {
  id: string
  tripId?: string
  seats?: number
  amount?: number
  status?: BookingStatus
  paymentMethod?: PaymentMethod
  createdAt?: string
  trip?: {
    originLabel?: string
    destLabel?: string
    departureAt?: string
  } | null
}
