import { useCallback, useEffect, useState } from 'react'

/*
 * Invitation a installer l'application (audit, section 5 #21) : bandeau discret
 * a partir de la deuxieme visite, jamais avant. Un refus est memorise ; une
 * installation deja faite (mode standalone) ne propose rien.
 *
 * Android / Chrome : l'evenement `beforeinstallprompt` est intercepte et rejoue
 * au clic. iOS / Safari ne l'emet pas : on affiche alors le guide « Partager,
 * puis Sur l'écran d'accueil ».
 */

const VISITS_KEY = 'ekuiseo.pwa.visits'
const DISMISSED_KEY = 'ekuiseo.pwa.dismissed'
/** Nombre de visites (ouvertures de l'application) avant de proposer l'installation. */
export const INSTALL_PROMPT_AFTER_VISITS = 2

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

function readNumber(key: string): number {
  try {
    return Number(localStorage.getItem(key)) || 0
  } catch {
    return 0
  }
}

function write(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    /* stockage indisponible : le bandeau ne s'affiche simplement pas */
  }
}

function isStandalone(): boolean {
  if (typeof window === 'undefined') return true
  const nav = navigator as Navigator & { standalone?: boolean }
  return window.matchMedia?.('(display-mode: standalone)').matches === true || nav.standalone === true
}

function isIos(): boolean {
  if (typeof navigator === 'undefined') return false
  return /iphone|ipad|ipod/i.test(navigator.userAgent)
}

/** Compte une visite (une fois par chargement de l'application). Appele par AppShell. */
let visitCounted = false
function countVisit(): number {
  const visits = readNumber(VISITS_KEY)
  if (visitCounted) return visits
  visitCounted = true
  const next = visits + 1
  write(VISITS_KEY, String(next))
  return next
}

export type InstallMode = 'native' | 'ios-guide'

export interface PwaInstallState {
  /** Faut-il afficher l'invitation maintenant ? */
  visible: boolean
  /** `native` = bouton qui ouvre l'invite du navigateur ; `ios-guide` = etapes Safari. */
  mode: InstallMode
  install: () => Promise<void>
  dismiss: () => void
}

export function usePwaInstall(): PwaInstallState {
  const [deferred, setDeferred] = useState<BeforeInstallPromptEvent | null>(null)
  const [visits, setVisits] = useState(0)
  const [dismissed, setDismissed] = useState(() => readNumber(DISMISSED_KEY) > 0)
  const [installed, setInstalled] = useState(() => isStandalone())

  useEffect(() => {
    setVisits(countVisit())
    const onPrompt = (event: Event) => {
      event.preventDefault()
      setDeferred(event as BeforeInstallPromptEvent)
    }
    const onInstalled = () => setInstalled(true)
    window.addEventListener('beforeinstallprompt', onPrompt)
    window.addEventListener('appinstalled', onInstalled)
    return () => {
      window.removeEventListener('beforeinstallprompt', onPrompt)
      window.removeEventListener('appinstalled', onInstalled)
    }
  }, [])

  const ios = isIos()
  const mode: InstallMode = deferred ? 'native' : 'ios-guide'
  const visible = !installed && !dismissed && visits >= INSTALL_PROMPT_AFTER_VISITS && (deferred !== null || ios)

  const dismiss = useCallback(() => {
    setDismissed(true)
    write(DISMISSED_KEY, String(Date.now()))
  }, [])

  const install = useCallback(async () => {
    if (!deferred) return
    await deferred.prompt()
    const choice = await deferred.userChoice
    setDeferred(null)
    if (choice.outcome === 'accepted') setInstalled(true)
    else dismiss()
  }, [deferred, dismiss])

  return { visible, mode, install, dismiss }
}
