# Rapport final — refonte et mise en production d'Ekuiseo

Période : 5 au 7 septembre 2026. Point de départ : `docs/AUDIT-COMPLET.md` (351 constats,
score global 46/100). Suivi constat par constat : `docs/AUDIT-SUIVI.md`. Production :
https://ekuiseo.com, VPS partagé, déploiement continu par GitHub Actions.

## 1. Ce qui a été livré

| Phase | Contenu | Migrations | Commits principaux |
|---|---|---|---|
| 0 — Sécurisation immédiate | journaux sans secrets, timeouts sortants, JWT sans repli, refresh refusé comme access, rate limiting par IP réelle, CORS fermé, Swagger/actuator fermés, HSTS, arrêt gracieux, sauvegarde quotidienne + restauration testée | — | `744b812` |
| 1.1 — Authentification | E.164 béninois (10 chiffres), refresh tokens enregistrés avec rotation et révocation, comptes en attente purgés, changement d'e-mail vérifié, correction de contact admin | V11 | `558f80c` |
| 1.2 — Argent | remboursements en deux temps (`REFUND_PENDING` / `REFUND_MANUAL`, reprise, file admin), paiement orphelin remboursé, reversements après service et vers un compte vérifié, prix bornés, abonnement réconcilié | V12 | `cd22fdd` |
| 1.3 — Cycle de vie et quotidien | fuseau Africa/Porto-Novo, ONGOING/COMPLETED automatiques, no-show, passagers d'un trajet, navettes = modèles avec occurrences (COUNT/UNTIL/BYDAY), cascade de suspension, annulation gratuite après changement d'horaire | V13 | `6145cf2` |
| 1.4 — Confiance et conformité | avis et signalements liés à une réservation, identité révocable, routeur de notifications (in-app, e-mail, SMS), sens de la recherche, suppression et anonymisation de compte, caches purgés à la déconnexion, Messages sur mobile | V14 | `983b791` |
| 2 — P2 | erreurs RFC 7807 avec identifiant, bornes et quotas, validation, rétention nocturne, statut EXPIRED, abonnement complet, reversements gardés, alertes complètes, messagerie fermée, CGU horodatées, export des données, recherche par arrêts avec tri serveur, aperçu WhatsApp, front lazy et PWA en mode `prompt`, pages légales, fiche utilisateur admin, tests d'intégration Testcontainers et `@WebMvcTest`, Spring Boot 3.5.16, Dependabot | V15, V16 | `2b10f60`, `c839765` |
| 3, 4, 5 — Qualité, design, fonctionnalités | webhook et paiements idempotents, verrous, contraintes CHECK, index, journal des connexions, référentiel de lieux étendu (communes, quartiers, gares, alias, `pg_trgm`), numéro de pièce tronqué, KPI de rétention et de paiement, CASH réservé aux conducteurs vérifiés ; contraste et jetons vérifiés en CI, titres par route, cibles 44 px, radios accessibles, icônes PWA, heures en heure du Bénin, avis conducteur → passager, villes récentes, trajet retour, invite d'installation | V17 | `abc2401`, `7bd17ab` |
| Infra transverse | sauvegarde préalable et retour arrière automatique du déploiement, exercice de restauration mensuel, commit figé, conteneurs sans escalade de privilèges, CSP resserrée, Cache-Control du front, identifiant de requête Caddy → backend | — | `f44abfa` |

Volumes : 17 migrations Flyway ; backend 454 tests unitaires et 43 tests d'intégration PostGIS
(exécutés en CI sous Docker) ; frontend 99 tests Vitest / Testing Library ; budget de taille
vérifié en CI (entrée 163 Kio gzip, contre environ 550 Kio avant).

## 1 bis. Bilan des 351 constats (`docs/AUDIT-SUIVI.md`)

| Sévérité | Constats | Fait | Partiel | Non fait | Décision fondateur |
|---|---:|---:|---:|---:|---:|
| P0 | 1 | 0 | 0 | 0 | 1 (DNS corrigé, sonde externe à souscrire) |
| P1 | 67 | 65 | 1 | 0 | 1 (sauvegarde hors site) |
| P2 | 162 | 152 | 7 | 1 | 2 |
| P3 | 121 | 95 | 19 | 5 | 2 |
| **Total** | **351** | **312 (89 %)** | **27** | **6** | **6** |

