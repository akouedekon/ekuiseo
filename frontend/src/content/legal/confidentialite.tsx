import { CONTACT_EMAIL } from '@/lib/legal'

/**
 * Politique de confidentialité — version 2026-09.
 *
 * Rédigée à partir de `docs/CONFORMITE.md` (catégories de données, durées de
 * conservation proposées, sous-traitants, droits) et de ce que le code fait
 * réellement (anonymisation du compte, purge des traces de recherche à 180 jours,
 * numéro de pièce masqué dans le journal d'audit). Les durées de conservation
 * reprennent les hypothèses de travail de CONFORMITE.md et restent à confirmer.
 */
export function ConfidentialiteContent() {
  return (
    <>
      <p>
        Cette politique explique quelles données à caractère personnel Ekuiseo collecte lorsque vous utilisez la
        plateforme de covoiturage, pourquoi, combien de temps elles sont conservées, avec qui elles sont partagées
        et comment exercer vos droits. Elle s'inscrit dans le cadre de la loi n° 2017-20 portant code du numérique
        en République du Bénin et sous le contrôle de l'Autorité de Protection des Données à caractère Personnel
        (APDP).
      </p>

      <h2>1. Responsable de traitement</h2>
      <p>
        Le responsable du traitement est l'entité qui exploite Ekuiseo : [à compléter : raison sociale, forme
        juridique, RCCM, IFU, adresse du siège]. Contact pour toute question relative aux données :{' '}
        <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>. Numéro de déclaration ou d'autorisation auprès de
        l'APDP : [à compléter : numéro et date, une fois la démarche effectuée].
      </p>

      <h2>2. Données collectées</h2>
      <ul>
        <li>
          <strong>Identité</strong> : prénom, nom, photo de profil et présentation facultatives.
        </li>
        <li>
          <strong>Contact</strong> : numéro de téléphone béninois et adresse e-mail, utilisés pour la connexion par
          code à usage unique et pour les notifications.
        </li>
        <li>
          <strong>Pièce d'identité</strong> (conducteurs qui demandent la vérification) : type de pièce (carte
          nationale d'identité, passeport ou permis de conduire) et numéro déclaré. <strong>Aucune photo ni copie de
          la pièce n'est téléversée ni stockée.</strong> Le statut de la vérification (en attente, approuvée,
          rejetée) est conservé ; dans notre journal interne, le numéro n'apparaît que masqué, seuls ses derniers
          caractères restant lisibles. Le numéro est effacé lors de la suppression du compte.
        </li>
        <li>
          <strong>Véhicule</strong> (conducteurs) : marque, modèle, couleur, immatriculation et nombre de places.
        </li>
        <li>
          <strong>Localisation</strong> : lieux de départ, d'arrivée et arrêts intermédiaires des trajets publiés ou
          recherchés, sous forme de coordonnées géographiques et de libellés de ville. Ekuiseo ne suit pas votre
          position en temps réel.
        </li>
        <li>
          <strong>Réservations et paiements</strong> : trajets réservés, nombre de places, montants, mode de paiement,
          statut et référence de la transaction transmise par notre prestataire Kkiapay. <strong>Aucune donnée
          bancaire n'est stockée par Ekuiseo.</strong> Pour les reversements, les conducteurs déclarent un numéro de
          compte mobile money.
        </li>
        <li>
          <strong>Messages</strong> échangés entre passager et conducteur dans le cadre d'une réservation, avis et
          notes laissés après un trajet, signalements.
        </li>
        <li>
          <strong>Journaux techniques</strong> : adresse IP, horodatage des requêtes, type de navigateur, erreurs,
          nécessaires à la sécurité du service (limitation des tentatives de connexion, détection d'abus).
        </li>
        <li>
          <strong>Traces de recherche</strong> : chaque recherche de trajet enregistre les lieux demandés, la date, le
          nombre de places, le mode et le nombre de résultats obtenus, ainsi que votre identifiant si vous êtes
          connecté. Ces traces servent uniquement à mesurer les liaisons où l'offre manque et ne sont exploitées
          que sous forme agrégée.
        </li>
      </ul>

      <h2>3. Finalités et bases du traitement</h2>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">Finalité</th>
              <th scope="col">Données concernées</th>
              <th scope="col">Base</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>Créer et gérer votre compte, vous connecter</td>
              <td>Identité, contact, codes de connexion</td>
              <td>Exécution du contrat</td>
            </tr>
            <tr>
              <td>Publier, rechercher et réserver des trajets</td>
              <td>Localisation, véhicule, réservations</td>
              <td>Exécution du contrat</td>
            </tr>
            <tr>
              <td>Encaisser les acomptes, rembourser, reverser les conducteurs</td>
              <td>Paiements, compte mobile money</td>
              <td>Exécution du contrat, obligations comptables</td>
            </tr>
            <tr>
              <td>Vérifier l'identité des conducteurs, traiter les signalements, suspendre un compte</td>
              <td>Pièce d'identité, signalements, journal d'audit</td>
              <td>Intérêt légitime (sécurité et confiance)</td>
            </tr>
            <tr>
              <td>Permettre la messagerie et les avis entre utilisateurs</td>
              <td>Messages, avis</td>
              <td>Exécution du contrat</td>
            </tr>
            <tr>
              <td>Vous notifier (confirmation, annulation, rappel, code de connexion)</td>
              <td>Contact, préférences de notification</td>
              <td>Exécution du contrat ; préférences réglables</td>
            </tr>
            <tr>
              <td>Sécuriser le service et diagnostiquer les incidents</td>
              <td>Journaux techniques</td>
              <td>Intérêt légitime</td>
            </tr>
            <tr>
              <td>Piloter l'offre de trajets (liaisons sans résultat)</td>
              <td>Traces de recherche</td>
              <td>Intérêt légitime</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p>
        Les bases indiquées sont celles retenues comme hypothèse de travail ; leur qualification exacte au regard
        du droit béninois est à confirmer par un juriste [à compléter].
      </p>

      <h2>4. Durées de conservation</h2>
      <p>
        Les durées ci-dessous sont celles appliquées ou prévues par Ekuiseo. Certaines restent des hypothèses de
        travail en attente de validation juridique, notamment au regard des obligations comptables (droit OHADA) ;
        elles sont signalées comme telles.
      </p>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">Donnée</th>
              <th scope="col">Durée</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>Compte actif (identité, contact, véhicule)</td>
              <td>Durée de vie du compte</td>
            </tr>
            <tr>
              <td>Compte créé mais jamais activé</td>
              <td>24 heures, puis suppression automatique</td>
            </tr>
            <tr>
              <td>Compte inactif</td>
              <td>2 à 3 ans après la dernière activité, puis suppression ou anonymisation [à valider]</td>
            </tr>
            <tr>
              <td>Codes de connexion à usage unique</td>
              <td>Quelques minutes ; purgés après expiration ou utilisation</td>
            </tr>
            <tr>
              <td>Sessions (jetons de connexion)</td>
              <td>90 jours au plus, révoqués à la déconnexion, à la suspension ou à la correction de contact</td>
            </tr>
            <tr>
              <td>Trajets et réservations</td>
              <td>Durée de vie du compte, puis délai de prescription des litiges [à valider]</td>
            </tr>
            <tr>
              <td>Paiements</td>
              <td>Durée des obligations comptables et fiscales, potentiellement 10 ans [à valider]</td>
            </tr>
            <tr>
              <td>Avis et notes</td>
              <td>Durée de vie du compte concerné, sauf demande de suppression justifiée</td>
            </tr>
            <tr>
              <td>Messages entre utilisateurs</td>
              <td>Durée courte après le trajet [à valider : quelques mois]</td>
            </tr>
            <tr>
              <td>Numéro de pièce d'identité</td>
              <td>Effacé à la suppression du compte ; durée après traitement de la vérification [à valider en priorité]</td>
            </tr>
            <tr>
              <td>Journal d'audit des actions d'administration</td>
              <td>Quelques années, à des fins de sécurité et de preuve [à valider]</td>
            </tr>
            <tr>
              <td>Journaux techniques</td>
              <td>Quelques semaines à quelques mois (rotation automatique)</td>
            </tr>
            <tr>
              <td>Traces de recherche</td>
              <td>180 jours, purge automatique quotidienne</td>
            </tr>
          </tbody>
        </table>
      </div>

      <h2>5. Destinataires et sous-traitants</h2>
      <p>
        Vos données sont traitées par l'équipe Ekuiseo et, pour les besoins du service, par les prestataires
        suivants :
      </p>
      <ul>
        <li>
          <strong>Kkiapay</strong> (agrégateur de paiement mobile money, Bénin) : traitement des acomptes, paiements
          intégraux, remboursements et abonnements. Kkiapay reçoit le montant, la référence de la réservation et
          les informations que vous saisissez dans sa fenêtre de paiement.
        </li>
        <li>
          <strong>Prestataire d'envoi d'e-mails</strong> [à compléter : nom du relais SMTP retenu] : acheminement des
          codes de connexion et des notifications par e-mail (adresse e-mail et contenu du message).
        </li>
        <li>
          Aucun SMS n'est envoyé : les codes de connexion et toutes les notifications partent par e-mail. Le numéro de
          téléphone sert d'identifiant et n'est jamais transmis à un prestataire d'envoi de messages.
        </li>
        <li>
          <strong>OVH</strong> (hébergement) : le service et sa base de données sont hébergés sur un serveur virtuel
          privé [à compléter : localisation du centre de données et entité contractante].
        </li>
        <li>
          <strong>MapTiler</strong> (fond de carte), <em>uniquement si l'affichage cartographique est activé</em> :
          votre navigateur charge alors les tuiles de carte depuis les serveurs de MapTiler, qui peut recevoir votre
          adresse IP. Tant que cette option n'est pas activée, la carte est un tracé schématique sans appel externe.
        </li>
      </ul>
      <p>
        Les autres utilisateurs voient votre prénom, votre photo, votre note, vos avis, votre statut de vérification
        et, pour les conducteurs, les informations du véhicule ; votre numéro de téléphone et votre e-mail ne sont
        pas affichés. Ekuiseo ne vend ni ne loue vos données. Les conditions de transfert de données hors du Bénin
        par ces prestataires sont à vérifier [à compléter].
      </p>

      <h2>6. Vos droits</h2>
      <ul>
        <li>
          <strong>Accès et export</strong> : vous pouvez obtenir une copie de vos données depuis les réglages de
          votre compte (« Télécharger mes données ») ou en écrivant à {CONTACT_EMAIL}.
        </li>
        <li>
          <strong>Rectification</strong> : vos informations de profil, votre véhicule et vos comptes mobile money se
          modifient depuis votre espace ; l'adresse e-mail se change en deux temps avec confirmation. Pour le
          numéro de téléphone, contactez-nous.
        </li>
        <li>
          <strong>Suppression</strong> : l'action « Supprimer mon compte » dans les réglages anonymise votre profil :
          nom, numéro, e-mail, photo, numéro de pièce et comptes mobile money sont effacés et vous ne pouvez plus
          vous connecter. Vos réservations, paiements et avis sont conservés sans lien avec votre identité, pour la
          comptabilité et l'intégrité des données des autres voyageurs. La suppression est impossible tant qu'un
          trajet publié, une réservation en cours ou un reversement non soldé subsiste.
        </li>
        <li>
          <strong>Opposition</strong> : vous pouvez désactiver chaque type de notification dans vos réglages et vous
          opposer, pour des motifs légitimes, à un traitement en nous écrivant.
        </li>
        <li>
          <strong>Réclamation</strong> : vous pouvez saisir l'Autorité de Protection des Données à caractère
          Personnel du Bénin (APDP) si vous estimez que vos droits ne sont pas respectés.
        </li>
      </ul>
      <p>
        Toute demande adressée à {CONTACT_EMAIL} reçoit une réponse dans un délai de [à compléter : délai
        d'engagement fixé avec le juriste].
      </p>

      <h2>7. Cookies et stockage local</h2>
      <p>
        Ekuiseo n'utilise <strong>aucun traceur publicitaire</strong> ni outil de mesure d'audience tiers. Votre
        navigateur conserve uniquement :
      </p>
      <ul>
        <li>vos jetons de session, pour rester connecté sur cet appareil ;</li>
        <li>
          un cache des écrans consultés, pour que l'application reste utilisable en réseau dégradé ou hors ligne ;
        </li>
        <li>vos préférences d'affichage (thème clair ou sombre).</li>
      </ul>
      <p>
        Ces éléments sont stockés localement sur votre appareil, ne sont pas partagés avec des tiers et sont
        effacés lorsque vous vous déconnectez ou videz les données du site. La fenêtre de paiement Kkiapay peut
        déposer ses propres cookies, régis par la politique de Kkiapay.
      </p>

      <h2>8. Sécurité</h2>
      <p>
        Les échanges sont chiffrés (TLS). La connexion se fait par code à usage unique, sans mot de passe stocké.
        Les tentatives de connexion et les demandes de code sont limitées par adresse. Les codes, numéros de
        téléphone et numéros de pièce n'apparaissent que masqués dans nos journaux. Les actions sensibles de
        l'administration (suspension, remboursement manuel, correction de contact) sont journalisées.
      </p>

      <h2>9. Modifications</h2>
      <p>
        Cette politique peut évoluer, notamment à l'issue de la validation juridique et de la démarche auprès de
        l'APDP. La version en vigueur et sa date de mise à jour sont indiquées en tête de page.
      </p>

      <h2>10. Contact</h2>
      <p>
        Pour toute question ou demande relative à vos données :{' '}
        <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>.
      </p>
    </>
  )
}
