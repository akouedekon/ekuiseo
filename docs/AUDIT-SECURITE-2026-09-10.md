# Audit de sécurité — 10 septembre 2026

Audit en lecture seule du dépôt (backend, frontend, proxys, scripts, CI), grille OWASP Top 10 2021,
mené pendant la refonte « production-grade ». Ce document liste les constats **et leur état de
traitement**. Il complète `docs/AUDIT-COMPLET.md` (audit général du 5 septembre) et `docs/CONFORMITE.md`.

Légende : ✅ corrigé dans le dépôt · 🟡 corrigé en partie ou à activer par le fondateur · ⏳ à faire · 🔒 décision assumée (non corrigé, motif donné).

## Ce qui est solide (à conserver tel quel)

- Contrôle d'appartenance objet (IDOR) complet et uniforme sur les 31 contrôleurs, toujours **avant** l'écriture.
- Chaîne de paiement : montant serveur, re-vérification serveur-à-serveur du statut **et** du montant, verrou pessimiste, idempotence par référence Kkiapay, remboursement automatique d'un paiement orphelin.
- Session : rotation du jeton de rafraîchissement, détection de réutilisation, borne absolue 90 jours, cookie `HttpOnly`/`Secure`/`SameSite=Strict`/`Path`, en-tête anti-CSRF. OTP haché, 5 essais, triple quota.
- Pièces d'identité : AES-256-GCM avec nom de fichier en AAD, nom aléatoire validé, type déduit des octets, purge 30 jours.
- Toutes les requêtes SQL sont paramétrées ; CSP sans `unsafe-inline` sur `script-src` ; aucun jeton stocké côté front ; service worker qui refuse de mettre en cache une réponse authentifiée.

## Constats et traitement

### Secrets et configuration

| Gravité | Constat | État |
|---|---|---|
| Critique | Un fichier de sauvegarde du `.env` de production (tous les secrets en clair) se trouve dans l'arborescence de travail du poste du fondateur (ignoré par git, jamais commité, vérifié par `git log -S`). | 🟡 **Action fondateur** : sortir le fichier vers un gestionnaire de mots de passe, puis faire tourner les huit secrets (JWT, clé de chiffrement des pièces, clés Kkiapay, secret webhook, mot de passe SMTP, clé VAPID, clé Africa's Talking). |
| Critique | `cp .env.example .env` livrait CORS `*`, Swagger ouvert et Kkiapay en bac à sable ; `docker-compose.prod.yml` ne protégeait que par des valeurs de repli. | ✅ `.env.example` fermé par défaut ; `scripts/deploy-vps.sh` refuse CORS `*`, `SPRINGDOC_ENABLED=true`, `KKIAPAY_MODE=stub`, mot de passe de base d'exemple, et avertit en bac à sable. Production vérifiée conforme. |
| Élevé | Sourcemaps du front publiées dans l'image nginx et sur GitHub Pages (tout le TypeScript lisible). | ✅ supprimées au build (Dockerfile, workflow Pages) + `404` sur `*.map` dans nginx. |
| Élevé | Sauvegardes de base envoyées hors site sans chiffrement. | 🟡 `scripts/backup.sh` chiffre en AES-256 (gpg) quand `BACKUP_PASSPHRASE` est définie, `restore.sh` déchiffre ; **le fondateur doit poser la phrase dans `.env`** (`openssl rand -base64 32`) et la conserver hors du serveur. |
| Faible | `GITHUB_TOKEN` de la CI avec les permissions par défaut ; actions épinglées par étiquette. | ✅ `permissions: contents: read` au niveau du workflow. ⏳ épinglage par SHA (à faire avec Dependabot). |

### Transport et en-têtes

