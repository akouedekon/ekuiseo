# Conformité — loi n° 2017-20 portant code du numérique du Bénin

> **Avertissement** : ce document est une aide au repérage à destination d'une équipe
> technique, pas un avis juridique. Il distingue explicitement ce qui relève de faits
> structurels sur lesquels ce dépôt peut s'appuyer (existence d'une autorité de
> contrôle, catégories de données traitées par le schéma actuel, etc.) de ce qui doit
> être confirmé, précisé ou complété par un juriste béninois avant l'ouverture au
> public — notamment tout numéro d'article précis, tout délai chiffré, et la
> qualification juridique exacte de l'activité. **Aucune obligation ni aucun article
> de loi n'est cité ici avec un numéro ou un contenu que nous ne sommes pas en mesure
> de garantir exact** ; là où un chiffre est nécessaire pour illustrer un raisonnement,
> il est explicitement marqué comme une hypothèse à valider.
>
> Mis à jour le 2026-09-07 à l'issue des phases 0 à 7 de l'audit
> (`docs/AUDIT-COMPLET.md`) : ce document décrit **le système déployé**, pas un
> système souhaité. Chaque section « État d'implémentation » cite l'endpoint ou le
> mécanisme qui sert l'obligation, ou dit « non implémenté ».

## 1. Ce qui est établi

- Le Bénin dispose d'un cadre légal sur la protection des données à caractère
  personnel, avec une autorité de contrôle dédiée : l'**APDP** (Autorité de Protection
  des Données à caractère Personnel). Ekuiseo, en tant qu'opérateur qui collecte et
  traite des données à caractère personnel (identité, téléphone, e-mail, localisation,
  historique de trajets, moyens de paiement) pour son propre compte, a vocation à être
  qualifié de **responsable de traitement** au sens de ce cadre.
