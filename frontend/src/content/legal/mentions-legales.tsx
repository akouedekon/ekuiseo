import { CONTACT_EMAIL } from '@/lib/legal'

/**
 * Mentions légales — version 2026-09.
 *
 * Les informations d'identification de l'éditeur ne figurent pas dans le
 * dépôt : chaque mention absente est un espace réservé « [à compléter : …] ».
 */
export function MentionsLegalesContent() {
  return (
    <>
      <h2>1. Éditeur du service</h2>
      <p>
        Le service Ekuiseo, accessible à l'adresse ekuiseo.com et sous forme d'application web installable, est
        édité par :
      </p>
      <ul>
        <li>Raison sociale : [à compléter]</li>
        <li>Forme juridique et capital : [à compléter]</li>
        <li>Numéro RCCM : [à compléter]</li>
        <li>Numéro IFU : [à compléter]</li>
        <li>Adresse du siège : [à compléter]</li>
        <li>
          E-mail : <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>
        </li>
      </ul>

      <h2>2. Directeur de la publication</h2>
      <p>[à compléter : nom et qualité du directeur de la publication].</p>

      <h2>3. Hébergement</h2>
      <p>
        Le service est hébergé sur un serveur virtuel privé fourni par <strong>OVH</strong> [à compléter : entité
        contractante, adresse du siège et localisation du centre de données]. Le nom de domaine ekuiseo.com est
        enregistré auprès de [à compléter : registrar].
      </p>

      <h2>4. Prestataire de paiement</h2>
      <p>
        Les paiements en mobile money (acomptes, paiements intégraux, abonnements) et les remboursements sont
        traités par <strong>Kkiapay</strong>, agrégateur de paiement établi au Bénin [à compléter : raison sociale et
        adresse de Kkiapay]. Ekuiseo ne collecte ni ne conserve de données bancaires.
      </p>

      <h2>5. Protection des données</h2>
      <p>
        Le traitement des données à caractère personnel est décrit dans la politique de confidentialité. Autorité
        de contrôle : Autorité de Protection des Données à caractère Personnel du Bénin (APDP). Numéro de
        déclaration ou d'autorisation : [à compléter].
      </p>

      <h2>6. Propriété intellectuelle</h2>
      <p>
        L'ensemble des éléments composant le service (structure, textes, interface, logotype, code) est protégé par
        le droit de la propriété intellectuelle et reste la propriété exclusive de l'éditeur ou de ses ayants droit.
        Toute reproduction, représentation, adaptation ou extraction, totale ou partielle, sans autorisation écrite
        préalable est interdite. Les contenus publiés par les utilisateurs (profils, avis, trajets) restent leur
        propriété ; en les publiant, ils accordent à Ekuiseo le droit de les afficher dans le cadre du service.
      </p>

      <h2>7. Marque</h2>
      <p>
        « Ekuiseo » est une marque utilisée par l'éditeur [à compléter : dépôt à l'OAPI, numéro et classes]. Le nom
        Ekuiseo désigne également un autre produit du même éditeur ; les présentes mentions concernent
        exclusivement le service de covoiturage.
      </p>

      <h2>8. Contact</h2>
      <p>
        Pour toute question, réclamation ou demande relative au service :{' '}
        <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>.
      </p>
    </>
  )
}
