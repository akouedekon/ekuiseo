# Rapport fonctionnel — audit, corrections et tests de bout en bout

Session du 2026-09-05. Point de départ : `docs/AUDIT-FONCTIONNEL.md` (audit avant
modification). Tout ce qui suit a été **exécuté contre la production**
(https://ekuiseo.com, backend réel, base PostGIS réelle, Kkiapay en bac à sable) avec
deux comptes de test créés pour l'occasion : `+22997000322` (Afi Testeur, conducteur)
et `+22997000321` (Test Kkiapay, passager), plus le compte administrateur de démonstration.

## ✅ Fonctionnalités vérifiées de bout en bout

Chaque ligne = interface → action → API → base → réponse → interface mise à jour →
retour utilisateur, puis **rechargement de la page** pour vérifier la persistance.

| Parcours | Vérification |
|---|---|
| Inscription par OTP | Champs invalides refusés (prénom, nom, numéro, e-mail) ; compte créé (`POST /auth/otp/register`), code SMS journalisé, code faux → « Code OTP incorrect », code juste → session ouverte, profil en base (`role: USER`, `phoneVerified: true`). Numéro déjà inscrit → 409 affiché sur le champ. |
| Connexion OTP, `?next=` | Retour sur l'écran demandé après connexion ; seuls les chemins internes sont acceptés. |
| Session expirée | Jetons invalides → 401 → rafraîchissement refusé → jetons effacés, message « Votre session a expiré », redirection `/login?next=`. |
| Rôle et permissions | Compte USER sur `/admin` → écran « Accès réservé » sans charger le module ; API `/admin/**` → **403 RFC 7807** (était 401 : corrigé). Sans jeton → 401. Compte suspendu → OTP refusé (« Compte suspendu ») et jeton existant rejeté à la requête suivante. |
| Accueil | Axes populaires réels, erreur affichée avec « Réessayer » quand l'API est injoignable (constaté pendant un redémarrage). |
| Recherche | Résultats serveur, pagination « Voir plus », filtres, état vide avec création d'alerte (`POST /trip-alerts`). |
| Détail trajet | Arrêts intermédiaires et tarif par tronçon, profil conducteur, avis, signalement du trajet. |
| Réservation + acompte Kkiapay | Devis serveur (jamais estimé quand l'API répond), création (`PENDING_PAYMENT`), reprise depuis « Mes trajets » (`/book/:id?booking=`) avec échéance serveur, widget sandbox, `POST /payments/{id}/confirm` → `CONFIRMED` / `DEPOSIT_PAID`, notification. |
| Réservation espèces | `CASH` → confirmée immédiatement. |
| Messagerie | Passager → conducteur et retour, interlocuteur correct des deux côtés, liste des conversations, compteur de non-lus. |
| Notifications | Liste réelle, « Tout marquer comme lu » avec retour. |
| Compte : véhicules | Validation (marque, modèle, immatriculation, 8 places max), création, plaque normalisée, persistance après rechargement, suppression avec confirmation. |
| Compte : mobile money | Ajout, badge « par défaut », suppression avec confirmation, persistance. |
| Compte : identité | Dépôt (type + numéro) → `PENDING` en base → validation par l'admin → badge « Identité vérifiée » sur le profil public. |
| Compte : réglages | Interrupteur e-mail persisté en base après rechargement. |
| Compte : revenus | Solde net (commission déduite) et lots de reversement du conducteur. |
| Abonnement conducteur | `POST /me/subscription` → widget Kkiapay (2 038 F frais inclus) → confirmation serveur → `ACTIVE` jusqu'au 5 octobre ; la commission de la réservation suivante est bien 0 F. |
| Publication de trajet | Assistant 3 étapes, autocomplétion géographique des villes **et des arrêts** (coordonnées en base), prix conseillé, récapitulatif, trajet visible en recherche et dans « Je conduis ». |
| Modification de trajet | `PATCH /trips/{id}` : prix et description modifiés, persistés après rechargement. |
| Annulation par le conducteur | Réservation passée `CANCELLED_BY_DRIVER`, **remboursement Kkiapay sandbox effectué** (`REFUNDED`), notification au passager. |
| Avis | Après le départ, « Noter le conducteur » → 5/5 + commentaire → note et compteur du conducteur recalculés, avis visible sur le profil public ; second avis sur le même trajet → 409 expliqué. |
| Signalement | Depuis le détail d'un trajet → visible dans le back-office. |
| Back-office | Tableau de bord et liquidité (données réelles, export CSV 200 `text/csv`), signalements (prise en charge, classement avec note obligatoire), vérifications (validation, onglets validées/refusées), reversements (constitution des lots : 4 lots créés), utilisateurs (recherche, suspension **avec motif**, réactivation), journal d'audit paginé avec les actions ci-dessus. |
| Responsive | Aucun débordement horizontal à 375, 768, 1024 et 1920 px ; tableaux du back-office rendus en cartes sous 1024 px. |

## 🔧 Problèmes trouvés et corrigés

### Trouvés à l'audit (voir `docs/AUDIT-FONCTIONNEL.md`)

- Mode démonstration : 568 lignes de données inventées servies dès qu'une requête
  échouait, connexion sans backend, paiement auto-confirmé. **Supprimé intégralement.**
- Suspension d'utilisateur sans motif (500 en production), mauvais type de réponse des
  reversements, comparaisons `=== null` inopérantes, cache profil corrompu,
  `paymentMethod`/`paymentMode` en double, message optimiste attribué au mauvais
  expéditeur, frontière d'erreur hors du routeur (écran blanc), `?next=` non validé.
- Pas de timeout réseau, réessais sur 403/404, expiration de session non détectée,
  déconnexion sans navigation ni purge du cache persisté.
- États d'erreur manquants sur 11 écrans (accueil, détail trajet, profil, réservation,
  publication, compte, messages, notifications, tableau de bord).
- Inscription impossible (`/register` était un alias de `/login`).
- Arrêts intermédiaires perdus silencieusement à la publication.
- Plafond de prix invisible (20 000 F) et 30 résultats maximum en recherche.

### Trouvés pendant les tests

| Problème | Cause | Correction |
|---|---|---|
| Un USER recevait **401** (pas 403) sur `/admin/**` | Le dispatch `/error` de Tomcat n'est pas traversé par le filtre JWT (`OncePerRequestFilter`) : la page d'erreur repassait par le point d'entrée anonyme | Dispatch d'erreur autorisé, réponses 401/403 écrites directement en RFC 7807 |
| Un compte suspendu gardait l'accès jusqu'à l'expiration du jeton (60 min) | Le filtre JWT ne vérifiait pas le statut | Statut `ACTIVE` exigé à chaque requête, et à la vérification OTP / au rafraîchissement |
| Descente à un arrêt intermédiaire facturée au **prix du trajet complet** (3 500 F au lieu de 1 500 F) | `BookingService` ignorait `dropoffStopId` pour le tarif | `resolveUnitPrice` : tarif du tronçon, arrêt étranger refusé ; test unitaire |
| « Noter le conducteur » proposé même après un avis déposé | Aucune information dans la réponse | `BookingDetailResponse.reviewedByMe` |
| CORS `*` avec `allowCredentials` | Valeur en dur | `CORS_ALLOWED_ORIGINS` configurable |
| Proxy de développement ignorant `.env.local` | `process.env` ne contient pas les variables Vite | `loadEnv` dans `vite.config.ts` |
| Libellés : « vérifié sous 48 h » (promesse non tenue), double point, « a » sans accent | — | Corrigés |

## ➕ Fonctionnalités ajoutées (endpoints backend existants sans écran)

- Inscription réelle par OTP (`POST /auth/otp/register`, nouveau côté backend).
- Avis après trajet (`POST /trips/{id}/reviews`).
- Signalement d'un trajet ou d'un membre (`POST /reports`).
- Modification d'un trajet publié (`PATCH /trips/{id}`).
- Revenus et reversements du conducteur (`GET /me/payouts`, `/balance`).
- Abonnement conducteur avec paiement Kkiapay et confirmation serveur (étendue aux abonnements).
- Journal d'audit (`/admin/audit`), constitution des lots de reversement, résolution motivée
  des signalements, historique des vérifications d'identité.
- Reprise d'une réservation en attente d'acompte, pagination des résultats, arrêts
  géolocalisés à la publication, garde de rôle côté front (`RequireAdmin`).
- Tests unitaires frontend (Vitest, 27 tests) et étape CI ; 95 tests backend.

## ⚠️ Problèmes restants (non corrigeables côté frontend seul)

| Sujet | Cause | Impact | Solution recommandée |
|---|---|---|---|
| Photo de la pièce d'identité et photos de profil / véhicule | Aucun stockage de fichiers côté backend | Le modérateur valide sur déclaration (type + numéro) ; l'écran le dit explicitement | Stockage objet (S3 compatible) + endpoint multipart + URL signée pour l'aperçu admin |
| Révocation du jeton de rafraîchissement à la déconnexion | JWT sans état | Un jeton volé reste valable jusqu'à expiration (30 jours) | Table de jetons de rafraîchissement ou liste de révocation |
| Messages jamais marqués « lus » | Aucun endpoint | Le compteur de non-lus ne se vide pas | `POST /bookings/{id}/messages/read` |
| SMS réels | `SMS_MODE=log` en production : les codes OTP sont dans les journaux du backend | **Bloquant avant ouverture au public** | Choisir un fournisseur et passer `SMS_MODE=http` |
| Webhook Kkiapay | Aucun webhook reçu pendant les tests (URL non déclarée ou sandbox muet) | La confirmation immédiate couvre le cas courant ; un navigateur fermé avant la confirmation dépend du webhook | Déclarer `https://ekuiseo.com/api/v1/payments/kkiapay/webhook` dans le tableau de bord Kkiapay |
| Numéro Kkiapay en bac à sable | Le sandbox n'accepte que ses numéros de test | Sans effet en production | — |
| Clé MapTiler dans le bundle | Toute variable `VITE_*` est publique | Quota consommable par un tiers | Restreindre la clé au domaine côté MapTiler |
| Origines CORS | `CORS_ALLOWED_ORIGINS=*` encore sur le serveur | Faible (JWT en en-tête, pas de cookie) | Mettre `https://ekuiseo.com,https://akouedekon.github.io` dans `/opt/ekuiseo/.env` |
| Secrets échangés en clair pendant la mise en place | Mot de passe SSH, clés Kkiapay live, clé MapTiler | — | À faire tourner |

## 🧪 Tests

```text
Frontend : OK (parcours ci-dessus rejoués dans le navigateur contre la production)
Backend : OK (mvn test : 95 tests, 0 échec, 4 ignorés = Testcontainers)
API : OK (200/201/202/204/400/401/403/404/409 vérifiés ; 422 jamais émis, la validation renvoie 400)
CRUD : OK (véhicules, comptes mobile money, trajets, réservations, avis, signalements, préférences, identité)
Auth : OK (inscription, connexion, expiration, rafraîchissement, déconnexion, suspension)
Permissions : OK (USER vs ADMIN, front et API)
Responsive : OK (375 / 768 / 1024 / 1920, sans débordement)
Tests automatisés : OK (Vitest 27, JUnit 95, tous deux en CI)
Build : OK (tsc + vite build)
Lint : OK (oxlint : 0 erreur, 6 avertissements React Compiler non bloquants)
```

## 📊 État final

**Pré-production.** L'application est utilisable de bout en bout par de vrais
utilisateurs, sans donnée factice, avec paiement mobile money réel (bac à sable) et un
back-office opérationnel. Trois points empêchent de dire « production-ready » :
l'envoi de SMS réel (`SMS_MODE=http`), le passage des clés Kkiapay en production après
déclaration du webhook, et le cadre juridique du covoiturage rémunéré au Bénin
(`docs/CONFORMITE.md`). Le téléversement des pièces d'identité est le premier chantier
produit à ouvrir ensuite.