- La loi n° 2017-20 portant code du numérique du Bénin couvre, entre autres, les
  transactions électroniques, la signature électronique, la lutte contre la
  cybercriminalité et la protection des données personnelles. La numérotation exacte
  des livres/titres/articles applicables à la protection des données doit être vérifiée
  sur le texte consolidé en vigueur (des lois ultérieures peuvent l'avoir modifié) — ne
  pas se fier à une version non officielle trouvée en ligne sans la dater.
- De manière généralement admise dans ce type de cadre (à confirmer article par
  article) : un responsable de traitement a une obligation de **déclaration ou
  d'autorisation préalable** auprès de l'autorité de contrôle avant certains
  traitements, doit tenir une trace de ses traitements, doit assurer la sécurité des
  données, et doit permettre l'exercice de droits par les personnes concernées
  (accès, rectification, opposition, effacement). Le régime précis (simple déclaration
  vs autorisation préalable, selon la nature des données — la géolocalisation et les
  données de paiement étant souvent traitées avec plus de rigueur) est **à confirmer
  auprès de l'APDP ou d'un juriste** avant le lancement.

## 2. Démarche à mener auprès de l'APDP

**À faire confirmer/réaliser par un juriste béninois ou directement auprès de l'APDP** :

1. Identifier si Ekuiseo doit faire l'objet d'une **déclaration simplifiée** ou d'une
   **demande d'autorisation** (le critère dépend généralement de la nature des
   données — ici : géolocalisation, données financières liées aux paiements mobile
   money, numéro de pièce d'identité déclaré — et du volume/type de personnes concernées).
2. Préparer le dossier de déclaration/autorisation : identité du responsable de
   traitement (la structure juridique exploitant Ekuiseo), finalités précises du
   traitement, catégories de données, catégories de destinataires (y compris
   sous-traitants, voir section 5), durées de conservation (section 3), mesures de
   sécurité mises en œuvre (section 8).
3. Déposer le dossier avant le lancement commercial, pas après — un traitement de
   données personnelles non déclaré/autorisé alors qu'il aurait dû l'être expose à des
   sanctions dont la nature et le montant sont à vérifier dans le texte en vigueur.

## 3. Registre des traitements et durées de conservation

### 3.1 Registre des traitements

Une ligne par traitement réellement effectué par le système déployé. Les bases légales
sont des propositions à faire valider.

| Traitement | Finalité | Données concernées | Base légale (proposée) | Destinataires | Durée | Mesures de sécurité |
|---|---|---|---|---|---|---|
| Création et gestion de compte | Identification des utilisateurs | Nom, prénom, téléphone (E.164), e-mail, photo, bio, préférences | Exécution du contrat | Interne | Vie du compte (voir 3.2) | Connexion par code à usage unique (aucun mot de passe), sessions révocables, TLS |
| Codes de connexion (OTP, `otp_codes`) | Authentification, changement d'e-mail, suppression de compte | Téléphone, e-mail de destination, code haché, compteur d'essais | Exécution du contrat | Relais SMTP (sous-traitant) | 24 h après expiration, purge nocturne | Code haché, 5 essais, expiration courte, limitation de débit par IP et par numéro, codes masqués dans les journaux |
| Publication de trajets | Mise en relation | Origine/destination (libellé + coordonnées), arrêts, horaires, prix, véhicule (marque, modèle, couleur, plaque) | Exécution du contrat | Public (fiche de trajet, aperçu de partage), passagers | Vie du compte + prescription (3.2) | — |
| Réservation et paiement fractionné | Exécution du contrat de transport | Réservation, montants, statut, référence Kkiapay, numéro mobile money du payeur (côté Kkiapay) | Exécution du contrat ; obligations comptables | Kkiapay (sous-traitant), conducteur (prénom, places, solde à bord) | Obligations comptables (3.2) | TLS, webhook signé, idempotence, remboursements tracés |
| Comptes mobile money des conducteurs (`payment_accounts`) | Reversement des sommes encaissées | Opérateur, numéro, statut de vérification | Exécution du contrat | Interne (back-office), opérateur mobile money lors du virement | Vie du compte ; masqué à l'anonymisation | Numéro vérifié (égal au numéro du compte ou validé par l'administration), plafond 3 comptes |
| Reversements (`driver_payouts`) | Paiement des conducteurs | Montant, destination (opérateur, numéro), référence de virement, statut | Exécution du contrat ; obligations comptables | Interne ; opérateur mobile money | Obligations comptables | Lots audités, transitions d'état contrôlées |
| Vérification d'identité (`identity_verifications`) | Confiance, lutte contre la fraude | Type et numéro de pièce déclarés, statut, motif de refus | Intérêt légitime (à confirmer : consentement ?) | Interne (modération) | Jusqu'à la décision puis 4 derniers caractères (3.2) | Numéro masqué dans les journaux et l'audit, resoumission bornée, badge révocable |
| Pièces d'identité téléversées (`identity_documents`, V20) | Contrôle de la pièce déclarée par la modération | Photos ou PDF du recto, du verso et d'un selfie avec la pièce (type et taille en base ; contenu chiffré sur disque) | Intérêt légitime (à confirmer : consentement ?) | Interne (modération), chaque consultation journalisée (`ADMIN_IDENTITY_DOCUMENT_VIEWED`) | 30 jours après la décision (3.2) ; immédiatement à l'anonymisation | Chiffrement AES-256-GCM au repos (clé hors dépôt), nom de fichier aléatoire, type contrôlé par les octets, 5 Mo max, jamais mis en cache ni exporté en clair, accès réservé à ROLE_ADMIN et journalisé |
| Abonnements Web Push (`push_subscriptions`, V20) | Notifications sur le navigateur de l'utilisateur | Endpoint du service push du navigateur, clés de chiffrement du navigateur, agent utilisateur | Exécution du contrat ; préférence `notify_by_push` désactivable | Service push du navigateur (Google, Mozilla, Apple : ils ne voient qu'un message chiffré) | Vie du compte ; supprimés à l'anonymisation, à la déconnexion (appareil) ou dès que le service push les déclare expirés | Contenu chiffré de bout en bout (RFC 8291), au plus 3 appareils par compte, endpoint réduit à son origine dans les journaux |
| Géolocalisation des trajets et recherches | Recherche et mise en relation | Coordonnées origine/destination demandées et publiées | Exécution du contrat | Interne | Trajets : vie du compte ; recherches : 180 jours | Aucune position en temps réel : uniquement des points saisis |
| Trace des recherches (`search_events`, V9) | Pilotage de l'offre (recherches sans résultat, axes en pénurie) | Coordonnées et libellés demandés, date, places, mode, résultats, identifiant utilisateur si connecté | Intérêt légitime | Interne (agrégats du back-office) | 180 jours, purge quotidienne | `user_id` mis à NULL à l'anonymisation ; agrégats sans donnée nominative |
| Alertes de recherche (`search_alerts`) | Prévenir un passager quand un trajet correspond | Axe, dates, places, rayon, préférence e-mail | Consentement (création volontaire) | Interne ; relais SMTP pour l'e-mail | Désactivation à la date de fin, suppression 90 jours après | Liste et suppression par l'utilisateur, plafond 10 |
| Messagerie liée à une réservation (`messages`) | Coordination du jour du trajet | Corps des messages, horodatage | Exécution du contrat | L'autre partie ; modération sur signalement (accès journalisé) | 180 jours après le départ, sauf signalement en cours | Écriture fermée après la fin de la réservation ; accès administrateur journalisé |
| Avis et notation (`reviews`) | Confiance entre utilisateurs | Note, commentaire, auteur, cible | Intérêt légitime | Public (profil) | Vie du compte cible | Avis possible seulement après un trajet effectué ensemble |
| Signalements (`reports`) | Modération, sécurité des utilisateurs | Motif, détails, parties, réservation liée, note de résolution | Intérêt légitime | Interne (modération) | Vie du compte + prescription | Réservation commune exigée, plafond, notification d'issue |
| Notifications (`notifications`) | Information des utilisateurs | Type, charge utile (montants, trajets), état de lecture | Exécution du contrat | Interne ; relais SMTP | 180 jours, purge nocturne | E-mail toujours pour les informations critiques, préférence respectée pour le reste |
| Journal d'audit (`audit_log`) | Traçabilité des actions sensibles | Acteur, action, entité, détails (données masquées) | Intérêt légitime / obligation de sécurité | Interne | Plusieurs années (à fixer) | Lecture seule, consultation filtrée |
| Journaux applicatifs et d'accès | Diagnostic, sécurité | Identifiant de requête, identifiant utilisateur, chemin, statut, durée ; adresse IP dans les journaux du proxy | Intérêt légitime | Interne ; hébergeur (disque) | Rotation Docker (3 × 10 Mo par service) ; journaux nginx de l'hôte selon la configuration système | Codes, e-mails et téléphones masqués ; aucun corps de requête |
| Sauvegardes | Continuité de service | Copie complète de la base | Intérêt légitime | Interne ; stockage hors site si `BACKUP_REMOTE` est configuré | 7 jours (quotidiennes) + 4 semaines (hebdomadaires) ; les données effacées disparaissent donc des sauvegardes sous 35 jours | Disque du serveur ; exercice de restauration mensuel automatisé |

