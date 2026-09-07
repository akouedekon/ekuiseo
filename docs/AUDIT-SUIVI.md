Fait | `scripts/restore.sh` (`--single-transaction --exit-on-error`), `scripts/restore-drill.sh` (exercice mensuel), `scripts/deploy-vps.sh` (cron), `backup.sh` (rclone si `BACKUP_REMOTE`). || IDENTITY_*, ACCOUNT_SUSPENDED, REPORT_* émis ; passager d une réservation CASH notifié (critique) avec le solde à bord (`BookingService#createBooking`, `NotificationTemplates`). || `frontend/src/lib/payments.ts`, `components/booking/PaymentSplit.tsx` : textes alignés sur `CancellationPolicy` (intégral > 24 h, moitié retenue en deçà, totalité après le départ, retenue plateforme). || `frontend/src/api/client.ts` : `downloadFile` envoie `Accept: text/csv` pour les exports CSV (corrigé le 2026-09-07 après ce suivi). || Sévérité | Constats | Fait | Partiel | Non fait | Décision fondateur | Réfuté/obsolète |
|---|---:|---:|---:|---:|---:|---:|
| P0 | 1 | 0 (0 %) | 0 (0 %) | 0 (0 %) | 1 (100 %) | 0 (0 %) |
| P1 | 67 | 65 (97 %) | 1 (1,5 %) | 0 (0 %) | 1 (1,5 %) | 0 (0 %) |
| P2 | 162 | 152 (93,8 %) | 7 (4,3 %) | 1 (0,6 %) | 2 (1,2 %) | 0 (0 %) |
| P3 | 121 | 95 (78,5 %) | 19 (15,7 %) | 5 (4,1 %) | 2 (1,7 %) | 0 (0 %) |
| **Total** | **351** | **312 (88,9 %)** | **27 (7,7 %)** | **6 (1,7 %)** | **6 (1,7 %)** | **0 (0 %)** |

Lecture : 312 constats sur 351 (88,9 %) sont clos dans le dépôt (F451, F046, F134 et F127 ont été clos juste après ce suivi) ; 33 (9,4 %) appellent encore du code (section 5) ; 6 dépendent d une action du fondateur hors dépôt (section 4). Sur les 68 constats P0 et P1, 65 sont Fait ; les trois exceptions sont L1 (P0) et F431 (P1), en « Décision fondateur » car le dépôt est prêt, et F027 (P1), partiel sur un point mineur (claims `issuer`/`audience`).

## 3. Détail par sévérité

### 3.1. P0 (1 constat)

