# Ekuiseo — plateforme de covoiturage pour le Bénin

Contexte de projet pour Claude Code. Écrit à l'issue d'une session Cowork qui a
produit l'intégralité de ce dépôt. Tout est en français : code commenté, interface,
documentation, messages de commit.

## Ce que c'est

Application **web** (pas native) de covoiturage destinée au marché béninois, couvrant
**deux modes** dans le même produit :

- **Interurbain** — trajets planifiés longue distance (Cotonou–Bohicon, Cotonou–Parakou,
  Cotonou–Natitingou, Cotonou–Porto-Novo, Cotonou–Lomé).
- **Quotidien** — trajets domicile-travail récurrents (Abomey-Calavi–Cotonou), avec règle
  de récurrence hebdomadaire et génération d'occurrences.

Le mode quotidien n'est pas une fonctionnalité secondaire : c'est le modèle économique.
L'interurbain seul génère 2 à 4 trajets par utilisateur et par an, ce qui ne rentabilise
aucune acquisition — c'est d'ailleurs ce qui a poussé le concurrent local RMobility à
pivoter vers le VTC en 2024. Le quotidien crée la fréquence d'usage.

Le web est un choix délibéré : pas de friction de store, pas de 60 Mo à télécharger sur un
terminal d'entrée de gamme, et un lien de trajet partageable dans un groupe WhatsApp —
le canal de distribution réel au Bénin. PWA installable pour l'icône et les notifications.

## Stack

**Backend** — **Java 17** (LTS), Spring Boot 3.5, Maven, PostgreSQL 16 + PostGIS, Flyway,
Spring Security JWT, Spring Data JPA, MapStruct, springdoc-openapi. Package `bj.ekuiseo.api`.
**Frontend** — React 19, TypeScript, Vite, Tailwind v4, TanStack Query v5, react-router v7,
Radix UI (primitives), Framer Motion (`motion`), lucide-react, sonner, recharts, maplibre-gl,
date-fns (locale fr), vite-plugin-pwa.
**Le backend cible Java 17, et doit le rester.** C'est une contrainte, pas un défaut :
le `pom.xml`, le `Dockerfile` et la CI sont tous alignés sur 17. N'utilise aucune
fonctionnalité introduite après Java 17 — pas de `SequencedCollection` (`getFirst()`,
`getLast()`, `reversed()` sur une `List`), pas de threads virtuels, pas de `ScopedValue`
ni de `StructuredTaskScope`, pas de patrons de `record` dans un `switch`. Les expressions
`switch`, les `record` et le `switch` sur `String` sont disponibles en 17 et largement
utilisés dans le code.

**Infra** — Docker Compose (dev et prod), Caddy en reverse proxy avec TLS automatique,
GitHub Actions, cible de déploiement : VPS Hostinger.

## Règles métier — valeurs exactes, à ne pas dériver

1. **Devise** : XOF (FCFA). Tous les montants sont des **entiers** (`bigint` en base,
   `long` en Java). Le franc CFA n'a pas de subdivision en circulation et les pièces
   s'arrêtent à 5 F : tout arrondi se fait **aux 5 FCFA supérieurs**.
2. **Commission de la plateforme** : **8 %** du montant de la réservation, arrondis aux
   5 FCFA supérieurs. Source unique : `FeePolicy` / `MoneyUtils` côté back,
   `src/lib/payments.ts` côté front. Ces deux-là doivent rester alignés.
3. **Paiement fractionné — l'argument différenciant du produit.** Le passager ne paie pas
   tout en ligne. Il verse un **acompte** en mobile money et règle le **solde en espèces
   à bord**.
   - `acompte = min(total, arrondi_5_sup(max(1000, frais_de_service)))`
   - `solde_a_bord = total - acompte`
   - Le `max` avec les frais de service est indispensable : sans lui, sur une réservation
     où 8 % dépassent 1 000 F, la plateforme encaisserait moins que sa propre commission
     et devrait de l'argent au conducteur.
   - Trois modes : `MOMO_DEPOSIT` (défaut), `MOMO_FULL`, `CASH`. Ces noms font foi — ils
     sont en base.
