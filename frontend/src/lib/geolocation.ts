/*
 * Position de l appareil, une fois (« Autour de moi », « Ma position » du formulaire de
 * recherche). Enveloppe minimale de navigator.geolocation : haute precision, 10 s au plus,
 * erreurs traduites en francais. Sans dependance : un autre lot branchera le plugin natif
 * de l application mobile derriere cette meme fonction, l interface doit rester celle-ci.
 * Le suivi continu du conducteur (watchPosition) vit ailleurs (features/trips/LiveSharingControl).
 */

export interface DevicePosition {
  lat: number
  lng: number
  /** Precision annoncee par l appareil, en metres. */
  accuracyM: number
}

export interface PositionOptions {
  /** Delai maximal avant abandon (ms), 10 s par defaut. */
  timeoutMs?: number
  /** Age maximal d une position en cache (ms), 0 par defaut : une mesure fraiche. */
  maximumAgeMs?: number
  /** GPS plutot que cellule/wifi quand c est possible ; vrai par defaut. */
  highAccuracy?: boolean
}

export type GeolocationErrorCode = 'unsupported' | 'denied' | 'unavailable' | 'timeout'

/** Erreur de position, avec un message deja redige pour l ecran et un code pour brancher un repli. */
export class GeolocationError extends Error {
  readonly code: GeolocationErrorCode

  constructor(code: GeolocationErrorCode, message: string) {
    super(message)
    this.name = 'GeolocationError'
    this.code = code
  }
}

export const DEFAULT_POSITION_TIMEOUT_MS = 10_000

/** Textes des erreurs, une seule source : l ecran les affiche tels quels. */
export const GEOLOCATION_MESSAGES: Record<GeolocationErrorCode, string> = {
  unsupported: 'Cet appareil ne permet pas de connaître votre position.',
  denied: 'Vous avez refusé l’accès à votre position. Autorisez-le dans les réglages du navigateur, ou indiquez un lieu de départ.',
  unavailable: 'Position introuvable pour l’instant : vérifiez que la localisation est activée et réessayez.',
  timeout: 'La position n’a pas pu être obtenue à temps. Réessayez, ou indiquez un lieu de départ.',
}

export function isGeolocationSupported(): boolean {
  return typeof navigator !== 'undefined' && !!navigator.geolocation && typeof navigator.geolocation.getCurrentPosition === 'function'
}

/** Code de l erreur du navigateur (1 = refus, 2 = indisponible, 3 = delai) vers le notre. */
export function translateGeolocationError(error: { code?: number } | null | undefined): GeolocationError {
  switch (error?.code) {
    case 1:
      return new GeolocationError('denied', GEOLOCATION_MESSAGES.denied)
    case 3:
      return new GeolocationError('timeout', GEOLOCATION_MESSAGES.timeout)
    default:
      return new GeolocationError('unavailable', GEOLOCATION_MESSAGES.unavailable)
  }
}

/**
 * Position courante de l appareil. Rejette toujours avec une {@link GeolocationError} :
 * `unsupported` sans API, `denied`, `unavailable` ou `timeout` sinon. Une coordonnee non
 * finie (rare, appareil defaillant) compte comme `unavailable`.
 */
export function getCurrentPosition(options: PositionOptions = {}): Promise<DevicePosition> {
  if (!isGeolocationSupported()) {
    return Promise.reject(new GeolocationError('unsupported', GEOLOCATION_MESSAGES.unsupported))
  }
  return new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(
      (position) => {
        const { latitude, longitude, accuracy } = position.coords
        if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
          reject(new GeolocationError('unavailable', GEOLOCATION_MESSAGES.unavailable))
          return
        }
        resolve({ lat: latitude, lng: longitude, accuracyM: Number.isFinite(accuracy) ? accuracy : 0 })
      },
      (error) => reject(translateGeolocationError(error)),
      {
        enableHighAccuracy: options.highAccuracy ?? true,
        timeout: options.timeoutMs ?? DEFAULT_POSITION_TIMEOUT_MS,
        maximumAge: options.maximumAgeMs ?? 0,
      },
    )
  })
}
