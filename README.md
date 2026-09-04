# Ekuiseo — Covoiturage Bénin

**Ekuiseo** (« route » en fon) est un squelette de plateforme de covoiturage pour le Bénin,
couvrant deux usages :

- **Interurbain** : trajets longue distance planifiés (Cotonou–Parakou, Cotonou–Bohicon,
  Cotonou–Natitingou, Cotonou–Porto-Novo, et transfrontalier Cotonou–Lomé).
- **Quotidien** : trajets domicile-travail récurrents intra-urbains (Cotonou, Porto-Novo,
  Abomey-Calavi), avec récurrence hebdomadaire.

Paiement en FCFA (XOF, montants entiers) : par défaut, seul un **acompte** est prélevé en
ligne via mobile money (agrégateur **Kkiapay** : MTN MoMo, Moov Money, Celtiis Cash), le
solde étant réglé en espèces au conducteur pendant le trajet (`MOMO_DEPOSIT`) ; un passager
peut aussi tout régler en ligne (`MOMO_FULL`) ou tout régler en espèces (`CASH`, sans aucun
prélèvement ni commission plateforme).

> **Statut** : squelette de démarrage fonctionnel sur les cas nominaux, pensé pour être
> complété. Voir la section [Prochaines étapes](#prochaines-étapes) ci-dessous.
>
> ⚠️ **Le backend n'a jamais été compilé** dans l'environnement où ce dépôt a été
> généré (Maven Central y était bloqué par la politique réseau du bac à sable). La
> **toute première chose** à faire, avant quoi que ce soit d'autre — dev, CI ou
> déploiement — est de vérifier que `cd backend && mvn -B verify` passe sur une
> machine avec un accès réseau normal. Voir `docs/DEPLOIEMENT.md`.

## Architecture

```
                    ┌──────────────────────────────────────────┐
                    │            Internet (HTTPS)               │
                    └───────────────────┬────────────────────────┘
                                        │
                                 ┌──────▼──────┐
                                 │    Caddy     │  TLS auto (Let's Encrypt)
                                 │ reverse proxy│  en-tetes de securite
                                 └──┬────────┬──┘
                          /api/*   │        │   / (repli SPA)
                        ┌──────────▼─┐   ┌──▼──────────┐
                        │   backend   │   │  frontend    │
                        │ Spring Boot │   │ nginx (build │
                        │  (Java 17)  │   │  React/Vite) │
                        └──────┬──────┘   └──────────────┘
                               │
                        ┌──────▼──────┐
                        │   postgis    │  jamais expose
                        │ PostgreSQL 16│  (reseau interne uniquement)
                        └──────────────┘
```

En développement (`docker-compose.yml`), le frontend tourne en serveur Vite avec
rechargement à chaud et Caddy n'est pas utilisé (accès direct aux ports des services).
En production (`docker-compose.prod.yml` + `Caddyfile`), Caddy est le seul point
d'entrée public ; la base de données n'est accessible depuis aucun réseau public.

## Stack technique

| Composant | Choix |
|---|---|
| Backend | Java 17, Spring Boot 3.3.4, Maven, Spring Security + JWT (jjwt), Spring Data JPA, Bean Validation, MapStruct, springdoc-openapi |
| Base de données | PostgreSQL 16 + PostGIS, migrations Flyway |
| Frontend | React 19 + TypeScript + Vite, TanStack Query v5, React Router v7, Tailwind CSS v4, Zod, react-hook-form |
| Infra dev | docker-compose (postgis + backend + frontend Vite + adminer) |
| Infra prod | docker-compose.prod.yml (postgis + backend + frontend statique + Caddy, TLS auto) |
| CI | GitHub Actions (`.github/workflows/ci.yml`) : build+tests backend, lint+build frontend, build d'images sur `main` |

## Démarrage en une commande

```bash
cp .env.example .env       # puis completer .env, voir le tableau plus bas
docker compose up --build  # postgis + backend + frontend (dev) + adminer
```

- Frontend (Vite, rechargement à chaud) : http://localhost:5173
- Backend (Swagger UI : `/swagger-ui.html`) : http://localhost:8080
- Adminer (inspection de la base) : http://localhost:8081 (serveur `postgis`,
  identifiants de `.env`)