4. **Reversement conducteur** : la plateforme ne redistribue que ce qu'elle a réellement
   encaissé. `net = deposit_amount - service_fee` en `MOMO_DEPOSIT`,
   `net = amount - service_fee` en `MOMO_FULL`, rien en `CASH`.
5. **Annulation passager**, calculée sur l'acompte (seul montant encaissé) : gratuite à
   plus de 24 h du départ ; 50 % retenus en deçà ; 100 % retenus après l'heure de départ.
6. **Réservation impayée** : expire après **20 minutes** et libère les places.
7. **Places** : ressource concurrente. Décrément atomique par UPDATE conditionnel
   (`WHERE seats_available >= :seats`). Deux réservations simultanées sur la dernière
   place doivent produire une confirmation et un refus, jamais deux confirmations.
8. **Un conducteur ne peut pas réserver sur son propre trajet.**
9. **Récurrence** : les trajets quotidiens génèrent leurs occurrences sur 14 jours glissants.
    Le trajet créé est un **modèle** (`TripStatus.TEMPLATE`, jamais cherchable ni réservable) ;
    ses occurrences (`parent_trip_id`) sont générées à la création puis chaque nuit, dans le
    fuseau `Africa/Porto-Novo` (`common/Tz`), en respectant `COUNT`/`UNTIL`/`BYDAY`.
11. **Cycle de vie** (`TripLifecycleScheduler`, toutes les 5 min) : `PUBLISHED`/`FULL` → `ONGOING`
    à l heure de départ, → `COMPLETED` 6 h après (`ekuiseo.trip.completion-delay-hours`) avec
    les réservations confirmées ; un trajet parti ne se réserve ni ne s annule plus. Le
    conducteur peut signaler un **no-show** jusqu à 48 h après le départ (acompte acquis,
    reversé net au conducteur). Un changement d horaire par le conducteur ouvre 24 h
    d annulation gratuite (`bookings.free_cancellation_until`).
10. **Abonnement conducteur** : 2 000 FCFA/mois, commission ramenée à 0 %.

## Conventions

- Recherche de trajets **géographique**, pas textuelle : `geography(Point,4326)`,
  `ST_DWithin` sur l'origine **et** la destination, arrêts intermédiaires inclus.
  Classement par distance de détour, écart horaire et note du conducteur.
- Erreurs HTTP en **RFC 7807** (`ProblemDetail`).
- Migrations Flyway **numérotées à la suite**. Ne jamais modifier une migration déjà
  écrite — V1 à V20 existent (la prochaine est V21).
- Le front ne recalcule jamais un montant pour une réservation existante : il lit le
  `paymentPlan` renvoyé par l'API. Les estimations locales sont autorisées **avant**
  création, et doivent être affichées comme telles.
- Réseau dégradé assumé : cache TanStack Query persisté, bandeau hors ligne, réessais
  uniquement sur erreur transitoire (réseau, 5xx), délai de 20 s par requête.
  **Aucune donnée factice** : le mode démonstration a été retiré, chaque écran lit
  l'API ou affiche un état d'erreur avec réessai.

## Où en est le projet

Complet et cohérent de bout en bout : API, interface, back-office d'administration,
chaîne de déploiement, jeu de démonstration, documentation d'exploitation.

- `backend/` — 20 migrations. Kkiapay (initiation, webhook signé et
  idempotent, vérification serveur, remboursements), codes de connexion par e-mail (SMS en repli) avec limitation de débit,
  géocodage des villes béninoises en base, rôles et back-office, reversements, signalements,
  journal d'audit, alertes de recherche, abonnements, trace des recherches (`search_events`,
  V9) et indicateurs de liquidité (`/api/v1/admin/stats/liquidity`, export CSV).
