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

## 1. Ce qui est établi

- Le Bénin dispose d'un cadre légal sur la protection des données à caractère
  personnel, avec une autorité de contrôle dédiée : l'**APDP** (Autorité de Protection
  des Données à caractère Personnel). Ekuiseo, en tant qu'opérateur qui collecte et
  traite des données à caractère personnel (identité, téléphone, localisation,
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
   money — et du volume/type de personnes concernées).
2. Préparer le dossier de déclaration/autorisation : identité du responsable de
   traitement (la structure juridique exploitant Ekuiseo), finalités précises du
   traitement, catégories de données, catégories de destinataires (y compris
   sous-traitants, voir section 4), durées de conservation envisagées (voir section
   3), mesures de sécurité mises en œuvre.
3. Déposer le dossier avant le lancement commercial, pas après — un traitement de
   données personnelles non déclaré/autorisé alors qu'il aurait dû l'être expose à des
   sanctions dont la nature et le montant sont à vérifier dans le texte en vigueur.

## 3. Registre des traitements et durées de conservation

### 3.1 Registre des traitements

Tenir un registre des traitements est une bonne pratique quasi universelle en matière
de protection des données, indépendamment de son caractère obligatoire précis dans le
droit béninois (à confirmer). Structure minimale recommandée, une ligne par
traitement :

| Traitement | Finalité | Données concernées | Base légale | Destinataires | Durée de conservation | Mesures de sécurité |
|---|---|---|---|---|---|---|
| Création de compte | Identification des utilisateurs | Nom, téléphone, e-mail, date de naissance | Exécution du contrat | Interne | Voir 3.2 | Mot de passe haché (bcrypt), TLS |
| Vérification téléphonique (OTP) | Sécurité / lutte anti-fraude | Numéro de téléphone, code OTP haché | Intérêt légitime | Fournisseur SMS (sous-traitant) | Voir 3.2 | Code haché, expiration courte |
| Réservation et paiement | Exécution du contrat de transport | Trajet, montant, statut de paiement | Exécution du contrat | Kkiapay (sous-traitant) | Voir 3.2 | TLS, idempotence webhook |
| Géolocalisation des trajets | Recherche et mise en relation | Coordonnées origine/destination | Exécution du contrat | Interne | Voir 3.2 | — |
| Avis et notation | Confiance entre utilisateurs | Note, commentaire | Intérêt légitime | Public (profil) | Voir 3.2 | — |
| Trace des recherches de trajets (`search_events`, migration V9) | Pilotage de l'offre : mesurer les recherches sans résultat et les axes à développer | Coordonnées et libellés origine/destination demandés, date, places, mode, nombre de résultats, identifiant de l'utilisateur s'il est connecté | Intérêt légitime | Interne (back-office uniquement, données agrégées) | 180 jours (voir 3.2) | Purge automatique quotidienne ; aucune donnée nominative dans les agrégats servis |

Ce tableau est un point de départ à faire valider et compléter (finalités reformulées
juridiquement, bases légales confirmées) par la personne responsable de la conformité.

### 3.2 Durées de conservation par type de donnée — hypothèses de travail

**Ces durées sont des hypothèses de travail raisonnables, pas des obligations légales
citées avec certitude.** Un juriste béninois doit les confirmer, notamment au regard
des règles comptables (droit OHADA, dont le Bénin est membre, qui impose usuellement la
conservation des pièces comptables sur une durée de plusieurs années — la durée exacte
est à vérifier dans l'Acte uniforme relatif au droit comptable et à la présentation des
états financiers en vigueur) et des prescriptions civiles/commerciales applicables aux
litiges liés au transport.

