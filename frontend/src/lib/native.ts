import { Capacitor } from '@capacitor/core'

/*
 * Coque native (application Android/iOS Capacitor, dossier mobile/). Le meme site est
 * charge dans un WebView ; `window.Capacitor` y est injecte par le pont natif. Tout ce
 * qui n a de sens que dans l application passe par ici : detection, barre d etat,
 * bouton « retour » d Android, feuille de partage, vibrations, appareil photo.
 *
 * Les greffons sont importes a la demande : dans un navigateur, aucun octet de plus.
 * Chaque fonction est sans effet (ou retombe sur l API web) hors de l application, pour
 * que les composants n aient jamais a distinguer les deux cas.
 */

/** Vrai dans l application Android/iOS, faux dans un navigateur (PWA installee comprise). */
export function isNativeApp(): boolean {
  try {
    return Capacitor.isNativePlatform()
  } catch {
    return false
  }
}

/** « android » / « ios » dans l application, « web » sinon. */
export function nativePlatform(): 'android' | 'ios' | 'web' {
  const platform = isNativeApp() ? Capacitor.getPlatform() : 'web'
  return platform === 'android' || platform === 'ios' ? platform : 'web'
}

/** Chemins ou le bouton « retour » d Android ferme l application au lieu de reculer. */
const ROOT_PATHS = new Set(['/', '/bookings', '/publish', '/messages', '/me', '/autour', '/login'])

let initialised = false

/**
 * A appeler une fois au demarrage (main.tsx). Pose la classe `app-native` sur <html>
 * (styles d application : zones sures, pas de surlignage tactile, pas de rebond), regle la
 * barre d etat, et branche le bouton « retour » d Android : recule dans l historique, ou
 * quitte l application depuis un ecran racine.
 */
export async function initNativeShell(): Promise<void> {
  if (initialised || !isNativeApp()) return
  initialised = true
  document.documentElement.classList.add('app-native', `app-${nativePlatform()}`)
  await syncStatusBar(document.documentElement.classList.contains('dark'))
  try {
    const { App } = await import('@capacitor/app')
    await App.addListener('backButton', ({ canGoBack }) => {
      const path = window.location.pathname.replace(/\/$/, '') || '/'
      if (canGoBack && !ROOT_PATHS.has(path)) {
        window.history.back()
      } else {
        void App.exitApp()
      }
    })
  } catch {
    /* greffon absent : le bouton retour garde le comportement du WebView */
  }
  try {
    const { Keyboard } = await import('@capacitor/keyboard')
    // Le WebView se redimensionne avec le clavier : les champs restent visibles, les barres fixes aussi.
    await Keyboard.setResizeMode({ mode: 'native' as never })
  } catch {
    /* iOS uniquement pour setResizeMode, ou greffon absent */
  }
}

/** Barre d etat aux couleurs du theme (appelee au demarrage et a chaque changement de theme). */
export async function syncStatusBar(dark: boolean): Promise<void> {
  if (!isNativeApp()) return
  try {
    const { StatusBar, Style } = await import('@capacitor/status-bar')
    await StatusBar.setStyle({ style: dark ? Style.Dark : Style.Light })
    if (nativePlatform() === 'android') {
      await StatusBar.setBackgroundColor({ color: dark ? '#0d0d0f' : '#f6f6f3' })
      await StatusBar.setOverlaysWebView({ overlay: false })
    }
  } catch {
    /* greffon absent */
  }
}

/** Petite vibration de confirmation (reservation, constat, envoi) ; rien dans un navigateur. */
export async function hapticSuccess(): Promise<void> {
  if (!isNativeApp()) return
  try {
    const { Haptics, NotificationType } = await import('@capacitor/haptics')
    await Haptics.notification({ type: NotificationType.Success })
  } catch {
    /* greffon absent */
  }
}

/** Vibration legere au toucher d un element important (bouton principal, selection). */
export async function hapticTap(): Promise<void> {
  if (!isNativeApp()) return
  try {
    const { Haptics, ImpactStyle } = await import('@capacitor/haptics')
    await Haptics.impact({ style: ImpactStyle.Light })
  } catch {
    /* greffon absent */
  }
}

/**
 * Feuille de partage : greffon natif dans l application, `navigator.share` dans un
 * navigateur qui l offre. Renvoie faux si aucun des deux n est disponible (l appelant
 * copie alors le lien). Une annulation par l utilisateur n est pas une erreur.
 */
export async function shareNative(data: { title: string; text: string; url: string }): Promise<boolean> {
  if (isNativeApp()) {
    try {
      const { Share } = await import('@capacitor/share')
      await Share.share({ title: data.title, text: data.text, url: data.url, dialogTitle: data.title })
      return true
    } catch (error) {
      if (isUserCancellation(error)) return true
      /* greffon absent ou en echec : on tente l API web ci-dessous */
    }
  }
  if (typeof navigator !== 'undefined' && typeof navigator.share === 'function') {
    try {
      await navigator.share(data)
    } catch (error) {
      if (!isUserCancellation(error)) throw error
    }
    return true
  }
  return false
}

function isUserCancellation(error: unknown): boolean {
  if (error instanceof DOMException && error.name === 'AbortError') return true
  const message = error instanceof Error ? error.message : String(error)
  return /cancel|annul|abort/i.test(message)
}

export type PhotoSource = 'camera' | 'photos'

/**
 * Photo prise ou choisie via l appareil (pieces d identite) : renvoyee comme un File JPEG,
 * que le reste du parcours traite exactement comme un fichier du navigateur. Null si
 * l utilisateur renonce. A n appeler que dans l application (`isNativeApp()`).
 */
export async function pickPhoto(source: PhotoSource): Promise<File | null> {
  const { Camera, CameraResultType, CameraSource } = await import('@capacitor/camera')
  try {
    const photo = await Camera.getPhoto({
      resultType: CameraResultType.Uri,
      source: source === 'camera' ? CameraSource.Camera : CameraSource.Photos,
      quality: 85,
      width: 2000,
      correctOrientation: true,
      promptLabelHeader: 'Photo de la pièce',
      promptLabelCancel: 'Annuler',
    })
    if (!photo.webPath) return null
    const blob = await fetch(photo.webPath).then((response) => response.blob())
    const extension = (photo.format || 'jpeg').toLowerCase()
    return new File([blob], `piece.${extension === 'jpg' ? 'jpeg' : extension}`, {
      type: blob.type || `image/${extension === 'jpg' ? 'jpeg' : extension}`,
      lastModified: Date.now(),
    })
  } catch (error) {
    if (isUserCancellation(error)) return null
    throw error
  }
}
