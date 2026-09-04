/*
 * Conventions de lecture des chiffres du back-office, partagees par le tableau
 * de bord et la page Liquidite. Fichier sans composant (Fast Refresh exige que
 * les fichiers de composants n'exportent que des composants).
 *
 * Graphiques : une seule serie par axe, pas d'effet 3D, pas de degrade
 * decoratif. Les couleurs viennent des tokens et portent du sens :
 * indigo = volume, vert = revenu / bon signe, ocre = attente, vermillon = perte.
 */
export const CHART = {
  indigo: 'var(--indigo)',
  vert: 'var(--vert)',
  ocre: 'var(--ocre)',
  vermillon: 'var(--vermillon)',
  rule: 'var(--rule)',
  muted: 'var(--muted)',
}

export type DeltaUnit = '%' | 'pts' | 'h'

/** « 66,7 % » - un taux se lit avec sa virgule et son signe, jamais 66.7. */
export function formatPercent(value: number, digits = 1): string {
  return `${value.toFixed(digits).replace('.', ',')} %`
}

/** « 12,3 h », « 45 min » - delai en heures decimales, arrondi a la minute sous une heure. */
export function formatHours(hours: number): string {
  if (hours < 1) return `${Math.round(hours * 60)} min`
  return `${hours.toFixed(1).replace('.', ',')} h`
}

/** Variation relative en % (convention du backend : 0 si les deux valent 0, +100 depuis 0). */
export function relativeDelta(current: number, previous: number): number {
  if (previous === 0) return current === 0 ? 0 : 100
  return Math.round(((current - previous) / previous) * 1000) / 10
}

/** Variation en points entre deux taux deja exprimes en %. */
export function pointsDelta(current: number, previous: number): number {
  return Math.round((current - previous) * 10) / 10
}