Les 68 constats bloquants (P0 et P1) sont tous traités dans le dépôt, à l exception de deux
actions hors dépôt (sonde externe, stockage hors site des sauvegardes) et d un point mineur
(claims `issuer`/`audience` du JWT).

## 2. Vérification finale sur la production (7 septembre 2026, commit `7bd17ab`)

Sondage HTTP depuis l'extérieur :

- En-têtes : HSTS un an, CSP limitée à `'self'`, Kkiapay et MapTiler, `X-Frame-Options`,
  `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, pas de `Server` Caddy.
- Surfaces fermées : `/swagger-ui.html`, `/v3/api-docs`, `/actuator/env`, `/actuator/metrics`
  ne sont jamais relayés au backend (le front sert sa page d'accueil) ; sous `/api`, tout
  chemin inconnu répond 401. Seul `/actuator/health` est exposé (UP/DOWN).
- CORS : aucun en-tête pour une origine inconnue, `Access-Control-Allow-Origin: https://ekuiseo.com`
  pour l'origine du site, sans `credentials`.
- Authentification : `/me` 401 sans jeton et avec un jeton forgé ; `/admin/**` 401 ; le parcours
  par mot de passe n'existe plus (404).
- Validation : `size=500` → 400, latitude 999 → 400, UUID invalide → 400 (RFC 7807).
- Limitation de débit : 10 demandes de code par IP et 10 minutes, puis 429 avec `Retry-After` ;
  60 recherches par minute et par IP.
- Contrats nouveaux : référentiel `GET /api/v1/geo/places` (67 lieux : 36 villes, 23 quartiers,
  8 gares avec rattachement), recherche avec tri serveur et nom du conducteur réduit à
  l'initiale pour un visiteur anonyme, aperçu Open Graph d'un trajet pour les robots WhatsApp.
- Charge légère : 60 recherches géographiques, 6 en parallèle : p50 303 ms, p95 481 ms,
  aucune erreur serveur (les 6 dernières ont reçu 429, quota atteint comme prévu).
- Base : Flyway à la version 17, sauvegarde préalable du déploiement présente, quatre
  conteneurs sains, `no-new-privileges` et limite de processus actifs.

Limites de cette vérification : les parcours qui envoient un code (inscription, connexion,
suppression de compte) et les paiements Kkiapay n'ont pas été rejoués de bout en bout dans
cette passe ; ils l'ont été aux lots 1.1 et 1.2 en sandbox. Les tests d'intégration couvrent
la concurrence sur la dernière place, le webhook tardif, la recherche avec arrêts et le
fuseau, la récurrence et les requêtes de KPI.

## 3. Nouvelle grille de scores (/100)