### 3.2 Durées de conservation — valeurs implémentées et hypothèses

**Les durées marquées « implémentée » sont appliquées automatiquement par le
backend ; les autres sont des hypothèses de travail** à faire confirmer par un juriste
béninois, notamment au regard des règles comptables (droit OHADA, dont le Bénin est
membre, qui impose usuellement la conservation des pièces comptables sur plusieurs
années — durée exacte à vérifier dans l'Acte uniforme en vigueur) et des prescriptions
applicables aux litiges liés au transport.

| Type de donnée | Durée | État |
|---|---|---|
| Compte jamais vérifié (`PENDING_VERIFICATION`) | 24 h puis suppression | Implémentée (`AuthHousekeepingScheduler`, `ekuiseo.auth.pending-account-ttl-hours`) |
| Compte actif | Vie du compte ; anonymisation à la demande (section 4) | Implémentée |
| Compte inactif | 2-3 ans après la dernière activité, puis anonymisation | Non implémentée (à décider) |
| Codes OTP | 24 h après expiration | Implémentée (`RetentionScheduler`, `OTP_RETENTION_HOURS`) |
| Sessions (`refresh_tokens`) | 30 jours glissants, 90 jours absolus, révocation à la déconnexion, à la suspension, au changement de contact | Implémentée (V11) |
| Trajets, réservations, avis, signalements | Vie du compte + délai de prescription (à confirmer) ; conservés anonymisés après suppression du compte | Hypothèse |
| Paiements et reversements | Obligations comptables OHADA (potentiellement ~10 ans) | Hypothèse ; conservés anonymisés |
| Numéro de pièce d'identité | Jusqu'à la décision du modérateur, puis tronqué aux 4 derniers caractères | À vérifier après la phase 2 (`AdminVerificationService`) ; en attendant, supprimé à l'anonymisation |
| Pièces d'identité téléversées | Chiffrées au repos ; supprimées 30 jours après la décision (validation ou refus) et immédiatement à l'anonymisation ; accès journalisé | Implémentée (`RetentionScheduler`, `IDENTITY_DOCUMENTS_RETENTION_DAYS`, `IdentityDocumentService`) |
| Abonnements Web Push | Vie du compte ; retirés à la déconnexion de l'appareil, à l'anonymisation, ou dès que le service push répond 404/410 | Implémentée (`PushSubscriptionService`, `NotificationDispatcher`) |
| Messages | 180 jours après le départ du trajet, sauf signalement ouvert | Implémentée (`MESSAGES_RETENTION_DAYS`) |
| Notifications | 180 jours | Implémentée (`NOTIFICATIONS_RETENTION_DAYS`) |
| Trace des recherches | 180 jours | Implémentée (`SEARCH_EVENTS_RETENTION_DAYS`) |
| Alertes de recherche | Désactivées à leur date de fin (30 jours par défaut pour une alerte sans date), supprimées 90 jours après | Implémentée |
| Journal d'audit | Quelques années (objectif de preuve interne) | Hypothèse, aucune purge |
| Journaux techniques | 3 fichiers de 10 Mo par service (Docker) ; journaux d'accès du nginx de l'hôte selon `logrotate` système | Implémentée (rotation), durée en jours non garantie |
| Sauvegardes | 7 quotidiennes + 4 hebdomadaires (35 jours au plus) | Implémentée (`scripts/backup.sh`) |

## 4. Droits des personnes — état d'implémentation

| Droit | Mécanisme dans le produit | État |
|---|---|---|
| Accès et portabilité | `GET /api/v1/me/export` : fichier JSON de toutes les données du compte (profil, préférences, véhicules, comptes mobile money masqués, identité sans le numéro complet, abonnements, trajets et arrêts, réservations et plans de paiement, paiements, reversements, avis écrits et reçus, messages envoyés, notifications, alertes, signalements déposés) ; bouton « Télécharger mes données » dans les réglages ; 1 export par 24 h | Implémenté en phase 2 |
| Rectification | Profil modifiable (`PATCH /api/v1/me`), changement d'e-mail en deux temps (`/me/email/request` puis `/confirm`), correction de contact par l'administration après vérification hors ligne (`PATCH /api/v1/admin/users/{id}/contact`, journalisée) | Implémenté |
| Effacement | Suppression de compte par l'utilisateur, confirmée par un code envoyé à son e-mail (`POST /api/v1/me/delete/request` puis `POST /api/v1/me/delete`), ou anonymisation par l'administration (`POST /api/v1/admin/users/{id}/anonymize`, motif obligatoire, journalisée). La ligne `users` est conservée **anonymisée** (identité remplacée, téléphone factice unique, e-mail supprimé, photo et bio effacées, statut `DELETED`), les comptes mobile money, alertes, notifications, préférences et dossier d'identité sont supprimés, la plaque du véhicule et la destination des reversements sont masquées, le corps des messages est remplacé. Réservations, paiements, reversements et avis sont conservés pour les obligations comptables et l'intégrité des autres comptes. Refusée tant qu'un trajet à venir, une réservation active ou un reversement en attente existent. | Implémenté (lot 1.4) |
| Opposition | Préférence de notification par e-mail respectée par le routeur pour les messages non critiques ; alertes de recherche supprimables ; aucun traitement marketing | Implémenté |
| Contestation d'une suspension | Motif transmis à l'utilisateur (notification et e-mail) ; adresse de contact affichée sur l'écran de connexion et dans le pied de page | Implémenté (phase 2, pages légales) |

**Canal de dépôt des demandes** : `contact@ekuiseo.com` (`VITE_SUPPORT_EMAIL`), affiché
dans le produit. Le délai de réponse engageant est à fixer avec le juriste. Les
sauvegardes contenant des données effacées expirent sous 35 jours (section 3.2).

## 5. Sous-traitants et tiers recevant des données

Inventaire du système déployé (`docker-compose.prod.yml`, `application.yml`, front) :

1. **Kkiapay** (agrégateur de paiement mobile money, Bénin) — deux flux : les appels
   serveur (initiation, vérification, remboursement) et le **script tiers**
   `cdn.kkiapay.me/k.js` avec son iframe `*.kkiapay.me` exécutés dans le navigateur
   du passager, qui reçoivent le téléphone, le nom et l'e-mail du payeur au moment du
   paiement. Vérifier : accord de traitement, localisation des serveurs, engagements
   de conformité.
2. **Relais SMTP** (`MAIL_MODE=smtp`, en production : Hostinger, boîte
   `noreply@ekuiseo.com`) — reçoit l'adresse e-mail, les codes de connexion, les
   notifications (reçus d'acompte, confirmations, annulations). Localisation et
   conditions du prestataire à documenter.
3. **SMS** — aucun : décision du 7 septembre 2026, l e-mail est le seul canal sortant
   (`SMS_MODE=log`, aucun numéro n est transmis à un prestataire de messages).
4. **MapTiler** (ou le fournisseur de `VITE_MAP_STYLE_URL`) — les tuiles de carte sont
   chargées par le navigateur, qui envoie son adresse IP et la zone consultée. Non
   activé tant que la variable est vide ; alternative : proxy Caddy ou tuiles
   auto-hébergées.
5. **OVH** — VPS **partagé avec un autre produit du même exploitant** (voir
   `docs/DEPLOIEMENT.md`). Cloisonnement : réseau Docker interne pour PostGIS, Caddy
   exposé uniquement sur `127.0.0.1` derrière le nginx de l'hôte ; l'autre application
   n'accède pas aux conteneurs Ekuiseo mais partage l'hôte et son administrateur.
6. **GitHub** (code source, intégration et déploiement continus) — aucune donnée
   personnelle d'utilisateur, seulement le code et les secrets de déploiement.
7. **Stockage hors site des sauvegardes** — uniquement si `BACKUP_REMOTE` (rclone) est
   configuré ; à documenter au moment du choix (fournisseur, chiffrement, localisation).

Le registrar du domaine n'est pas un sous-traitant de données. Le collecteur d'erreurs
front (`VITE_ERROR_REPORT_URL`) n'envoie aucune donnée personnelle (message, trace
tronquée, route, version) ; s'il est branché sur un service externe, l'ajouter ici.

**Pour chacun** : un contrat ou des conditions générales encadrant le traitement des
données pour le compte d'Ekuiseo devrait exister (obligations de sécurité, limitation
de l'usage des données aux finalités convenues, notification en cas d'incident). Si un
de ces prestataires traite des données en dehors du territoire béninois, vérifier si
le cadre légal béninois impose des conditions particulières au transfert
transfrontalier de données personnelles.

