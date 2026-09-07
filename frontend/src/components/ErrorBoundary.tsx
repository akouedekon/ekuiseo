import { Component, type ErrorInfo, type ReactNode } from 'react'
import { useLocation } from 'react-router'
import { isStaleChunkError, reportError } from '@/lib/monitoring'
import { ErrorPage } from '@/pages/SystemPages'

interface State {
  error: Error | null
  /** Route pour laquelle l'erreur a ete capturee : une autre route la retire. */
  resetKey: string
}

interface BoundaryProps {
  children: ReactNode
  /** Change a chaque navigation : l'ecran de secours se retire quand l'utilisateur change de route (audit F235). */
  resetKey: string
}

const RELOAD_FLAG = 'ekuiseo.chunkReload'

/**
 * Import paresseux casse : un deploiement a remplace les chunks haches et le
 * navigateur tient encore l'ancien index. Un rechargement (une seule fois, pour
 * ne pas boucler) recupere le nouveau paquet (audit F344).
 */
function reloadOnce(): boolean {
  try {
    if (sessionStorage.getItem(RELOAD_FLAG) === '1') return false
    sessionStorage.setItem(RELOAD_FLAG, '1')
  } catch {
    return false
  }
  window.location.reload()
  return true
}

class ErrorBoundaryInner extends Component<BoundaryProps, State> {
  constructor(props: BoundaryProps) {
    super(props)
    this.state = { error: null, resetKey: props.resetKey }
  }

  static getDerivedStateFromError(error: Error): Partial<State> {
    return { error }
  }

  /** Nouvelle route pendant qu'une erreur est affichee : on la retire et on retente le rendu. */
  static getDerivedStateFromProps(props: BoundaryProps, state: State): Partial<State> | null {
    if (props.resetKey !== state.resetKey) {
      return { error: null, resetKey: props.resetKey }
    }
    return null
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    if (isStaleChunkError(error) && reloadOnce()) return
    reportError(error, { source: 'componentDidCatch', componentStack: info.componentStack ?? undefined })
  }

  render() {
    if (this.state.error) {
      return <ErrorPage onReset={() => this.setState({ error: null })} />
    }
    return this.props.children
  }
}

/**
 * Frontiere d'erreur globale : un plantage de rendu ne doit jamais laisser
 * un ecran blanc. React n'expose pas encore d'equivalent en composant
 * fonctionnel, d'ou la classe. Doit etre montee A L'INTERIEUR du routeur :
 * l'ecran de secours contient des liens, et la frontiere se reinitialise au
 * changement de route.
 */
export function ErrorBoundary({ children }: { children: ReactNode }) {
  const location = useLocation()
  return <ErrorBoundaryInner resetKey={location.pathname}>{children}</ErrorBoundaryInner>
}
