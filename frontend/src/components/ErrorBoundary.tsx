import { Component, type ErrorInfo, type ReactNode } from 'react'
import { ErrorPage } from '@/pages/SystemPages'

interface State {
  error: Error | null
}

/**
 * Frontiere d'erreur globale : un plantage de rendu ne doit jamais laisser
 * un ecran blanc. React n'expose pas encore d'equivalent en composant
 * fonctionnel, d'ou la classe.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // TODO(observabilite) : brancher un collecteur (Sentry ou equivalent).
    console.error('Erreur non rattrapée :', error, info.componentStack)
  }

  render() {
    if (this.state.error) {
      return <ErrorPage error={this.state.error} onReset={() => this.setState({ error: null })} />
    }
    return this.props.children
  }
}