Voir `make help` pour les raccourcis équivalents (`make dev`, `make test`, `make seed`,
`make backup`, `make deploy`, `make logs`, `make clean`), et
[`docs/DEPLOIEMENT.md`](./docs/DEPLOIEMENT.md) pour la mise en production sur un VPS.

## Structure du dépôt

```
ekuiseo/
├── backend/                    # API Spring Boot (bj.ekuiseo.api)
│   ├── src/main/java/bj/ekuiseo/api/
│   │   ├── domain/             # Entites JPA + enums
│   │   ├── domain/enums/
│   │   ├── dto/                # DTO request/response (records)
│   │   ├── repository/         # Spring Data JPA (dont la recherche geospatiale)
│   │   ├── mapper/             # Mappers MapStruct entite <-> DTO
│   │   ├── service/            # Logique metier (reservations, paiement, recurrence...)
│   │   ├── security/           # JWT, UserDetails, filtre d'authentification
│   │   ├── config/             # Securite, OpenAPI
│   │   ├── common/             # MoneyUtils, exceptions, gestion d'erreurs RFC 7807
│   │   └── web/controller/     # Controleurs REST
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/       # V1__init.sql (schema + PostGIS + triggers)
│   ├── src/test/java/...       # Tests unitaires + integration (Testcontainers)
│   └── Dockerfile
├── frontend/                   # SPA React + TypeScript
│   ├── src/
│   │   ├── api/                # Client HTTP type + types miroirs des DTO
│   │   ├── hooks/               # Hooks TanStack Query par ressource
│   │   ├── components/          # Button, Input, Card, Badge, Avatar, RatingStars...
│   │   ├── pages/                # Ecrans (recherche, resultats, detail, publication...)
│   │   └── lib/                  # queryClient (persistance), format, villes
│   ├── nginx.conf
│   └── Dockerfile
├── docs/
│   ├── DEPLOIEMENT.md          # Du VPS nu a la production
│   ├── EXPLOITATION.md         # Sauvegardes, secrets, mises a jour, incidents
│   ├── CONFORMITE.md           # Loi n 2017-20 (code du numerique du Benin), APDP
│   ├── LANCEMENT.md            # Checklist avant ouverture au public
│   └── donnees-demo.sql        # Jeu de donnees de demonstration (Benin)
├── scripts/
│   ├── backup.sh               # Sauvegarde pg_dump horodatee + retention
│   ├── restore.sh              # Restauration (avec confirmation explicite)
│   ├── deploy.sh                # git pull + build + migration + rollback si echec
│   └── seed-demo.sh            # Charge docs/donnees-demo.sql
├── .github/workflows/ci.yml    # Build+tests backend, lint+build frontend, images
├── docker-compose.yml          # Developpement
├── docker-compose.prod.yml     # Production
├── Caddyfile                   # Reverse proxy + TLS + en-tetes de securite
├── Makefile                    # make dev|build|test|seed|backup|deploy|logs|clean
├── .env.example
└── README.md
```

## Prérequis