| Type de donnée | Durée proposée (à valider) | Justification de départ |
|---|---|---|
| Compte utilisateur actif | Durée de vie du compte | Nécessaire à l'exécution du service |
| Compte inactif / jamais activé | 2-3 ans après la dernière activité, puis suppression ou anonymisation | Limiter la conservation de données non utilisées |
| Code OTP (`otp_codes`) | Quelques minutes à quelques heures après expiration/consommation, purge régulière | Donnée strictement transitoire, aucune utilité au-delà |
| Historique des trajets et réservations | Durée de vie du compte + délai lié à la prescription des litiges commerciaux (à confirmer, souvent plusieurs années) | Preuve en cas de litige, obligations comptables |
| Données de paiement (`payments`) | Alignée sur les obligations comptables (OHADA) — à confirmer, potentiellement ~10 ans | Obligations comptables et fiscales |
| Avis/notations (`reviews`) | Durée de vie du compte cible, sauf demande de suppression justifiée | Utilité continue pour la confiance entre utilisateurs |
| Messagerie entre utilisateurs (`messages`) | Courte (ex. durée du trajet + quelques mois), à réévaluer selon le risque de litige | Minimisation, sauf besoin probatoire |
| Numéro de pièce d'identité déclaré (`identity_verifications.document_number`, depuis la migration V6) | La plus courte possible une fois la vérification traitée (ex. le numéro purgé/tronqué après APPROVED ou REJECTED, en ne gardant que le statut) — **à confirmer en priorité, donnée particulièrement sensible** | Vérifier l'identité d'un conducteur ; aucune pièce jointe (photo) n'est stockée à ce jour, seuls le type et le numéro déclarés le sont |
| Journal d'audit (`audit_log`, actions sensibles back-office) | Alignée sur un objectif de sécurité/preuve interne (ex. quelques années), distincte des durées "métier" ci-dessus | Traçabilité des actions d'administration (suspensions, remboursements manuels...) |
| Journaux techniques/serveur | Quelques semaines à quelques mois (voir aussi `docker-compose.prod.yml`, rotation des logs à 3 fichiers de 10 Mo par service) | Sécurité opérationnelle, pas de finalité au-delà du diagnostic |
| Trace des recherches de trajets (`search_events`, migration V9) | **180 jours** (valeur par défaut de `SEARCH_EVENTS_RETENTION_DAYS`), **purge automatique quotidienne déjà implémentée** (`SearchEventRetentionScheduler`) — durée à confirmer par le juriste ; la réduire ne demande qu'un changement de variable d'environnement | Mesurer la liquidité (recherches sans résultat, axes en pénurie) sur quelques mois glissants ; aucune finalité au-delà. `user_id` est nullable (recherche anonyme) et passe à NULL si le compte est supprimé (`ON DELETE SET NULL`) : l'anonymisation d'un compte ne laisse aucun lien nominatif |

Une fois ces durées validées, elles doivent être **implémentées techniquement**
(purges automatiques). À ce jour, seule la purge des traces de recherche l'est ; les
autres restent à ajouter côté backend (hors du périmètre infrastructure de ce dépôt).

## 4. Droits des personnes et comment les exercer techniquement

Droits généralement associés à ce type de cadre (à confirmer précisément dans le texte
béninois) : accès, rectification, effacement, opposition, et éventuellement
portabilité. État actuel du backend vis-à-vis de chacun — **constats techniques**,
établis en lisant le schéma de base (`backend/src/main/resources/db/migration/V1__init.sql`) :