## 6. Question ouverte : statut du covoiturage rémunéré au Bénin

**Ce point est délibérément laissé ouvert dans ce document : il n'est pas tranché
ici et doit être confirmé par un juriste béninois avant tout lancement commercial.**

Des questions qui se posent typiquement pour ce type de plateforme, sans réponse
établie dans ce dépôt :

- Le partage de frais entre un conducteur et des passagers sur un trajet qu'il
  effectue de toute façon (cas du covoiturage « quotidien » domicile-travail) est-il
  traité différemment, sur le plan réglementaire, d'un transport rémunéré de personnes
  au sens du code du transport routier béninois (taxi, transport interurbain agréé) ?
- Les trajets **interurbains** planifiés par des conducteurs qui ne font pas ce trajet
  « de toute façon » se rapprochent-ils d'une activité de transport routier de
  personnes soumise à agrément/licence ?
- Y a-t-il une obligation d'assurance spécifique (responsabilité civile transport de
  personnes contre rémunération) distincte de l'assurance automobile personnelle ?
- Quel est le régime fiscal applicable aux revenus perçus par les conducteurs via la
  plateforme, et quelles obligations en découlent pour Ekuiseo (déclaration de revenus
  versés à des tiers, éventuelle retenue à la source) ?
- La plateforme a-t-elle des obligations d'immatriculation ou d'agrément spécifiques
  en tant qu'intermédiaire de mise en relation dans le secteur du transport ?

