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

**Backend** — **Java 17** (LTS), Spring Boot 3.3, Maven, PostgreSQL 16 + PostGIS, Flyway,
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
10. **Abonnement conducteur** : 2 000 FCFA/mois, commission ramenée à 0 %.

## Conventions

- Recherche de trajets **géographique**, pas textuelle : `geography(Point,4326)`,
  `ST_DWithin` sur l'origine **et** la destination, arrêts intermédiaires inclus.
  Classement par distance de détour, écart horaire et note du conducteur.
- Erreurs HTTP en **RFC 7807** (`ProblemDetail`).
- Migrations Flyway **numérotées à la suite**. Ne jamais modifier une migration déjà
  écrite — V1 à V9 existent.
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

- `backend/` — 9 migrations. Kkiapay (initiation, webhook signé et
  idempotent, vérification serveur, remboursements), OTP par SMS avec limitation de débit,
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

**Le backend compile et sa suite de tests passe** (`mvn test` : 79 tests, 0 échec, 4 ignorés
= tests d'intégration Testcontainers). Deux corrections mécaniques ont suffi (voir
`backend/README.md` §9). Ce qui reste non rejoué : les tests d'intégration (`@Disabled`,
image `postgis/postgis` à télécharger) et donc les **requêtes SQL natives** contre un vrai
PostGIS — celles de la liquidité (V9) en premier, au premier démarrage avec
`docker compose up`.

Le frontend compile : `npm run build` et `npm run lint` passent.

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
- Les indicateurs de rétention (section 2) supposent des requêtes par cohorte
  hebdomadaire : à écrire en SQL natif agrégé, jamais en chargeant des collections en
  mémoire — même modèle que `AdminLiquidityService`.
- Les taux d'échec de paiement se calculent depuis `payments` (statut + canal), déjà
  disponible — rien à instrumenter.
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
- Aucun fournisseur SMS réel n'est choisi (`SmsGateway` avec implémentation journalisée).
- Le téléversement de la photo de pièce d'identité n'est pas implémenté (stockage sécurisé
  à décider) ; seul l'état de la vérification existe.
- Aucun fournisseur de tuiles cartographiques n'est câblé : `RouteMap` dessine un tracé
  schématique tant que `VITE_MAP_STYLE_URL` n'est pas renseignée.
- Web Push non implémenté ; les notifications critiques passent par SMS.
- Le décaissement effectif des reversements est manuel.
- Le mode `CASH` confirme immédiatement la réservation, sans validation du conducteur :
  à réserver aux conducteurs de confiance, sinon la commission est contournable.

## Marque

Le nom **Ekuiseo** est aussi celui d'un SaaS immobilier du même fondateur (ekuiseo.com).
Deux produits sur la même marque — à arbitrer.