- Java 17 (JDK)
- Maven 3.9+
- Node.js 22+ / npm 10+
- Docker + Docker Compose (pour l'exécution complète, PostGIS inclus)

Voir la section [Démarrage en une commande](#démarrage-en-une-commande) plus haut pour
le développement ; pour la production sur un VPS, voir
[`docs/DEPLOIEMENT.md`](./docs/DEPLOIEMENT.md) (`docker-compose.prod.yml` + `Caddyfile`).

## Variables d'environnement

Détail complet, avec pour chaque variable une explication et où obtenir sa valeur :
voir [`.env.example`](./.env.example). Résumé :

| Variable | Description |
|---|---|
| `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_PORT` | Connexion PostgreSQL/PostGIS |
| `JWT_SECRET` | Clé HMAC (HS256) pour signer les JWT, ≥ 32 caractères |
| `JWT_ACCESS_TTL_MIN`, `JWT_REFRESH_TTL_DAYS` | Durées de vie des jetons |
| `SERVICE_FEE_RATE`, `FEE_ROUNDING_STEP` | Taux de commission et palier d'arrondi des frais de service |
| `BOOKING_DEPOSIT_BASE_FCFA` | Plancher (FCFA) de l'acompte prélevé en ligne pour une réservation `MOMO_DEPOSIT` ; le solde est réglé en espèces au conducteur à bord |
| `KKIAPAY_MODE` | `stub` (paiement toujours simulé réussi, jamais en prod) ou `http` (appel réel à Kkiapay) |
| `KKIAPAY_PUBLIC_KEY`, `KKIAPAY_PRIVATE_KEY`, `KKIAPAY_SECRET` | Identifiants d'API Kkiapay (tableau de bord Kkiapay) |
| `KKIAPAY_WEBHOOK_SECRET` | Secret de signature du webhook Kkiapay (distinct des clés d'API ci-dessus) |
| `KKIAPAY_SANDBOX` | `true` en bac à sable, `false` avec des clés de production |
| `SMS_MODE`, `SMS_HTTP_URL`, `SMS_PROVIDER_KEY` | Envoi des OTP : `log` (journalisé, dev uniquement) ou `http` (fournisseur SMS réel) |
| `OTP_MAX_ATTEMPTS`, `OTP_RATE_LIMIT_MAX_REQUESTS`, `OTP_RATE_LIMIT_WINDOW_MINUTES` | Anti-abus sur la vérification/l'envoi des codes OTP |
| `SEARCH_ALERT_RADIUS_KM` | Rayon (km) de déclenchement des alertes de recherche |
| `PAYOUT_MIN_THRESHOLD_FCFA` | Seuil minimal avant reversement à un conducteur |
| `SUBSCRIPTION_PRICE_FCFA` | Prix mensuel de l'abonnement conducteur (commission ramenée à 0 %) |
| `RATE_LIMIT_AUTH_MAX`/`_WINDOW_SECONDS`, `RATE_LIMIT_WEBHOOK_MAX`/`_WINDOW_SECONDS` | Anti-abus sur `/auth/**` et le webhook Kkiapay |
| `SERVER_PORT` | Port d'écoute du backend |
| `SPRING_PROFILES_ACTIVE` | Informationnel : aucun profil Spring n'existe encore côté backend (un seul `application.yml`) |
| `VITE_API_URL` | URL de l'API utilisée par le frontend au build (vide en production : chemins relatifs derrière Caddy) |
| `VITE_DEMO_FALLBACK` | **Doit valoir `false` en production** — sinon une panne réelle de l'API est masquée par des données factices (voir `.env.example`) |
| `DOMAIN`, `ACME_EMAIL` | Production uniquement : domaine public et courriel pour le certificat TLS Let's Encrypt (Caddy) |

## Développement local (sans Docker)

### Backend

```bash
cd backend
# Une instance PostgreSQL + PostGIS doit tourner localement (voir docker-compose.yml
# pour ne demarrer que ce service : docker compose up postgis)
mvn -DskipTests package
mvn test
java -jar target/ekuiseo-api.jar
```

### Frontend

```bash
cd frontend
npm install
npm run dev       # serveur de developpement Vite (http://localhost:5173)
npm run build     # build de production dans dist/
```

## API

Toutes les routes sont préfixées par `/api/v1`. Documentation interactive générée par
springdoc-openapi : `GET /swagger-ui.html` une fois le backend démarré.

Principaux groupes d'endpoints : `auth` (inscription, OTP, connexion, refresh), `me`
(profil, véhicules), `trips` (publication, recherche géospatiale, réservation, avis),
`bookings` (réservations, annulation, messagerie), `payments/kkiapay` (initiation,
webhook), `users/{id}/reviews`, `notifications`.

Toutes les erreurs sont renvoyées au format **RFC 7807** (`application/problem+json`).

## Règles métier implémentées

> Liste non exhaustive : le backend s'est enrichi rapidement (rôles, modération,
> abonnement conducteur, lots de reversement, vérification d'identité, préférences,
> paiement fractionné...
> — voir les migrations `V2` à `V7` sous `backend/src/main/resources/db/migration/`,
> chacune commentée avec le numéro de règle métier qu'elle sert). Les points ci-dessous
> restent exacts mais ne couvrent que le socle initial.

1. **Réservation atomique** : la décrémentation de `seats_available` se fait via un
   `UPDATE ... WHERE seats_available >= :seats` (voir `TripRepository#decrementSeatsIfAvailable`),
   garantissant qu'aucune surréservation n'est possible même en cas de requêtes
   concurrentes sur la dernière place. Testé par
   `BookingServiceConcurrencyTest` (deux réservations simultanées sur la dernière place).
2. **Expiration des réservations non payées** : une tâche planifiée
   (`BookingExpiryScheduler`, toutes les minutes) annule les réservations
   `PENDING_PAYMENT` de plus de 20 minutes et libère les places.
3. **Idempotence du webhook Kkiapay** : identifiée par `(provider, provider_tx_id)`
   (contrainte unique en base) ; un même événement reçu plusieurs fois ne confirme la
   réservation qu'une seule fois.
4. **Frais de service** : 8 % du montant, arrondis aux 5 FCFA supérieurs
   (`MoneyUtils.computeServiceFee`), testé unitairement (`MoneyUtilsTest`).
5. Un conducteur ne peut pas réserver son propre trajet.
6. Les trajets `QUOTIDIEN` avec `recurrence_rule` génèrent leurs occurrences des 14
   prochains jours via une tâche planifiée quotidienne (`RecurrenceService`).
7. **Politique d'annulation passager** (`CancellationPolicy`, testée unitairement) :
   gratuite si > 24 h avant le départ, 50 % retenu en deçà, 100 % après l'heure de
   départ.
8. **Paiement fractionné** (migration `V7`, `FeePolicy#computeDepositAmount`) : en mode
   `MOMO_DEPOSIT` (défaut), seul un acompte — `max(BOOKING_DEPOSIT_BASE_FCFA, frais de
   service)`, arrondi aux `FEE_ROUNDING_STEP` FCFA supérieurs, plafonné au montant total —
   est prélevé en ligne ; le solde (`balance_due_on_board`) est réglé en espèces au
   conducteur pendant le trajet. Le reversement conducteur (`driver_payouts`) ne porte
   donc que sur ce qui a été réellement encaissé en ligne, jamais sur le solde en espèces.

## Recherche géospatiale

La recherche (`GET /api/v1/trips/search`) utilise `ST_DWithin` sur l'origine **et** la
destination (colonnes `geography(Point,4326)` maintenues par triggers PostgreSQL à partir
de colonnes `lat`/`lng` simples côté entité JPA — voir le commentaire en tête de
`V1__init.sql`), triée par distance cumulée ajustée de la note du conducteur.

## Tests

- `MoneyUtilsTest` : calcul des frais de service (arrondi aux 5 FCFA supérieurs).
- `CancellationPolicyTest` : les trois paliers de remboursement.
- `BookingServiceConcurrencyTest` : deux réservations simultanées sur la dernière place
  (un seul succès, l'autre reçoit un conflit), places jamais négatives.
- `TripSearchIntegrationTest` : test d'intégration Spring Boot + Testcontainers PostGIS
  sur la recherche géospatiale. **Désactivé par défaut** (voir le commentaire dans le
  fichier) : l'environnement où ce squelette a été généré n'a pas accès au registre
  Docker Hub. Retirez `@Disabled` pour l'exécuter dans un environnement avec accès
  Docker complet — les runners GitHub Actions standard (utilisés par
  `.github/workflows/ci.yml`) ont un accès Docker complet et devraient exécuter ce
  test sans configuration supplémentaire une fois `@Disabled` retiré.

## Prochaines étapes

Ce squelette couvre les cas nominaux ; en vue d'une mise en production, il reste
notamment à :

- **Vérification de build** : exécuter `mvn -DskipTests package` et `mvn test` dans un
  environnement avec accès à Maven Central — non vérifiable dans l'environnement
  sandbox utilisé pour générer ce squelette (réseau restreint). C'est justement ce que
  fait `.github/workflows/ci.yml` à chaque push/pull request.
- **Intégration Kkiapay** : `KkiapayHttpGateway` appelle réellement l'API (vérification,
  remboursement) et `KkiapayConfig` bascule dessus via `KKIAPAY_MODE=http` — mais reste, de
  l'aveu de sa propre javadoc, à valider contre la documentation officielle Kkiapay avant
  la première mise en production réelle (voir `docs/LANCEMENT.md`).
- **Fournisseur SMS** : `HttpSmsGateway` appelle une URL HTTP générique configurable
  (`SMS_MODE=http`) — mais son contrat exact (champs du corps JSON, en-têtes
  d'authentification) reste à adapter au fournisseur réellement choisi avant la
  production, ce générique n'étant vérifié contre aucun fournisseur en particulier.
- **Vérification d'identité des conducteurs** : existe désormais comme ressource dédiée
  et modérable (`identity_verifications`, `MeIdentityController`, statuts
  PENDING/APPROVED/REJECTED) — mais se limite à un type et un numéro de document
  déclarés, sans upload de la pièce elle-même (aucune colonne ni endpoint de fichier).
- Étendre le parseur de récurrence (`RecurrenceService`) au-delà du sous-ensemble RRULE
  actuellement supporté (`FREQ=WEEKLY;BYDAY=...`).
- Tests d'intégration frontend (actuellement seul le build de production est vérifié) et
  tests end-to-end.

## Points relevés en relecture (à traiter)

Corrigés dans cette version :

- **Webhook Kkiapay** — sans `KKIAPAY_WEBHOOK_SECRET`, la vérification de signature était
  contournée et l'endpoint (nécessairement public) acceptait n'importe quel payload. Le webhook
  est désormais refusé si le secret n'est pas configuré.
- **Conflit d'unicité** — une double réservation concurrente du même passager remontait en 500.
  `DataIntegrityViolationException` est maintenant traduite en 409.
- **Taux de commission codé en dur** — `ekuiseo.fee.service-fee-rate`/`rounding-step` sont
  désormais effectivement lus (voir `FeePolicy`), plus besoin de recompiler pour les faire évoluer.
- **`JwtService` complétait silencieusement** un secret trop court avec des zéros au lieu de le
  rejeter — il refuse désormais de démarrer si `JWT_SECRET` fait moins de 32 octets.

Restent ouverts (à la date de cette relecture — à revérifier, le backend évolue vite) :

- `GET /api/v1/trips/{id}` est public sans filtrer le statut : un trajet `DRAFT` est consultable
  par quiconque connaît son UUID.
- `PATCH /api/v1/trips/{id}` n'a pas les annotations de validation de `CreateTripRequest`
  (prix négatif, places hors bornes possibles).
- CORS en `allowedOriginPatterns("*")` avec `allowCredentials(true)` : à restreindre aux domaines
  réels avant la production.
- Barème d'annulation : à exactement 24 h du départ, 50 % sont retenus (le « gratuit » vaut pour
  strictement plus de 24 h). À confirmer côté métier.
- **Le backend n'a jamais été compilé** : Maven Central était inaccessible depuis l'environnement
  de génération. Lancer `mvn -DskipTests package` puis `mvn test` en local avant toute autre chose.

## Documentation

| Document | Contenu |
|---|---|
| [`docs/DEPLOIEMENT.md`](./docs/DEPLOIEMENT.md) | Du VPS Hostinger nu à la production : DNS, pare-feu, Docker, TLS, vérifications post-déploiement |
| [`docs/EXPLOITATION.md`](./docs/EXPLOITATION.md) | Sauvegardes/restauration, rotation des secrets, mises à jour, logs, métriques, incidents (paiement, base, signalement) |
| [`docs/CONFORMITE.md`](./docs/CONFORMITE.md) | Implications de la loi n° 2017-20 (code du numérique du Bénin) : APDP, durées de conservation, droits des personnes, sous-traitants, statut du covoiturage rémunéré |
| [`docs/LANCEMENT.md`](./docs/LANCEMENT.md) | Checklist avant ouverture au public : comptes de test, clés Kkiapay de production, CGU/confidentialité, modération, corridor de lancement, indicateurs à suivre |