- `frontend/` — Design system maison sur Radix + Tailwind (tokens dans `index.css`, dont
  une échelle typographique nommée `text-caption` … `text-display`), mode sombre, PWA.
  Une vingtaine d'écrans dont `/admin`. Conventions : `components/ui` (primitives),
  `components/{feedback,forms,tables,layout,trip,booking}` (briques réutilisables :
  `ConfirmDialog`, `SelectField`, `DataTable`, `AdminPageHeader`, `ShareTripButton`),
  `features/<domaine>` (sections d'écran et formulaires RHF + Zod, schémas dans
  `lib/validation.ts`), `pages/` (orchestration seulement). Toute action destructrice ou
  financière passe par `ConfirmDialog` ; les toasts de succès ne partent qu'en `onSuccess`.
- `docs/` — `DEPLOIEMENT.md`, `EXPLOITATION.md`, `CONFORMITE.md`, `LANCEMENT.md`,
  `donnees-demo.sql` (jeu de démonstration réellement rejoué contre PostGIS, idempotent).
- `backend/README.md` — architecture, modèle de données, endpoints, règles métier
  implémentées, et une section honnête sur ce qui reste incertain.

## État de vérification

**Backend** : `mvn test` = 514 tests unitaires, 0 échec, plus 43 tests d intégration Testcontainers (`mvn verify`, exécutés en CI). **Frontend** :
`npm run lint`, `npm test` (Vitest, 129 tests : client HTTP et rafraîchissement de jeton,
erreurs, règles de paiement, validation) et `npm run build` passent ; les trois tournent en CI.

