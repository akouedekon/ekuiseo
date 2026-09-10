import { useSyncExternalStore } from 'react'

/*
 * Ce que l ecran courant dit de lui-meme a la coque (AppTopBar, en dessous de 768 px) :
 * son titre court (pose par PageMeta) et la destination de repli de son bouton retour
 * (posee par PageHeader quand l historique de l application est vide). Un magasin
 * minuscule plutot qu un contexte : les pages ecrivent, la barre haute lit, sans que
 * l arbre React ait a etre re-rendu au-dela de la barre.
 */
export interface ScreenState {
  /** Titre court de l ecran, sans le nom du site. */
  title: string | null
  /** Destination du retour quand il n y a pas d ecran precedent (lien partage). */
  backTo: string | null
}

let state: ScreenState = { title: null, backTo: null }
const listeners = new Set<() => void>()

function update(patch: Partial<ScreenState>) {
  state = { ...state, ...patch }
  for (const listener of listeners) listener()
}

export function setScreenTitle(title: string | null): void {
  if (state.title !== title) update({ title })
}

export function setScreenBackTo(backTo: string | null): void {
  if (state.backTo !== backTo) update({ backTo })
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function useScreen(): ScreenState {
  return useSyncExternalStore(subscribe, () => state, () => state)
}
