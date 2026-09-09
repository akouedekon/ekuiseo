import type { BookingStatus, PassengerConfirmation } from '@/api/types'

/*
 * Constat du passager apres le depart (V21) : « le trajet a eu lieu » ou « le conducteur
 * n est pas venu ». Memes regles que BookingService#requirePassengerConfirmable et
 * PASSENGER_CONFIRMATION_WINDOW cote serveur ; le serveur reste seul juge.
 */

/** Delai pendant lequel le passager peut declarer un conducteur absent : jusqu a l eligibilite au reversement. */
export const PASSENGER_CONFIRMATION_WINDOW_MS = 24 * 60 * 60 * 1000

export type TripConfirmationState =
  /** Trajet parti, reservation honoree, rien declare, dans les 24 h : on pose la question. */
  | 'ask'
  /** Passe 24 h sans reponse : confirmation tacite, plus rien a declarer. */
  | 'tacit'
  /** Le passager a confirme le trajet. */
  | 'done'
  /** Le passager a declare le conducteur absent : signalement en cours. */
  | 'driver-no-show'
  /** Pas concerne (trajet a venir, reservation annulee, expiree, passager absent...). */
  | 'none'

export function tripConfirmationState(
  booking: { status: BookingStatus; passengerConfirmation?: PassengerConfirmation | null; trip: { departureAt: string } },
  now: number = Date.now(),
): TripConfirmationState {
  if (booking.status === 'DRIVER_NO_SHOW' || booking.passengerConfirmation === 'DRIVER_NO_SHOW') return 'driver-no-show'
  if (booking.status !== 'CONFIRMED' && booking.status !== 'COMPLETED') return 'none'
  const departure = new Date(booking.trip.departureAt).getTime()
  if (now < departure) return 'none'
  if (booking.passengerConfirmation === 'TRIP_DONE') return 'done'
  return now <= departure + PASSENGER_CONFIRMATION_WINDOW_MS ? 'ask' : 'tacit'
}