| Dimension | Avant | Après | Ce qui a changé |
|---|---|---|---|
| Architecture | 58 | 78 | Effets de bord réseau sortis des transactions (après commit, asynchrone), timeouts, décision de vérification Kkiapay unique, gabarits de notification, événement de publication, code mort et alias retirés. Reste : `BookingService` et `PaymentService` encore volumineux. |
| Fonctionnel | 40 | 82 | Cycle de vie complet, navettes correctes, recherche orientée et par arrêts, reversements après service, notifications réelles, abonnement, no-show, CASH conditionné. Reste : validation conducteur d'une réservation, Web Push. |
| UX/UI | 55 | 76 | Promesses alignées sur le produit, Messages mobile, tunnel de réservation robuste, tri serveur, estimations affichées comme telles, pages légales, villes récentes, trajet retour. Reste : page de publication encore monolithique, quelques avertissements lint. |
| Sécurité | 38 | 80 | Sessions révocables, OTP borné et durable, CORS et CSP fermés, surfaces de diagnostic fermées, journaux masqués, journal des connexions, anonymisation, suppression de compte, verrous sur paiements et lots. Reste : jeton de rafraîchissement en `localStorage` (cookie HttpOnly non fait), second facteur admin, énumération des comptes par le 404 de `/otp/request` (choix assumé). |
| Performance | 52 | 75 | Batch fetch, statistiques en SQL natif, listes bornées, index et contraintes, précache réduit, chunk initial divisé par trois, carte chargée à la demande. Reste : listes admin non paginées côté serveur, pas de cache HTTP sur la recherche. |
| Tests | 32 | 74 | 454 unitaires, 43 intégration PostGIS en CI, `@WebMvcTest` sécurité et contrôleurs financiers, 99 tests front dont composants, budget de taille, contraste et jetons en CI. Reste : Playwright de bout en bout, couverture JaCoCo avec seuil. |
| PWA | 48 | 74 | Cache SW limité aux données publiques et purgé, précache 1,2 Mo, mise à jour proposée, promesses hors ligne retirées, état « hors ligne sans données », icônes de la marque, invite d'installation. Reste : file de mutations hors ligne (assumée absente), Web Push. |
| Accessibilité | 55 | 78 | Contrastes vérifiés en CI, bordures de champs 3:1, titres par route et live region, reduced-motion, cibles 44 px, radios et menus au clavier, live region du compte à rebours par paliers. Reste : audit lecteur d'écran réel sur mobile. |
| Production readiness | 35 | 74 | Sauvegarde avant déploiement, retour arrière automatique, exercice de restauration mensuel, commit figé, journaux corrélés, erreurs 500 journalisées avec identifiant, durcissement des conteneurs, Dependabot, tests d'intégration en CI. Reste : sauvegardes hors site (rclone), sonde externe et alerting, collecte d'erreurs branchée sur un service, images non publiées dans un registre. |
| **Global** | **46** | **77** | Pondération inchangée (fonctionnel, sécurité, production ×2). Le produit est prêt pour une ouverture contrôlée (pilotes, axes ciblés) une fois les actions du fondateur ci-dessous réalisées ; l'ouverture large reste conditionnée au cadre juridique. |

## 4. Actions attendues du fondateur (hors dépôt)

1. **Secrets** : faire tourner les secrets qui ont circulé en clair pendant la session (mot de
   passe SSH, clés Kkiapay sandbox et live, secret de webhook, clé MapTiler, clé Africa's
   Talking, mot de passe de la boîte `noreply@ekuiseo.com`).
2. **Sauvegardes hors site** : installer `rclone`, renseigner `BACKUP_REMOTE` dans `.env`
   (voir `docs/EXPLOITATION.md`) ; vérifier `backups/last-drill` après le 1er octobre.
3. **Surveillance** : sonde externe sur `https://ekuiseo.com/actuator/health` avec alerte ;
   éventuellement `VITE_ERROR_REPORT_URL` vers un collecteur.
4. **Canal sortant** : décision prise le 7 septembre 2026, l e-mail est le seul canal ; aucun
   fournisseur SMS à choisir (les notifications critiques partent toujours à l adresse vérifiée,
   V18).
5. **Cartographie** : restreindre la clé MapTiler aux origines `ekuiseo.com` avant de renseigner
   `VITE_MAP_STYLE_URL`.
6. **Juridique** : faire valider les pages CGU / confidentialité / mentions légales (projets de
   texte livrés) et trancher le statut du covoiturage rémunéré ; déclaration APDP.
7. **Vitrine GitHub Pages** : décider de la conserver (CORS l'inclut encore) ou de la retirer.
8. **Dépendances** : revoir les PR Dependabot ouvertes (mineures groupées) ; les majeures sont
   désormais exclues et se décident à la main.
9. **Comptes** : se reconnecter une fois (les anciens jetons de rafraîchissement sont
   invalides), vérifier la boîte `contact@ekuiseo.com`.

## 5. Ce qui reste dans le dépôt

Voir la section « Reste à faire » de `docs/AUDIT-SUIVI.md`. Les points les plus structurants :
jeton de rafraîchissement en cookie HttpOnly, validation conducteur d'une réservation
(accept/decline), pagination serveur des listes admin, tests Playwright de bout en bout,
publication des images dans un registre pour un retour arrière par tag, découpage de
`PublishTripPage` et `SearchResultsPage`, Web Push.
