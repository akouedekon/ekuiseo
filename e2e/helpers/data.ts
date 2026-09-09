/*
 * Donnees de reference des parcours : comptes du jeu de demonstration
 * (docs/donnees-demo.sql) et trajet dedie aux tests (e2e/seed-e2e.sql).
 */

/** Compte de demonstration du back-office, promu ADMIN par e2e/seed-e2e.sql. */
export const SEED_ADMIN = {
  phone: '+2290190000000',
  email: 'admin@ekuiseo.bj',
  firstName: 'Fabrice',
  lastName: 'Houngbedji',
}

/** Conducteur du seed a identite verifiee (le mode especes lui est reserve). */
export const SEED_DRIVER = {
  phone: '+2290197001006',
  email: 'marcellin.sagbo@example.bj',
  firstName: 'Marcellin',
  lastName: 'Sagbo',
  /** Forme affichee sur une carte de resultat : « Prenom N. ». */
  cardName: 'Marcellin S.',
  /** Vehicule enregistre pour ce conducteur, tel qu'affiche dans la liste de publication. */
  vehicle: /Renault Logan/,
}

/** Trajet insere par e2e/seed-e2e.sql : Cotonou -> Bohicon, J+3 a 09:00 (heure du Benin), 4 places. */
export const E2E_TRIP = {
  originLabel: 'Cotonou, gare Jonquet',
  destLabel: 'Bohicon, gare routiere',
  daysAhead: 3,
}

/** Villes du referentiel serveur (geo_places, V3) telles qu'elles apparaissent dans l'autocompletion. */
export const CITY = {
  cotonou: { query: 'Cotonou', option: /^Cotonou\s+Littoral$/, label: 'Cotonou' },
  bohicon: { query: 'Bohicon', option: /^Bohicon\s+Zou$/, label: 'Bohicon' },
}

export const BENIN_TIME_ZONE = 'Africa/Porto-Novo'

/** Jour civil au Benin, « AAAA-MM-JJ », decale de `days` jours : la cle que compare le serveur. */
export function beninDatePlusDays(days: number): string {
  const now = new Date()
  // Le formateur en-CA produit directement AAAA-MM-JJ dans le fuseau demande.
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: BENIN_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now)
  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value)
  const utcMidnight = Date.UTC(get('year'), get('month') - 1, get('day') + days)
  return new Date(utcMidnight).toISOString().slice(0, 10)
}