**Vérifié en production le 2026-09-05** (https://ekuiseo.com, `docs/RAPPORT-FONCTIONNEL.md`) :
inscription et connexion OTP, expiration de session, permissions USER/ADMIN (401/403 RFC 7807),
recherche paginée, réservation avec acompte Kkiapay sandbox et reprise, espèces, messagerie,
notifications, compte (véhicules, mobile money, identité, réglages, revenus, abonnement payé),
publication avec arrêts géolocalisés, modification et annulation de trajet (remboursement
sandbox), avis, signalement, back-office complet (signalements, vérifications, lots de
reversement, suspension motivée, journal d'audit, export CSV), responsive 375 → 1920.
Comptes de test en production : `+2290197000322` (conducteur), `+2290197000321` (passager). Les
numéros béninois s écrivent `+229 01 XX XX XX XX` (10 chiffres nationaux depuis 2024).

Il n'existe **plus aucun mode démonstration** : `api/demo.ts` et `api/resilient.ts` ont été
supprimés, chaque hook appelle l'API et chaque écran affiche un état d'erreur avec réessai.
Audit de départ dans `docs/AUDIT-FONCTIONNEL.md`.

**Audit complet du 2026-09-05** : `docs/AUDIT-COMPLET.md` (351 constats, scores avant
correction, plan en 8 phases) et son backlog `docs/AUDIT-COMPLET.constats.json`. **Phase 0
(sécurisation immédiate) livrée le 2026-09-05** : journaux sans codes ni identifiants,
timeouts HTTP sortants, clé JWT sans repli, refresh token refusé comme jeton d accès,
rate limiting par IP réelle (X-Real-IP), suppression de la connexion par mot de passe
(`/auth/register`, `/auth/login` n existent plus : parcours OTP seul), compteur OTP
persistant, CORS sans credentials et restreint en production, Swagger et actuator fermés,
HSTS, arrêt gracieux, cron de sauvegarde installé par le déploiement, exercice de
restauration consigné dans `docs/EXPLOITATION.md`. Phases 1 à 7 : voir le plan de l audit.

**Phase 1, lot 1.1 (authentification et sessions) livré le 2026-09-05** : normalisation E.164
partagée (`PhoneNumbers` / `toE164`, numéros béninois à 10 chiffres obligatoires), refresh tokens
enregistrés avec rotation, détection de réutilisation, révocation à la déconnexion, à la suspension
et à la correction de contact, durée absolue 90 jours (V11) ; compte créé `PENDING_VERIFICATION`
et activé au premier code, purgé après 24 h sinon ; changement d e-mail en deux temps ; correction
de contact par l administration (journalisée) ; quota par IP dédié aux demandes de code ; alias
admin morts retirés. Le seed et les comptes de test sont au format `+229 01 …`. **Complément du
2026-09-07 (F355/F405)** : le refresh token voyage dans le cookie `HttpOnly` `ekuiseo_refresh`
(`Secure`, `SameSite=Strict`, `Path=/api/v1/auth`, `RefreshCookies`), `/auth/refresh` et
`/auth/logout` exigent `X-Requested-With: XMLHttpRequest` quand le jeton vient du cookie, le corps
`{ refreshToken }` reste accepté en transition ; côté front l access token ne vit qu en mémoire et
`restoreSession()` (main.tsx) rouvre la session au chargement — plus rien dans localStorage.

**Phase 1, lot 1.2 (argent) livré le 2026-09-05** : remboursements en deux temps (`RefundService` :
décision et statut `REFUND_PENDING` dans la transaction d annulation, appel Kkiapay après validation,
reprise toutes les 5 min, `REFUND_MANUAL` pour les partiels et échecs définitifs, file `/admin/payments`) ;
acompte reçu après expiration remboursé automatiquement et affiché comme tel ; échéance `bookings.expires_at`
prolongée à l initiation du paiement ; reversements limités aux réservations voyagées depuis 24 h et
encaissées, destination = compte mobile money vérifié (jamais le numéro de connexion), conducteurs sans
compte exclus et notifiés, réservation remboursée retirée du lot ou marquée à déduire ; comptes mobile
money vérifiés d office (numéro du compte) ou par l administration ; prix par place et par arrêt
strictement positifs, bornés et croissants (V12) ; modification de trajet sous verrou avec recalcul
FULL/PUBLISHED ; abonnement : souscription en attente réutilisée, confirmation rejouée avec le même
paiement, expiration après 30 min.

**Phase 1, lot 1.3 (cycle de vie et mode quotidien) livré le 2026-09-05** : fuseau `Africa/Porto-Novo`
partout (`common/Tz`), `TripLifecycleScheduler` (ONGOING au départ, COMPLETED 6 h après, réservations
clôturées), no-show conducteur (48 h, acompte reversé net), liste des passagers d un trajet, cascade de la
suspension, notification et annulation gratuite 24 h après un changement d horaire, navettes = modèles
`TEMPLATE` avec occurrences générées à la création et chaque nuit (COUNT/UNTIL/BYDAY, arrêts copiés,
index unique parent/départ, V13).

**Phase 1, lot 1.4 (confiance, notifications, recherche, conformité) livré le 2026-09-05** : avis et
signalements liés à une réservation commune, identité révocable et resoumission bornée, routeur de
notifications (in-app + e-mail + SMS selon préférences, après commit, asynchrone, gabarits
`NotificationTemplates`), contrainte de sens et rayon borné dans la recherche, vérifications admin
filtrées, suppression de compte par OTP et anonymisation admin (V14), caches du service worker et de
TanStack limités aux données publiques et purgés à la déconnexion, Messages dans la barre mobile.

**Phase 2 livrée le 2026-09-07** (V15, V16, Spring Boot 3.5.16) : erreurs MVC en RFC 7807 avec
`errorId` et `X-Request-Id` (MDC), bornes et quotas sur les GET publics et la messagerie, validation
Bean complète, rétention nocturne (OTP, notifications, messages, alertes), transactions par élément dans
les tâches planifiées, véhicules supprimés logiquement, statistiques admin en SQL natif ; statut
`EXPIRED`, décision de vérification Kkiapay factorisée, cycle de vie de l abonnement (J-7, J-3, EXPIRED),
reversements gardés (`settle`/`fail`, référence de virement, verrou de lot), comptes mobile money
dédoublonnés et préfixes par opérateur, alertes de recherche complètes (liste, suppression, rayon,
matching en une requête après commit, e-mail), messagerie fermée après la réservation, transitions de
signalement, conversations d un signalement (accès journalisé), CGU horodatées, export des données
personnelles, tri et filtres serveur de la recherche **avec arrêts intermédiaires** (tronçon et prix
renvoyés), aperçu Open Graph `/share/trips/{id}` pour WhatsApp ; front : lazy par route, LazyMotion,
service worker en mode `prompt`, pages légales, collecte d erreurs, fiche utilisateur admin, tests
Testing Library ; tests d intégration Testcontainers (`*IT.java`, `mvn verify`) et `@WebMvcTest`
exécutés en CI, Dependabot.

**Phases 3, 4 et 5 livrées le 2026-09-07** (V17) : webhook acquitté sur cible inconnue, paiement
INITIATED réutilisé et abandonné après 20 min, verrou sur le paiement, opérateur réel dans
`payments.channel`, contraintes CHECK sur les statuts, index manquants, `password_hash` nullable,
journal des connexions et `last_login_at`, quota OTP durable en base, référentiel `geo_places` étendu
(communes, quartiers, gares `STATION`, alias, `pg_trgm`, `GET /api/v1/geo/places` source unique du
front), numéro de pièce tronqué après décision, KPI de rétention et de paiement
(`GET /api/v1/admin/stats/retention` + CSV, page `/admin/retention`), **CASH réservé aux conducteurs à
identité vérifiée** ; front : jetons de couleur et contraste vérifiés en CI (`check-contrast`,
`check-tokens`), titres par route et live region, cibles 44 px, radios Radix, icônes PWA régénérées,
heures en `Africa/Porto-Novo`, avis conducteur → passager, villes récentes, trajet retour, invite
d installation PWA.

Suivi constat par constat : `docs/AUDIT-SUIVI.md` ; bilan et nouvelle grille de scores :
`docs/RAPPORT-FINAL.md`.

## Commandes

```bash
# backend — la première commande à lancer
cd backend && mvn -DskipTests package && mvn test

# frontend
cd frontend && npm install && npm run build

# tout démarrer
cp .env.example .env    # renseigner au minimum JWT_SECRET
docker compose up
./scripts/seed-demo.sh
```

## Back-office : les KPI à mesurer

Le back-office (`/admin` côté front, `/api/v1/admin/**` côté API) existe et fonctionne,
mais il ne mesure aujourd'hui que du **volume** : `GET /api/v1/admin/stats?days=N` renvoie
séries journalières, totaux, variations vs période précédente, répartition des réservations
par statut, et axes les plus demandés (trajets, réservations, GMV, revenu, utilisateurs
actifs et nouveaux).

Le volume ne dit pas si l'affaire tourne. Les indicateurs ci-dessous, oui — ils sont à
construire, chacun répondant à une question précise que le fondateur se posera vraiment.

**Métrique nord** : places confirmées par semaine. Le seuil de viabilité établi dans
l'étude est de **2 000 places par mois** ; en dessous, le projet paie l'hébergement, pas
un salaire.

### 1. Liquidité — le risque qui tue le projet

Un passager qui ne trouve rien ne revient pas ; un conducteur sans passager ne republie pas.
Ces deux courbes s'éteignent mutuellement en six semaines si personne ne les regarde.

- **Taux de recherche aboutie** : recherches ayant renvoyé au moins un trajet / total.
  Complété par le taux de recherche → réservation.
- **Taux de remplissage** : places réservées / places publiées, par axe et par mode.
- **Trajets orphelins** : part des trajets publiés n'ayant reçu aucune réservation.
- **Délai médian publication → première réservation.**
- **Axes en pénurie** : couples origine/destination les plus recherchés sans résultat —
  c'est la liste des corridors à démarcher en priorité.

### 2. Rétention — ce qui distingue un produit d'un dépannage

- **Rétention conducteur** : part des conducteurs qui republient la semaine suivante (W1)
  puis quatre semaines après (W4).
- **Rétention passager** : part des passagers qui réservent à nouveau sous 30 jours.
- **Part du mode quotidien** dans les réservations. C'est la thèse produit : si le quotidien
  ne décolle pas, le modèle économique ne tient pas, quel que soit le volume interurbain.
- **Trajets récurrents actifs** et nombre moyen d'occurrences réellement remplies.

### 3. Transaction et paiement

- **GMV et revenu net** (commission moins frais d'agrégateur) — déjà en place.
- **Taux de conversion réservation → acompte encaissé**, et son miroir : part des
  réservations expirées faute de paiement dans les 20 minutes.
- **Taux d'échec Kkiapay par opérateur** (MTN, Moov, Celtiis). Une panne côté opérateur
  doit être visible en quelques minutes, pas découverte par les plaintes.
- **Répartition des modes de paiement** (`MOMO_DEPOSIT` / `MOMO_FULL` / `CASH`) et part
  du volume qui échappe à la commission.
- **Panier moyen, places par réservation.**

### 4. Confiance et qualité

- **Taux d'annulation** séparé conducteur / passager, et part des annulations tardives.
- **Taux de no-show.**
- **Note moyenne** et part des trajets effectivement notés.
- **Signalements** : ouverts, délai médian de traitement.
- **Part des conducteurs à identité vérifiée.**

### 5. Exploitation

- **Reversements en attente** : nombre et montant total dû aux conducteurs.
- **Délai médian de réponse des conducteurs** aux messages.
- **File de vérification d'identité** : en attente et ancienneté du plus vieux dossier.

### Ce qui manque techniquement pour les calculer

- **Fait — section 1 (liquidité) et métrique nord.** La table `search_events` existe (V9),
  écrite en asynchrone par `SearchEventService` depuis `TripService#search` (première page
  seulement), rattachée à la ville `geo_places` la plus proche pour regrouper les axes,
  purgée chaque nuit au-delà de 180 jours (`SEARCH_EVENTS_RETENTION_DAYS`, déclaré dans
  `docs/CONFORMITE.md`). `AdminLiquidityService` calcule tout en SQL natif agrégé et sert
  `GET /api/v1/admin/stats/liquidity?days=N` + `/liquidity/export` (CSV) ; le front l'affiche
  en tête de `/admin` (métrique nord, quatre chiffres de liquidité) et en détail sur
  `/admin/liquidity`. Approximation assumée : recherche → réservation = même utilisateur
  connecté, réservation sous 24 h (pas d'identifiant de recherche transmis par le front).
- **Fait — sections 2 et 3 (rétention, paiement).** `AdminRetentionService` (SQL natif par
  cohorte) sert `GET /api/v1/admin/stats/retention?days=N` + `/retention/export` : rétention
  conducteur W1/W4, passager 30 j, part du quotidien, navettes actives, conversion
  réservation → acompte, part des expirées, échecs Kkiapay par opérateur réel
  (`payments.channel`), répartition des modes, panier moyen ; page `/admin/retention` et bloc
  « Rétention » du tableau de bord.
- Sections 4 et 5 : les compteurs de file (`GET /api/v1/admin/overview` : signalements ouverts,
  vérifications en attente et ancienneté, reversements dus, remboursements à traiter, gros
  émetteurs de messages) existent ; taux d annulation par acteur, no-show et délai médian de
  traitement des signalements restent à calculer.
- **Export CSV** de chaque indicateur : le fondateur travaillera dans un tableur, pas
  seulement dans le tableau de bord. Fait pour la liquidité (`AdminLiquidityService#toCsv`,
  `;` + virgule décimale + BOM) ; à reproduire pour les sections suivantes.

### Comment les présenter

Le tableau de bord n'est pas une galerie de graphiques. Chaque indicateur affiché doit
répondre à une question et déclencher une décision. Une valeur qui n'appelle aucune action
est du bruit. Priorité d'affichage : la métrique nord et sa trajectoire vers le seuil de
2 000 places par mois, puis la liquidité, puis la rétention. Le reste vit dans des onglets.
Toujours montrer la variation par rapport à la période précédente — un chiffre seul ne
s'interprète pas.

## Points ouverts connus

- **Juridique, bloquant avant toute ouverture au public** : le statut du covoiturage
  rémunéré au Bénin n'est pas tranché. `docs/CONFORMITE.md` pose la question sans y
  répondre — c'est à un juriste béninois de le faire. Un éventuel agrément auprès du
  ministère des Transports n'a pas été confirmé.
- Kkiapay : le widget est intégré côté front (`src/lib/kkiapay.ts`, script officiel
  `cdn.kkiapay.me/k.js`, ouvert depuis `BookingPage` après `/payments/deposit`, `data` =
  `{ bookingId }`), et le serveur confirme par deux voies indépendantes — le webhook signé
  et `POST /api/v1/payments/{id}/confirm` appelé sur l'évènement `success` du widget —
  toujours après re-vérification du statut **et du montant** auprès de l'API Kkiapay.
  Reste à valider sur un compte marchand réel (sandbox validée d'abord) : le nom exact
  des options du widget selon la version du script (`key` / `api_key`, les deux sont
  passés) et le format de `stateData` (objet ou chaîne, les deux sont acceptés).
- Les codes de connexion partent par e-mail (`MailGateway`, relais SMTP à renseigner via
  `MAIL_MODE=smtp` ; journalisés sinon). **Décision du 7 septembre 2026 : l e-mail est le seul
  canal sortant**, aucun fournisseur SMS ne sera branché ; les notifications critiques partent
  toujours à l adresse vérifiée, les autres selon `notify_by_email` (vrai par défaut, V18). Le
  `SmsGateway` reste en mode `log` et n est pas un canal du produit.
- Pièces d'identité (V20) : recto, verso et selfie sont téléversés (`/api/v1/me/identity/documents`),
  chiffrés AES-256-GCM sur le disque du conteneur (`IDENTITY_STORAGE_KEY`, `ekuiseo.storage.identity-dir`
  = `./data/identity`, à monter sur un volume nommé en production), lus par le back-office avec
  audit `ADMIN_IDENTITY_DOCUMENT_VIEWED`, purgés 30 jours après la décision. Sans clé, le
  téléversement répond 503 et l'interface l'explique. Côté front, une photo de plus de 1 Mo ou
  dans un format que le serveur refuse (HEIC des iPhone) est redessinée en JPEG de 2 000 px
  maximum avant l'envoi (`lib/imageReduction.ts`) : la limite serveur de 5 Mo ne vaut plus que
  pour les PDF.
- Aucun fournisseur de tuiles cartographiques n'est câblé : `RouteMap` dessine un tracé
  schématique tant que `VITE_MAP_STYLE_URL` n'est pas renseignée.
- Web Push (V20) : canal complémentaire de l'e-mail (`WebPushSender`, `nl.martijndwars:web-push`,
  clés VAPID `PUSH_VAPID_PUBLIC_KEY` / `PUSH_VAPID_PRIVATE_KEY` ; vides = désactivé proprement),
  service worker maison `src/sw.ts` (`injectManifest`), abonnement par appareil depuis les réglages.
  Sur iPhone, seul un site installé sur l'écran d'accueil peut s'abonner.
- Le décaissement effectif des reversements est manuel (référence de virement et échec
  consignés depuis le back-office).
- Le mode `CASH` confirme immédiatement la réservation sans validation du conducteur ; il
  n est proposé qu avec un conducteur à identité vérifiée. Une validation conducteur
  (accept/decline) reste à concevoir si la commission contournée devient un problème.
- Actions hors dépôt attendues du fondateur : voir `docs/AUDIT-SUIVI.md` (sauvegardes hors site,
  sonde externe, clé MapTiler, rotation des secrets, juriste).
- La vitrine GitHub Pages (origine tierce) ne peut plus ouvrir ni rafraîchir une session : le
  cookie de rafraîchissement est `SameSite=Strict` et CORS reste sans credentials (front et API
  sur le même domaine via Caddy). Elle se limite aux routes publiques (recherche, fiches).

## Marque

Le nom **Ekuiseo** est aussi celui d'un SaaS immobilier du même fondateur (ekuiseo.com).
Deux produits sur la même marque — à arbitrer.