| Gravité | Constat | État |
|---|---|---|
| Moyen | Le nginx de l'hôte n'impose pas HTTPS dans le fichier livré (redirection ajoutée par certbot seulement). | 🟡 Modèle documenté avec `certbot --redirect` et la vérification `curl -I`. **À vérifier une fois sur le serveur** par le fondateur. |
| Moyen | `server.error.include-message: always` : une exception hors du gestionnaire global renvoyait son message. | ✅ `never`. |
| Faible | Pas d'isolation d'origine (`Cross-Origin-Opener-Policy`). | ✅ `same-origin-allow-popups` dans les deux Caddyfile. `Cross-Origin-Resource-Policy` non posé (aperçus de partage). |
| Moyen | `X-Real-IP` cru sans liste de proxys de confiance : un appel qui contourne nginx neutralise tous les quotas. | ⏳ à faire dans `RateLimitingFilter` (`ekuiseo.http.trusted-proxies`) et `Caddyfile.proxied` (`header_up X-Real-IP {remote_host}`). |

### Authentification et quotas

| Gravité | Constat | État |
|---|---|---|
| Moyen | Routes coûteuses hors du filtre de débit : confirmation de paiement, initiation d'acompte, téléversement d'identité, publication, signalement, `/trips/popular`. | ⏳ quotas `pay:`, `upload:`, `write:` et ajout de `/trips/popular` aux chemins de recherche (`RateLimitingFilter`). |
| Moyen | `/trips/popular` public, non limité, exécute une agrégation. | ⏳ (même correctif) + `Cache-Control: public, max-age=300`. |
| Moyen | Énumération de comptes : `POST /auth/otp/request` répond 404 pour un numéro inconnu. | 🔒 Décision assumée : le 404 permet à l'écran de connexion de proposer l'inscription, comportement voulu pour le public visé ; risque limité par le quota 10 demandes / 10 min / IP. À revoir si des tentatives massives apparaissent dans les journaux. |

### Données et journaux

| Gravité | Constat | État |
|---|---|---|
| Faible | Le jeton du lien public de suivi pouvait apparaître dans le journal des erreurs navigateur (`route`). | ✅ masqué (`/live/***`). |
| Faible | Le jeton du lien public de suivi est remis à tout passager de la réservation, et survit à une annulation. | 🟡 Depuis V28 seuls le conducteur et les passagers **confirmés** lisent le suivi ; le jeton reste partageable par un passager confirmé (fonction produit). Un passager qui annule perd l'accès au suivi mais pas un lien déjà copié : rotation du jeton à envisager. |
| Faible | Pièce d'identité servie `inline` dans l'origine du back-office (PDF piégé). | ✅ `Content-Disposition: attachment` (le front l'affiche via un blob). |
| Faible | Listes personnelles non paginées (réservations, conversations, reversements, alertes). | ⏳ voir `docs/AUDIT-PERFORMANCE-2026-09-10.md` (même correctif). |

### Frontend

| Gravité | Constat | État |
|---|---|---|
| Moyen | Script Kkiapay chargé sans intégrité (SRI impossible : SDK versionné en place). | 🔒 Risque structurel accepté à ce stade ; piste : isoler le widget dans une iframe d'origine distincte. |
| Faible | Cache des tuiles du service worker sur tout hôte contenant « tiles »/« maptiler ». | ✅ hôte exact `api.maptiler.com`. |

### Dépendances

| Gravité | Constat | État |
|---|---|---|
| Faible | `httpasyncclient 4.1.5` (fin de vie) tiré par la bibliothèque Web Push. | ⏳ tester `web-push` avec `httpclient5` ou le client HTTP de la JVM. |

## Ordre des actions restantes

1. Fondateur : sortir le fichier de sauvegarde du `.env`, faire tourner les secrets, poser `BACKUP_PASSPHRASE`, vérifier la redirection HTTPS.
2. Dépôt : proxys de confiance pour `X-Real-IP`, quotas sur les routes de paiement/téléversement/publication, `/trips/popular` limité et mis en cache.
3. Dépôt : pagination des listes personnelles (avec l'audit de performance), épinglage des actions CI par SHA, remplacement de `httpasyncclient`.