**Recommandation** : traiter ce point avec un cabinet d'avocats béninois compétent en
droit du numérique et droit des transports avant tout lancement commercial à grande
échelle, documenter la réponse obtenue et sa date.

## 7. Cadre contractuel dans le produit

- Pages `/cgu`, `/confidentialite` et `/mentions-legales` servies par le front
  (contenu versionné dans `frontend/src/content/legal/`), **rédigées comme projets de
  texte à valider par un juriste** et affichées avec cet avertissement.
- Acceptation horodatée à l'inscription : case obligatoire, version de texte
  (`ekuiseo.terms.version`, `users.terms_version`, `users.terms_accepted_at`, V16) ;
  quand la version change, l'application bloque l'utilisateur sur un écran
  d'acceptation (`GET /api/v1/me` → `termsAcceptanceRequired`, `PATCH /api/v1/me/terms`).
- Le barème d'annulation et le paiement fractionné décrits dans les CGU sont ceux de
  `CancellationPolicy` et `FeePolicy` (règles métier de `CLAUDE.md`).

## 8. Mesures de sécurité (résumé pour le dossier APDP)

Authentification par code à usage unique envoyé à l'e-mail vérifié, sans mot de passe ;
sessions à jetons courts avec rafraîchissement enregistré, rotation et révocation ;
limitation de débit par adresse IP réelle et par numéro ; TLS de bout en bout (nginx de
l'hôte, HSTS) ; en-têtes de sécurité et politique de contenu ; base de données
accessible uniquement depuis le réseau interne Docker ; masquage des codes, e-mails,
téléphones et numéros de pièce dans les journaux et le journal d'audit ; journal
d'audit des actions d'administration ; sauvegardes quotidiennes vérifiées et exercice
de restauration mensuel ; mises à jour de dépendances suivies (Dependabot) ; accès au
back-office réservé au rôle ADMIN, actions sensibles avec motif obligatoire.

## 9. Ce qu'il reste à faire, en résumé

- [ ] Confirmer le régime exact (déclaration/autorisation) applicable auprès de l'APDP
      et déposer le dossier avant le lancement commercial.
- [ ] Faire valider par un juriste les durées « hypothèse » de la section 3.2 (comptes
      inactifs, prescription, audit) et implémenter la purge des comptes inactifs.
- [ ] Faire valider les textes des pages légales et la formulation des bases légales.
- [ ] Sécuriser contractuellement la relation avec Kkiapay, le relais SMTP, OVH (et le
      fournisseur de cartes et le stockage hors site le jour où ils sont activés).
- [ ] Faire trancher la question du statut du covoiturage rémunéré (section 6).
- [ ] Décider du second facteur ou de la restriction d'accès pour le back-office.
