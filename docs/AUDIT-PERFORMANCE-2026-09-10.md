# Audit de performance — 10 septembre 2026

Audit en lecture seule (services, repositories, migrations V1–V25, tâches planifiées, front, service
worker), mené pendant la refonte « production-grade ». Contexte : `open-in-view: false`,
`hibernate.default_batch_fetch_size: 50` (les relations `@ManyToOne` des mappers sont chargées par
lots, donc **pas** de N+1 dans les mappers) ; Hikari `maximum-pool-size: 10`. Les index géographiques
GIST (`trips`, `trip_stops`) sont présents et corrects.

Légende : ✅ fait · ⏳ à faire (ordre de rendement ci-dessous).

## 1. Requêtes N+1 (appels de repository dans une boucle)

| Priorité | Endroit | Problème | Correctif | État |
|---|---|---|---|---|
| P1 | `GET /api/v1/bookings` (`BookingService.toDetail`) | 1 + 3N requêtes : messages non lus, avis, résumé de remboursement par réservation ; liste non paginée | trois requêtes agrégées sur la page (`in :ids`), `toDetail` pure, pagination 20 | ⏳ |
| P1 | `GET /api/v1/me/conversations` (`MessageService.toSummary`) | 1 + 2N, non paginé, **rafraîchi toutes les 30 s par tout compte connecté sur tout écran** (badge) | requêtes agrégées (`countUnreadByConversationIds`, `findLastMessages`), endpoint dédié `GET /me/messages/unread-count`, polling du badge 60 s | ⏳ |
| P2 | Signalements admin (`ReportService.toAdminResponse`) | `countOthersAgainstTarget` + `findById(booking)` par ligne | agrégation par page, `findAllById` | ⏳ |
| P2 | Vérifications d'identité admin | 2 requêtes par dossier, liste non paginée | pagination + pièces chargées en une requête | ⏳ |
| P2 | `GET /api/v1/me/recurring-trips` | 2 requêtes par axe, en seq scan (pas d'index `(origin_label, dest_label)`) | index + une requête paginée | ⏳ |
| P2 | Lot de reversement (`PayoutService.runWeeklyBatch`) | `NOT IN (sous-requête)` rejoué par conducteur, sans borne temporelle | `NOT EXISTS`, une passe triée par conducteur, borne `from` | ⏳ |
| P2 | `AuditService.log` en `REQUIRES_NEW` dans des boucles | seconde connexion par écriture, pool de 10 vite saturé | `maximum-pool-size` ≥ 20 ou `saveAll` hors boucle | ⏳ |

## 2. Index manquants (proposition V29)

| Priorité | Table / colonnes | Sert |
|---|---|---|
| P1 | `bookings (created_at)` | toutes les agrégations du back-office (`/admin`, liquidité, rétention) : aujourd'hui des seq scans complets |
| P1 | `trips (origin_label, dest_label, departure_at) WHERE status='PUBLISHED'` | trajets récurrents du passager |
| P1 | `messages (conversation_id, sender_id) WHERE read_at IS NULL` | compteur de non-lus (requête la plus fréquente) |
| P1 | `messages (conversation_id, created_at DESC)` | dernier message d'une conversation |
| P2 | `bookings (trip_id, status)` ; `bookings (passenger_id, status)` | liste des passagers, remplissage, cascade de suspension |
| P2 | `trips (created_at)` ; `trips (driver_id, created_at) WHERE parent_trip_id IS NULL` | séries et rétention conducteur |
| P2 | `payments (booking_id, status, created_at DESC)` ; `payments (provider, created_at)` | reversements, conversion, échecs par opérateur |
| P2 | `reports (booking_id) WHERE booking_id IS NOT NULL` ; `reports (status, created_at DESC)` | dossiers « conducteur absent », file de modération |
| P2 | `driver_payouts (status, requested_at DESC)` ; `identity_verifications (status, submitted_at)` | back-office |
| P2 | `users` GIN `pg_trgm` sur l'expression concaténée nom/prénom/téléphone/e-mail | recherche libre admin (à coupler à la réécriture de `UserRepository.search`) |
| P3 | `notifications (created_at)` ; `trip_positions (recorded_at)` ; `audit_log (action, created_at DESC)` ; `refresh_tokens (absolute_expires_at)` | purges nocturnes et journal |

À corriger aussi : `idx_trips_reminder_pending` ne couvre que `PUBLISHED` alors que le rappel cible aussi `FULL`.

Migration proposée (`V29__performance_indexes.sql`, idempotente, sans `CONCURRENTLY`) : tous les index P1/P2 ci-dessus suivis d'un `ANALYZE` des tables concernées. Sur une base volumineuse, prévoir une fenêtre : chaque `CREATE INDEX` bloque les écritures de sa table le temps de la construction.

## 3. Pagination et bornes

- ⏳ `GET /api/v1/bookings`, `GET /api/v1/me/conversations`, `GET /api/v1/admin/verifications`, `GET /api/v1/admin/payments?status=ALL`, `GET /api/v1/admin/payment-accounts`, `GET /api/v1/me/payouts` : non paginés → `Page` avec `Paging.of(...)`.
- ⏳ Recherche géographique : plafonner `page` à ~20 et ne calculer le `count` qu'en page 0 (le front utilise « Voir plus »).
- ✅ Bornés et légitimes : `/geo/*`, `/trips/popular`, `/trips/nearby`, `/trips/{id}/stops`, `/me/vehicles`, `/trips/{id}/bookings`.

## 4. Tâches planifiées

- ⏳ `TripLifecycleScheduler` : clôture des réservations `CONFIRMED` par balayage de `bookings` (statut dominant) : partir des trajets ou passer en `UPDATE` de masse.
- ⏳ `PayoutScheduler` : borne temporelle basse + `NOT EXISTS`.
- ⏳ `RetentionScheduler` : purges de `notifications` et `trip_positions` sans index sur la colonne balayée (index P3).
- ✅ `TripReminderScheduler`, `PaymentHousekeepingScheduler`, `SubscriptionLifecycleScheduler`, `AuthHousekeepingScheduler`, `RecurrenceService` : correctement servis.

## 5. Frontend et PWA

- ✅ Découpage de bundle excellent : MapLibre chargé à l'ouverture de la carte et hors precache, Recharts confiné au tableau de bord admin, `motion` en mode allégé, cache TanStack réglé finement, service worker sûr (aucune réponse authentifiée mise en cache).
- ⏳ Ne précharger le groupe de pages « connecté » qu'avec une session ouverte (`App.tsx`), comme pour le groupe admin : évite le double téléchargement du parcours connecté pour un visiteur anonyme.
- ⏳ Polling du badge de messages toutes les 30 s sur tous les écrans : endpoint `unread-count` + 60 s.
- ⏳ Mineurs : dimensions sur les aperçus de pièces d'identité, six graisses de police précachées à vérifier.

## Les cinq changements les plus rentables, dans l'ordre

1. Dé-N+1 de `/me/conversations` + endpoint `unread-count` + index `messages`.
2. Dé-N+1 + pagination de `/bookings`.
3. `NOT EXISTS` et borne temporelle dans le lot de reversement.
4. Préchargement « connecté » conditionné à la session.
5. Pagination des trois listes admin restantes.