| Id | Titre | Statut | Preuve / reste à faire |
|---|---|---|---|
| L1 | Deux enregistrements DNS A pour ekuiseo.com : l IP Vercel 216.198.79.1 (poignee… | Décision fondateur | Enregistrement A Vercel supprimé par le fondateur ; `docs/DEPLOIEMENT.md` (vérification `dig`, check-list) ; `frontend/vercel.json` supprimé. Reste : souscrire une sonde externe multi-résolveurs (UptimeRobot / Better Stack). |

### 3.2. P1 (67 constats)

| Id | Titre | Statut | Preuve / reste à faire |
|---|---|---|---|
| F001 | Le filtre JWT ne vérifie pas la claim `type` : un refresh token (30 j, glissant… | Fait | `security/JwtService` (claim `type` vérifiée), `service/RefreshTokenService` (jti, rotation, réutilisation, révocation), V11 |
| F002 | /auth/register et /auth/login (mot de passe) restent publics et inutilisés par… | Fait | `web/controller/AuthController` (OTP seul : register/request/verify/refresh/logout) ; `RegisterRequest`, `LoginRequest`, `EkuiseoUserDetailsService` supprimés ; statut `PENDING_VERIFICATION` (V11) |
| F003 | Aucun timeout sur aucun client HTTP sortant (Kkiapay et les quatre gateways… | Fait | `config/HttpClientConfig` (RestClientCustomizer 5 s / 15 s), `application.yml` (`hikari.connection-timeout`), `service/RefundService` (appels Kkiapay après commit) |
| F004 | Remboursements Kkiapay exécutés dans la transaction d'annulation en cascade… | Fait | `service/RefundService` (REFUND_PENDING dans la transaction, appel Kkiapay afterCommit, reprise 5 min, REFUND_MANUAL), V12, `pages/admin/AdminPayments.tsx` |
| F005 | updateTrip écrase seats_available par lecture-modification-écriture (pas de… | Fait | `service/TripService#updateTrip` (findByIdForUpdate, recalcul FULL/PUBLISHED), `domain/Trip` (`@DynamicUpdate`) |
| F006 | Reversement calculé sur des réservations CONFIRMED (avant le trajet) et jamais… | Fait | `service/PayoutService` (départ passé de 24 h + acompte encaissé, retrait/deduction d'un lot, settle gardé), V12 |
| F008 | Clé du rate limiting = premier élément de X-Forwarded-For : Caddy 2.8 sans… | Fait | `security/RateLimitingFilter#clientIp` (X-Real-IP puis dernier XFF), `Caddyfile.proxied` (X-Real-IP non réécrit), test `RateLimitingFilterTest` |
| F013 | PATCH /api/v1/me accepte n'importe quel e-mail sans validation, unicité ni… | Fait | `service/EmailChangeService` (deux temps), `dto/user/UpdateMeRequest` (validé, sans e-mail), V11 (`uq_users_email_lower`) |
| F022 | Avis : cible et rôle fournis par le client sans lien avec le trajet, avis… | Fait | `service/ReviewService` (cible et rôle déduits côté serveur, refus avant le départ), `ReviewServiceTest` |
| F023 | Inscription OTP : compte créé ACTIF avant toute vérification et numéro jamais… | Fait | `service/AuthService` (compte PENDING_VERIFICATION activé au premier code), `service/AuthHousekeepingScheduler` (purge 24 h), V11 |
| F027 | Clé JWT publique acceptée par toutes les gardes (application.yml… | Partiel | `application.yml` (`${JWT_SECRET:}` sans repli), `security/JwtService` (refus `change-me`), `.env.example` (vide), `scripts/deploy-vps.sh` (refus). Manque : claims `issuer`/`audience` non posées ni vérifiées. |
| F035 | Aucun cycle de vie du trajet : jamais ONGOING/COMPLETED ni COMPLETED/NO_SHOW… | Fait | `service/TripLifecycleScheduler` (ONGOING/COMPLETED), `service/BookingService` (refus trajet parti, no-show 48 h), `repository/TripRepository#search` (départ futur) |
| F036 | Acompte encaissé après expiration ou annulation de la réservation : seulement… | Fait | `service/PaymentService` (acompte tardif remboursé automatiquement, `expires_at` prolongé), `service/RefundService`, V12 |
| F039 | Suspension d'un conducteur : ses trajets restent cherchables et réservables, et… | Fait | `service/admin/AdminUserService#suspend` (cascade : sessions, trajets annulés et remboursés, réservations), `TripRepository#search` (conducteur ACTIVE) |
| F040 | Prix d'arrêt intermédiaire (et prix par place via l'API) non borné… | Fait | `service/TripService` (prix > 0, arrêts bornés et croissants), V12 (CHECK), `lib/validation.ts` |
| F041 | Récurrence : COUNT ignoré, aucune occurrence créée à la publication alors que… | Fait | `service/RecurrenceService` (COUNT/UNTIL/BYDAY, génération à la création), `TripService#createTrip` (`withGeneratedOccurrences`), `RecurrenceServiceTest`, `RecurrenceIT` |
| F042 | Récurrence : génération stoppée dès que le parent est complet… | Fait | `service/RecurrenceService` + `TripService` (statut TEMPLATE, cascade annulation/modification, arrêts copiés, index unique parent/départ), V13 |
| F043 | Modification d'un trajet déjà réservé (heure, lieu via l'API, prix) sans… | Fait | `service/TripService#updateTrip` (itinéraire figé dès la première réservation, TRIP_UPDATED critique, `freeCancellationUntil` 24 h, rappel réinitialisé), V13 |
| F044 | Annulation passager : conducteur jamais notifié, annulation possible sans… | Fait | `service/BookingService#cancelByPassenger` (conducteur notifié, refus après le départ), `TripLifecycleScheduler` |
| F101 | Aucune transition vers COMPLETED / NO_SHOW : les trajets passés restent… | Fait | `service/TripLifecycleScheduler`, `POST /bookings/{id}/no-show`, V13 (index partiel) |
| F102 | Lots de reversement constitués sur des réservations CONFIRMED non encore… | Fait | `service/PayoutService` (éligibilité départ passé + encaissé, retrait/déduction), V12 |
| F103 | Reversement adressé au téléphone du compte (users.phone) et non au compte… | Fait | `service/PayoutService` (compte mobile money vérifié, conducteurs sans compte exclus et notifiés), V12 (`destination_provider`) |
| F104 | users.email sans unicité en base et email_verified jamais réinitialisé alors… | Fait | V11 (`uq_users_email_lower`), `service/EmailChangeService` |
| F105 | Paiement abouti après expiration (ou montant insuffisant) : argent encaissé… | Fait | `service/PaymentService#handleBookingPaymentResult` (remboursement automatique, statut client REFUND_PENDING/REFUNDED), `features/booking/ExpiredStep.tsx` |
| F106 | Remboursement partiel ou échec HTTP du remboursement : réservation annulée mais… | Fait | `service/RefundService` (REFUND_PENDING / REFUND_MANUAL), `GET /admin/payments`, `pages/admin/AdminPayments.tsx` |
| F107 | Notifications critiques mortes en production : SMS_MODE=log, préférences… | Fait | `service/NotificationService` (routeur), `service/NotificationDispatcher` (e-mail + SMS selon préférences), `service/NotificationTemplates`, envoi afterCommit asynchrone |
| F108 | Les trajets complets (statut FULL) ne reçoivent jamais le rappel J-1 | Fait | `repository/TripRepository#findDueForReminder` (PUBLISHED ou FULL), `TripReminderSchedulerTest` |
| F109 | Annulation passager : le conducteur n'est jamais notifié, le passager non plus… | Fait | `service/BookingService#cancelByPassenger` (BOOKING_CANCELLED au conducteur et au passager avec montants) |
| F110 | Modification d'un trajet (horaire, lieu, prix) sans aucune notification aux… | Fait | `service/TripService#updateTrip` (TRIP_UPDATED, origine/destination refusées une fois réservé) |
| F111 | Appels Kkiapay et SMS sans timeout, exécutés dans des transactions qui tiennent… | Fait | `config/HttpClientConfig`, `service/NotificationService` (afterCommit + exécuteur dédié) |
| F112 | Cache service worker des réponses API authentifiées, clé = URL, jamais purgé à… | Fait | `frontend/vite.config.ts` (runtimeCaching limité aux routes publiques, `cacheWillUpdate` refuse Authorization), `lib/queryClient.ts#clearApiCache` |
| F113 | Expiration de session : jetons effaces mais QueryClient et cache persiste… | Fait | `hooks/useAuth.ts#resetSession` (appelé sur `expired` par `AppShell.tsx` et au logout) |
| F114 | Cache TanStack persiste en localStorage sans filtre : donnees personnelles et… | Fait | `lib/queryClient.ts` (`shouldPersistQuery` : liste blanche publique) |
| F125 | Trajets récurrents : parent réservable et doublonné, génération stoppée si le… | Fait | `service/TripService` + `RecurrenceService` (TEMPLATE, génération à la création, cascade), V13 |
| F201 | Un trajet déjà parti reste visible, ouvert et réservable (aucune clôture ni… | Fait | `TripRepository#search` (départ futur), `BookingService` (refus), `pages/TripDetailPage.tsx` (verrouillage) |
| F202 | Recurrence quotidienne : le nombre de semaines saisi est ignore, la serie est… | Fait | `service/RecurrenceService` (COUNT/UNTIL), `pages/PublishTripPage.tsx` (toast fondé sur le nombre réel d'occurrences) |
| F203 | Le trajet parent d'une navette est lui-meme reservable, se duplique avec sa… | Fait | `TripStatus.TEMPLATE` (V13), `RecurrenceService` (première occurrence incluse, sélection hors CANCELLED) |
| F204 | Annuler une navette depuis « Je conduis » n'annule que le parent : les… | Fait | `service/TripService#cancelTrip` (cascade sur les occurrences à venir d'un modèle), `pages/MyTripsPage.tsx` (navette) |
| F205 | Modifier l'horaire d'un trajet reserve ne previent pas les passagers | Fait | `service/TripService#updateTrip` (TRIP_UPDATED critique, SMS, rappel réinitialisé), `features/trips/EditTripSheet.tsx` |
| F206 | Abonnement conducteur : « Reprendre le paiement » cree un nouveau paiement a… | Fait | `service/SubscriptionService` (souscription PENDING réutilisée, expiration 30 min), `features/account/SubscriptionSection.tsx` |
| F207 | Arrets intermediaires : prix par defaut 0 F, aucune borne par rapport au prix… | Fait | `service/TripService` (bornes des arrêts), V12, `lib/validation.ts` |
| F208 | Sur mobile, un conducteur n'a aucun acces a la messagerie : ni onglet Messages… | Fait | `components/layout/AppShell.tsx` (Messages dans la barre basse), `pages/MyTripsPage.tsx` (feuille Passagers) |
| F209 | Les statuts ONGOING et COMPLETED ne sont jamais poses : aucun trajet n'est «… | Fait | `service/TripLifecycleScheduler`, `TripLifecycleSchedulerTest` |
| F210 | Le filtre de statut des vérifications est ignoré : les onglets « Validées » et… | Fait | `service/admin/AdminVerificationService#listByStatus`, `dto/admin/AdminVerificationResponse` (reviewedAt, motif), `pages/admin/AdminVerifications.tsx` |
| F211 | Le numéro destinataire d'un lot de reversement est le téléphone de connexion… | Fait | `service/PayoutService` (compte mobile money vérifié, plus de repli MTN), V12 (`destination_provider`) |
| F212 | L'interface promet des notifications aux utilisateurs (refus de vérification… | Fait | `NotificationType` (IDENTITY_APPROVED/REJECTED, ACCOUNT_SUSPENDED, REPORT_RESOLVED), `AdminVerificationService`, `AdminUserService`, `ReportService` |
| F336 | Cache runtime du service worker : réponses API authentifiées mises en cache par… | Fait | `frontend/vite.config.ts` (routes publiques seulement, `cacheWillUpdate`), `lib/queryClient.ts#clearApiCache`, `docs/CONFORMITE.md` |
| F346 | Cache TanStack persisté en localStorage sans filtre : profil, téléphone, e-mail… | Fait | `lib/queryClient.ts` (liste blanche, `readCacheOwner`/`writeCacheOwner` par utilisateur), `hooks/useAuth.ts#resetSession` |
| F408 | La recherche renvoie les trajets en sens inverse sur les axes courts (rayon 15… | Fait | `service/TripService` (rayon borné à la moitié de l'axe), `TripRepository#search` (contrainte de sens), `SearchAlertRepository`, `TripServiceRadiusTest` |
| F410 | Numéro de téléphone : le front accepte les espaces (placeholder « +229 01 97 00… | Fait | `common/PhoneNumbers`, `lib/validation.ts#toE164`, seed et comptes de test au format +229 01 |
| F415 | Fuseau horaire : recherche par date, occurrences recurrentes, « trajet de la… | Fait | `common/Tz` (Africa/Porto-Novo) utilisé par `TripService`, `RecurrenceService`, `BookingService`, `TripReminderScheduler`, `AdminStatsService` |
| F428 | Derrière nginx → Caddy, le rate limiting par IP voit une IP unique… | Fait | `security/RateLimitingFilter#clientIp` (X-Real-IP puis dernier XFF), `Caddyfile.proxied`, test dédié |
| F429 | RestClient Kkiapay (et SMS) sans timeout, appelé dans des transactions JPA : un… | Fait | `config/HttpClientConfig` (Kkiapay et SMS via le Builder configuré) |
| F431 | Sauvegardes locales au VPS uniquement, cron non provisionné par le déploiement… | Décision fondateur | Dépôt à jour : cron idempotent et exercice mensuel dans `scripts/deploy-vps.sh`, `scripts/backup.sh` (rclone si `BACKUP_REMOTE`, `last-success`), `scripts/restore-drill.sh`, `docs/EXPLOITATION.md`. Reste au fondateur : configurer `rclone` et `BACKUP_REMOTE` sur le VPS. |
| F449 | Recherche envoyée sans JWT : search_events.user_id toujours null, taux… | Fait | `hooks/useTrips.ts` (jeton envoyé sur la recherche), `TripRepository` (rétention conducteur) |
| F457 | /auth/register et /auth/login publics et inutilisés par le front : création de… | Fait | `AuthController` (OTP seul), alias `/activate`, `/pay`, `statsByRange`/`expand` retirés, `POST /admin/vehicles/{id}/verify` branché dans `pages/admin/AdminUserDetail.tsx` |
| F507 | Aucun parcours de suppression ni d'anonymisation de compte : droit à… | Fait | `service/AccountDeletionService` (OTP), `UserService#anonymize`, `POST /admin/users/{id}/anonymize`, V14 (`DELETED`, `deleted_at`), `features/account/DataSection.tsx` |
| F536 | Le compteur de tentatives OTP est annulé par le rollback : la limite de 5… | Fait | `service/OtpCodeService` (compteur persistant, `noRollbackFor`) |
| F537 | Aucun parcours de récupération de compte : e-mail erroné ou inaccessible =… | Fait | `PATCH /admin/users/{id}/contact` (motif, audit), `service/EmailChangeService`, `AuthHousekeepingScheduler`, aide sur `pages/LoginPage.tsx` |
| F538 | PATCH /me change l'e-mail de connexion sans vérification, sans unicité et sans… | Fait | `service/EmailChangeService` (`/me/email/request` + `/confirm`), V11, `features/account/EmailChangeDialog.tsx` |
| F539 | Les endpoints mot de passe /auth/register et /auth/login restent publics et… | Fait | `AuthController` sans `/register` ni `/login` ; `docs/donnees-demo.sql` sans mot de passe utilisable |
| F548 | Signalement accepté sans interaction commune, sans dédoublonnage ni plafond… | Fait | `service/ReportService` (réservation commune, doublons, plafond 5/24 h, tri), `ReportRepository`, V14 (`reports.booking_id`) |
| F601 | Le badge « identité vérifiée » n'est jamais révocable : resoumission et rejet… | Fait | `service/IdentityVerificationService` (resoumission refusée si APPROVED), `AdminVerificationService#reject` (badge retiré), `POST /admin/users/{id}/revoke-identity` |
| F602 | La destination de reversement affichée à l'admin est le numéro de connexion… | Fait | `service/PayoutService` (destination = compte vérifié), `service/PaymentAccountService` (`verified_at`), V12, `features/admin/PaymentAccountsToVerify.tsx` |
| F611 | Resoumission d'identité : badge vérifié conservé après changement de numéro… | Fait | `service/IdentityVerificationService` (délai minimal entre soumissions, badge retiré, audit) |
| L2 | CORS ouvert a toute origine avec allow-credentials, et absence de HSTS en… | Fait | `config/SecurityConfig` (`allowCredentials(false)`), `docker-compose.prod.yml` (origines par défaut ekuiseo.com), `Caddyfile.proxied` (HSTS) |
| L3 | Swagger UI, la spec OpenAPI et /actuator/metrics repondent 200 sans… | Fait | `application.yml` (`SPRINGDOC_ENABLED`), `docker-compose.prod.yml` (false), `SecurityConfig` (`/actuator/**` ADMIN sauf health/info), Caddy ne relaie plus Swagger |

### 3.3. P2 (162 constats)

| Id | Titre | Statut | Preuve / reste à faire |
|---|---|---|---|
| F007 | Les 500 ne sont jamais journalisés (handler générique sans Logger, résolveur… | Fait | `common/GlobalExceptionHandler` (Logger, handlers 400/404/405, errorId), `GlobalExceptionHandlerTest` |
| F010 | Pas de statut EXPIRED : une réservation expirée faute de paiement est… | Fait | `BookingStatus.EXPIRED` (V16), `BookingService#expireStalePendingBookings` (audit + notification BOOKING_EXPIRED) |
| F011 | handleWebhook marque FAILED (et annule un abonnement) sur une vérification… | Fait | `service/PaymentService#applyVerification` (décision partagée widget/webhook, PENDING laissé INITIATED) |
| F016 | Statistiques admin agrégées en mémoire (deux chargements Booking+Trip) avec… | Fait | `service/admin/AdminStatsService` (SQL agrégé), `BookingRepository`/`TripRepository` (par liste d'identifiants), `AdminUserService` |
| F017 | Codes OTP en clair dans les journaux (mode `log` actif en production) alors… | Fait | `common/Masking`, `service/mail/LoggingMailGateway`, `MailConfig` (refus mode log avec paiements réels), `OTP_LOG_PLAIN_CODES`, `logback-spring.xml` |
| F018 | Spring Boot 3.3.4 hors support OSS depuis juin 2025 ; aucune veille de… | Fait | `backend/pom.xml` (Spring Boot 3.5.16, springdoc 2.8), `.github/dependabot.yml`, `ci.yml` (audit non bloquant) |
| F024 | HSTS absent et version de nginx divulguée en production (proxy amont non durci) | Partiel | HSTS posé par `Caddyfile.proxied`. Manque : `server_tokens off` et bloc 443 versionnés dans `deploy/nginx/ekuiseo.com.conf`. |
| F025 | Recherche publique non bornée (taille de page, rayon, coordonnées) et… | Fait | `web/controller/TripController` (bornes `@Validated`), `RateLimitingFilter` (60/min/IP sur les GET publics), `RequestBoundsTest` |
| F026 | Validation Bean lacunaire : champs texte sans @Size (messages, trajets… | Fait | `dto/trip/CreateTripRequest` (bornes alignées V1, coordonnées), DTO messages/véhicules/signalements/alertes, `RequestBoundsTest` |
| F028 | La vérification post-déploiement (deploy-prod.yml et deploy-vps.sh) sonde le… | Fait | `Caddyfile` et `Caddyfile.proxied` (`handle /actuator/health` seul), `scripts/deploy-vps.sh` et `deploy-prod.yml` (sonde JSON + `/api/v1/trips/popular`) |
| F029 | Compte ADMIN de démo à mot de passe public dans le dépôt, chargé en production… | Fait | `scripts/seed-demo.sh` (refus de la production), `docs/donnees-demo.sql` (aucun compte ADMIN, hash inutilisable) |
| F037 | Remboursement partiel (annulation passager < 24 h) marqué MANUAL_REQUIRED… | Fait | `service/RefundService` (REFUND_MANUAL visible dans `/admin/payments`), `NotificationTemplates` (montant et délai au passager), `BookingService` |
| F038 | Rappel J-1 jamais envoyé pour les trajets complets (statut FULL exclu par… | Fait | `TripRepository#findDueForReminder` (PUBLISHED, FULL) |
| F046 | Textes d'annulation du sélecteur de paiement contredisent CancellationPolicy… | Non fait | `frontend/src/lib/payments.ts` : les trois textes `cancellation` contredisent toujours `CancellationPolicy` (« reste acquis au conducteur », « seuls les frais de service restent acquis » au lieu de 50 % puis 100 % retenus par la plateforme). |
| F047 | Fuseau UTC partout côté serveur (bornes de jour de la recherche, récurrence… | Fait | `common/Tz` utilisé par la recherche, la récurrence, les SMS ; `TripRepository#search` (départ futur) |
| F048 | instantBooking stocké, affiché et filtrable mais sans aucun effet : aucune… | Fait | Interrupteur, badge et filtre « immédiat » retirés (`pages/PublishTripPage.tsx`, `TripCard.tsx`, `SearchResultsPage.tsx`, `api/types.ts`) |
| F049 | Abonnement conducteur : renouvellement impossible avant l'échéance (409), aucun… | Fait | `service/SubscriptionService` (renouvellement dès J-7), `SubscriptionLifecycleScheduler` (rappel J-3, EXPIRED), V16 |
| F115 | Recherche : arrêts intermédiaires (trip_stops.point, déjà géolocalisés) jamais… | Fait | `TripRepository#search` (arrêts intermédiaires, tronçon apparié), `dto/trip/TripResponse` (pickup/dropoff/segmentPrice), V16 (index GIST), `TripSearchIT` |
| F116 | Expiration sans statut propre : réservations expirées confondues avec les… | Fait | `BookingStatus.EXPIRED` (V16), notification et audit dans `BookingService`, libellé front « Expirée » |
| F117 | N+1 sur la recherche de trajets : driver et vehicle lazy, aucun batch fetch | Fait | `application.yml` (`hibernate.default_batch_fetch_size`) |
| F118 | Listes non bornées et statistiques calculées en mémoire | Fait | `AdminStatsService` (SQL agrégé), `TripController` (`size` borné), pagination des notifications (`NotificationController`), `PayoutService` |
| F119 | N+1 sur les écrans du back-office (reversements, utilisateurs, signalements… | Fait | `ReportRepository` (EntityGraph), `DriverPayoutRepository`/`DriverPayoutItemRepository` (agrégats), `AdminUserService` |
| F120 | otp_codes et notifications jamais purgés, contrairement à ce que déclare… | Fait | `service/RetentionScheduler` (otp_codes, notifications), `OtpCodeRepository`, `NotificationRepository`, `docs/CONFORMITE.md` |
| F121 | Tests d'intégration PostGIS désactivés et concurrence sur la dernière place… | Fait | `integration/AbstractPostgisIT`, `BookingConcurrencyIT`, `TripSearchIT`, `KpiNativeQueriesIT`, `LatePaymentWebhookIT` (failsafe, exécutés en CI) |
| F123 | SMS envoyés avant le commit de la transaction métier | Fait | `service/NotificationService` (afterCommit + exécuteur dédié `AsyncConfig`) |
| F124 | Suppression de véhicule impossible dès qu'il a servi sur un trajet passé ou… | Fait | V15 (`vehicles.deleted_at`), `UserService#deleteVehicle` (suppression logique), `TripService` (véhicule supprimé refusé) |
| F126 | Jour civil, semaine et textes SMS calculés en UTC (Bénin = UTC+1) ; SMS… | Partiel | `common/Tz` partout, SMS formatés en heure du Bénin (`BookingService`, `TripReminderScheduler`). Manque : rappel au conducteur avec le nombre de passagers confirmés (`TripReminderScheduler` ne notifie que les passagers). |
| F127 | Restauration non transactionnelle (rollback manuel via le dump de sécurité) et… | Partiel | `scripts/restore-drill.sh` (exercice mensuel), `scripts/deploy-vps.sh` (cron), `backup.sh` (rclone hors site). Manque : `pg_restore --single-transaction --exit-on-error` dans `scripts/restore.sh` (repli = dump `pre-restore`). |
| F128 | Ordonnanceur mono-thread partagé, sans verrou multi-instance : un rappel SMS… | Fait | `application.yml` (`scheduling.pool.size: 3`), `TripReminderScheduler` (transaction par trajet, marquage conditionnel), try/catch sur chaque job |
| F129 | Abonnement conducteur : aucune expiration, aucun renouvellement, aucun rappel… | Fait | `service/SubscriptionLifecycleScheduler` (EXPIRED, rappel J-3, PENDING > 30 min), `SubscriptionService` (renouvellement anticipé) |
| F130 | Rejeu d'un webhook après remboursement : REFUNDED peut être écrasé en SUCCEEDED | Fait | `service/PaymentService` (`isTerminal` : REFUNDED/FAILED jamais écrasés) |
| F131 | Cas « argent encaissé sans confirmation » journalisés en log.error seulement… | Fait | `PaymentService` (`rawPayload` complet, audits PAYMENT_ORPHAN / PAYMENT_OVERPAID, opérateur réel `channel` F140) |
| F132 | Écran « Paiement non abouti » affirme « Aucun montant n'a été débité » dans des… | Fait | Statut client dérivé de (payment, booking) ; `features/booking/ExpiredStep.tsx` (débit détecté = remboursement automatique, référence support) |
| F133 | Reversements : settle() sans garde d'état (rejeu API ou liste périmée re-règle… | Fait | `service/PayoutService#settle` (garde d'état, référence, montant), `POST /admin/payouts/{id}/fail`, V16 (CHECK, colonnes), `pages/admin/AdminPayouts.tsx` |
| F134 | Transitions métier sans notification : réservation espèces, résultat de… | Partiel | IDENTITY_APPROVED/REJECTED, ACCOUNT_SUSPENDED, REPORT_RECEIVED/RESOLVED émis. Manque : notification BOOKING_CONFIRMED au **passager** d'une réservation CASH (`BookingService#createBooking` ne notifie que le conducteur). |
| F135 | BookingPage : apres expiration puis « Recommencer », le plan affiche est celui… | Fait | `features/booking/useBookingFlow.ts` (« Recommencer » remet l'état et l'URL à zéro) |
| F136 | Widget Kkiapay : ecouteurs success/failed accumules quand la fenetre est fermee… | Fait | `lib/kkiapay.ts` (`settle()` unique retirant les écouteurs), `lib/kkiapay.test.ts` |
| F137 | Recherche : tri, filtres et reperes de prix appliques cote client sur les… | Fait | `TripService`/`TripRepository` (tri et filtres SQL), `hooks/useTrips.ts`, `pages/SearchResultsPage.tsx` (paramètres dans l'URL) |
| F138 | « Votre trajet de la semaine » : heure de depart affichee en UTC brut | Fait | `BookingService#myRecurringTrips` (heure locale `Tz.BENIN`) ; javadoc de `RecurringTripResponse` encore libellée « UTC » (cosmétique) |
| F139 | Promesse hors ligne non tenue : les mutations en pause ne survivent pas a un… | Fait | `lib/queryClient.ts` (`networkMode: 'online'`, aucune file promise), `OfflineBanner.tsx` |
| F141 | Precache PWA de tous les chunks (carte 984 Ko, graphiques 408 Ko, back-office)… | Fait | `vite.config.ts` (`globIgnores` map/charts/admin, CacheFirst sur `/assets/`), `App.tsx` (lazy par route) |
| F142 | Pages monolithiques et schemas Zod locaux, hors convention pages/features/lib | Fait | `features/booking/*` (steps + `useBookingFlow`), `features/trips/*`, schémas dans `lib/validation.ts` |
| F143 | Couverture lint et tests trop faible pour le tunnel de reservation | Fait | `.oxlintrc.json` (exhaustive-deps, no-explicit-any, no-console), `vitest.config.ts` (jsdom), `pages/BookingPage.test.tsx`, `hooks/useBookings.test.tsx` |
| F213 | Le filtre et le badge « Réservation immédiate » promettent un accord conducteur… | Fait | Filtre, badge et mention retirés (`SearchResultsPage.tsx`, `TripCard.tsx`, `TripDetailPage.tsx`) |
| F214 | Numéro de téléphone envoyé brut : les espaces du placeholder et un numéro sans… | Fait | `lib/validation.ts#toE164` appliqué avant chaque appel (`LoginPage`, `MomoForm`, réservation) |
| F215 | Promesse hors ligne fausse : les envois (messages, réservation, publication)… | Fait | `lib/queryClient.ts` (mutations `online`, `retry: false`), textes corrigés (`OfflineBanner.tsx`, `HomeSearchPage.tsx`, `BookingMessagesPage.tsx`) |
| F216 | Première visite hors ligne : squelette infini sur résultats, détail… | Fait | `components/ui/states.tsx` (état « hors ligne, aucune donnée » sur `fetchStatus === 'paused'`) |
| F217 | La réservation créée n'est pas inscrite dans l'URL : rafraîchissement ou… | Fait | `features/booking/useBookingFlow.ts` (`?booking=` dans l'URL), `RecapStep.tsx` (« Reprendre ma réservation en attente ») |
| F218 | Écran « Paiement non abouti » : ancien bookingId conservé, montants périmés, et… | Fait | `useBookingFlow.ts` (échéance = `depositDueAt` serveur, réinitialisation complète), `ExpiredStep.tsx` |
| F219 | Filtres appliqués côté client sur les pages chargées : « Voir plus » disparaît… | Fait | `pages/SearchResultsPage.tsx` (filtres serveur sur l'ensemble des départs) |
| F220 | Retour depuis un lien partagé : le détail renvoie vers /search sans paramètres… | Fait | `pages/TripDetailPage.tsx` (retour vers `/search` reconstruit ou vers l'accueil) |
| F221 | Profil conducteur sans en-tête ni bouton retour | Fait | `pages/DriverProfilePage.tsx` (en-tête avec retour) |
| F222 | Autocomplétion : la saisie disparaît au blur sans sélection, sans reprise de la… | Fait | `components/trip/CityAutocomplete.tsx` (saisie conservée, champ en erreur) |
| F223 | Inscription : « Modifier le numéro » puis renvoi rejoue POST /otp/register →… | Fait | `pages/LoginPage.tsx` (409 → bascule vers la demande de code) |
| F224 | EditTripSheet : impossible de vider la politique bagages ou la description… | Fait | `features/trips/EditTripSheet.tsx` (chaîne vide = effacement), `TripService#updateTrip` |
| F225 | Publication : date par defaut aujourd'hui 07:00 sans controle « dans le futur »… | Fait | `lib/validation.ts` (départ ≥ +15 min), `pages/PublishTripPage.tsx` (heure initiale honnête, contrôle à l'étape 1) |
| F226 | Publication : « Ajouter un vehicule » abandonne le formulaire, et le nombre de… | Fait | `pages/PublishTripPage.tsx` (feuille véhicule sans perdre le formulaire, places bornées par le véhicule) |
| F227 | Verification des vehicules promise partout, mais aucun ecran admin ne la fait… | Fait | `features/account/VehiclesSection.tsx` (badge seulement si attesté), `pages/admin/AdminUserDetail.tsx` (`useVerifyVehicle`) |
| F228 | Modifier le parent d'une navette ne touche pas les occurrences deja generees | Fait | `service/TripService#propagateTemplateUpdate` (occurrences à venir sans réservation) |
| F229 | « Je conduis » : liste non paginee, passes et annules melanges, occurrences… | Fait | `pages/MyTripsPage.tsx` (navettes regroupées, passés/annulés séparés), `GET /trips/{id}/bookings` (feuille Passagers) |
| F230 | NotificationsPage : textes construits sur des cles de payload jamais emises… | Fait | `pages/NotificationsPage.tsx` (résumés construits sur la charge utile réellement émise), `NotificationTemplates` |
| F231 | Liste des notifications non bornee, rechargee integralement toutes les 60 s sur… | Fait | `GET /notifications?size=` paginé + `/unread-count`, `hooks/useNotifications.ts` |
| F232 | Une reservation expiree faute d'acompte est affichee « Annulee par vous » | Fait | `BookingStatus.EXPIRED` (V16), libellé « Expirée » dans `pages/MyTripsPage.tsx` |
| F233 | Revenus : promesse d'un reversement hebdomadaire automatique et d'une « relance… | Fait | `features/account/EarningsSection.tsx` (libellés honnêtes, virement manuel), notifications PAYOUT_SETTLED/FAILED |
| F234 | Avec les codes de connexion par e-mail, phoneVerified n'est jamais vrai : badge… | Fait | `features/account/AccountHeaderCard.tsx` (badge négatif retiré), `pages/HomeSearchPage.tsx` (texte reformulé) |
| F235 | ErrorBoundary : le lien « Retour a l'accueil » de l'ecran d'erreur ne… | Fait | `components/ErrorBoundary.tsx` (réinitialisation au changement de route) |
| F236 | L'API accepte un avis avant le depart du trajet ; seule l'interface l'empeche | Fait | `service/ReviewService` (refus avant le départ), `ReviewServiceTest` |
| F237 | Listes admin tronquées silencieusement (100 utilisateurs, 200 signalements) et… | Partiel | Signalements paginés (`ReportService#list` → `Page`), fiche utilisateur paginée (`AdminUserService#bookings/trips/payments`). Manque : recherche utilisateurs toujours limitée à 100 sans indicateur (`AdminUserService.SEARCH_LIMIT`), `PayoutService#listAllForAdmin` en liste non paginée. |
| F238 | Statistiques de volume calculées en chargeant toutes les réservations (avec… | Fait | `service/admin/AdminStatsService` (SQL `date_trunc` en heure du Bénin, `days` borné), `BookingRepository`/`TripRepository` |
| F301 | « Prendre en charge » un signalement le marque comme résolu… | Fait | `service/ReportService#updateStatus` (transitions OPEN→IN_REVIEW→RESOLVED/DISMISSED, note obligatoire à la clôture) |
| F302 | Transitions d'état non contrôlées : lot réglé re-réglable, vérification déjà… | Fait | `PayoutService#settle`, `AdminVerificationService` (PENDING exigé), `AdminUserService#suspend` (409) |
| F303 | Constitution des lots de reversement sans verrou ni idempotence : deux… | Fait | `DriverPayoutRepository` (verrou consultatif transactionnel), `PayoutService#runWeeklyBatch` |
| F304 | Un administrateur peut se suspendre lui-même ou suspendre un autre… | Fait | `service/admin/AdminUserService` (409 pour soi-même et pour un ADMIN), `dto/admin/AdminUserResponse.role`, `pages/admin/AdminUsers.tsx` |
| F305 | Journal d'audit sans filtre, sans recherche et avec acteurs affichés en UUID… | Fait | `service/AuditService` (filtres action/acteur/entité/période, nom de l'acteur), `pages/admin/AdminAudit.tsx` |
| F306 | Aucune alerte de file dans la navigation admin (signalements ouverts… | Fait | `GET /api/v1/admin/overview` (`AdminOverviewService`), pastilles dans `pages/admin/AdminLayout.tsx` |
| F307 | Signalements renvoyés sans ordre déterministe | Fait | `ReportRepository`/`ReportService` (tri déterministe, `priorReportsAgainstTarget`) |
| F308 | Requêtes N+1 sur les listes admin (2 count par utilisateur, 2 requêtes par lot… | Fait | `BookingRepository`/`TripRepository` (comptes par liste d'identifiants), `DriverPayoutItemRepository` (agrégats) |
| F313 | Aucune fiche utilisateur admin : le lien mène au profil public conducteur, sans… | Fait | `GET /admin/users/{id}` + `/bookings\|trips\|payments`, `pages/admin/AdminUserDetail.tsx`, audit filtré par entité |
| F314 | Teinte pleine `--danger` posée en texte sur fond pâle : 4,03:1, sous le seuil… | Fait | `components/ui/input.tsx`, `index.css` (`--danger-ink`), `scripts/check-contrast.mjs` en lint |
| F315 | Bordures des champs de saisie à 1,55:1 : le contour disparaît en plein soleil… | Fait | `index.css` (`--field-border` 3:1), `components/ui/misc.tsx` |
| F316 | prefers-reduced-motion neutralise le CSS mais pas les animations motion/react… | Fait | `main.tsx` (`MotionConfig reducedMotion="user"`), `pages/SystemPages.tsx` |
| F317 | Aucun titre ni description par route, aucune annonce de changement d'écran… | Fait | `components/layout/PageMeta.tsx` (title/description par écran), `AppShell.tsx` (annonce aria-live), `AdminPageHeader.tsx` |
| F318 | Navigation mobile : « Messages » inaccessible depuis les menus, et deux entrées… | Fait | `components/layout/AppShell.tsx` (Messages dans la barre basse et repli menu) |
| F319 | Icônes PWA encore à l'ancienne marque bleue et tracé de carte en `#2E3FA8` codé… | Fait | `components/trip/RouteMap.tsx` (couleur lue dans le thème), `frontend/scripts/icons.mjs` (icônes régénérées) |
| F320 | Compte à rebours d'acompte en live region : annonce vocale chaque seconde, en… | Fait | `components/booking/Countdown.tsx` (chiffre `aria-hidden`, annonces aux paliers) |
| F321 | Groupes « radio » maison sans navigation clavier par flèches ni tabindex… | Fait | `components/ui/tabs.tsx`, `components/ui/misc.tsx`, `ReviewDialog.tsx`, `PreferencesSection.tsx` (Radix RadioGroup) |
| F322 | Focus clavier quasi invisible dans les menus et listes déroulantes… | Fait | `components/ui/dropdown-menu.tsx`, `components/ui/select.tsx`, `CityAutocomplete.tsx` (surbrillance clavier lisible) |
| F323 | Promesses du héro que le produit ne tient pas : « numéro confirmé par SMS », «… | Fait | `pages/HomeSearchPage.tsx` (promesses tenues), `features/account/forms/IdentityForm.tsx` (aucun délai chiffré) |
| F335 | Recharts (416 kB / 118 kB gz) chargé au premier affichage de toutes les pages à… | Fait | `vite.config.ts` (`manualChunks` limité à maplibre, chunk `charts` retiré), routes admin en lazy |
| F337 | Précache du service worker de 2,67 Mo téléchargé par chaque visiteur (MapLibre… | Fait | `vite.config.ts` (`globIgnores`, `maximumFileSizeToCacheInBytes` 2 Mo, CacheFirst sur `/assets/`) |
| F338 | Premier chargement : ~550 kB compressés avant interaction, toutes les pages… | Fait | `App.tsx` (lazy par route), `main.tsx` (`LazyMotion` + `m`), `ci.yml` (budget de taille gzip) |
| F339 | Réponses périmées servies comme fraîches dès que l'API dépasse 6 s… | Fait | `vite.config.ts` (pas de `networkTimeoutSeconds`, cache sur échec réseau seulement, routes personnelles exclues) |
| F340 | La file de mutations hors ligne annoncée (bandeau « actions en attente »… | Fait | `components/layout/OfflineBanner.tsx` (aucune file annoncée), `lib/queryClient.ts` |
| F341 | Aperçu WhatsApp/Facebook d'un lien de trajet : générique, sans image ni trajet… | Fait | `web/controller/ShareController` (`GET /share/trips/{id}`, Open Graph), `Caddyfile`/`Caddyfile.proxied` (`@sharebots`), `ShareControllerTest` |
| F343 | Pas de Cache-Control sur index.html, sw.js, manifest, icônes : cache… | Fait | `frontend/nginx.conf` (`no-cache` sur index/sw/manifest, `immutable` sur `/assets/`) |
| F344 | Mise à jour du service worker en autoUpdate sans gestion côté application… | Fait | `vite.config.ts` (`registerType: 'prompt'`), `components/layout/ServiceWorkerUpdate.tsx`, `ErrorBoundary.tsx` (rechargement sur chunk manquant) |
| F345 | MapLibre (1 Mo) chargé sur mobile pour une carte invisible ; deux instances… | Fait | `hooks/useMediaQuery.ts`, `pages/TripDetailPage.tsx`/`SearchResultsPage.tsx` (carte montée seulement quand visible) |
| F355 | Jeton de rafraîchissement (30 jours, non révocable) lisible en JavaScript dans… | Partiel | Rotation et révocation des refresh tokens (`RefreshTokenService`, V11), CSP `connect-src` restreinte. Manque : refresh token toujours en `localStorage` (`api/client.ts`), pas de cookie HttpOnly, TTL glissant encore 30 j. |
| F357 | HSTS absent de la configuration versionnée de la topologie déployée… | Fait | `Caddyfile.proxied` (`Strict-Transport-Security max-age=31536000; includeSubDomains`) |
| F406 | L'expiration de session ne purge ni le cache mémoire ni le cache persisté, et… | Fait | `hooks/useAuth.ts#resetSession` (appelé sur `expired`, au logout et avant une nouvelle session) |
| F409 | Les arrêts intermédiaires sont ignorés par la recherche géographique… | Fait | `TripRepository#search` (arrêts intermédiaires), `TripResponse` (tronçon apparié), V16 (index GIST), `SearchAlertRepository` |
| F412 | Couverture géographique insuffisante du référentiel geo_places (17 villes… | Fait | V17 (`geo_places` : communes, quartiers, gares `STATION`, alias, Sèmè-Kpodji), `api/extended.ts` (`kind`, `parentName`), `CityAutocomplete.tsx` |
| F414 | Durees et heures d'arrivee estimees irrealistes en zone urbaine et affichees… | Fait | `lib/route.ts` / `lib/cities.ts` (modèle à deux vitesses, arrivée « estimée »), `TripCard.tsx`, `RouteTimeline.tsx` |
| F416 | Parametres de recherche publics non bornes (radiusKm, size) et aucun rate… | Fait | `TripController` (bornes), `RateLimitingFilter` (GET publics), `GeoController` (Cache-Control) |
| F417 | Le service worker precache maplibre (1 Mo), recharts (416 Ko) et tout le… | Fait | `vite.config.ts` (`globIgnores` map/charts/admin, CacheFirst `/assets/`) |
| F418 | Le cache Workbox « ekuiseo-api » (24 h, cle = URL sans distinction… | Fait | `lib/queryClient.ts#clearApiCache` (logout et expiration), `vite.config.ts` (routes publiques seulement) |
| F419 | Arrets intermediaires publies sans heure de passage (plannedAt jamais envoye)… | Fait | `service/TripService` (heures de passage facultatives mais bornées, prix des arrêts), `TripServiceStopsTest`, `pages/PublishTripPage.tsx` |
| F427 | Les 500 (et les 409 d'intégrité) ne sont jamais journalisés, et le handler… | Fait | `common/GlobalExceptionHandler` (log.error + errorId, handlers 400/404/405) |
| F430 | Spring Boot 3.3.4 hors support OSS depuis juin 2025 et aucun mécanisme de… | Fait | `backend/pom.xml` (Spring Boot 3.5.16), `.github/dependabot.yml`, `ci.yml` (`npm audit` informatif) |
| F433 | 13 requetes natives PostGIS et l'UPDATE conditionnel JPQL des places ne sont… | Fait | `ci.yml` (service postgis retiré, `mvn verify` avec failsafe), `BookingConcurrencyIT`, `KpiNativeQueriesIT`, `TripSearchIT` |
| F434 | Aucun test de controleur, de filtre JWT, de SecurityConfig ni d'AuthService… | Fait | `web/AbstractWebMvcTest`, `SecurityRulesWebMvcTest`, `AuthControllerWebMvcTest`, `BookingControllerWebMvcTest`, `PaymentControllerWebMvcTest`, `AuthServiceTest` |
| F435 | Frontend : aucun test de composant, de hook ni de bout en bout ; la… | Partiel | Étape 1 faite : `vitest.config.ts` (jsdom, `.tsx`), Testing Library, `components/RequireAuth.test.tsx`, `pages/BookingPage.test.tsx`. Manque : suite Playwright de bout en bout (étape 2). |
| F436 | Le chemin de deploiement reel (deploy-vps.sh via GitHub Actions) n'a ni… | Fait | `scripts/deploy-vps.sh` (sauvegarde préalable, images `:previous`, `rollback()`), `docs/EXPLOITATION.md` |
| F437 | Images Docker reconstruites sur le VPS partage a chaque push (mvn package +… | Non fait | `ci.yml` construit toujours les images sans les pousser (pas de ghcr.io) et `scripts/deploy-vps.sh` fait `up -d --build` sur le VPS ; retour arrière par images `:previous` locales seulement. |
| F438 | Aucune surveillance externe ni alerte : une panne n'est decouverte que par les… | Décision fondateur | `docs/EXPLOITATION.md` et `docs/DEPLOIEMENT.md` décrivent la sonde attendue (`/actuator/health` relayé par Caddy, alerte sur `backups/last-success` > 36 h). Reste au fondateur : souscrire la sonde externe (UptimeRobot / Better Stack / Uptime Kuma). |
| F439 | Journaux backend non structures, sans identifiant de correlation ni journal… | Fait | `web/filter/RequestIdFilter` (MDC requestId/userId, log par requête), `logback-spring.xml`, `Caddyfile.proxied` (`X-Request-Id`), `GlobalExceptionHandler` |
| F440 | Erreurs frontend jamais remontees : ErrorBoundary se limite a console.error… | Décision fondateur | `lib/monitoring.ts` (ErrorBoundary, `error`/`unhandledrejection`, QueryCache), `vite.config.ts` (`sourcemap: 'hidden'`), `@vercel/analytics` retiré. Reste au fondateur : choisir le collecteur (Sentry / GlitchTip) et renseigner son URL. |
| F441 | Arret non gracieux du backend (server.shutdown immediate, pas de… | Partiel | `application.yml` (`server.shutdown: graceful`, 25 s), `docker-compose.prod.yml` (`stop_grace_period: 30s`), paiement INITIATED réutilisé (F019). Manque : `GET /payments/{id}` ne re-vérifie pas un paiement en attente auprès de Kkiapay. |
| F450 | GET /admin/verifications?status= ignore le statut : les onglets Validées /… | Fait | `AdminVerificationController` (paramètre typé), `AdminVerificationService#listByStatus`, `IdentityVerificationRepository` |
| F451 | Export CSV de /admin/liquidity toujours en échec : le client envoie Accept… | Non fait | `api/client.ts#downloadFile` envoie toujours `Accept: application/json` alors que `AdminStatsController` garde `produces = "text/csv"` sur `/liquidity/export` **et** `/retention/export` : les deux exports CSV répondent 406. |
| F452 | GlobalExceptionHandler attrape Exception.class : toutes les erreurs MVC… | Fait | `common/GlobalExceptionHandler` (400 sur paramètre/JSON invalide, 404, 405, 406), `pages/SearchResultsPage.tsx` (`type` validé) |
| F453 | PATCH /trips/{id} : le front envoie null pour vider luggagePolicy/description… | Fait | `EditTripSheet.tsx` (chaîne vide), `TripService#updateTrip` (vide = null) |
| F458 | Reversements : settle marque SETTLED sans vérifier l'état précédent… | Fait | `PayoutService#settle` (PENDING/FAILED seulement), `POST /admin/payouts/{id}/fail`, statuts réels dans `AdminPayouts.tsx` |
| F508 | Aucun export des données personnelles (droit d'accès) : demande satisfiable… | Fait | `service/UserDataExportService` (`GET /me/export`, 1/24 h), V16 (`last_export_at`), `features/account/DataSection.tsx` |
| F509 | Inscription sans acceptation horodatée des CGU/politique de confidentialité (et… | Fait | `service/TermsPolicy`, `OtpRegisterRequest` (acceptTerms/termsVersion), `PATCH /me/terms`, V16, `features/account/TermsGate.tsx` |
| F510 | Aucune page légale ni contact dans le produit : un utilisateur suspendu n'a… | Fait | `pages/LegalPage.tsx` sur `/cgu`, `/confidentialite`, `/mentions-legales`, pied de page dans `AppShell.tsx`, contact dans `SystemPages.tsx` (compte suspendu), `features/account/DataSection.tsx` |
| F511 | Codes OTP, e-mails et téléphones en clair dans les journaux en mode log, sans… | Fait | `MailConfig` (refus mode log avec paiements réels), `LoggingMailGateway`/`LoggingSmsGateway` masqués, audits OTP dans `AuthService` |
| F512 | Énumération des comptes et envoi de codes non sollicités : /auth/otp/request… | Fait | `AuthService` (message 409 unique `ALREADY_USED`), `common/Masking` (`l***@g***.com`), `RateLimitingFilter` (quota `otp:` par IP), `OtpCodeRepository` (plafond quotidien compté en base), `pages/LoginPage.tsx` |
| F513 | Préférences de notification sans effet : notifyCritical envoie le SMS sans lire… | Fait | `service/NotificationDispatcher` (préférences e-mail et SMS lues), `PreferencesSection.tsx` (push retiré) |
| F514 | Alertes de recherche : aucune liste ni suppression côté utilisateur (POST seul)… | Fait | `GET`/`DELETE /trip-alerts`, `TripAlertService`, `features/account/AlertsSection.tsx`, `docs/CONFORMITE.md` |
| F515 | Numéro de pièce d'identité conservé en clair et sans limite de durée après la… | Fait | `AdminVerificationService` (troncature aux 4 derniers caractères après décision), V17 (script de données), `AdminVerificationServiceTest` |
| F516 | otp_codes et notifications jamais purgés : durées de conservation annoncées… | Fait | `service/RetentionScheduler` (`OTP_RETENTION_HOURS`, `NOTIFICATIONS_RETENTION_DAYS`), `.env.example`, `docs/CONFORMITE.md` |
| F517 | Sous-traitants réels non inventoriés dans CONFORMITE §5 : relais SMTP, MapTiler… | Fait | `docs/CONFORMITE.md` §5 (Kkiapay widget et champs transmis, relais SMTP, MapTiler, OVH partagé, SMS si activé), CSP resserrée |
| F518 | docs/CONFORMITE.md et docs/LANCEMENT.md décrivent un système différent de celui… | Fait | `docs/CONFORMITE.md` réécrit sur le système déployé (registre, durées, droits, sous-traitants), `docs/LANCEMENT.md` aligné |
| F523 | L'alerte de recherche ne notifie qu'in-app (sondage 60 s, application ouverte)… | Fait | `TripAlertService` (préférence e-mail à la création), `NotificationTemplates` (objet explicite, lien direct), `NotificationDispatcher` |
| F524 | Ni plafond ni dédoublonnage ni limitation de débit sur POST /trip-alerts… | Fait | `TripAlertService` (dédoublonnage, plafond 10 → 422), `RateLimitingFilter` (`alert:` 10/10 min), V16 |
| F525 | Aucune expiration ni purge des alertes : les alertes datées restent… | Fait | `RetentionScheduler` (désactivation à la date de fin, purge 90 j), `TripAlertService` (30 j sans date), `docs/CONFORMITE.md` |
| F526 | Matching d'alertes en N+1 : chargement de toutes les alertes candidates sans… | Fait | `SearchAlertRepository#findMatching` (une requête PostGIS), V16 (index partiel) |
| F527 | Matching d'alertes exécuté dans la transaction de publication et du job… | Fait | `service/TripPublishedEvent` + `SearchAlertMatchService` (`@TransactionalEventListener` AFTER_COMMIT, `alertExecutor` dans `AsyncConfig`) |
| F530 | Rayon d'alerte (10 km) inférieur au rayon de recherche du front (15 km) : des… | Fait | `domain/SearchAlert.radiusKm` (V16, figé à la création), `TripController` (`radiusKm` ≤ 50) |
| F540 | Plusieurs codes OTP valides simultanément : seul le dernier est accepté, les… | Fait | `OtpCodeRepository` (codes actifs invalidés à chaque émission), `OtpCodeService`, `OtpDeliveryService` (« ce code remplace le précédent ») |
| F542 | Aucune issue de secours sur l'écran de code quand l'e-mail masqué n'est pas… | Fait | `pages/LoginPage.tsx` (aide « adresse plus accessible ? », contact support cliquable), `Retry-After` sur les 429 (`RateLimitingFilter`, `TooManyRequestsException`), `OTP_SMS_FALLBACK=false` par défaut |
| F546 | Messagerie déconnectée du cycle de vie de la réservation : écriture possible à… | Fait | `service/MessageService#send` (conversation close selon le statut), `pages/BookingMessagesPage.tsx`, `MyTripsPage.tsx` |
| F547 | Messages sans limite de taille (jusqu'à 10 Mo via nginx) ni de fréquence… | Partiel | `SendMessageRequest` (2000), V15 (CHECK), `RateLimitingFilter` (`msg:` 30/10 min), pagination des messages et notifications. Manque : `client_max_body_size` nginx toujours 10 Mo et aucun `request_body max_size` dans les Caddyfile. |
| F549 | Modération sans accès aux échanges : aucun endpoint admin vers les… | Fait | `GET /admin/reports/{id}/conversations` (audit ADMIN_CONVERSATION_VIEWED), `AdminReportConversationResponse`, volet dans `AdminReports.tsx`, `docs/CONFORMITE.md` |
| F550 | REPORT_RECEIVED jamais émis (constat déjà noté dans types.ts) et aucune… | Fait | `ReportService` (REPORT_RECEIVED à la cible sans identité de l'auteur, REPORT_RESOLVED au signalant), `NotificationTemplates` |
| F551 | `reasonCode` texte libre accepté par l'API (replié silencieusement sur OTHER en… | Fait | `dto/report/CreateReportRequest` (`ReportReason` typé), V15 (CHECK `reason_code`) |
| F552 | Vue admin des signalements sans note de résolution relue, sans compteur de… | Fait | `dto/report/AdminReportResponse` (note, résolu par, `priorReportsAgainstTarget`), `pages/admin/AdminReports.tsx` (lien vers l'utilisateur, suspension) |
| F553 | Aucune purge des messages malgré la durée « courte » annoncée dans… | Fait | `service/RetentionScheduler` + `MessageRepository` (`MESSAGES_RETENTION_DAYS`), `.env.example`, `docs/CONFORMITE.md` |
| F554 | Aucun test unitaire sur MessageService ni ReportService (contrôle d'accès aux… | Fait | `service/MessageServiceTest`, `service/ReportServiceTest` |
| F603 | Numéro mobile money et numéro de pièce acceptés sans format ni borne serveur… | Fait | `service/PaymentAccountService` (E.164, bornes), `IdentityVerificationService` (forme canonique), `lib/validation.ts` |
| F604 | Le même numéro de pièce peut être approuvé sur plusieurs comptes sans que le… | Fait | `IdentityVerificationService` (normalisation), V16 (index `document_type, upper(document_number)`), `AdminVerificationResponse` (doublons signalés) |
| F606 | Pas de dédoublonnage ni de plafond des comptes mobile money | Fait | `PaymentAccountService` (plafond 3, doublon 409), V16 (index unique) |
| F607 | Aucun contrôle de cohérence opérateur / préfixe du numéro mobile money, et… | Fait | `service/MobileMoneyPrefixes` (préfixes configurables), `PaymentAccountService`, `destination_provider` figé (V12) |
| F608 | Notifications d'identité promises à l'utilisateur mais jamais envoyées | Fait | `NotificationType.IDENTITY_APPROVED/REJECTED`, `AdminVerificationService` (motif), `NotificationDispatcher` (e-mail) |
| F609 | Les onglets « Validées » et « Refusées » du back-office affichent la file… | Fait | `AdminVerificationService#listByStatus`, `IdentityVerificationRepository`, `AdminVerificationResponse` (reviewedAt, motif) |
| F610 | POST /admin/users/{id}/verify-identity pose le badge sans dossier : deux… | Fait | `POST /admin/users/{id}/verify-identity` supprimé (`AdminUserController`) : le badge ne vient que d'un dossier approuvé |
| L4 | GET /api/v1/trips/search sans parametres renvoie 500 au lieu de 400 | Fait | `TripController` (paramètres obligatoires, `@Validated`), `GlobalExceptionHandler` (400), `web/controller/RequestBoundsTest` |
| L5 | 1,4 Mo de JavaScript au premier chargement, dont le module graphiques (416 Ko)… | Fait | `vite.config.ts` (chunk charts retiré, lazy par route), `ci.yml` (budget gzip : 163 Kio pour l'entrée) |
| L6 | Aucune meta description ni image Open Graph : un lien Ekuiseo partage sur… | Fait | `frontend/index.html` (description, og:image 1200×630, twitter:card), `public/og-image.png`, `ShareController` par trajet |
| L7 | Mise a jour PWA silencieuse : l ancien bundle reste servi par le service worker… | Fait | `vite.config.ts` (`registerType: 'prompt'`), `components/layout/ServiceWorkerUpdate.tsx` |
| L8 | Fiche trajet sur mobile : la barre d action fixe recouvre la ligne conducteur… | Fait | `pages/TripDetailPage.tsx` (hauteur réservée aux barres fixes), `components/trip/RouteMap.tsx` (carte inerte tant qu'elle n'est pas activée) |

### 3.4. P3 (121 constats)

| Id | Titre | Statut | Preuve / reste à faire |
|---|---|---|---|
| F009 | Schedulers et limiteurs conçus pour une instance unique : contrainte non… | Fait | `docs/EXPLOITATION.md` (mono-instance), `RateLimitingFilter`/`OtpRateLimiter` (documentés), V13 (index unique parent/départ), `TripRepository` (marquage conditionnel du rappel) |
| F012 | Le webhook répond 404 pour un bookingId/subscriptionId inconnu au lieu… | Fait | `PaymentService#handleWebhook` (évènement non rattachable acquitté, vérification après résolution de la cible) |
| F014 | BookingService et PaymentService cumulent plusieurs responsabilités (assemblage… | Partiel | `PaymentService#applyVerification` extrait. Manque : `PaymentPlanAssembler`/`BookingDetailAssembler` et `RecurringTripDetector` (BookingService cumule encore ces rôles). |
| F015 | Code mort (méthodes de dépôt, service, bean de sécurité, dépendance H2) et… | Fait | Alias `/initiate`, `/activate`, `/settle` en doublon, `statsByRange`, `EkuiseoUserDetailsService`, H2 retirés ; `MoneyUtils` purement arithmétique |
| F019 | Gardes d'état et d'idempotence manquantes sur des opérations financières… | Fait | `PaymentService` (paiement INITIATED réutilisé), `PaymentHousekeepingScheduler` (INITIATED > 20 min → FAILED), `settle` gardé, V17 (index) |
| F020 | Incohérences de configuration (valeurs par défaut divergentes, propriétés… | Partiel | CORS restreint par défaut en production (`docker-compose.prod.yml`), `MailConfig` valide le mode. Manque : pas de `@ConfigurationProperties` validée ; `ekuiseo.sms.otp` et `ekuiseo.otp` coexistent dans `application.yml`. |
| F021 | Lisibilité : noms pleinement qualifiés inline, numérotation de règles métier… | Fait | `service/kkiapay/KkiapayWebhookParser` (logique sortie du DTO), commentaires rattachés aux constats et règles |
| F030 | CORS : toute origine est reflétée avec Access-Control-Allow-Credentials: true… | Fait | `config/SecurityConfig` (`allowCredentials(false)`), `docker-compose.prod.yml` (origines ekuiseo.com par défaut) |
| F031 | Swagger UI et OpenAPI complets exposés publiquement en production (avec URL… | Fait | `application.yml` (`SPRINGDOC_ENABLED`), `docker-compose.prod.yml` (false), Caddy ne relaie plus Swagger |
| F032 | Énumération de comptes par les codes 404/409 de /auth/otp/request et… | Partiel | `AuthService` (message 409 unique sur `/otp/register`), `Masking` (e-mail masqué), quotas IP et plafond quotidien (F512). Manque : le 404 de `/otp/request` est conservé sans être documenté comme choix dans `docs/CONFORMITE.md`. |
| F033 | Numéro de pièce d'identité conservé en clair sans purge après décision et… | Partiel | Troncature aux 4 derniers caractères après décision (`AdminVerificationService`, V17), numéro masqué dans les journaux et l'audit. Manque : HMAC ou chiffrement au repos (aucun `pgcrypto`), affichage réservé à la fiche détail avec audit. |
| F034 | Aucune garde sur la suspension d'un compte ADMIN (soi-même ou le dernier… | Fait | `service/admin/AdminUserService` (409 pour soi-même ou un ADMIN), audit |
| F045 | Estimation front des frais de service divergente du back de 5 F sur les totaux… | Fait | `lib/payments.ts` (calcul entier `Math.ceil(total * 8 / 500) * 5`), `lib/payments.test.ts` |
| F122 | pickupStopId accepté sans vérification d'appartenance au trajet (API seulement… | Fait | `BookingService#resolveStop` (appartenance, montée avant descente, tarif par tronçon), `BookingQuoteRequest` |
| F140 | payments.channel reflète l'opérateur déclaré, pas l'opérateur réel : KPI « taux… | Fait | `service/kkiapay/KkiapayHttpGateway` (`source_common_name`), `PaymentService` (opérateur réel dans `channel`), `PaymentRepository` |
| F144 | Annulation conducteur : SMS « vous serez remboursé intégralement » envoyé aussi… | Fait | `BookingService#cascadeCancelForDriverTripCancellation` (remboursement seulement si CONFIRMED avec acompte, message adapté) |
| F145 | Index manquants ou partiels pour les requêtes réellement exécutées | Fait | V17 (index notifications, réservations en attente, otp_codes, abonnements actifs) |
| F146 | Statuts stockés en VARCHAR sans CHECK sur users/trips/bookings/payments/driver_p… | Fait | V17 (contraintes CHECK sur les statuts alignées sur les enums Java) |
| F147 | Recalcul de la note et compteur d'annulations tardives en… | Fait | `UserRepository` (UPDATE atomiques : note moyenne, annulations tardives), `ReviewService`, `BookingService` |
| F148 | users.password_hash NOT NULL rempli d'un hash factice depuis le passage à l'OTP | Fait | V17 (`users.password_hash` nullable), `AuthService` (NULL pour les comptes OTP) |
| F149 | Webhook abonnement : création d'un second Payment au lieu de réutiliser… | Fait | `PaymentRepository` (INITIATED d'abonnement réutilisé), `PaymentController` (signature invalide → 401, jamais 400) |
| F150 | Course widget/webhook non sérialisée : double confirmation et double… | Fait | `PaymentRepository#findByIdForUpdate` (verrou pessimiste dans widget et webhook) |
| F151 | Aucun contrôle de cohérence du montant vérifié au-delà du plancher… | Fait | V17 (`payments.verified_amount`), `PaymentService` (surpaiement audité PAYMENT_OVERPAID) |
| F152 | Alertes de recherche : correspondance synchrone dans la transaction de… | Fait | `SearchAlertRepository#findMatching` (une requête), `SearchAlertMatchService` (après commit, `alertExecutor`), e-mail via `NotificationDispatcher` |
| F153 | Invalidations incompletes : resultats de recherche perimes apres… | Fait | `hooks/useTrips.ts` (clé racine `['trips','search']` invalidée), `hooks/useBookings.ts`, `hooks/useAccount.ts` (`['me']` exact) |
| F154 | Delai d'acompte code en dur cote front et echeance calculee sur l'horloge client | Fait | `features/booking/useBookingFlow.ts` (échéance lue dans `paymentPlan.depositDueAt`) |
| F239 | Logiques et libelles dupliques entre pages (arrivee estimee, dates, enums) | Fait | `lib/route.ts` (`estimateArrival`), `lib/format.ts`, `lib/labels.ts` partagés |
| F240 | Code mort : ~60 exports jamais importes, dependance @vercel/analytics… | Partiel | `vercel.json` et `@vercel/analytics` retirés, `lib/motion.ts` et `misc.tsx` élagués. Manque : `knip`/`oxlint --unused-exports` en CI. |
| F241 | RouteMap : la carte reelle serait detruite et recreee a chaque rendu de… | Fait | `pages/TripDetailPage.tsx` (`mapPoints` mémorisé) |
| F242 | Commentaires evoquant React Compiler alors qu'il n'est pas configure… | Fait | `pages/PublishTripPage.tsx` (`useWatch` ciblés, commentaire corrigé) |
| F243 | SubscriptionSection contourne les hooks et duplique le flux Kkiapay de… | Fait | `hooks/useKkiapayCheckout.ts` partagé par `useBookingFlow.ts` et `SubscriptionSection.tsx` |
| F244 | AbortSignal de TanStack jamais transmis : cancelQueries n'annule aucune requete… | Fait | `api/client.ts` (`signal` transmis et composé avec le délai) |
| F245 | Fenêtre Kkiapay fermée sans payer : écouteurs jamais retirés, promesse pendante… | Fait | `lib/kkiapay.ts` (fermeture réglée, écouteurs retirés), `useKkiapayCheckout.ts` |
| F246 | Erreurs 4xx définitives affichées comme des problèmes de connexion (ErrorState… | Fait | `lib/errors.ts` (erreur définitive), `components/ui/states.tsx` (`describeError`, réessai masqué) |
| F247 | Heure d'arrivée estimée présentée comme exacte, et tri « Trajet le plus court »… | Fait | `lib/route.ts` (arrivée estimée « ≈ »), tri « durée » retiré |
| F248 | Filtre de date en jour UTC alors que le Bénin est UTC+1 ; date passée non… | Fait | `TripService#search` (`Tz.BENIN`), `pages/HomeSearchPage.tsx` et `SearchResultsPage.tsx` (date passée refusée) |
| F249 | Tri et filtres non reflétés dans l'URL ; nombre de places recherché non… | Fait | `pages/SearchResultsPage.tsx` (tri et filtres dans l'URL), `?seats=` propagé jusqu'à `useBookingFlow.ts` |
| F250 | Mode quotidien : le texte promet de « mémoriser » une navette, fonctionnalité… | Fait | `pages/HomeSearchPage.tsx` (formulation honnête) |
| F251 | Signalement : promesse « examiné sous 48 h » non tenue et motif non réinitialisé | Fait | `components/feedback/ReportDialog.tsx` (aucun délai promis, motif réinitialisé) |
| F252 | Réseau lent : jusqu'à ~60 s de squelette sans indication (timeout 20 s × 3… | Fait | `hooks/useNetwork.ts` (`useSlow` 8 s), `components/ui/states.tsx` |
| F253 | Lien « Se connecter » du bloc quotidien sans ?next= : retour à l'accueil en… | Fait | `pages/HomeSearchPage.tsx` (`?next=/?type=QUOTIDIEN`, mode lu dans l'URL) |
| F254 | Formulaire d'identite : promesse « sous 24 a 48 h » non tenue par le processus | Fait | `features/account/forms/IdentityForm.tsx` (aucun délai chiffré) |
| F255 | Erreurs d'ajout/suppression de vehicule et de compte mobile money masquees par… | Fait | `features/account/VehiclesSection.tsx` (`describeError`, motif serveur repris), `PaymentMethodsSection.tsx` |
| F256 | Reglages : preference « bavard / calme » et langue exposees publiquement mais… | Partiel | `features/account/PreferencesSection.tsx` (push retiré). Manque : sélecteur « calme / ça dépend / bavard » et langue toujours non modifiables (aucun champ `chattiness` dans `features/`). |
| F257 | Compte suspendu : l'utilisateur voit « Votre session a expire » au lieu d'un… | Fait | `api/client.ts` (403 `account-suspended`), `pages/SystemPages.tsx` (écran dédié), `AppShell.tsx` |
| F309 | Libellé « Relancer » sur un lot FAILED alors que l'action marque le lot comme… | Fait | `POST /admin/payouts/{id}/fail` (motif), FAILED réel avec `failureReason` dans `pages/admin/AdminPayouts.tsx` |
| F310 | Refus de vérification sans motif accepté par l'API (reason nul journalisé «… | Fait | `dto/admin/RejectVerificationRequest` (`@NotBlank reason`), `AdminVerificationController` (`@Valid`) |
| F311 | Seuil de viabilité codé en dur dans le texte du tableau de bord alors que l'API… | Fait | `pages/admin/AdminDashboard.tsx` (seuil lu dans `northStar.monthlyTarget`) |
| F312 | Endpoints admin existants sans écran : validation des véhicules et vérification… | Fait | Section véhicules de `pages/admin/AdminUserDetail.tsx` (`useVerifyVehicle`), `/verify-identity` supprimé |
| F324 | Cibles tactiles sous 44 px : interrupteurs, onglets, icônes d'en-tête… | Fait | `components/ui/misc.tsx` (SettingRow cliquable, zone tactile 44 px), `tabs.tsx` (44 px), `dialog.tsx` |
| F325 | Barre d'action collante du détail de trajet en conflit avec la zone sûre… | Fait | `components/layout/StickyActionBar.tsx` (au-dessus de la barre basse et de la zone sûre), `TripDetailPage.tsx` |
| F326 | Dette de cohérence mesurable : 252 tailles arbitraires, 237 alias historiques… | Fait | `index.css` (`--text-micro` 11 px, échelle nommée), `frontend/scripts/check-tokens.mjs` en lint (tailles arbitraires refusées), `StyleGuidePage.tsx` |
| F327 | Heure d'arrivée et durée estimées à vol d'oiseau, affichées comme des faits | Fait | `lib/route.ts` (estimation), `TripCard.tsx`/`TripDetailPage.tsx` (« ≈ », « arrivée estimée ») |
| F328 | Bouton d'inversion départ/arrivée masqué sur mobile et libellés du Stepper… | Fait | `pages/HomeSearchPage.tsx` (inversion visible sur mobile, 44 px), `components/ui/misc.tsx` (libellés du Stepper) |
| F329 | Aucun pied de page ni point de contact : pas de CGU, confidentialité, aide ou… | Fait | `components/layout/AppShell.tsx` (pied de page), `PreferencesSection.tsx` (aide, contact, textes légaux), routes `/cgu`, `/confidentialite`, `/mentions-legales` |
| F330 | Hiérarchie des titres irrégulière et écran profil conducteur sans en-tête ni… | Fait | `components/ui/states.tsx` (`headingLevel`), `DriverProfilePage.tsx` (en-tête), `pages/admin/AdminLayout.tsx` (h1) |
| F331 | Indicateurs d'étapes hétérogènes entre réservation et publication, libellés… | Fait | `components/feedback/StepIndicator.tsx` partagé par la réservation et la publication |
| F332 | Écran de démarrage inline ignorant le thème mémorisé : flash clair/sombre au… | Fait | `public/theme-boot.js` (thème mémorisé avant le premier rendu), `index.html`, `lib/theme.ts` |
| F333 | aria-label posé sur des <span> non interactifs et états vides en simple texte | Fait | `pages/NotificationsPage.tsx` (`sr-only`), `AppShell.tsx`, états vides avec action |
| F334 | Cartes de trajets complets à 70 % d'opacité : contraste global dégradé sur un… | Fait | `components/trip/TripCard.tsx` (contraste plein, badge), `SearchResultsPage.tsx` (filtre « places disponibles ») |
| F342 | robots.txt et sitemap absents, soft-404 en 200 et vitrine GitHub Pages en… | Non fait | Aucun `robots.txt` ni `sitemap.xml` dans `frontend/public/`, pas de `<link rel="canonical">`, vitrine GitHub Pages sans `noindex`. |
| F347 | Polices : familles de repli déclarées mais jamais définies, aucun preload, six… | Non fait | `main.tsx` importe toujours six fichiers `@fontsource` (Inter 400/500/600/700, Archivo 700/800) ; aucun `<link rel="preload">` ni `@font-face` de repli (`size-adjust`) dans `index.html`/`index.css`. |
| F348 | Manifeste installable mais incomplet : pas d'id ni de screenshots, orientation… | Partiel | `components/layout/PwaInstallBanner.tsx` (invite d'installation). Manque : `id`, `screenshots` et retrait de `orientation: 'portrait'` dans le manifeste (`vite.config.ts`). |
| F349 | navigateFallbackDenylist trop étroit : /swagger-ui, /v3/api-docs, /actuator… | Partiel | `vite.config.ts` : `navigateFallbackDenylist` couvre `/api/` et `/share/`. Manque : `/actuator`, `/swagger-ui`, `/v3/api-docs` et les URL à extension (risque limité : Swagger fermé en prod). |
| F350 | motion complet dans le chunk principal et transitions de page en mode « wait »… | Partiel | `main.tsx` (`LazyMotion` + `domAnimation` + `m`). Manque : `AnimatePresence mode="wait"` conservé dans `AppShell.tsx` (page entrante retardée). |
| F351 | Dépendance @vercel/analytics déclarée, jamais importée et inopérante hors… | Partiel | `@vercel/analytics` retiré de `package.json`. Manque : aucune mesure d'audience ni de Web Vitals (`web-vitals`, Plausible/Umami). |
| F352 | Écran de démarrage retiré avant le premier rendu React : frame blanche possible | Non fait | `main.tsx` appelle encore `document.getElementById('boot')?.remove()` avant `root.render` : frame blanche possible. |
| F353 | Cascade de requêtes sur le détail de trajet : profil et avis du conducteur… | Partiel | `dto/trip/DriverSummary` enrichi (note, nombre d'avis, identité). Manque : `pages/TripDetailPage.tsx` appelle toujours `usePublicUser(driverId)` après le trajet ; avis non différés. |
| F356 | Swagger UI et /v3/api-docs servis publiquement en production (dépôt déjà public… | Fait | `docker-compose.prod.yml` (`SPRINGDOC_ENABLED=false`), blocs Swagger retirés des deux Caddyfile, `docs/DEPLOIEMENT.md` |
| F401 | `photoUrl` accepté sans validation (PATCH /me sans @Valid) et rendu en <img>… | Fait | `dto/user/UpdateMeRequest` et `VehicleRequest` (`photoUrl` refusé tant qu'aucun stockage maîtrisé n'existe), `MeController` (`@Valid`), CSP `img-src` précise |
| F402 | Clé MapTiler exposée dans le bundle de production sans consigne documentée de… | Décision fondateur | `.env.example` (consigne de restriction d'origine), `components/trip/RouteMap.tsx` (repli schématique sur erreur de style). Reste au fondateur : restreindre la clé dans MapTiler Cloud à `https://ekuiseo.com` et créer une clé `localhost` pour le dev. |
| F403 | CSP trop large sur connect-src et img-src (`https:` générique) | Fait | `Caddyfile` et `Caddyfile.proxied` (`connect-src 'self' https://api.maptiler.com https://*.kkiapay.me`, `img-src` précis) |
| F404 | Déploiement Vercel alternatif sans aucun en-tête de sécurité et avec API… | Fait | `frontend/vercel.json` supprimé |
| F405 | Script tiers Kkiapay chargé avec accès complet au DOM et au localStorage de la… | Partiel | CSP `connect-src` restreinte, `docs/CONFORMITE.md` §5 (champs transmis au widget). Manque : jetons toujours en `localStorage` (voir F355). |
| F407 | CORS par défaut `*` avec `allowCredentials(true)` si CORS_ALLOWED_ORIGINS est… | Fait | `SecurityConfig` (`allowCredentials(false)`), `docker-compose.prod.yml` (`CORS_ALLOWED_ORIGINS` par défaut = ekuiseo.com), avertissement dans `deploy-vps.sh` |
| F411 | Double référentiel des villes : cities.ts et geo_places divergent (libellés… | Fait | `GET /api/v1/geo/places` (référentiel unique), `hooks/useGeo.ts` (dédoublonnage, repli), `api/extended.ts`, `hooks/useGeo.test.ts` |
| F413 | Rattachement des recherches a la ville la plus proche (30 km) : Seme-Podji… | Fait | V17 (villes manquantes), `service/SearchEventService` (rayon ramené à 15 km) |
| F420 | Tri et filtres appliques cote client sur les pages deja chargees : « Le moins… | Fait | `TripService`/`TripRepository` (tri et filtres serveur), `pages/SearchResultsPage.tsx` |
| F421 | Recherche de lieux sans tolerance aux fautes malgre la documentation, alias… | Fait | V17 (`pg_trgm`, index GIN, `aliases`), `GeoPlaceRepository` (repli par similarité), `service/geo/GeocodingService` |
| F422 | Les quartiers sont proposes sans leur ville de rattachement et sans adapter le… | Fait | `dto/geo/GeoPlaceResponse` (`parentName`, `kind`), `CityAutocomplete.tsx` (« Agla — Cotonou »), `lib/cities.ts` (rayon selon le type de lieu) |
| F423 | Rayons incoherents entre recherche (15 km), defaut API (5 km) et alertes (10 km) | Fait | `SearchAlert.radiusKm` (V16), `lib/cities.ts` (rayon dérivé du type de lieu et de l'axe), `TripController` (borne 50) |
| F424 | Heures affichees et saisies dans le fuseau du navigateur sans indication… | Fait | `lib/format.ts` (fuseau Africa/Porto-Novo), `lib/validation.ts`, `PublishTripPage.tsx`, `EditTripSheet.tsx`, `lib/format.test.ts` |
| F425 | Apercu de lien WhatsApp generique : pas de metadonnees Open Graph par trajet | Fait | `ShareController` (`GET /share/trips/{id}`, Open Graph par trajet), règle Caddy `@sharebots` |
| F426 | Jeu de demonstration : tous les departs de Cotonou partagent les memes… | Non fait | `docs/donnees-demo.sql` : « Cotonou, Godomey » et « Cotonou, Etoile Rouge » partagent toujours 6.3703, 2.3912 ; Godomey non renommé « Abomey-Calavi, Godomey ». |
| F432 | La verification post-deploiement (GitHub Actions et deploy-vps.sh) interroge… | Fait | `deploy-prod.yml` et `scripts/deploy-vps.sh` (sonde `/actuator/health` relayé + `/api/v1/trips/popular` JSON), `docs/DEPLOIEMENT.md` |
| F442 | Aucune mesure de couverture (JaCoCo, Vitest coverage) ni seuil : la CI ne… | Non fait | Aucun `jacoco-maven-plugin` dans `backend/pom.xml`, aucun `@vitest/coverage-v8` ni seuil dans `vitest.config.ts`, aucun rapport de couverture en CI. |
| F443 | Documentation des chiffres de tests perimee : 79 (backend/README), 95… | Partiel | `backend/README.md` mis à jour (454 + 43). Manque : `docs/RAPPORT-FONCTIONNEL.md` annonce encore 95 tests ; `CLAUDE.md` réécrit des nombres absolus (454/43/99) au lieu de « la suite complète passe ». |
| F444 | frontend/Dockerfile sans .dockerignore : `COPY . .` embarque .env.local, dist/… | Partiel | `backend/.dockerignore` et `frontend/.dockerignore`. Manque : `frontend/Dockerfile` reste sur `nginx:1.27-alpine` (root) et non `nginx-unprivileged`. |
| F445 | Durcissement conteneurs absent : pas de no-new-privileges, read_only… | Partiel | `docker-compose.prod.yml` (`no-new-privileges`, `pids` limités, sonde HTTP, `admin off` Caddy). Manque : `read_only` + `tmpfs` et `cap_drop: [ALL]`. |
| F446 | Planificateur Spring a un seul thread : les taches nocturnes et horaires… | Fait | `application.yml` (`scheduling.pool.size: 3`), `TripReminderScheduler` (marquage avant envoi), `HttpClientConfig` |
| F447 | Vitrine GitHub Pages : un second client de production hors du VPS, qui impose… | Décision fondateur | `.github/workflows/deploy-pages.yml` toujours déclenché au push ; CORS de production restreint à ekuiseo.com par défaut (la vitrine ne consomme donc plus l'API). Le fondateur doit trancher : retirer la vitrine ou l'ajouter à `CORS_ALLOWED_ORIGINS` et la conditionner à la CI. |
| F448 | deploy-vps.sh fait `git pull --ff-only` apres que le workflow a fige le commit… | Fait | `scripts/deploy-vps.sh` (`git checkout --detach $EKUISEO_SHA`, aucun `pull` quand le commit est figé) |
| F454 | Alerte de trajet : sur une URL de recherche sans paramètre type, le front force… | Fait | `dto/alert/TripAlertRequest` (`tripType` nullable), `api/extended.ts`, `pages/SearchResultsPage.tsx` |
| F455 | Enums exposées en String dans six DTO et double vocabulaire PAID/SETTLED pour… | Fait | `dto/payment/PaymentClientStatus`, `dto/auth/OtpChannel`, `GeoPlaceResponse.kind`, un seul vocabulaire SETTLED (`PayoutService`, `extended.ts`) |
| F456 | OpenAPI exposé mais types front écrits à la main ; javadoc de… | Partiel | Javadoc de `BookingQuoteRequest` corrigée ; Swagger fermé en production. Manque : génération `types.generated.ts` depuis `/v3/api-docs` comparée en CI. |
| F501 | PaymentStatusResponse : bookingId requis côté front mais absent pour un… | Fait | `dto/payment/PaymentStatusResponse` (`bookingId` nullable, `subscriptionId`, `updatedAt` réel V17, REFUNDED), `PaymentClientStatus` |
| F502 | PATCH /me : le front envoie null pour effacer email/bio, le serveur ignore null | Fait | `UserService#updateMe` (présentation vide = effacement), `dto/user/UpdateMeRequest` |
| F503 | Nullabilité déclarée côté front différente du serveur sur plusieurs champs | Fait | `dto/report/AdminReportResponse.detail` jamais null, `api/extended.ts` aligné |
| F504 | Champs renvoyés par l'API mais jamais utilisés par le front | Fait | `features/account/EarningsSection.tsx` et `pages/admin/AdminPayouts.tsx` (date réelle du virement) |
| F505 | Sérialisation directe de PageImpl (Spring Data 3.3) : forme non garantie à terme | Fait | `application.yml` (`spring.data.web.pageable.serialization-mode` explicite) |
| F506 | 429 : réponse JSON écrite à la main sans Retry-After | Partiel | `RateLimitingFilter` et `TooManyRequestsException`/`GlobalExceptionHandler` (`Retry-After`). Manque : le front (`lib/errors.ts`, `LoginPage.tsx`) ne lit ni n'affiche `Retry-After`. |
| F519 | Profil public accessible sans authentification avec nom complet, bio… | Fait | `common/Masking#lastNameInitial`, `DriverSummary`/`TripResponse`/`UserService` (initiale pour un appelant anonyme), `UserPublicController` |
| F520 | Consultations de données personnelles par les administrateurs non journalisées | Fait | `AdminUserService` (ADMIN_USERS_SEARCHED), `AdminVerificationService` (ADMIN_VERIFICATIONS_LISTED), audit filtrable par entité |
| F521 | Le cache du service worker conserve les réponses API personnelles 24 h après la… | Fait | `lib/queryClient.ts#clearApiCache` au logout, `vite.config.ts` (routes personnelles exclues du cache) |
| F522 | Journaux d'accès et applicatifs avec adresses IP sans durée de conservation… | Partiel | Caddy journalise sur stdout avec rotation Docker (`max-file: 3`), `docs/CONFORMITE.md` §3.2 le déclare. Manque : durée en jours garantie (`roll_keep_for`) et logrotate du nginx hôte vérifié. |
| F528 | Alerte limitée au jour exact, calculé en jour civil UTC (même biais que la… | Partiel | Fuseau corrigé des deux côtés (`Tz.BENIN` dans `TripService` et `SearchAlertMatchService`). Manque : option « ±1 jour » (`dateFrom`/`dateTo` restent un seul jour dans `TripAlertRequest`). |
| F529 | `tripType` @NotNull dans le DTO alors que l'entité, V6 et le matching gèrent… | Fait | `dto/alert/TripAlertRequest` (`tripType` nullable = tous modes), `api/extended.ts` |
| F531 | Validation défensive absente sur POST /trip-alerts (coordonnées non bornées… | Fait | `dto/alert/TripAlertRequest` (coordonnées bornées, `@Size(255)`, date non passée) |
| F532 | Notification de correspondance vide de sens : ni axe, ni date, ni prix dans le… | Fait | `SearchAlertMatchService` (payload : axe, date, prix, places), `pages/NotificationsPage.tsx` (résumé construit sur le payload) |
| F533 | Alerte sans date sur un trajet quotidien : rafale de notifications à chaque… | Fait | V17 (`search_alert_matches`), `SearchAlertRepository`/`SearchAlertMatchService` (une notification par alerte et trajet ou navette) |
| F534 | Aucun test unitaire ni d'intégration sur la création et le matching des alertes | Fait | `service/SearchAlertMatchServiceTest`, `service/TripAlertServiceTest`, matching PostGIS dans `TripSearchIT` |
| F535 | Modification ou annulation d'un trajet : les alertes ne sont pas rejouées, et… | Fait | `TripService#updateTrip` (matching rejoué après commit, dédoublonné), `TripRepository` (PUBLISHED avec places) |
| F541 | Les évènements d'authentification (connexion par mot de passe, rafraîchissement… | Fait | `AuthService` (OTP_REQUESTED / OTP_VERIFY_FAILED / OTP_VERIFY_SUCCEEDED / TOKEN_REFRESHED avec IP et User-Agent, `RequestContext`), V17 (`last_login_at`), `RetentionScheduler` (otp_codes) |
| F543 | OtpRateLimiter consomme le quota avant l'envoi et repart de zéro à chaque… | Fait | `OtpCodeRepository` (plafond compté en base), `OtpCodeService` (limite glissante durable), `OtpRateLimiter` (Retry-After) |
| F544 | Le back-office ne voit ni email_verified ni l'état des envois de code : le… | Fait | `dto/admin/AdminUserResponse` et `AdminUserDetailResponse` (`emailVerified`, `lastLoginAt`), `ContactCorrectionDialog` |
| F545 | OtpVerifyRequest.code n'est pas contraint à 6 chiffres : BCrypt calculé sur… | Fait | `dto/auth/OtpVerifyRequest` (`@Pattern ^[0-9]{6}$`) |
| F555 | Aucune journalisation applicative des envois de messages : investigation d'un… | Fait | `AdminOverviewService` (`heavyMessageSenders` > 50/24 h), `MessageService` (trace structurée), `MessageRepository` |
| F605 | Prérequis au correctif F602 : un compte mobile money doit prouver la possession… | Fait | `PaymentAccountService` (`verified_at` d'office si numéro de connexion, sinon `/admin/payment-accounts/{id}/verify` audité), V12, e-mail au conducteur |
| F612 | settle() accepte n'importe quel état et aucun chemin ne produit… | Fait | `PayoutService#settle` (PENDING/FAILED), `POST /admin/payouts/{id}/fail` audité |
| F613 | Aucun test sur IdentityVerificationService, AdminVerificationService et… | Fait | `service/IdentityVerificationServiceTest`, `service/admin/AdminVerificationServiceTest`, `service/PaymentAccountServiceTest`, `PayoutServiceTest` |
| L9 | Textes obsoletes sur l accueil (« numero confirme par SMS ») et espace vide… | Fait | `pages/HomeSearchPage.tsx` (texte mis à jour, axes proposés issus du serveur, rien affiché s'il n'y en a aucun) |

## 4. Décisions et actions du fondateur

Constats au statut « Décision fondateur » : L1, F431, F438, F440, F447, F402 (6). Pour chacun, le dépôt est prêt ; il manque un choix ou un geste hors dépôt. Les thèmes sans constat dédié mais bloquants pour l ouverture (SMS, juridique, secrets, pièces, 2FA) sont rappelés à la suite, avec le constat auquel ils se rattachent.

### Surveillance et alertes

- **L1, F438** — Souscrire une sonde HTTP externe (UptimeRobot, Better Stack ou l Uptime Kuma déjà sur l hôte) sur `https://ekuiseo.com/actuator/health` (relayé par Caddy, réponse `{"status":"UP"}`) et sur `https://ekuiseo.com/api/v1/trips/popular`, depuis plusieurs résolveurs, avec alerte e-mail ou Telegram ; ajouter l alerte « `backups/last-success` plus vieux que 36 h » décrite dans `docs/EXPLOITATION.md`. Vérifier une fois `dig +short ekuiseo.com A @1.1.1.1` (une seule adresse) comme le demande la check-list de `docs/DEPLOIEMENT.md`.
- **F440** — Choisir le collecteur d erreurs frontend (Sentry plan gratuit ou GlitchTip auto-hébergé sur le VPS), créer le projet, renseigner l URL attendue par `lib/monitoring.ts` dans le build (`VITE_*`) et ajouter le domaine du collecteur à `connect-src` dans les deux Caddyfile ; documenter dans `docs/EXPLOITATION.md` où lire les erreurs.

### Sauvegardes hors site

- **F431** (et le volet hors site de **F127**) — Sur le VPS : `rclone config` vers un bucket S3-compatible ou Backblaze B2, puis renseigner `BACKUP_REMOTE` (et `BACKUP_REMOTE_KEEP_DAYS`) dans `/opt/ekuiseo/.env`. `scripts/backup.sh` envoie alors chaque dump hors site et échoue explicitement si l envoi échoue ; le cron quotidien et l exercice de restauration mensuel sont déjà installés par `scripts/deploy-vps.sh`.

### Cartographie

- **F402** — Dans MapTiler Cloud, restreindre la clé de production aux origines `https://ekuiseo.com` et `https://www.ekuiseo.com`, créer une clé distincte limitée à `localhost` pour le développement, puis renseigner `VITE_MAP_STYLE_URL` (consigne dans `.env.example`). Tant que la variable est vide, `RouteMap` dessine le tracé schématique et aucune donnée ne part chez MapTiler (`docs/CONFORMITE.md` §5).

### Vitrine GitHub Pages

- **F447** — Trancher : (a) retirer `.github/workflows/deploy-pages.yml` (un lien vers ekuiseo.com suffit) ou (b) conserver la vitrine en la conditionnant à une CI réussie (`workflow_run`) et en ajoutant `https://akouedekon.github.io` à `CORS_ALLOWED_ORIGINS` sur le VPS. Aujourd hui le CORS de production est restreint à ekuiseo.com : la vitrine ne consomme plus l API et publie l application sur un domaine tiers sans `noindex` (voir aussi F342).

### Rappels hors constat « Décision fondateur » (bloquants avant ouverture)

- **SMS** (rattaché à F107, F234, F605) — **Tranché le 2026-09-07 : l e-mail est le seul canal sortant**, aucun fournisseur SMS ne sera branché. Les notifications critiques partent toujours à l adresse vérifiée, les autres selon la préférence e-mail (vraie par défaut, V18). Conséquence : un compte mobile money sur un numéro différent du numéro de connexion ne peut être vérifié que par l administration.
- **Juridique** (rattaché à F509, F510, F518) — Faire rédiger et valider par un juriste béninois les CGU, la politique de confidentialité et les mentions légales servies sur `/cgu`, `/confidentialite`, `/mentions-legales` (contenu versionné côté front, version dans `ekuiseo.terms.version`) ; trancher le statut du covoiturage rémunéré et l éventuel agrément (`docs/CONFORMITE.md`, point ouvert de `CLAUDE.md`). Signer les accords de sous-traitance (Kkiapay, relais SMTP, OVH).
- **Secrets** (rattaché à F027, L2, F017) — Vérifier sur le VPS que `JWT_SECRET`, `KKIAPAY_*`, `CORS_ALLOWED_ORIGINS=https://ekuiseo.com,https://www.ekuiseo.com` et `MAIL_MODE=smtp` sont renseignés dans `/opt/ekuiseo/.env` (le déploiement refuse désormais une clé d exemple et le backend refuse `MAIL_MODE=log` avec des paiements réels) ; faire tourner la clé JWT si elle a été exposée avant la phase 0.
- **Stockage des pièces d identité** (rattaché à F033, F401) — Décider s il y aura téléversement de la photo de pièce et où elle sera stockée (bucket chiffré, durée, accès). Sans décision, `photoUrl` reste refusé et seul le numéro (tronqué après décision) est conservé.
- **Authentification forte des administrateurs** (aucun constat dédié) — Le back-office est protégé par le parcours OTP e-mail et le rôle ADMIN ; décider si une seconde vérification (code SMS ou TOTP) est exigée pour `ROLE_ADMIN` avant l ouverture, et sur quel support.

## 5. Reste à faire dans le dépôt

Constats **Non fait** : F437, F426, F347, F352, F342, F442 (6). Constats **Partiel** : F027, F441, F126, F237, F024, F355, F547, F435, F353, F506, F014, F444, F522, F033, F528, F350, F348, F349, F020, F240, F351, F443, F456, F032, F405, F445, F256 (29). Effort : S = moins d une demi-journée, M = un à deux jours, L = plus.

### Bug fonctionnel visible (à traiter en premier)

| Id | Statut | Effort | À faire |
|---|---|---|---|
| F126 | Partiel | S | Ajouter dans `TripReminderScheduler` une notification au conducteur avec le nombre de passagers confirmés. |

### Argent et paiement

| Id | Statut | Effort | À faire |
|---|---|---|---|
| F441 | Partiel | M | Faire re-vérifier par `GET /payments/{id}` un paiement INITIATED dont `provider_tx_id` est connu (reprise après coupure), ou persister le `transactionId` du widget côté front pour rejouer `/confirm`. |
| F014 | Partiel | M | Extraire `PaymentPlanAssembler` / `BookingDetailAssembler` et `RecurringTripDetector` de `BookingService` (refactoring sans changement de comportement, tests existants comme filet). |

### Sécurité et durcissement

| Id | Statut | Effort | À faire |
|---|---|---|---|
| F355 | Partiel | L | Déplacer le refresh token dans un cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth/refresh` (garder l access token en mémoire), avec CSRF et `CORS_ALLOWED_ORIGINS` fixé ; à défaut ramener `JWT_REFRESH_TTL_DAYS` à 7. Règle aussi F405. |
| F405 | Partiel | L | Même chantier que F355 (jetons hors `localStorage`) ; la CSP et la mention des champs transmis au widget dans `docs/CONFORMITE.md` sont déjà en place. |
| F027 | Partiel | S | Poser `issuer` (`ekuiseo-api`) et `audience` à l émission dans `JwtService` et les vérifier au parse. |
| F445 | Partiel | S | Ajouter `read_only: true` + `tmpfs: [/tmp]` et `cap_drop: [ALL]` (+ `NET_BIND_SERVICE` pour Caddy/nginx) dans `docker-compose.prod.yml`. |
| F444 | Partiel | S | Passer `frontend/Dockerfile` sur `nginxinc/nginx-unprivileged:1.27-alpine` (port 8080) et adapter Caddy et le healthcheck. |
| F024 | Partiel | S | Versionner le bloc 443 complet dans `deploy/nginx/ekuiseo.com.conf` avec `server_tokens off` et le déployer via `scripts/deploy-vps.sh`. |
| F547 | Partiel | S | Abaisser `client_max_body_size` à 1m sur `/api` dans `deploy/nginx/ekuiseo.com.conf` et ajouter `request_body { max_size 1MB }` dans les deux Caddyfile. |
| F033 | Partiel | M | Chiffrer la colonne `identity_verifications.document_number` au repos (pgcrypto ou chiffrement applicatif) ou n en garder qu un HMAC + 4 caractères ; n afficher le numéro que sur la fiche détail avec entrée d audit. |
| F032 | Partiel | S | Documenter dans `docs/CONFORMITE.md` le choix de conserver le 404 sur `/otp/request` (ou passer en 202 neutre) ; le front dépend aujourd hui du 404 pour proposer l inscription. |
| F020 | Partiel | M | Regrouper la configuration dans une `@ConfigurationProperties(prefix = "ekuiseo")` validée et fusionner `ekuiseo.sms.otp` dans `ekuiseo.otp`. |

### Exploitation et déploiement

| Id | Statut | Effort | À faire |
|---|---|---|---|
| F437 | Non fait | M | Publier les images sur ghcr.io depuis `ci.yml` (tag SHA + `main`), faire consommer `EKUISEO_TAG` par `docker-compose.vps.yml` et remplacer `up -d --build` par `pull` + `up -d` dans `scripts/deploy-vps.sh` ; le retour arrière devient un changement de tag. |
| F522 | Partiel | S | Fixer une durée de conservation des journaux Caddy (`roll_keep_for 720h` sur fichier, ou `max-file`/`max-size` alignés sur 30 jours) et vérifier le logrotate du nginx hôte ; reporter la valeur dans `docs/CONFORMITE.md` §3.2. |
| F443 | Partiel | S | Corriger `docs/RAPPORT-FONCTIONNEL.md` (95 tests) et remplacer les nombres absolus de `CLAUDE.md` par « la suite complète passe ». |

### Tests et qualité

| Id | Statut | Effort | À faire |
|---|---|---|---|
| F442 | Non fait | S | Ajouter `jacoco-maven-plugin` (report en `verify`, seuil bas à relever) et `@vitest/coverage-v8` avec `coverage.thresholds` ; publier les rapports en artefacts CI. |
| F435 | Partiel | L | Suite Playwright de quatre parcours (OTP via `MAIL_MODE=log`, recherche, réservation CASH, publication) contre `docker compose up` avec `KKIAPAY_MODE=stub`, en CI. |
| F456 | Partiel | M | Générer `types.generated.ts` depuis `/v3/api-docs` (openapi-typescript) et le comparer en CI aux types écrits à la main. |
| F240 | Partiel | S | Ajouter `knip` (ou `oxlint --unused-exports`) au lint CI et supprimer les exports morts restants. |
| F426 | Non fait | S | Corriger `docs/donnees-demo.sql` : coordonnées réelles des lieux (Godomey, Étoile Rouge, Agla, Akpakpa…) issues de `geo_places` V17, et « Abomey-Calavi, Godomey ». |

### Frontend, PWA et performance

| Id | Statut | Effort | À faire |
|---|---|---|---|
| F342 | Non fait | S | Ajouter `public/robots.txt` (Disallow des écrans privés, `Sitemap:`), un `sitemap.xml` statique, `<link rel="canonical">` par écran (via `PageMeta`) et `noindex` quand `VITE_BASE_PATH ≠ /`. |
| F347 | Non fait | S | Précharger Inter 400 et Archivo 700, générer les `@font-face` de repli avec `size-adjust`/`ascent-override` (fontaine ou capsize), passer à Inter variable et supprimer les `.woff`. |
| F352 | Non fait | S | Retirer `document.getElementById("boot")?.remove()` de `main.tsx` avant `root.render` et laisser une règle CSS masquer `#boot` après le premier commit (ou `useEffect` du composant racine). |
| F350 | Partiel | S | Passer `AnimatePresence` d `AppShell.tsx` en `mode="popLayout"` (ou `sync`) pour monter la page entrante immédiatement, ou précharger les données au clic. |
| F353 | Partiel | S | Lire la note, le nombre d avis et l identité depuis `TripResponse.driver` dans `TripDetailPage.tsx` au lieu de `usePublicUser`, et ne charger les avis qu à l ouverture de la section. |
| F348 | Partiel | S | Compléter le manifeste (`vite.config.ts`) : `id: "/"`, deux `screenshots`, retrait d `orientation`, seconde balise `theme-color` pour le sombre. |
| F349 | Partiel | S | Étendre `navigateFallbackDenylist` à `/actuator`, `/swagger-ui`, `/v3/api-docs` et aux URL à extension. |
| F351 | Partiel | S | Brancher `web-vitals` (LCP/INP/CLS) vers un endpoint backend ou un Plausible/Umami auto-hébergé (CSP à ajuster). |
| F506 | Partiel | S | Lire `Retry-After` dans `lib/errors.ts` (`ApiError.retryAfter`) et l afficher sous le champ de code (`LoginPage.tsx`) avec `useCountdown`. |
| F256 | Partiel | S | Ajouter dans `PreferencesSection.tsx` un `SelectField` « Pendant le trajet : calme / ça dépend / bavard » et la langue, branchés sur `PATCH /me/preferences`. |

### Back-office

| Id | Statut | Effort | À faire |
|---|---|---|---|
| F237 | Partiel | M | Paginer la recherche d utilisateurs (`AdminUserService#search`, `Page<T>` avec `totalElements`) et la liste des lots (`PayoutService#listAllForAdmin`), afficher « x sur N » dans `DataTable`. |

### Alertes de recherche

| Id | Statut | Effort | À faire |
|---|---|---|---|
| F528 | Partiel | M | Exposer `dateFrom`/`dateTo` dans `TripAlertRequest` et proposer « Jour exact / ±1 jour / Toutes dates » dans la feuille de création. |

## 6. Bilan

- Fait : 308 — Partiel : 29 — Non fait : 8 — Décision fondateur : 6 — Réfuté/obsolète : 0.
- Par sévérité (Fait / total) : P0 0/1, P1 65/67, P2 148/162, P3 95/121.
- Aucun constat n est réfuté : chaque point de l audit correspondait à un défaut réel à la date de l audit.
- Les huit « Non fait » sont bornés : deux bugs fonctionnels rapides (F451 export CSV, F046 textes d annulation), une chaîne de livraison d images (F437), la couverture de tests (F442), le SEO (F342), les polices (F347), l écran de démarrage (F352) et le jeu de démonstration (F426).
