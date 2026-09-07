import { CONTACT_EMAIL } from '@/lib/legal'

/**
 * Conditions générales d'utilisation — version 2026-09.
 *
 * Projet de texte rédigé à partir des règles métier réellement implémentées
 * (CLAUDE.md, `CancellationPolicy`, `FeePolicy`) et de `docs/CONFORMITE.md`.
 * Les mentions « [à compléter : …] » sont des faits non établis dans le dépôt ;
 * l'ensemble doit être validé par un juriste béninois avant ouverture au public.
 */
export function CguContent() {
  return (
    <>
      <p>
        Les présentes conditions générales d'utilisation (« CGU ») régissent l'accès et l'utilisation de la
        plateforme de covoiturage Ekuiseo (le « Service »), accessible à l'adresse ekuiseo.com et sous forme
        d'application web installable. En créant un compte, vous acceptez ces CGU dans leur version en vigueur.
      </p>

      <h2>1. Objet du Service</h2>
      <p>
        Ekuiseo est une plateforme de <strong>mise en relation</strong> entre des conducteurs qui effectuent un
        trajet avec des places disponibles et des passagers qui souhaitent partager ce trajet et ses frais. Le
        Service couvre deux types de trajets : les trajets <strong>interurbains</strong> planifiés (par exemple
        Cotonou–Bohicon, Cotonou–Parakou, Cotonou–Lomé) et les trajets <strong>quotidiens</strong> récurrents,
        typiquement domicile-travail (par exemple Abomey-Calavi–Cotonou).
      </p>
      <p>
        <strong>Ekuiseo n'est pas un transporteur.</strong> Ekuiseo ne fournit aucun service de transport, ne possède
        aucun véhicule et n'emploie aucun conducteur. Le contrat de covoiturage est conclu directement entre le
        conducteur et le passager ; Ekuiseo intervient comme intermédiaire technique et encaisse, pour le compte
        des parties, une partie du prix sous forme d'acompte (voir l'article 5).
      </p>
      <p>
        Le statut juridique du covoiturage à frais partagés au Bénin est <strong>en cours de clarification</strong>.
        Ekuiseo se réserve le droit d'adapter les présentes CGU, le fonctionnement du Service et les catégories de
        trajets autorisées en fonction des conclusions obtenues et de toute exigence réglementaire.
      </p>

      <h2>2. Comptes</h2>
      <ul>
        <li>
          L'inscription requiert un <strong>numéro de téléphone béninois</strong> et une <strong>adresse e-mail</strong>{' '}
          valides. Il n'existe pas de mot de passe : la connexion se fait par un <strong>code à usage unique</strong>{' '}
          envoyé par e-mail (ou par SMS en repli). Ce code est personnel ; ne le communiquez à personne.
        </li>
        <li>
          Le compte est activé lors de la saisie du premier code. Un compte créé mais jamais activé est supprimé
          automatiquement après 24 heures.
        </li>
        <li>
          Vous devez être <strong>majeur</strong> et juridiquement capable pour utiliser le Service, que ce soit
          comme passager ou comme conducteur.
        </li>
        <li>
          <strong>Un seul compte par personne.</strong> Les informations fournies (nom, prénom, contacts, véhicule,
          pièce d'identité) doivent être exactes et tenues à jour.
        </li>
        <li>
          Vous êtes responsable de toute activité réalisée depuis votre compte. Une session reste ouverte au plus
          90 jours sur un appareil ; la déconnexion la révoque immédiatement sur cet appareil.
        </li>
      </ul>

      <h2>3. Publication d'un trajet — obligations du conducteur</h2>
      <p>En publiant un trajet, le conducteur déclare et garantit :</p>
      <ul>
        <li>être titulaire d'un <strong>permis de conduire</strong> en cours de validité ;</li>
        <li>
          que le véhicule utilisé est celui déclaré sur son profil, en bon état de fonctionnement, régulièrement
          immatriculé et couvert par une <strong>assurance</strong> en cours de validité ;
        </li>
        <li>
          que les informations du trajet (lieux de départ, d'arrivée et arrêts intermédiaires, horaire, nombre de
          places, prix par place) sont <strong>exactes</strong> et correspondent à un trajet qu'il effectue réellement ;
        </li>
        <li>
          que le prix demandé correspond à un <strong>partage des frais</strong> du trajet (carburant, péages, usure) et
          non à une activité professionnelle de transport de personnes. Ekuiseo n'est pas destiné à une activité de
          transport rémunéré exercée à titre professionnel ;
        </li>
        <li>
          qu'il respectera le Code de la route, transportera le nombre de passagers convenu et se présentera au lieu
          et à l'heure annoncés.
        </li>
      </ul>
      <p>
        Les trajets quotidiens sont publiés sous forme de règle de récurrence hebdomadaire ; les occurrences sont
        générées sur 14 jours glissants et chacune est réservable séparément. Un conducteur peut vérifier son
        identité en déclarant le type et le numéro d'une pièce (carte nationale d'identité, passeport ou permis) ;
        le statut de vérification est visible par les passagers. Un conducteur ne peut pas réserver une place sur
        son propre trajet.
      </p>

      <h2>4. Réservation</h2>
      <p>
        Le passager choisit un trajet, un nombre de places et un mode de paiement. Les places sont attribuées dans
        l'ordre des réservations effectivement confirmées : si deux passagers réservent simultanément la dernière
        place, une seule réservation est confirmée. Une réservation en attente de paiement <strong>expire après 20
        minutes</strong> et libère les places. Un trajet dont l'heure de départ est passée ne peut plus être réservé.
      </p>

      <h2>5. Prix, frais de service et paiement fractionné</h2>
      <p>
        Le prix est exprimé en francs CFA (XOF), en montants entiers. Tout arrondi se fait aux 5 FCFA supérieurs.
        Ekuiseo perçoit des <strong>frais de service de 8 %</strong> du montant de la réservation, arrondis aux 5 FCFA
        supérieurs. Ces frais sont inclus dans le prix affiché au passager et dans l'acompte ; ils ne s'ajoutent pas
        au prix par place.
      </p>
      <p>Trois modes de paiement sont proposés :</p>
      <ul>
        <li>
          <strong>Acompte en mobile money, solde à bord</strong> (mode par défaut). Le passager règle en ligne, via
          le prestataire Kkiapay, un acompte égal au plus grand des deux montants suivants : 1 000 FCFA ou les
          frais de service, arrondi aux 5 FCFA supérieurs, sans pouvoir dépasser le prix total. Le{' '}
          <strong>solde est remis en espèces au conducteur</strong> à bord du véhicule.
        </li>
        <li>
          <strong>Paiement intégral en ligne</strong> : la totalité du prix est réglée en mobile money via Kkiapay.
        </li>
        <li>
          <strong>Espèces</strong> : la totalité du prix est remise au conducteur à bord ; la réservation est
          confirmée immédiatement. Ce mode n'est disponible que sur les trajets pour lesquels le conducteur
          l'autorise.
        </li>
      </ul>
      <p>
        Le montant exact de l'acompte et du solde à bord est affiché avant confirmation de la réservation et
        rappelé dans le récapitulatif. Les montants indiqués avant la création d'une réservation sont des
        estimations ; seul le plan de paiement de la réservation confirmée fait foi. Un acompte reçu après
        l'expiration de la réservation est remboursé automatiquement.
      </p>

      <h2>6. Annulation par le passager</h2>
      <p>
        Le passager peut annuler une réservation tant que le trajet n'est pas parti. La retenue est calculée sur{' '}
        <strong>l'acompte</strong>, seul montant encaissé en ligne par Ekuiseo, selon le barème suivant :
      </p>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">Moment de l'annulation</th>
              <th scope="col">Montant retenu</th>
              <th scope="col">Montant remboursé</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>Plus de 24 heures avant le départ</td>
              <td>0 % (annulation gratuite)</td>
              <td>100 % de l'acompte</td>
            </tr>
            <tr>
              <td>Moins de 24 heures avant le départ</td>
              <td>50 % de l'acompte</td>
              <td>50 % de l'acompte</td>
            </tr>
            <tr>
              <td>Après l'heure de départ</td>
              <td>100 % de l'acompte</td>
              <td>Aucun remboursement</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p>
        En cas de paiement intégral en ligne, le même barème s'applique au montant encaissé. En mode espèces, aucun
        montant n'ayant été encaissé, aucune retenue n'est appliquée. Lorsque le conducteur modifie l'horaire d'un
        trajet, le passager dispose de 24 heures pour annuler gratuitement. Les remboursements sont effectués sur le
        moyen de paiement mobile money utilisé ; certains remboursements partiels ou en échec peuvent nécessiter un
        traitement manuel par l'équipe Ekuiseo.
      </p>

      <h2>7. Annulation par le conducteur et absence</h2>
      <ul>
        <li>
          Si le conducteur annule un trajet, chaque passager concerné est informé et{' '}
          <strong>l'acompte lui est intégralement remboursé</strong>, quel que soit le délai.
        </li>
        <li>
          <strong>Absence du passager (« no-show »)</strong> : si un passager ne se présente pas au départ sans avoir
          annulé, le conducteur peut le signaler jusqu'à 48 heures après l'heure de départ. L'acompte est alors
          acquis et reversé au conducteur, déduction faite des frais de service.
        </li>
        <li>
          Les annulations répétées ou tardives, de la part d'un conducteur comme d'un passager, peuvent entraîner une
          suspension du compte (article 9).
        </li>
      </ul>

      <h2>8. Avis, messagerie et signalements</h2>
      <p>
        Après un trajet, conducteur et passagers peuvent se laisser un avis et une note, visibles sur les profils.
        Les avis doivent être sincères, respectueux et porter sur le trajet effectué. Une messagerie permet
        d'échanger dans le cadre d'une réservation ; elle ne doit pas servir à contourner le Service ni à des fins
        de démarchage ou de harcèlement. Tout utilisateur peut <strong>signaler</strong> un comportement contraire aux
        présentes CGU (absence, conduite dangereuse, faux profil, propos inappropriés, fraude au paiement). Les
        signalements sont examinés par l'équipe Ekuiseo.
      </p>

      <h2>9. Suspension et contestation</h2>
      <p>
        Ekuiseo peut suspendre un compte, temporairement ou définitivement, en cas de manquement aux présentes CGU,
        de fraude, de comportement dangereux signalé, de faux profil ou d'annulations abusives. Toute suspension est{' '}
        <strong>motivée</strong> : le motif est communiqué à l'utilisateur, et l'action est consignée dans un journal
        interne. L'utilisateur peut <strong>contester</strong> la décision en écrivant à {CONTACT_EMAIL} ; Ekuiseo
        répond après examen des éléments fournis. Un compte suspendu est déconnecté de tous ses appareils et ne peut
        plus publier ni réserver.
      </p>

      <h2>10. Abonnement conducteur</h2>
      <p>
        Un conducteur peut souscrire un <strong>abonnement de 2 000 FCFA par mois</strong>, réglé en mobile money via
        Kkiapay. Pendant la durée de l'abonnement, les frais de service sur ses trajets sont ramenés à{' '}
        <strong>0 %</strong>. Une souscription non payée dans les 30 minutes est abandonnée ; l'abonnement ne se
        renouvelle pas automatiquement.
      </p>

      <h2>11. Reversements aux conducteurs</h2>
      <p>
        Ekuiseo ne reverse que ce qu'elle a réellement encaissé en ligne : l'acompte diminué des frais de service en
        mode fractionné, le montant total diminué des frais de service en paiement intégral, rien en mode espèces
        (le conducteur ayant déjà perçu le prix à bord). Les reversements portent sur les réservations dont le
        trajet a eu lieu depuis au moins 24 heures et dont le paiement est confirmé. Ils sont versés sur le{' '}
        <strong>compte mobile money vérifié</strong> déclaré par le conducteur dans son espace, jamais sur le simple
        numéro de connexion. Le décaissement est effectué par l'équipe Ekuiseo par lots ; un conducteur sans compte
        mobile money vérifié en est informé et son reversement est différé jusqu'à la vérification. Une réservation
        remboursée est retirée du reversement ou déduite d'un lot ultérieur.
      </p>

      <h2>12. Responsabilité</h2>
      <ul>
        <li>
          Ekuiseo n'est pas partie au contrat de covoiturage et ne saurait être tenue responsable de l'exécution du
          trajet, d'un retard, d'un accident, d'un dommage aux personnes ou aux biens, ni du comportement d'un
          utilisateur. Le conducteur est seul responsable de la conduite du véhicule et de sa couverture d'assurance.
        </li>
        <li>
          Ekuiseo met en œuvre des moyens raisonnables pour assurer la disponibilité du Service, sans garantie
          d'absence d'interruption, notamment en cas de panne d'un opérateur de mobile money ou du prestataire de
          paiement.
        </li>
        <li>
          Les paiements en ligne sont traités par Kkiapay ; Ekuiseo ne stocke aucune donnée bancaire ni aucun
          identifiant de compte mobile money autre que le numéro déclaré pour les reversements.
        </li>
        <li>
          Les utilisateurs s'engagent à ne pas utiliser le Service à des fins illicites, à ne pas tenter d'en
          perturber le fonctionnement et à ne pas collecter les données d'autres utilisateurs.
        </li>
      </ul>

      <h2>13. Modification des CGU</h2>
      <p>
        Ekuiseo peut modifier les présentes CGU. La version en vigueur, identifiée par son numéro de version et sa
        date de mise à jour, est publiée sur cette page. Toute modification substantielle est portée à la
        connaissance des utilisateurs, qui peuvent être invités à accepter la nouvelle version lors de leur
        prochaine connexion.
      </p>

      <h2>14. Droit applicable et litiges</h2>
      <p>
        Les présentes CGU sont régies par le <strong>droit béninois</strong>. En cas de différend, les parties
        s'efforceront de trouver une solution amiable en contactant Ekuiseo à l'adresse ci-dessous. À défaut, le
        litige relève des juridictions compétentes du Bénin [à compléter : juridiction et règles de compétence
        retenues par le juriste].
      </p>

      <h2>15. Contact</h2>
      <p>
        Pour toute question sur les présentes CGU, une réservation, un remboursement ou une suspension :{' '}
        <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>.
      </p>
    </>
  )
}