- **Droit d'accès** : aucun endpoint d'export des données personnelles d'un utilisateur
  n'existe aujourd'hui dans l'API (voir README, "Prochaines étapes" — l'endpoint public
  `GET /api/v1/users/{id}` lui-même n'existe pas encore). À construire : un endpoint (ou
  une procédure manuelle documentée en attendant) qui exporte l'ensemble des données
  liées à un compte (`users`, `vehicles`, `trips`, `bookings`, `payments`, `reviews`,
  `messages` où l'utilisateur est expéditeur).
- **Droit de rectification** : partiellement possible via les endpoints de profil déjà
  existants (`PATCH` sur le profil, si présent — voir la documentation Swagger de
  l'API) ; les champs non modifiables via l'API (ex. téléphone vérifié) nécessitent une
  procédure manuelle.
- **Droit à l'effacement** : **techniquement contraint par le schéma actuel**. La table
  `users` est référencée par `bookings.passenger_id`, `payments` (via `bookings`),
  `reviews.author_id`/`target_id`, `driver_payouts.driver_id`, sans `ON DELETE CASCADE`
  sur ces clés étrangères (contrairement à `vehicles.owner_id` et
  `trip_stops.trip_id`, qui sont en cascade). Concrètement, **une suppression physique
  (`DELETE`) d'un utilisateur ayant déjà des réservations ou paiements échouera** au
  niveau de la base (violation de contrainte de clé étrangère). La voie réaliste est
  une **anonymisation** plutôt qu'une suppression physique : remplacer `first_name`,
  `last_name`, `email`, `phone`, `photo_url`, `bio` par des valeurs génériques
  (`"Utilisateur supprimé"`, un numéro de téléphone factice unique pour respecter la
  contrainte `UNIQUE`), invalider `password_hash`, et conserver la ligne pour
  l'intégrité référentielle et les obligations comptables. Cette procédure n'est pas
  automatisée aujourd'hui — à construire avant de promettre ce droit aux utilisateurs
  dans une politique de confidentialité.
- **Droit d'opposition** : à mettre en œuvre au cas par cas selon la finalité
  concernée (ex. opposition à la réception de notifications marketing, si de telles
  notifications existent).
- **Droit à la portabilité** : peut réutiliser le même export que le droit d'accès, dans
  un format structuré (JSON), si ce droit s'applique effectivement dans le cadre
  béninois — à confirmer.

**Canal de dépôt des demandes** : tant qu'aucun formulaire dédié n'existe dans le
produit, prévoir une adresse de contact clairement affichée dans la politique de
confidentialité (voir `docs/LANCEMENT.md`) et un délai de traitement engageant (à fixer
avec le juriste, en cohérence avec les délais imposés par le texte béninois s'ils sont
précisés).

## 5. Sous-traitants

Trois catégories de sous-traitants (au sens large — prestataires qui traitent des
données pour le compte d'Ekuiseo) sont déjà identifiables dans l'architecture actuelle :

1. **Kkiapay** (agrégateur de paiement mobile money) — traite les données de paiement
   et, transitivement, l'identité du payeur et son numéro de téléphone/moyen de
   paiement. Vérifier : existence d'un contrat/accord de traitement des données avec
   Kkiapay, localisation de leurs serveurs (transfert de données hors du Bénin ?), et
   leurs propres engagements de conformité.
2. **Fournisseur SMS** (à choisir, voir `.env.example` — `SMS_PROVIDER_KEY`) — traite au
   minimum le numéro de téléphone et le contenu du SMS (code OTP). Mêmes vérifications
   que pour Kkiapay.
3. **Hébergeur** (Hostinger, pour le VPS lui-même) — héberge physiquement l'ensemble
   des données. Vérifier la localisation des datacenters utilisés et les conditions
   contractuelles d'Hostinger en matière de protection des données.

**Pour chacun** : un contrat ou des conditions générales encadrant le traitement des
données pour le compte d'Ekuiseo devrait exister (obligations de sécurité, limitation
de l'usage des données aux finalités convenues, notification en cas d'incident). Si un
de ces prestataires traite des données en dehors du territoire béninois, vérifier si
le cadre légal béninois impose des conditions particulières au transfert
transfrontalier de données personnelles (mécanisme fréquent dans ce type de
législation, modalités précises à confirmer).

## 6. Question ouverte : statut du covoiturage rémunéré au Bénin

**Ce point est délibérément laissé ouvert dans ce document : il n'est pas tranché
ici et doit être confirmé par un juriste béninois avant tout lancement commercial.**

Des questions qui se posent typiquement pour ce type de plateforme, sans réponse
établie dans ce dépôt :

- Le partage de frais entre un conducteur et des passagers sur un trajet qu'il
  effectue de toute façon (cas du covoiturage "quotidien" domicile-travail) est-il
  traité différemment, sur le plan réglementaire, d'un transport rémunéré de personnes
  au sens du code du transport routier béninois (taxi, transport interurbain agréé) ?
  Le prix pratiqué (partage de frais réel vs tarif de marché) peut être un critère
  pertinent selon les juridictions, mais son application au Bénin spécifiquement n'est
  pas vérifiée ici.
- Les trajets **interurbains** planifiés par des conducteurs qui ne font pas ce trajet
  "de toute façon" (ex. un conducteur qui organise spécifiquement un aller-retour
  Cotonou-Parakou pour transporter des passagers) se rapprochent-ils davantage d'une
  activité de transport routier de personnes soumise à agrément/licence au Bénin ?
- Y a-t-il une obligation d'assurance spécifique (responsabilité civile transport de
  personnes contre rémunération) distincte de l'assurance automobile personnelle
  classique d'un conducteur particulier ?
- Quel est le régime fiscal applicable aux revenus perçus par les conducteurs via la
  plateforme (revenus occasionnels vs activité commerciale/professionnelle
  nécessitant une immatriculation) ? Cela peut aussi avoir une incidence sur les
  obligations d'Ekuiseo elle-même (déclaration de revenus versés à des tiers,
  éventuelle retenue à la source).
- La plateforme elle-même (l'entité qui exploite Ekuiseo) a-t-elle des obligations
  d'immatriculation ou d'agrément spécifiques en tant qu'intermédiaire de mise en
  relation dans le secteur du transport ?

**Recommandation** : traiter ce point avec un cabinet d'avocats béninois compétent en
droit du numérique et droit des transports avant tout lancement commercial à grande
échelle, et avant toute communication publique qui présenterait Ekuiseo comme
pleinement conforme sur ce point. Documenter la réponse obtenue et la date à laquelle
elle a été obtenue (le cadre réglementaire pouvant évoluer).

## 7. Ce qu'il reste à faire, en résumé

- [ ] Confirmer le régime exact (déclaration/autorisation) applicable auprès de l'APDP.
- [ ] Déposer le dossier auprès de l'APDP avant le lancement commercial.
- [ ] Faire valider les durées de conservation de la section 3.2 par un juriste et les
      implémenter techniquement (purges automatiques).
- [ ] Construire l'export de données personnelles (droit d'accès/portabilité) et la
      procédure d'anonymisation (droit à l'effacement) décrits en section 4.
- [ ] Sécuriser contractuellement la relation avec Kkiapay, le fournisseur SMS et
      Hostinger (section 5).
- [ ] Faire trancher la question du statut du covoiturage rémunéré (section 6) avant
      tout lancement commercial à grande échelle.
- [ ] Rédiger une politique de confidentialité et des CGU reflétant les réponses
      obtenues ci-dessus (voir `docs/LANCEMENT.md`).
