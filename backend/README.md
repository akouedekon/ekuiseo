# Ekuiseo API — Backend

API du backend de la plateforme de covoiturage Ekuiseo (Bénin). Spring Boot 3.3 / Java 17, PostgreSQL + PostGIS, Flyway, sécurité JWT.

> **Ce document couvre uniquement `backend/`.** Le frontend, `docker-compose.yml` et le `README.md` racine sont hors périmètre de ce document et n'ont pas été modifiés pour l'écrire.

> **Important — build non vérifié dans cet environnement de rédaction** : la politique réseau de l'environnement dans lequel ce code a été écrit bloque Maven Central (403 sur tous les miroirs testés) ; `mvn` n'a donc jamais pu être exécuté ici, ni pour compiler, ni pour lancer les tests. Tout le code a été relu manuellement avec rigueur (signatures, imports, champs d'entités, chemins de propriétés Spring Data, JPQL, cibles MapStruct, clés `@Value` présentes dans `application.yml`), mais **la première compilation doit être faite chez vous** avant toute mise en production.

---

## 1. Architecture

```
bj.ekuiseo.api
├── domain/            entités JPA (+ domain/enums)
├── repository/         Spring Data JPA (requêtes dérivées, JPQL, SQL natif PostGIS)
├── service/            logique métier (+ service/kkiapay, service/sms, service/geo, service/admin)
├── security/           JWT, filtres, RBAC (USER / ADMIN)
├── web/controller/      contrôleurs REST (+ web/controller/admin)
├── dto/                 requêtes/réponses (records Java, validation Bean Validation)
├── mapper/              MapStruct (entité <-> DTO)
├── common/              exceptions, ProblemDetail, FeePolicy, MoneyUtils
└── config/              SecurityConfig, OpenApiConfig
```

Points structurants :
- **Stateless JWT** (access + refresh), rôles `USER`/`ADMIN` portés par `users.role`, back-office sous `/api/v1/admin/**` (`hasRole("ADMIN")`).
- **PostGIS** pour la recherche géospatiale (`ST_DWithin` sur des colonnes `geography` alimentées par trigger à partir de lat/lng).
- **Idempotence des webhooks** Kkiapay via contrainte unique `(provider, provider_tx_id)` ; l'état d'une transaction n'est jamais déduit du seul payload webhook, toujours reconfirmé serveur-à-serveur.
- **Intégrations externes isolées derrière une interface** (`KkiapayGateway`, `SmsGateway`) avec une implémentation stub/log par défaut (aucun paiement/SMS réel tant que le mode n'est pas explicitement changé) et une implémentation HTTP réelle à activer/configurer.
- **Aucune dépendance externe** pour le rate limiting (pas de Redis/Bucket4j) : fenêtre glissante en mémoire, documentée avec ses limites (voir §8).
- **Hibernate `ddl-auto: validate`** : le schéma doit correspondre exactement aux entités ; toute correction passe par une nouvelle migration Flyway, jamais par une modification de `V1__init.sql`.

---

## 2. Modèle de données

### 2.1 Tables issues de `V1__init.sql` (existant, non modifié)
`users`, `otp_codes`, `vehicles`, `trips`, `trip_stops`, `bookings`, `payments`, `driver_payouts`, `reviews`, `conversations`, `messages`, `notifications`, `search_alerts`.

### 2.2 Migrations ajoutées (`V2` → `V8`, jamais de modification de V1)

| Migration | Contenu |
|---|---|
| `V2__roles_moderation_audit.sql` | `users.role` (+ `chk_users_role`), `users.late_cancellations_count`, `users.suspended_at`/`suspended_reason`, `users.push_subscription` (JSONB, préparé pour Web Push — non exploité) ; `otp_codes.attempts` ; `trips.reminder_sent_at` (+ index partiel pour le scheduler de rappel) ; table `reports` (signalements) ; table `audit_log` (journal d'audit) |
| `V3__geo_places.sql` | extension `unaccent` ; table `geo_places` (cache de géocodage) ; index sur `normalized_name` ; jeu de données : Cotonou, Porto-Novo, Abomey-Calavi, Bohicon, Abomey, Parakou, Natitingou, Djougou, Lokossa, Ouidah, Kandi, Malanville, Savalou, Comè, Grand-Popo, Lomé, Lagos + 10 quartiers de Cotonou |
| `V4__driver_subscriptions.sql` | table `driver_subscriptions` (+ index unique `uq_driver_subscriptions_active` : un seul abonnement ACTIVE par conducteur) ; `payments.booking_id` rendu nullable, `payments.subscription_id` ajouté, contrainte `chk_payments_target` (XOR booking/subscription) |
| `V5__payout_batches.sql` | `driver_payouts.period_start`/`period_end` ; table `driver_payout_items` (détail d'un lot, contrainte unique sur `booking_id` : une réservation n'est jamais reversée deux fois) ; index sur `bookings.payment_method` |
| `V6__preferences_identity_payment_accounts_alerts.sql` | tables `user_preferences` (1 ligne par utilisateur, notifications + préférences à bord), `identity_verifications` (1 ligne par utilisateur, statut de vérification d'identité), `payment_methods` (comptes mobile money de l'utilisateur, `uq_payment_methods_default` : un seul par défaut) ; `search_alerts.seats` (défaut 1) et `search_alerts.trip_type` (nullable = les deux types), pour que le matching des alertes respecte le nombre de places et le type de trajet recherchés |
| `V7__booking_deposit_split.sql` | `bookings.deposit_amount`/`balance_due_on_board` (paiement fractionné, règle métier n.21) ; migration de données : les réservations `payment_method = 'MOMO'` deviennent `'MOMO_FULL'` avec `deposit_amount = amount`, les `'CASH'` reçoivent `deposit_amount = 0` ; `payment_method` passe de deux valeurs (`MOMO`/`CASH`) à trois (`MOMO_DEPOSIT`/`MOMO_FULL`/`CASH`, nouveau défaut `MOMO_DEPOSIT`) ; contraintes `chk_bookings_deposit_balance` (`deposit_amount + balance_due_on_board = amount`) et `chk_bookings_deposit_range` |
| `V8__driver_profile_stats_index.sql` | `idx_messages_conversation_sender_created` (`messages(conversation_id, sender_id, created_at)`), seul index nécessaire aux statistiques de profil public d'un conducteur (règle métier n.22, voir §4quater) — aucune nouvelle colonne, aucune nouvelle table : `reliabilityRate` et `responseTimeMinutes` sont calculés à la volée par agrégation, jamais stockés |
| `V9__search_events.sql` | table `search_events` (trace de chaque recherche de trajets : coordonnées et libellés demandés, ville `geo_places` la plus proche de l'origine et de la destination, date, places, mode, rayon, nombre de résultats, utilisateur si connecté) ; index sur `created_at`, sur `(user_id, created_at)` partiel, et sur `(origin_place_id, dest_place_id)`. Écrite en asynchrone (`SearchEventService`, exécuteur borné `AsyncConfig`), purgée chaque nuit au-delà de `ekuiseo.search-events.retention-days` (180 j). Socle des indicateurs de liquidité (§4ter) |

### 2.3 Nouvelles entités
`Report`, `AuditLog`, `GeoPlace`, `DriverSubscription`, `DriverPayoutItem` (lot 1) ; `UserPreferences`, `IdentityVerification`, `PaymentAccount` (lot 2, V6) ; `SearchEvent` (V9, sans relation JPA : simples UUID vers `users` et `geo_places`) — voir javadoc de chaque classe dans `domain/` pour le détail des champs.

### 2.4 Entités modifiées
- `User` : `+role`, `+lateCancellationsCount`, `+suspendedAt`, `+suspendedReason`, `+pushSubscription`
- `Trip` : `+reminderSentAt`
- `Payment` : `booking` devient optionnel, `+subscription`
- `OtpCode` : `+attempts`
- `DriverPayout` : `+periodStart`, `+periodEnd`
- `SearchAlert` (lot 2, V6) : `+seats` (défaut 1), `+tripType` (nullable)
- `Booking` (lot 2, V7) : `+depositAmount`, `+balanceDueOnBoard` ; `paymentMethod` par défaut passe de `MOMO` à `MOMO_DEPOSIT`

> **`V6` a suffi pour le premier passage du lot 2** (endpoints manquants côté front, préférences/identité/moyens de paiement/alertes, voir §4bis) : elle avait déjà été écrite, dans une session de travail antérieure sur ce même dépôt, avec exactement les tables et colonnes que ces endpoints requièrent.
>
> **`V7` a ensuite été nécessaire pour le paiement fractionné réel** (règle métier n.21, demandé dans un second temps par le produit — voir §4ter et §7ter) : `bookings` ne portait pas encore de colonnes pour distinguer l'acompte du solde, il fallait donc une nouvelle migration plutôt qu'un simple DTO calculé.
>
> **Compatibilité avec `docs/donnees-demo.sql`** (fichier en lecture seule, non modifié) : vérifiée pour `V6` (aucun impact, ce script n'insère rien dans les trois tables qu'elle ajoute). **`V7` change en revanche ce constat pour `bookings`** : ce script devra être mis à jour pour fournir `deposit_amount`/`balance_due_on_board` (colonnes `NOT NULL` sans défaut au niveau base) et remplacer ses valeurs `'MOMO'` par `'MOMO_DEPOSIT'`/`'MOMO_FULL'` — la formule exacte est donnée en §10 pour l'agent qui maintient ce fichier ; je ne l'ai pas modifié moi-même (hors périmètre, lecture seule). **`V8` n'a aucun impact sur ce script** : un index pur, aucune colonne ni contrainte nouvelle sur une table que `donnees-demo.sql` alimente.

---

## 3. Corrections apportées à l'existant

| # | Sujet | Correction |
|---|---|---|
| 1 | Commission codée en dur | `FeePolicy` (bean injectable) lit `ekuiseo.fee.service-fee-rate` et `ekuiseo.fee.rounding-step` depuis `application.yml` au lieu de valeurs figées dans `MoneyUtils` ; `MoneyUtils` reste utilisé tel quel par les tests historiques (constantes par défaut identiques : 8 %, palier 5 FCFA). |
| 2 | Fuite de trajets DRAFT | `TripService#getTrip(id, requesterId)` : un trajet DRAFT renvoie 404 (jamais 403, pour ne pas révéler son existence) sauf pour son propre conducteur. `requesterId` peut être `null` (`CurrentUser#idOrNull`) pour un appel anonyme sur l'endpoint public. |
| 3 | Validation incohérente `UpdateTripRequest` | Contraintes alignées sur `CreateTripRequest` (bornes de places 1–8, prix ≥ 0, date future). `TripService#updateTrip` vérifie en plus que le nouveau `seatsTotal` ne dépasse jamais la capacité du véhicule ni ne descend sous le nombre de places déjà réservées. |
| 4 | Secret JWT trop court accepté silencieusement | `JwtService` lève désormais `IllegalStateException` (échec au démarrage) si `ekuiseo.jwt.secret` fait moins de 32 octets, avec un message explicite plutôt que de compléter la clé en silence. |
| 5 | Pas de profil public conducteur | `GET /api/v1/users/{id}` (public) : nom, photo, note, badges de vérification, ancienneté, véhicules, trajets complétés — jamais téléphone/e-mail/date de naissance. Complété depuis par `reliabilityRate`, `responseTimeMinutes` et `preferences` (règle métier n.22, voir §4quater) : le front affichait `undefined` à leur place, ces trois champs n'existaient pas encore sur `PublicUserProfileResponse`. |
| 6 | Annulation conducteur sans cascade | `BookingService#cascadeCancelForDriverTripCancellation` : toutes les réservations actives sont annulées, remboursées intégralement (jamais partiellement, ce n'est jamais la faute du passager), les passagers sont notifiés (in-app + SMS), et l'annulation est comptabilisée dans `lateCancellationsCount` du conducteur si elle intervient à moins de 24h du départ (`DriverCancellationPolicy`). |

---

## 4. Endpoints ajoutés

### Publics / semi-publics
- `GET /api/v1/users/{id}` — profil public
- `GET /api/v1/geo/search?q=...` — autocomplétion villes/quartiers

### Utilisateur connecté
- `POST /api/v1/reports` — signaler un utilisateur ou un trajet
- `GET /api/v1/me/payouts/balance`, `GET /api/v1/me/payouts` — solde et historique de reversement (conducteur)
- `GET /api/v1/me/subscription`, `POST /api/v1/me/subscription` — statut / souscription à l'abonnement conducteur

### Admin (`/api/v1/admin/**`, `ROLE_ADMIN`)
- `GET/POST /api/v1/admin/users`, `/{id}/suspend`, `/{id}/reinstate`, `/{id}/verify-identity`, `PATCH /{id}/contact`
- `POST /api/v1/admin/vehicles/{id}/verify`
- `GET /api/v1/admin/reports`, `POST /api/v1/admin/reports/{id}/resolve`
- `GET /api/v1/admin/payouts`, `POST /api/v1/admin/payouts/run`, `POST /api/v1/admin/payouts/{id}/settle`
- `GET /api/v1/admin/stats?from=...&to=...`
- `GET /api/v1/admin/stats/liquidity?days=N` et `GET /api/v1/admin/stats/liquidity/export?days=N` (CSV) — voir §4ter
- `GET /api/v1/admin/audit-log`

### 4ter. Indicateurs de liquidité (`AdminLiquidityService`, `AdminLiquidityResponse`)

`GET /api/v1/admin/stats/liquidity?days=N` (1 ≤ N ≤ 365, 30 par défaut) répond aux questions de la section « Back-office : les KPI à mesurer » de `CLAUDE.md`, section 1 (liquidité), plus la métrique nord :

- **`northStar`** : places réellement vendues (réservations `CONFIRMED`, `COMPLETED` ou `NO_SHOW` créées sur la période), période précédente, rythme extrapolé à 30 jours et progression vers le seuil de **2 000 places par mois**, série par semaine civile.
- **`current` / `previous`** (même forme, le front affiche la variation en points) : recherches, recherches abouties (≥ 1 résultat) et leur taux ; recherches d'utilisateurs connectés et part suivie d'une réservation **du même utilisateur sous 24 h** (attribution approximative assumée : les recherches anonymes ne sont pas attribuables et sortent du dénominateur) ; trajets **partis** sur la période (hors `DRAFT`/`CANCELLED`), places publiées, places vendues, taux de remplissage, trajets orphelins (aucune place vendue) et leur taux ; délai **médian** publication → première réservation (`percentile_cont`), sur les seuls trajets réservés (`firstBookingSampleSize`), `null` sans échantillon.
- **`fillByMode`** (`INTERURBAIN` / `QUOTIDIEN`) et **`fillByRoute`** (10 premiers axes par places publiées, regroupés par libellés exacts comme `topRoutes`).
- **`shortageRoutes`** : 10 premiers couples origine → destination recherchés **sans résultat**, regroupés par ville `geo_places` la plus proche (à défaut le libellé tapé, à défaut les coordonnées arrondies) — la liste des corridors à démarcher.

Tout est calculé par requêtes natives agrégées (`SearchEventRepository`, `TripRepository#getFillStats*`, `#getFirstBookingDelayStats`, `BookingRepository#getSeatsByWeek`) : aucune collection n'est chargée en mémoire. L'export `/liquidity/export` renvoie les mêmes chiffres en CSV (`;`, décimales à la virgule, BOM UTF-8) pour un tableur en français.

La trace de recherche elle-même est écrite par `TripService#search` → `SearchEventService#record` (`@Async`, exécuteur dédié à file bornée : une file pleine abandonne la trace en journalisant, jamais en ralentissant la recherche), uniquement pour la **première page** (feuilleter n'est pas chercher). `GET /api/v1/trips/search` accepte désormais `originLabel` et `destLabel` optionnels, qui ne filtrent rien et ne servent qu'à la lisibilité de la trace.

### Webhook (public, sécurisé par secret partagé, pas par JWT)
- `POST /api/v1/payments/kkiapay/webhook` — URL à déclarer dans le tableau de bord Kkiapay
  (menu Webhook) : `https://<domaine>/api/v1/payments/kkiapay/webhook`, avec le même
  « secret hash » que `KKIAPAY_WEBHOOK_SECRET`. La corrélation avec la réservation passe par
  `stateData.bookingId` (le paramètre `data` du widget, posé par `frontend/src/lib/kkiapay.ts`),
  accepté indifféremment comme objet JSON ou comme chaîne JSON. Le webhook réutilise le paiement
  `INITIATED` préparé par `/payments/deposit` (pas de seconde ligne), reverifie statut et montant,
  et ne reconfirme jamais une réservation déjà expirée (places libérées) : le cas est journalisé
  en `ERROR` pour remboursement manuel.

### Actuator
- `/actuator/health`, `/actuator/info` publics ; le reste (`/actuator/**`, dont `/metrics`) réservé à `ROLE_ADMIN`.

---

## 4bis. Deuxième lot d'endpoints — alignement avec le front déjà écrit

Le front (`frontend/src/api/extended.ts`, `types.ts`, hooks) appelait des endpoints qui n'existaient pas encore ou pas sous ce nom ; grâce au repli "demo/resilient" du front (`resilient()`/`resilientMutation()`, qui retombe sur des données factices sur 404/405/501/502+), l'application compilait et s'exécutait malgré tout — mais sans jamais toucher la vraie base de données pour ces écrans. Ce lot referme cet écart. **Le code TypeScript du front a servi de source de vérité** pour le chemin, le verbe et la forme JSON exacts à chaque fois qu'il divergeait de la description fonctionnelle fournie (ex. `GET /api/v1/bookings` : tableau plat, pas une page).

### Nouveaux endpoints

| Endpoint | Détail |
|---|---|
| `GET /api/v1/bookings?expand=trip,paymentPlan` | Liste plate (pas paginée, contrat front) des réservations de l'utilisateur courant. `expand` est accepté mais l'enrichissement (trajet + plan de paiement) est de toute façon inclus par défaut, la requête `JOIN FETCH` sous-jacente ne coûtant rien de plus. |
| `GET /api/v1/bookings/{id}?expand=trip,paymentPlan` | Idem, un seul élément (`BookingDetailResponse`). |
| `GET /api/v1/trips/{id}/stops` | Arrêts intermédiaires avec prix par tronçon depuis l'origine. Même règle de visibilité que `GET /api/v1/trips/{id}` (public si `PUBLISHED`, 404 sinon pour un tiers). |
| `POST /api/v1/bookings/{id}/payments/deposit` | Initie l'acompte mobile money pour cette réservation ; renvoie la même charge utile que `POST /api/v1/payments/kkiapay/initiate` (conservé, devient l'alias historique). |
| `POST /api/v1/auth/otp/register` | Inscription sans mot de passe : crée le compte (prénom, nom, e-mail **obligatoire**) avec un mot de passe aléatoire inutilisable et envoie le code de connexion à l e-mail ; 202 avec `{channel, destination}` (destination masquée), la session s ouvre ensuite via `/otp/verify`. 409 si le numéro ou l e-mail existe déjà. `POST /auth/otp/request` renvoie la même réponse, 404 si le numéro est inconnu. Un compte suspendu est refusé à la vérification OTP, au rafraîchissement **et à chaque requête** (filtre JWT). |
| `POST /api/v1/auth/refresh` | Rotation du refresh token (V11, `RefreshTokenService`) : l ancien est révoqué, un jeton déjà tourné présenté à nouveau révoque toute la chaîne (401), durée absolue 90 jours. |
| `POST /api/v1/auth/logout` | Révoque le refresh token présenté et sa chaîne ; toujours 204. |
| `POST /api/v1/me/email/request`, `POST /api/v1/me/email/confirm` | Changement d adresse e-mail en deux temps (code reçu sur la nouvelle adresse, unicité `lower(email)`, avis à l ancienne adresse, journal `USER_EMAIL_CHANGED`). `PATCH /me` ne touche plus à l e-mail. |
| `GET /api/v1/bookings` (champ `reviewedByMe`) | Vrai si le passager a déjà noté le conducteur de ce trajet : le front n'affiche alors plus « Noter le conducteur ». |
| `POST /api/v1/trips/{id}/bookings`, `/booking-quote` (tarif par tronçon) | Avec `dropoffStopId`, le prix unitaire est celui de l'arrêt (`trip_stops.price_from_origin`), pas celui du trajet complet ; un arrêt étranger au trajet renvoie 400. |
| Réponses de sécurité | 401 (non authentifié) et 403 (rôle insuffisant) sont écrits directement en RFC 7807 par `SecurityConfig` ; le dispatch `/error` de Tomcat est autorisé, sinon un 403 ressortait en 401 anonyme. `CORS_ALLOWED_ORIGINS` (liste, `*` par défaut) remplace l'origine `*` en dur. |
| `GET /api/v1/payments/{paymentId}` | État d'un paiement (sondage front en attendant le webhook) ; réservé au passager propriétaire. |
| `POST /api/v1/payments/{paymentId}/confirm` | Confirmation immédiate à partir de l'évènement `success` du widget Kkiapay (`{ transactionId }`). Le serveur reverifie la transaction auprès de Kkiapay — statut **et** montant (un widget ouvert avec 5 F ne confirme pas une réservation à 1 000 F) — puis inscrit l'identifiant Kkiapay dans `provider_tx_id`, ce qui rend le webhook ultérieur idempotent. Réservé au passager propriétaire. |
| `PATCH /api/v1/me` | Mise à jour partielle du profil (endpoint déjà présent, aucune modification nécessaire — vérifié conforme au contrat front). |
| `GET`/`PATCH /api/v1/me/preferences` | Préférences de notification (push/SMS/e-mail, langue) et préférences à bord (musique, fumeur, animaux, bavardage). Table `user_preferences` (V6), ligne créée paresseusement à la première consultation. |
| `GET`/`POST /api/v1/me/identity` | Dépôt et état de la vérification d'identité (`identity_verifications`, V6). Le téléversement de la photo du document reste un TODO documenté (voir §10) : seuls le type et le numéro de document sont enregistrés. |
| `GET`/`POST /api/v1/me/payment-methods`, `DELETE /api/v1/me/payment-methods/{id}` | Comptes mobile money de l'utilisateur (`payment_methods`, V6) : opérateur + MSISDN, un seul par défaut. Le premier ajouté devient automatiquement le défaut ; supprimer le défaut promeut le plus ancien restant. |
| `DELETE /api/v1/me/vehicles/{id}` | Refuse (409) si le véhicule est engagé sur un trajet `PUBLISHED`/`FULL` à venir. |
| `GET /api/v1/me/recurring-trips` | Trajets récurrents de l'utilisateur ("votre trajet de la semaine"), déduits par heuristique de l'historique de réservations (voir §10 — pas une préférence stockée). |
| `GET /api/v1/me/conversations` | Conversations de l'utilisateur avec dernier message et compteur de non-lus. |
| `POST /api/v1/notifications/{id}/read`, `POST /api/v1/notifications/read-all` | Marquage lu, unitaire ou en masse. |
| `POST /api/v1/trip-alerts` | Création d'une alerte de recherche (`search_alerts`, table déjà existante). |

### Endpoints admin renommés / doublés (le front appelle la nouvelle forme ; l'ancienne reste disponible)

| Nouvelle forme (front) | Ancienne forme (conservée en alias) |
|---|---|
| `GET /api/v1/admin/verifications?status=PENDING`, `POST /{id}/approve`, `POST /{id}/reject` (motif optionnel) | `POST /api/v1/admin/users/{id}/verify-identity` (conservé tel quel, chemin distinct — pas un simple alias, voir §10) |
| `POST /api/v1/admin/payouts/{id}/pay` | Marque un lot réglé après virement manuel ; refuse un lot déjà réglé ou sans compte de destination (V12 : destination = compte mobile money vérifié du conducteur, jamais son numéro de connexion ; `reversedCount`/`reversedAmount` signalent les réservations remboursées après inclusion). |
| `GET /api/v1/admin/payments?status=`, `POST /{id}/refund`, `POST /{id}/mark-refunded` | File des remboursements (lot 1.2) : `REFUND_PENDING` exécutés hors transaction avec reprise toutes les 5 min, `REFUND_MANUAL` (partiel, échec définitif, sans identifiant Kkiapay) à traiter depuis le tableau de bord Kkiapay puis marquer. |
| `GET /api/v1/admin/payment-accounts?verified=`, `POST /{id}/verify` | Comptes mobile money à vérifier (possession du numéro) avant de servir de destination de reversement. |
| `POST /api/v1/admin/users/{id}/reinstate` | Réactivation d un compte suspendu (l alias historique `/activate` a été retiré, lot 1.1). |
| `PATCH /api/v1/admin/reports/{id}` (`{status}`) | `POST /api/v1/admin/reports/{id}/resolve` (conservé, sémantique legèrement différente : `resolve` prend une note de résolution, `PATCH` change juste le statut) |
| `GET /api/v1/admin/stats?days=N` | `GET /api/v1/admin/stats?from=...&to=...` (conservé ; les deux formes coexistent sur le même chemin, distinguées par les paramètres de requête présents) |

`?days=N` renvoie désormais des séries temporelles jour par jour, des totaux, des variations en % par rapport à la période précédente de même durée, la répartition par statut de réservation, et les axes (origine/destination) les plus demandés (`AdminStatsResponse`, voir `AdminStatsService#computeStats(int)`).

### Autre changement de contrat
- `DriverSummary` (utilisé dans les résultats de recherche de trajets) porte désormais un champ `identityVerified` (booléen), reflet direct de `User.identityVerified` — le front approximait jusqu'ici le filtre "conducteurs vérifiés" par le nombre d'avis, faute de ce drapeau.
- `GET /api/v1/admin/users` : chemin inchangé mais forme de requête/réponse remplacée (pas doublée, aucun autre appelant dans le dépôt) — recherche libre `?q=...` au lieu d'un filtre par statut paginé, réponse alignée sur `AdminUserResponse` attendu par le front (`identityVerified`, `phoneVerified`, `suspended`, `tripsPublished`, `bookingsMade`, `ratingAvg`).

### 4ter. Paiement fractionné réel (règle métier n.21, migration V7)

Corrige `POST /api/v1/bookings/{id}/payments/deposit`, qui jusque-là initiait la totalité de `booking.amount` chez Kkiapay sous une apparence d'acompte — ce n'était pas un acompte, et la promesse faite au passager dans l'interface ("acompte maintenant, solde en espèces à bord") était fausse. Voir la règle métier n.21 (§6) pour la formule complète, et §7ter pour le détail de la relecture dédiée à ce changement.

- `POST /api/v1/bookings/{id}/payments/deposit` initie désormais réellement `deposit_amount`, pas `amount` (la totalité en `MOMO_FULL`, un acompte en `MOMO_DEPOSIT`) — même correction pour l'ancien `/api/v1/payments/kkiapay/initiate`, qui délègue au même code.
- `PayoutService` (règle n.12) ne reverse plus au conducteur que ce que la plateforme a réellement encaissé en ligne : `depositAmount - serviceFee` en `MOMO_DEPOSIT`, `amount - serviceFee` en `MOMO_FULL` (jamais `amount - serviceFee` pour un acompte partiel, qui créditerait un solde jamais perçu par la plateforme).
- Le barème d'annulation (règle n.7) porte sur `depositAmount`, seul montant réellement encaissé — jamais sur `amount`.
- `paymentPlan` (`GET /api/v1/bookings?expand=trip,paymentPlan`) reflète la décomposition réellement stockée sur la réservation : `totalAmount`, `serviceFee`, `depositAmount`, `balanceAmount`, `paymentMethod`, `paymentStatus` (vue simplifiée : `PENDING`/`DEPOSIT_PAID`/`PAID_IN_FULL`/`CASH_DUE_ON_BOARD`/`CANCELLED`).
- Le mode `MOMO_FULL` (payer la totalité en ligne) reste proposé pour un passager qui préfère ne rien régler en espèces ; le mode `CASH` reste possible mais **ne doit être ouvert qu'aux conducteurs de confiance** (voir règle n.21 et §10 — aucune commission n'est perçue par la plateforme sur ce mode).
- **`POST /api/v1/trips/{id}/booking-quote`** (nouveau, dernier écart de contrat refermé) — devis de réservation, authentifié, qui ne crée rien en base :
  - Requête (`BookingQuoteRequest`) : `{seats, dropoffStopId?, paymentMode?}`. Pas de `pickupStopId` : comme `createBooking`, le prix est toujours `pricePerSeat * seats`, jamais un tarif par tronçon (limitation connue, partagée par les deux endpoints — voir §10).
  - Réponse : le même `PaymentPlanResponse` qu'une réservation existante (`totalAmount`, `serviceFee`, `depositAmount`, `balanceAmount`, `paymentMethod`, `paymentStatus` — toujours `"PENDING"` ici, rien n'existe encore —, `depositDueAt` — une estimation ancrée sur l'instant de l'appel, pas un vrai `createdAt` —, `freeCancellationHours`).
  - Calcule via `BookingService#computeAmounts`, la **même** méthode privée que `createBooking` (factorisée pour cette raison précise) : devis et réservation ne peuvent jamais diverger, aucune formule dupliquée.
  - Refuse (409 `ConflictException`) si le trajet n'est pas `PUBLISHED` (complet, annulé, brouillon, terminé) ou si `seatsAvailable < seats` ; refuse (403 `ForbiddenException`) si l'appelant est le conducteur du trajet — mêmes vérifications, même ordre, que `createBooking`.
  - **Non couvert délibérément** : un appelant qui a déjà une réservation active sur ce trajet obtient quand même un devis valide (seule `createBooking` refuse ce cas, avec un 409 distinct) — le devis répond à "combien ça coûte", pas à "cette réservation précise réussira-t-elle", et ce cas n'était pas dans la liste des refus demandés.
- **`paymentMode`** — nom de champ JSON exact attendu par le front (distinct du nom de propriété interne `Booking.paymentMethod`), désormais accepté sur `POST /api/v1/trips/{id}/bookings` (`CreateBookingRequest`, renommé depuis `paymentMethod`) et sur le devis ci-dessus (`BookingQuoteRequest`) : nullable dans les deux cas, `MOMO_DEPOSIT` appliqué par défaut si absent, pour ne pas casser les appelants antérieurs à l'introduction du paiement fractionné. Valeurs acceptées : `MOMO_DEPOSIT` / `MOMO_FULL` / `CASH` — ce sont les noms qui font foi (ceux stockés en base, `bookings.payment_method`) ; le front adopte ces noms plutôt que l'inverse.

### 4quater. Statistiques du profil public conducteur (règle métier n.22, migration V8)

`GET /api/v1/users/{id}` affichait `undefined` sur trois champs que le front lit déjà : `reliabilityRate`, `responseTimeMinutes`, `preferences`. Les trois sont désormais calculés par une requête d'agrégation SQL dédiée à chaque fois (jamais par le chargement d'une collection de réservations ou de messages en mémoire — ce profil est consulté avant chaque réservation, donc souvent) — voir `UserService#getPublicProfile`.

- **`reliabilityRate`** (entier, `%`, ou `null`) — `COMPLETED / (COMPLETED + annulations conducteur tardives + NO_SHOW)`, arrondi à l'entier le plus proche. `null` en dessous de **5** trajets mesurables (`UserService.MIN_SAMPLE_SIZE`), pour que le front distingue « pas encore d'historique » de « mauvais historique » plutôt que d'afficher 0 %.
  - La « tardivité » d'une annulation conducteur (< 24h avant le départ, voir `DriverCancellationPolicy`) n'est stockée nulle part par réservation — seul `users.late_cancellations_count` existe, un compteur cumulé non rattachable à une réservation précise. Elle est donc **reconstruite** dans la requête (`BookingRepository#getReliabilityStats`) en comparant `bookings.updated_at` (horodatage de l'annulation en cascade) à `trips.departure_at - 24h`, exactement le seuil de `DriverCancellationPolicy`. Une réservation annulée par le conducteur mais **dans les temps** (> 24h avant le départ) n'entre ni au numérateur ni au dénominateur : ce n'est pas un manquement à la fiabilité.
  - Requête : une seule agrégation SQL native (`bookings JOIN trips`, filtrée par `trips.driver_id`, trois `sum(case when ...)`), indexée par `idx_trips_driver`/`idx_bookings_trip` (déjà présents depuis V1) — aucun index supplémentaire nécessaire.
- **`responseTimeMinutes`** (entier, minutes, ou `null`) — délai **médian** entre le premier message d'un passager dans une conversation et la première réponse du conducteur, sur les **90 derniers jours**. `null` en dessous de **5** échanges mesurables (même seuil, même constante).
  - Requête : une seule agrégation SQL native (`MessageRepository#getResponseTimeStats`), trois CTE — conversations du conducteur (jointes via `bookings`/`trips`), premier message du passager par conversation depuis 90 jours, premier message du conducteur strictement postérieur — puis `percentile_cont(0.5) within group (...)` sur l'écart en minutes, en une seule passe. Une conversation sans réponse conducteur (ou sans message passager dans la fenêtre) ne contribue à aucun échantillon — ce n'est pas un délai infini compté comme un échec, simplement une donnée absente.
  - Nécessite le nouvel index composite `idx_messages_conversation_sender_created` (`messages(conversation_id, sender_id, created_at)`, migration V8) : sert à la fois le filtre passager et le filtre conducteur de chaque CTE sans balayer les messages d'un tiers dans la conversation.
- **`preferences`** — sous-ensemble **public** des préférences à bord de l'utilisateur (`user_preferences`) : `smoking`, `music`, `pets`, `chatty`. **Jamais** les préférences de notification (push/SMS/e-mail, langue), qui restent privées (réservées à `GET /api/v1/me/preferences`). Absence de ligne `user_preferences` pour cet utilisateur → valeurs par défaut (règle métier n.17, même sémantique que le profil privé) — `UserService#resolvePublicPreferences` ne crée jamais de ligne en consultant le profil d'un tiers (contrairement à `UserPreferencesService#findOrCreate`, qui ne s'applique qu'à SON PROPRE profil) : consulter le profil de quelqu'un d'autre ne doit pas avoir d'effet de bord en base. Une seule requête par clé primaire (`user_id`), déjà indexée par construction (clé primaire de `user_preferences`).
- **Aucune fuite de donnée privée** : les trois requêtes ne renvoient que des compteurs/pourcentages/minutes ou les quatre booléens/enum publics ci-dessus — jamais de téléphone, e-mail, identité, ni aucun montant (`amount`/`serviceFee`/`depositAmount` ne quittent jamais ces requêtes, contrairement à `BookingRepository#sumAmountBetween` utilisé côté admin).
- **Écart constaté avec la demande initiale, tranché en faveur du contrat front réel** : la demande mentionnait une préférence « bagages » dans `preferences`. Ni `user_preferences` (V6) ni le type `DriverPreferences` réellement consommé par le front (`frontend/src/api/extended.ts`) ne portent un tel champ — seuls `smoking`/`music`/`pets`/`chatty` existent des deux côtés. Rien n'a donc été ajouté à ce sujet ; voir la réponse pour signalement explicite.

---

## 5. Variables d'environnement ajoutées

Toutes ont une valeur par défaut sûre pour le développement (voir `application.yml`) ; **à surcharger explicitement en production**.

| Variable | Défaut dev | Rôle |
|---|---|---|
| `MAIL_MODE` | `log` | `log` = e-mails journalisés (`[MAIL-STUB]`) ; `smtp` = envoi réel via `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` (Brevo, Gmail avec mot de passe d application, tout relais SMTP). Voir `service/mail/MailConfig`. |
| `OTP_CHANNEL` / `OTP_SMS_FALLBACK` | `email` / `false` | canal des codes de connexion ; en `email`, un compte sans adresse reçoit un 400 explicite sauf si le repli SMS est activé. Voir `OtpDeliveryService`. |
| `SMS_MODE` / `SMS_PROVIDER` | `log` / `generic` | `http` + `smspartner` (`SMSPARTNER_API_KEY`, `SMSPARTNER_SENDER`, `SMSPARTNER_SANDBOX`), `twilio` (`TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM`) ou `africastalking` (`AT_USERNAME`, `AT_API_KEY`, `AT_SENDER_ID`, `AT_SANDBOX`) pour envoyer de vrais SMS ; `generic` = URL + cle (`SMS_HTTP_URL`, `SMS_PROVIDER_KEY`). Voir `service/sms/SmsConfig`. |
| `KKIAPAY_MODE` | `stub` | `stub` = aucun paiement réel (dev/tests) ; `http` = appels réels à l'API Kkiapay |
| `KKIAPAY_PUBLIC_KEY` / `KKIAPAY_PRIVATE_KEY` / `KKIAPAY_SECRET` | vide | requis si `KKIAPAY_MODE=http` |
| `KKIAPAY_WEBHOOK_SECRET` | vide | secret hash du tableau de bord Kkiapay (menu Webhook), vérifié via l'en-tête `X-Kkiapay-Secret` |
| `KKIAPAY_SANDBOX` | `true` | bascule l'URL de base Kkiapay sandbox/prod |
| `SMS_MODE` | `log` | `log` = SMS journalisés seulement ; `http` = fournisseur HTTP réel |
| `SMS_HTTP_URL` / `SMS_PROVIDER_KEY` | vide | requis si `SMS_MODE=http` (aucun fournisseur imposé) |
| `OTP_MAX_ATTEMPTS` | `5` | tentatives de code OTP avant invalidation |
| `OTP_RATE_LIMIT_MAX_REQUESTS` / `OTP_RATE_LIMIT_WINDOW_MINUTES` | `3` / `10` | anti-spam des demandes d'OTP, par numéro |
| `SEARCH_ALERT_RADIUS_KM` | `10` | rayon de correspondance géographique des alertes de recherche |
| `PAYOUT_MIN_THRESHOLD_FCFA` | `2000` | solde minimum pour qu'un conducteur soit inclus dans un lot de reversement |
| `SUBSCRIPTION_PRICE_FCFA` | `2000` | prix mensuel de l'abonnement conducteur |
| `RATE_LIMIT_AUTH_MAX` / `RATE_LIMIT_AUTH_WINDOW_SECONDS` | `20` / `60` | limite de débit sur `/api/v1/auth/**` |
| `RATE_LIMIT_WEBHOOK_MAX` / `RATE_LIMIT_WEBHOOK_WINDOW_SECONDS` | `120` / `60` | limite de débit sur le webhook Kkiapay |
| `SERVICE_FEE_RATE` / `FEE_ROUNDING_STEP` | `0.08` / `5` | déjà présentes avant ce travail, désormais réellement injectées dans `FeePolicy` |
| `BOOKING_DEPOSIT_BASE_FCFA` | `1000` | acompte de base du mode `MOMO_DEPOSIT` (paiement fractionné, règle métier n.21) ; l'acompte réellement prélevé est `max(cette valeur, frais de service de la réservation)`, arrondi aux 5 FCFA supérieurs, plafonné au montant total — voir `FeePolicy#computeDepositAmount` |

---

## 6. Règles métier implémentées (avec leurs valeurs)

1. Décrémentation atomique des places (`seats_available`), jamais négative, même en cas de réservation concurrente sur la dernière place.
2. Réservation `PENDING_PAYMENT` expirée après **20 min** sans paiement (`ekuiseo.booking.pending-payment-ttl-minutes`) → places libérées.
3. Paiement confirmé uniquement après re-vérification serveur-à-serveur auprès de Kkiapay (jamais sur la seule foi du webhook).
4. Commission de service : **8 %** du montant, arrondie aux **5 FCFA** supérieurs (`ekuiseo.fee.*`).
5. Un conducteur ne peut pas réserver son propre trajet.
6. Trajets `QUOTIDIEN` récurrents : génération automatique des occurrences sur un horizon de **14 jours**, chaque jour à 03h00 UTC.
   - 6bis. Annulation par le conducteur : cascade complète (voir §3, point 6).
   - 6ter. Un trajet `DRAFT` n'est visible que par son conducteur.
7. Annulation par le passager : remboursement intégral si > 24h avant le départ, sinon selon le barème de `CancellationPolicy` (retenue partielle) ; rien si le trajet est déjà parti.
8. OTP : code à 6 chiffres envoyé par e-mail (SMS en repli configurable), expire après **5 min**, **5 tentatives** incorrectes maximum avant invalidation, **3 demandes / 10 min / numéro**.
9. Annulation conducteur tardive (< 24h avant le départ) : incrémente `lateCancellationsCount`, utilisé pour la modération des conducteurs peu fiables.
10. Rappel automatique la veille du départ (SMS + in-app), envoyé une fois par trajet, scanné chaque heure sur une fenêtre glissante [23h, 25h).
11. Abonnement conducteur : **2 000 FCFA/mois** (`ekuiseo.subscription.price-fcfa`) → commission ramenée à **0 %** pendant la période active.
12. Reversements conducteurs (corrigée par la règle n.21) : montant net = `booking.depositAmount - booking.serviceFee` en `MOMO_DEPOSIT`, `booking.amount - booking.serviceFee` en `MOMO_FULL` (jamais les espèces, déjà payées directement au conducteur) — la plateforme ne redistribue jamais plus qu'elle n'a réellement encaissé en ligne. Seuil minimum **2 000 FCFA** (`ekuiseo.payout.minimum-threshold-fcfa`) pour être inclus dans un lot.
13. Alertes de recherche : notification dès la publication d'un trajet correspondant, rayon **10 km** par défaut (`ekuiseo.search-alert.radius-km`) autour de l'origine ET de la destination de l'alerte.
14. Limitation de débit : **20 requêtes/60s/IP** sur `/api/v1/auth/**`, **120/60s/IP** sur le webhook Kkiapay (voir §8 pour les limites de cette implémentation).
15. Signalements : cible exactement un utilisateur OU un trajet, statuts `OPEN → REVIEWING → ACTION_TAKEN|DISMISSED`.
16. Profil public : jamais de téléphone, e-mail, ou date de naissance exposés.
17. Préférences utilisateur (notifications + à bord) : une ligne par utilisateur, créée paresseusement à la première consultation ou modification plutôt qu'à l'inscription ; son absence équivaut donc aux valeurs par défaut.
18. Moyens de paiement (mobile money) : au plus un par défaut par utilisateur (`uq_payment_methods_default`) ; le premier ajouté devient automatiquement le défaut ; supprimer le défaut promeut le plus ancien restant s'il en reste un.
19. Vérification d'identité : une seule ligne par utilisateur (une nouvelle soumission remplace la précédente et repasse au statut `PENDING`, jamais d'historique de soumissions multiples) ; une approbation admin positionne à la fois `identity_verifications.status = APPROVED` et `users.identity_verified = true`.
20. Alertes de recherche (complément à la règle n.13) : le matching respecte désormais aussi le nombre de places demandées (`seats`) et le type de trajet demandé (`tripType`, `null` = les deux types), colonnes ajoutées en V6 mais jusqu'ici non consultées par `SearchAlertMatchService`.
21. **Paiement fractionné (règle tranchée par le produit, migration V7)** : le mode de paiement d'une réservation est l'un de `MOMO_DEPOSIT` (défaut), `MOMO_FULL`, `CASH`. En `MOMO_DEPOSIT`, seul un acompte est prélevé en ligne maintenant — `depositAmount = min(amount, roundUp5(max(BOOKING_DEPOSIT_BASE_FCFA, serviceFee)))`, le reste (`balanceDueOnBoard = amount - depositAmount`) est réglé en espèces au conducteur pendant le trajet. Le `max` avec `serviceFee` est indispensable : sans lui, une réservation dont la commission dépasse l'acompte de base ferait encaisser à la plateforme, au moment de l'acompte, moins que sa propre commission (voir `FeePolicy#computeDepositAmount`). En `MOMO_FULL`, `depositAmount = amount` (comportement historique, toujours proposé). En `CASH`, `depositAmount = 0`, la plateforme ne perçoit rien pour cette réservation (**mode à réserver aux conducteurs de confiance** : sans acompte ni commission prélevés, il permet de contourner intégralement la commission de la plateforme — aucune validation conducteur ni restriction d'éligibilité n'est implémentée pour l'instant côté backend, voir §10). Le barème d'annulation (règle n.7) porte sur `depositAmount`, seul montant réellement encaissé — jamais sur `amount`. Cette même formule (via `BookingService#computeAmounts`, factorisée) est appliquée à l'identique par `POST /api/v1/trips/{id}/booking-quote` (§4ter) : un devis ne peut jamais annoncer un montant différent de celui qu'une réservation réelle produirait pour les mêmes paramètres.
22. **Statistiques du profil public conducteur (migration V8)** : `reliabilityRate = round(100 * COMPLETED / (COMPLETED + annulations conducteur tardives + NO_SHOW))`, `null` en dessous de **5** trajets mesurables ; `responseTimeMinutes` = médiane (pas la moyenne, pour rester robuste à quelques réponses très tardives) du délai entre le premier message d'un passager et la première réponse du conducteur, sur les **90 derniers jours**, `null` en dessous de **5** échanges mesurables ; `preferences` = sous-ensemble public de `user_preferences` (`smoking`/`music`/`pets`/`chatty`, jamais les préférences de notification). Les deux seuils de 5 sont une seule constante partagée (`UserService.MIN_SAMPLE_SIZE`) plutôt que deux valeurs indépendantes qui pourraient diverger sans raison. Voir §4quater.

---

## 7. Résultat de la passe de vérification finale

Une relecture systématique a été faite sur l'ensemble des fichiers ajoutés ou modifiés (environ 70 fichiers Java + 4 migrations), fichier par fichier, en particulier :

- **Migrations vs entités** : chaque colonne ajoutée en `V2`–`V5` a été confrontée à l'entité JPA correspondante (nom de colonne `@Column(name=...)`, nullabilité, type, valeur par défaut, contrainte CHECK). Aucun écart trouvé.
- **Constructeurs et sites d'appel** : `BookingService`, `TripService`, `PaymentService`, `PayoutService`, `SubscriptionService`, `AuthService`, `NotificationService`, `RateLimitingFilter`, `FeePolicy`, `JwtService` — chaque constructeur modifié a été comparé à tous ses sites d'instanciation (Spring, et manuellement dans les tests).
- **Repositories** : `TripRepository` et `BookingRepository` relus intégralement (ils n'avaient été touchés que par des `Edit` ciblés) — toutes les requêtes JPQL/SQL natif vérifiées contre les noms de champs d'entité réels et les colonnes réelles des migrations.
- **Mappers MapStruct** : chaque nouveau mapper (`ReportMapper`, `PayoutMapper`, `GeoPlaceMapper`, `AuditLogMapper`, `AdminUserMapper`) vérifié champ par champ contre son entité source et son DTO cible ; les correspondances implicites par nom (`AdminUserResponse`, `PayoutResponse`, etc.) confirmées exactes.
- **`GlobalExceptionHandler`** : les deux nouveaux handlers (`TooManyRequestsException` → 429, `KkiapayUnavailableException` → 503) ne recouvrent aucun handler existant.
- **`application.yml`** : toutes les clés `@Value(...)` référencées dans le code ont une entrée correspondante (avec valeur par défaut), aucune clé orpheline.
- **Bug trouvé et corrigé pendant cette passe** : `UpdateTripRequest.originLabel`/`destLabel` portaient `@NotBlank`, qui — contrairement aux autres contraintes du même DTO — rejette aussi `null`. Sur un DTO de PATCH partiel où `null` signifie « champ non modifié », cela aurait fait échouer la validation dès qu'un appelant omettait ces deux champs. Corrigé : `@Size(min = 1)` (qui autorise `null`) + rejet explicite d'une chaîne blanche côté `TripService#updateTrip`.
- **`Dockerfile`** : corrigé un bug préexistant (nom de jar `ali-api.jar` incohérent avec `finalName=ekuiseo-api` du `pom.xml`, et utilisateur système nommé `ali` sans rapport avec le projet) → jar et utilisateur renommés `ekuiseo-api`/`ekuiseo`.
- **`pom.xml`** : aucune dépendance supplémentaire nécessaire — `RestClient` (paiements/SMS), les annotations Jackson (`@JsonProperty`, `@JsonIgnoreProperties`) et Swagger/OpenAPI (`@Tag`, `@Operation`) sont toutes déjà couvertes par `spring-boot-starter-web` et `springdoc-openapi-starter-webmvc-ui`, déjà présents.

**Aucune compilation n'a pu être exécutée dans cet environnement** (contrainte du sujet) : cette relecture manuelle en tient lieu, mais une première compilation réelle chez vous reste indispensable avant tout déploiement.

### 7bis. Relecture du deuxième lot (endpoints manquants côté front)

Même exigence de rigueur appliquée à l'identique sur les ~45 fichiers Java créés ou modifiés dans ce lot (DTO records, mappers, services, contrôleurs, repositories, correctifs de tests) :
- **Ordre des champs des `record`** systématiquement comparé au site d'appel de leur constructeur (Jackson sérialise un record par ses composants, dans l'ordre déclaré — un décalage serait silencieux, jamais une erreur de compilation).
- **Chaque méthode de repository dérivée nouvellement référencée** (`findByStatusOrderBySubmittedAtAsc`, `findByUserIdOrderByCreatedAtAsc`, `countByUserId`, `existsByVehicleIdAndStatusInAndDepartureAtAfter`, etc.) vérifiée déclarée dans l'interface correspondante, avec un chemin de propriété valide vers l'entité réelle.
- **Convention Lombok booléenne** : `PaymentAccount.isDefault` génère un getter `isDefault()` et un setter `setDefault(boolean)` (Lombok retire le préfixe "is" côté setter) — vérifié utilisé correctement partout, y compris dans `PaymentAccountService#delete` lors de la promotion du moyen de paiement par défaut suivant.
- **Constructeurs de service modifiés** (`BookingService` +`MessageRepository`, `PayoutService` +`PaymentAccountRepository`) : les trois fichiers de test qui instancient ces services directement (`BookingServiceConcurrencyTest`, `BookingServiceDriverCancellationTest` — deux sites d'appel — et `PayoutServiceTest` — deux sites d'appel) ont été mis à jour en conséquence, sans changement de leur logique de test.
- **Aucune nouvelle migration** ne s'est révélée nécessaire (voir §2.2) ; confirmé en confrontant chaque nouvelle entité/colonne consommée par ce lot à `V6__preferences_identity_payment_accounts_alerts.sql`, déjà présente.

### 7ter. Paiement fractionné réel (règle n.21, migration V7) — relecture dédiée

Le plan de paiement acompte/solde a d'abord été livré comme une simple mise en forme d'affichage (voir historique git/conversation), puis **tranché et implémenté comme un vrai mouvement d'argent** sur demande explicite du produit. Relecture spécifique à ce second passage, en plus de celle du §7bis :

- **Invariant de reversement** : `PayoutService#netAmount` a été réécrit (`depositAmount - serviceFee` en `MOMO_DEPOSIT`, `amount - serviceFee` en `MOMO_FULL`, jamais atteint en `CASH`) et **`PayoutServiceTest` corrigé en conséquence** (pas contourné) — un nouveau test (`netAmount_neverNegative_becauseDepositAlwaysCoversServiceFee`) vérifie explicitement l'invariant `deposit >= serviceFee` garanti par `FeePolicy#computeDepositAmount`, qui est le point sur lequel tout le reste du calcul de reversement repose.
- **`BookingRepository#findPayableForDriver`/`findDriverIdsWithPayableBookings`** : le filtre `paymentMethod = :method` (un seul mode) est devenu `paymentMethod in :methods` (les deux modes MoMo), sinon un reversement `MOMO_DEPOSIT` n'aurait jamais été trouvé par ces requêtes.
- **`CancellationPolicy`** : aucun changement de logique (elle reste générique sur un montant), seul son appelant change — `BookingService#cancelByPassenger` lui passe désormais `booking.depositAmount`, jamais `booking.amount`. Vérifié qu'aucun autre appelant de `CancellationPolicy#evaluate` n'existe dans le code.
- **`PaymentService#initiate`** (utilisé aussi bien par l'ancien `/kkiapay/initiate` que par le nouveau `/bookings/{id}/payments/deposit`) charge désormais `booking.depositAmount`, jamais `booking.amount` — c'est le seul endroit où le montant réellement envoyé à Kkiapay est fixé, donc le seul endroit à corriger pour que **les deux endpoints** se comportent correctement.
- **`FeePolicy`** a gagné un troisième paramètre de constructeur (`depositBaseFcfa`) : tous les sites d'instanciation directe (`FeePolicyTest`, `BookingServiceConcurrencyTest`, `BookingServiceDriverCancellationTest`, 5 occurrences au total) ont été mis à jour.
- **`PaymentMethod`** : `MOMO` renommé/scindé en `MOMO_DEPOSIT`/`MOMO_FULL` (plus `CASH` inchangé) — recherche exhaustive de `PaymentMethod.MOMO` dans tout le code (main + tests) pour confirmer qu'aucune référence à l'ancienne valeur ne subsistait.
- **Migration V7** : vérifiée pour préserver les données existantes (une réservation `MOMO` historique devient `MOMO_FULL` avec `depositAmount = amount`, jamais recalculée au nouveau barème — son reversement éventuel, déjà en cours ou déjà soldé, n'est pas rouvert) ; les deux `CHECK` ajoutés (`deposit_amount + balance_due_on_board = amount`, `0 <= deposit_amount <= amount`) sont satisfaits par la migration de données qui les précède dans le même fichier.
- **Ce qui n'a délibérément PAS été construit** : une validation conducteur pour les réservations `CASH` (voir §10) — la formulation du produit ("reste soumise à validation du conducteur") n'a été spécifiée ni en nom d'endpoint, ni en statut, ni en délai, contrairement aux points 1 à 3 et 5 à 6 qui portaient une formule exacte ; construire un nouveau statut de réservation et son cycle de vie sans ces précisions aurait été une extrapolation, pas une implémentation de ce qui a été tranché. Le comportement actuel (confirmation immédiate) est documenté comme tel et signalé pour clarification.

### 7quater. Devis de réservation (`booking-quote`) et renommage `paymentMode` — dernier écart de contrat, relecture dédiée

- **Non-duplication de la formule, vérifiée à la lecture** : `BookingService#quote` et `BookingService#createBooking` appellent tous deux `resolvePaymentMethod` puis `computeAmounts` (méthodes privées partagées, extraites lors de ce lot) — aucune des deux méthodes ne recalcule quoi que ce soit en propre ; `quote` s'arrête avant toute écriture (pas de `bookingRepository.save`, pas de décrémentation de `seatsAvailable`, pas d'appel à `PaymentService`).
- **Ordre et nature des vérifications de refus comparés terme à terme** avec `createBooking` : trajet non `PUBLISHED` → `ConflictException` ; `seatsAvailable < seats` → `ConflictException` ; appelant = conducteur du trajet → `ForbiddenException`. Même ordre, mêmes exceptions, mêmes codes HTTP (via `GlobalExceptionHandler`, déjà en place, aucun changement nécessaire). Vérifié que `quote` ne vérifie délibérément pas l'existence d'une réservation active préalable de l'appelant (voir §4ter et §10) — seul `createBooking` le fait, avec un 409 distinct.
- **`SecurityConfig`** : `POST /api/v1/trips/{id}/booking-quote` n'apparaît pas dans la liste explicite de `permitAll` (relue intégralement) — il retombe donc correctement sur `anyRequest().authenticated()`, comme voulu (« authentifié » est une exigence explicite de la demande).
- **Renommage de champ JSON `paymentMethod` → `paymentMode`** sur `CreateBookingRequest` (et nouveau sur `BookingQuoteRequest`) : recherche exhaustive de `.paymentMethod()` sur ces deux types dans tout le code (main + tests) pour confirmer qu'aucun site d'appel n'utilisait encore l'ancien nom d'accesseur ; `@NotNull` retiré (le champ est désormais nullable, résolu par défaut à `MOMO_DEPOSIT` dans `resolvePaymentMethod`) pour ne pas casser un appelant du contrat précédent à l'introduction du paiement fractionné.
- **Ordre des composants du nouveau `record BookingQuoteRequest(seats, dropoffStopId, paymentMode)`** comparé à son unique site de construction (`TripController#quote`, désérialisation Jackson par `@RequestBody`) — conforme.
- **Confirmation du taux de commission à 8 % partout côté serveur**, demandée explicitement : `FeePolicy` lit `${ekuiseo.fee.service-fee-rate}`, dont la valeur par défaut dans `application.yml` est `${SERVICE_FEE_RATE:0.08}` ; `MoneyUtils` porte les constantes `SERVICE_FEE_RATE_NUMERATOR = 8` / `SERVICE_FEE_RATE_DENOMINATOR = 100` (utilisées indépendamment de `FeePolicy` par endroits) ; le README l'énonce à deux reprises (règle n.4 ci-dessus, §3). **Aucune incohérence trouvée** — les 7 % évoqués côté front étaient une estimation locale obsolète, corrigée côté front, sans lien avec une valeur serveur erronée.
- **Nouveau test dédié** : `BookingServiceQuoteTest` (voir §9) instancie une vraie `FeePolicy` (pas un mock) pour vérifier la parité devis/réservation pour de vrai plutôt que de la supposer.

### 7quinquies. Statistiques du profil public conducteur (règle n.22, migration V8) — relecture dédiée

- **Absence de cascade de requêtes, vérifiée par construction** : `UserService#getPublicProfile` appelle exactement trois nouvelles méthodes (`getReliabilityStats`, `getResponseTimeStats`, `findByUserId`), chacune UNE seule requête SQL (deux agrégations natives, une lecture par clé primaire) — aucune des trois ne charge une liste de `Booking`/`Message`/`Conversation` en entités JPA gérées ; relu ligne à ligne pour confirmer qu'aucun accès à une collection lazy (`trip.getBookings()` ou équivalent, qui n'existe d'ailleurs pas dans ce modèle) n'a été introduit par erreur.
- **Index confirmé nécessaire par lecture du plan de requête attendu** (pas d'`EXPLAIN` réel possible, aucune base disponible dans cet environnement) : la requête de délai de réponse filtre `messages` par `(conversation_id, sender_id)` deux fois (une fois pour le passager, une fois pour le conducteur) puis prend un `MIN(created_at)` — sans l'index composite `idx_messages_conversation_sender_created` (V8), chaque filtre retombe sur un balayage de `idx_messages_conversation` suivi d'un filtre en mémoire sur `sender_id`. La requête de fiabilité, elle, n'a nécessité aucun nouvel index : `idx_trips_driver` et `idx_bookings_trip` (tous deux déjà présents depuis `V1__init.sql`) suffisent à un enchaînement d'index nested loop efficace.
- **Reconstruction de la "tardivité" d'une annulation conducteur sans nouvelle colonne** : confirmé par lecture de `BookingService#cascadeCancelForDriverTripCancellation` (§7ter) que `booking.updatedAt` est bien réécrit (via `@UpdateTimestamp`) au moment précis où `booking.status` passe à `CANCELLED_BY_DRIVER`, dans la même transaction que le calcul de `late` par `DriverCancellationPolicy#isLate(now, trip.departureAt)` — comparer ensuite `bookings.updated_at >= trips.departure_at - 24h` dans la requête d'agrégation reproduit donc fidèlement ce même calcul, sans nécessiter de colonne dédiée juste pour cette statistique.
- **Aucune fuite de donnée privée, vérifiée champ par champ** : les deux projections (`DriverReliabilityStats`, `DriverResponseTimeStats`) et la réponse `PublicPreferencesResponse` ont été comparées un à un à la liste des informations interdites (téléphone, e-mail, identité, tout montant) — aucun des trois ne s'en approche, contrairement à ce qu'aurait risqué une réutilisation paresseuse de `BookingRepository#sumAmountBetween` ou de `UserPreferencesResponse` en entier (qui, lui, expose la langue et les canaux de notification, restés strictement privés dans `PublicPreferencesResponse`).
- **Pas d'effet de bord en base sur un profil consulté par un tiers** : `UserService#resolvePublicPreferences` a été délibérément écrit pour ne PAS créer de ligne `user_preferences` en l'absence d'une ligne existante (contrairement à `UserPreferencesService#findOrCreate`, correct sur SON PROPRE profil) — vérifié qu'aucun appel à `userPreferencesRepository.save(...)` n'a été introduit dans ce chemin.
- **Nouveau constructeur `UserService`** (+`BookingRepository`, +`MessageRepository`, +`UserPreferencesRepository`) : aucun site d'instanciation directe dans les tests existants (confirmé par recherche exhaustive de `new UserService(`), donc aucune signature à corriger ailleurs — seul le nouveau test `UserServicePublicProfileTest` instancie ce service, avec la signature à jour dès l'écriture.
- **Nouveaux tests** : `UserServicePublicProfileTest` (voir §9), six cas — passage sous/au-dessus du seuil de 5 pour chacune des deux statistiques (avec vérification de l'arrondi), préférences par défaut vs préférences stockées.

---

## 8. Limitation de débit — portée assumée

Implémentation en mémoire (`ConcurrentHashMap` + fenêtre glissante), sans dépendance externe :
- **État local à l'instance JVM** : avec plusieurs réplicas derrière un load-balancer, la limite effective globale est multipliée par le nombre de réplicas. Une limite strictement globale nécessiterait un compteur partagé (Redis).
- **Clé = adresse IP** (`X-Forwarded-For` sinon adresse socket) : un NAT partagé (plusieurs utilisateurs derrière la même box) partage le même quota.

## 9. Tests

**Mise à jour (septembre 2026)** : le backend a été compilé et la suite exécutée (`mvn test`, 79 tests, 0 échec, 4 ignorés = les tests d'intégration Testcontainers). Deux corrections mécaniques ont suffi : une capture de variable non effectivement finale dans `PayoutService#runWeeklyBatch`, et une assertion Mockito erronée dans `RateLimitingFilterTest` (`times(2)` sur une paire requête/réponse qui n'est passée qu'une fois). Tests ajoutés avec la liquidité : `AdminLiquidityServiceTest` (taux, fenêtres de période, métrique nord, CSV) et `SearchEventServiceTest` (rattachement aux villes, bornage, jamais d'exception). Les requêtes natives de `SearchEventRepository`, `TripRepository#getFillStats*` et `BookingRepository#getSeatsByWeek` n'ont **pas** été rejouées contre PostGIS (Docker indisponible sur le poste) : à vérifier au premier démarrage, voir §10.

Complétés/ajoutés (tous écrits avec soin mais **non exécutés à l'origine**, faute de build) :
- `MoneyUtilsTest`, `CancellationPolicyTest` — préexistants, conservés tels quels.
- `BookingServiceConcurrencyTest` — mis à jour pour le nouveau constructeur de `BookingService`.
- `FeePolicyTest` — barème de commission paramétrable (taux, palier d'arrondi, exonération abonné).
- `DriverCancellationPolicyTest` — frontière des 24h avant départ.
- `BookingServiceDriverCancellationTest` — cascade complète (annulation, remboursement intégral, notification, pénalité conducteur).
- `PayoutServiceTest` — calcul du montant net reversé, filtrage par seuil minimum dans `runWeeklyBatch`.
- `RateLimitingFilterTest` — fenêtre glissante, quotas indépendants par IP et par route (auth vs webhook).
- `AdminAuthorizationIntegrationTest` — 403 pour un utilisateur non-admin, 200 pour un admin, 401 pour un appel anonyme, sur `/api/v1/admin/**`.
- `TripSearchIntegrationTest` — préexistant, conservé.

Les deux tests d'intégration (`@SpringBootTest` + Testcontainers PostGIS) sont marqués `@Disabled` : cet environnement n'a pas accès au registre Docker (`docker pull` → 403). À réactiver (retirer `@Disabled`) sur un poste avec accès Docker complet.

**Deuxième lot (endpoints manquants côté front)** : aucun test automatisé nouveau n'a été écrit pour ce lot au-delà de la mise à jour des signatures de constructeur (voir §7bis). Écrire une couverture dédiée (`UserPreferencesServiceTest`, `PaymentAccountServiceTest`, `AdminStatsServiceTest` sur le calcul des deltas/séries, etc.) reste à faire chez vous, en priorité sur `AdminStatsService#computeStats(int)` et sur `PaymentAccountService#delete`.

**Paiement fractionné (règle n.21)** : contrairement au reste du deuxième lot, cette partie a reçu de nouveaux tests, pas seulement des corrections de signature — `MoneyUtilsTest#roundUpToStep_*`, `FeePolicyTest#computeDepositAmount_*` (les trois cas : commission > acompte de base, commission < acompte de base, acompte plafonné au montant total) et `PayoutServiceTest` réécrit pour distinguer explicitement `MOMO_FULL` et `MOMO_DEPOSIT` dans le calcul du solde et du lot de reversement, plus un test dédié à l'invariant `deposit >= serviceFee`.

**Devis de réservation (`booking-quote`)** : nouveau fichier `BookingServiceQuoteTest` — refus trajet non `PUBLISHED`, refus conducteur qui se cote lui-même, refus places insuffisantes, puis trois cas de parité exacte avec la formule de `createBooking` (`MOMO_DEPOSIT` avec commission dominante, défaut de `paymentMode` avec acompte plafonné au total, `CASH`, `MOMO_FULL`) — tous avec une `FeePolicy` réelle (pas mockée), pour vérifier la non-divergence devis/réservation plutôt que la supposer.

**Statistiques du profil public conducteur (règle n.22)** : nouveau fichier `UserServicePublicProfileTest` — `reliabilityRate` et `responseTimeMinutes` passent à `null` en dessous du seuil de 5 échantillons mesurables et sont correctement arrondis au-dessus (avec un cas `.6` pour vérifier l'arrondi au plus proche), `preferences` retombe sur les valeurs par défaut de `UserPreferences` en l'absence de ligne stockée et reflète fidèlement une ligne existante sinon. Les deux nouvelles requêtes SQL elles-mêmes (`BookingRepository#getReliabilityStats`, `MessageRepository#getResponseTimeStats`) ne sont pas exercées par ce test (projections mockées) — comme pour tout le reste de ce dépôt, aucune base n'est disponible dans cet environnement pour les exécuter réellement (voir §10).

---

## 10. Ce qui reste à faire / incertain (honnêteté requise)

- **Format exact de l'API Kkiapay** : confirmé via documentation publique et le code source du SDK Node.js officiel (base URLs, endpoints `/transactions/status` et `/transactions/revert`, en-têtes d'authentification, forme du webhook et de sa signature). **Non confirmé** : structure exacte des réponses d'erreur non-2xx, caractère synchrone ou non du remboursement, format précis du corps de réponse de remboursement. Toute la zone d'incertitude est isolée dans `KkiapayHttpGateway` — à valider contre un compte marchand réel avant production.
- **Remboursement partiel non automatisable** : l'API Kkiapay confirmée ne prend pas de montant en paramètre pour un remboursement (tout ou rien). Un remboursement partiel (barème d'annulation passager) est donc marqué `MANUAL_REQUIRED` et journalisé pour traitement manuel par le back-office, plutôt que remboursé à 100 % par erreur.
- **Aucun fournisseur SMS réel choisi** : `HttpSmsGateway` est un client HTTP générique (corps `{to, message}`, en-tête `Authorization: Bearer ...`) à adapter au contrat exact du fournisseur retenu (Africa's Talking, Twilio, agrégateur local...).
- **Géocodage** : recherche uniquement sur le cache `geo_places` (villes/quartiers pré-chargés). Aucun appel à un service de géocodage externe (Nominatim, Google, Mapbox) en cas d'échec de recherche — non implémenté, aucune clé de service disponible dans ce contexte.
- **Web Push non implémenté** : seule la colonne `users.push_subscription` (JSONB) existe, en préparation ; aucun envoi Web Push réel (VAPID, service worker côté frontend, etc.) n'est câblé.
- **Reversements conducteurs** : le calcul et le regroupement en lots sont automatisés, mais le **décaissement réel** (virement mobile money vers le conducteur) reste manuel — `PayoutService#settle` ne fait que marquer le lot comme réglé après un virement effectué hors-ligne. Aucune API de transfert/payout Kkiapay n'a pu être confirmée.
- **Vérification d'identité admin** : `AdminUserService#verifyIdentity` suppose qu'une vérification manuelle hors-ligne a déjà eu lieu (pas de sous-système de stockage de documents d'identité/selfie implémenté).
- **OpenAPI** : annotations `@Tag`/`@Operation` ajoutées sur tous les contrôleurs (résumé + description), mais pas d'énumération exhaustive des `@ApiResponse` par code d'erreur sur chaque endpoint.
- **Aucun test n'a pu être exécuté** dans cet environnement (contrainte réseau/Maven) — à lancer en priorité absolue avant tout déploiement, en commençant par `MoneyUtilsTest`/`FeePolicyTest`/`CancellationPolicyTest` (aucune dépendance externe).

### Deuxième lot (endpoints manquants côté front) — points ouverts

- **Plan de paiement (acompte/solde) : tranché et implémenté pour de vrai** (règle métier n.21, migration V7) — voir §7ter pour le détail de la relecture. `POST /api/v1/bookings/{id}/payments/deposit` initie désormais réellement `deposit_amount` chez Kkiapay, jamais `amount` ; `PayoutService` ne reverse au conducteur que ce qu'il a réellement encaissé en ligne.
- **Validation conducteur du mode `CASH` : non implémentée.** Le mode existe et fonctionne (réservation confirmée immédiatement, comme avant), mais la formulation produit ("reste soumise à validation du conducteur") n'a pas été traduite en code — aucun nouveau statut de réservation, aucun endpoint d'acceptation/refus, aucun délai n'ont été spécifiés pour ce point, contrairement au reste de la règle n.21. À spécifier (nom de statut, endpoint, délai avant expiration éventuelle) avant de l'implémenter. En attendant, et puisque ce mode ne fait transiter aucun paiement ni commission par la plateforme, **il ne doit être ouvert qu'aux conducteurs de confiance** (vérifiés, avec un historique établi) — sans quoi n'importe quel conducteur peut contourner intégralement la commission de la plateforme en le proposant systématiquement.
- **`docs/donnees-demo.sql`** (non modifié, hors périmètre de ce travail — en LECTURE SEULE pour moi) : après la migration V7, ce script doit être mis à jour, sinon son `INSERT INTO bookings` échouera (`deposit_amount`/`balance_due_on_board` sont `NOT NULL` sans défaut au niveau base, et `'MOMO'` n'est plus une valeur valide de `payment_method`). **Formule exacte à appliquer, par ligne, à transmettre à l'agent qui maintient ce fichier** :
  - `payment_method = 'CASH'` (inchangé) → `deposit_amount = 0`, `balance_due_on_board = amount`.
  - `payment_method = 'MOMO'` → renommer en `'MOMO_DEPOSIT'` (recommandé, pour que la démo illustre le nouveau flux par défaut) ou `'MOMO_FULL'` (pour illustrer l'alternative "tout payer en ligne") ; puis, selon le choix :
    - `'MOMO_FULL'` : `deposit_amount = amount`, `balance_due_on_board = 0`.
    - `'MOMO_DEPOSIT'` : `deposit_amount = LEAST(amount, GREATEST(1000, service_fee))` (déjà multiple de 5 pour toutes les valeurs actuelles du fichier, donc pas besoin d'arrondi supplémentaire en pratique — voir `FeePolicy#computeDepositAmount` pour la formule générale avec arrondi), `balance_due_on_board = amount - deposit_amount`.
  - Valeurs déjà calculées pour les 20 réservations actuellement `'MOMO'` du fichier, **si toutes renommées en `'MOMO_DEPOSIT'`** (une seule ligne par couple `amount`/`service_fee` distinct — plusieurs réservations du fichier partagent le même couple) :

    | amount | service_fee | → deposit_amount | → balance_due_on_board | ids concernés |
    |---|---|---|---|---|
    | 800 | 65 | 800 (plafonné au total, `amount < 1000`) | 0 | d...0026 |
    | 1200 | 100 | 1000 | 200 | d...0013, d...0014 |
    | 3000 | 240 | 1000 | 2000 | d...0015 |
    | 4000 | 320 | 1000 | 3000 | d...0002, d...0016, d...0017 |
    | 4500 | 360 | 1000 | 3500 | d...0003, d...0004 |
    | 8000 | 640 | 1000 | 7000 | d...0001 |
    | 9000 | 720 | 1000 | 8000 | d...0018, d...0019 |
    | 9500 | 760 | 1000 | 8500 | d...0009, d...0010 |
    | 10000 | 800 | 1000 | 9000 | d...0006, d...0007 |
    | 28000 | 2240 | 2240 | 25760 | d...0011 |
    | 28500 | 2280 | 2280 | 26220 | d...0008 |
    | 30000 | 2400 | 2400 | 27600 | d...0005 |
    | 40500 | 3240 | 3240 | 37260 | d...0012 |

    (formule : `deposit_amount = LEAST(amount, GREATEST(1000, service_fee))`, déjà multiple de 5 pour toutes ces valeurs donc pas d'arrondi supplémentaire à appliquer en pratique — voir `FeePolicy#computeDepositAmount` pour la formule générale avec arrondi aux 5 FCFA supérieurs.)
  - Les 6 réservations déjà `'CASH'` (d...0020 à d...0025) n'ont besoin que de `deposit_amount = 0` et `balance_due_on_board = amount` (800 ou 1600 selon la ligne).
- **`GET /api/v1/admin/stats?days=N`** : `activeUsers` est approximé par le nombre de passagers distincts ayant réservé sur la période (pas de notion de "session" ou de connexion) ; `topRoutes` regroupe par libellé exact d'origine/destination (deux libellés légèrement différents pour le même lieu ne sont pas fusionnés) et se limite aux 10 premiers axes par volume. Approximations pragmatiques, à raffiner si le tableau de bord devient un outil de pilotage fin.
- **`GET /api/v1/admin/stats/liquidity?days=N`** : le taux recherche → réservation attribue une réservation à une recherche par simple proximité temporelle (même utilisateur connecté, sous 24 h), pas par un identifiant de recherche transmis par le front — c'est une borne haute raisonnable, pas une attribution exacte. Le remplissage porte sur les trajets **partis** dans la fenêtre (date de départ), la métrique nord et les recherches sur la date de **création** : les deux populations ne se recouvrent pas exactement, c'est voulu (un trajet à venir n'est ni rempli ni orphelin). Les requêtes natives (`count(*) filter`, `left join lateral`, `percentile_cont`, projections par interface) ont été compilées mais pas rejouées contre PostGIS : à vérifier au premier `GET` réel, en particulier le type Java renvoyé pour les agrégats (`Long`/`Double`).
- **`GET /api/v1/me/recurring-trips`** : détection par heuristique sur l'historique de réservations (regroupement par axe + créneau horaire récurrent), pas une préférence explicitement enregistrée par l'utilisateur ni une notion stockée en base — les identifiants renvoyés sont des UUID déterministes calculés à la volée (`UUID.nameUUIDFromBytes`), pas des clés primaires réelles.
- **Téléversement de la photo du document d'identité** : toujours non implémenté (déjà signalé plus haut pour la vérification côté admin) — `POST /api/v1/me/identity` n'enregistre que le type et le numéro de document, jamais de fichier.
- **`POST /api/v1/admin/users/{id}/verify-identity`** (historique) et **`POST /api/v1/admin/verifications/{id}/approve`** (nouveau) coexistent et positionnent tous deux `users.identity_verified = true`, mais suivent des chemins de données différents : le premier agit "à l'aveugle" sans dossier de vérification associé, le second suppose une soumission effective côté utilisateur (`identity_verifications`). Les deux mènent au même badge, mais seul le second alimente l'historique de modération (`reviewedAt`/`reviewedBy`/`rejectionReason`).

### Dernier écart de contrat (devis de réservation) — point ouvert

- **`POST /api/v1/trips/{id}/booking-quote` ne vérifie pas qu'une réservation active existe déjà pour l'appelant sur ce trajet** (voir §4ter et §7quater) — seul `createBooking` le fait, avec un 409 dédié. Choix délibéré : la demande listait exactement trois refus pour le devis (trajet non `PUBLISHED`, places insuffisantes, conducteur qui se cote lui-même) et ce quatrième cas n'y figurait pas ; un devis répond à « combien ça coûterait », pas à « cette réservation précise réussirait-elle ». Conséquence pratique, à connaître côté front : un utilisateur qui a déjà une réservation active peut obtenir un devis valide puis se voir refuser la réservation elle-même par `createBooking` — comportement jugé acceptable pour un devis, mais à confirmer si le produit attend l'inverse.

### Statistiques du profil public conducteur (règle n.22) — points ouverts

- **Type front `PublicUserResponse.reliabilityRate`** (`frontend/src/api/extended.ts`) est déclaré `number` (non nullable), alors que la consigne explicite était de renvoyer `null` en dessous de 5 trajets mesurables — j'ai suivi la consigne (prioritaire sur ce fichier de types, qui n'est qu'un placeholder en attendant l'API réelle) plutôt que le type. `responseTimeMinutes`, lui, est déjà déclaré `number | null` côté front, cohérent avec l'implémentation. À faire évoluer côté front : `reliabilityRate: number | null`.
- **Aucune configuration externe pour les deux seuils (5) ni la fenêtre de 90 jours** : contrairement à `BOOKING_DEPOSIT_BASE_FCFA` (règle n.21), ces valeurs sont des constantes Java (`UserService.MIN_SAMPLE_SIZE`, `UserService.RESPONSE_TIME_WINDOW`), pas des clés `application.yml`/variables d'environnement — choix cohérent avec `CancellationPolicy.FREE_CANCELLATION_WINDOW` (24h, également une constante de code) et avec le fait qu'aucune configurabilité n'a été demandée pour ces trois valeurs précises. À revoir si le produit veut pouvoir les ajuster sans redéploiement.
- **`reliabilityRate` ne distingue pas un conducteur récent d'un conducteur inactif depuis longtemps** : les deux renvoient `null` en dessous de 5 trajets mesurables, sans indication de la raison. Non demandé, non implémenté.
- **« bagages » mentionné dans la demande, absent du contrat réel** : ni `user_preferences` (V6) ni `DriverPreferences` côté front n'ont de champ bagages — voir §4quater, dernier point. Signalé explicitement dans la réponse plutôt que silencieusement omis.
