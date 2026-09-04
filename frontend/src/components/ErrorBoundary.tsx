import { Component, type ErrorInfo, type ReactNode } from 'react'
import { ErrorPage } from '@/pages/SystemPages'

interface State {
  error: Error | null
}

/**
 * Frontiere d'erreur globale : un plantage de rendu ne doit jamais laisser
 * un ecran blanc. React n'expose pas encore d'equivalent en composant
 * fonctionnel, d'ou la classe. Doit etre montee A L'INTERIEUR du routeur :
 * l'ecran de secours contient des liens.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Le detail technique reste ici (console du navigateur) ; l'utilisateur voit un message generique.
    console.error('Erreur non rattrapée :', error, info.componentStack)
  }

  render() {
    if (this.state.error) {
      return <ErrorPage onReset={() => this.setState({ error: null })} />
    }
    return this.props.children
  }
}
