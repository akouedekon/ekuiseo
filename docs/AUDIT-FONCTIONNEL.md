# Audit fonctionnel du frontend — état avant la mise en conformité

Relevé du 2026-09-05, avant la mission « application 100 % dynamique ». Chaque point
renvoie au fichier concerné à cette date. La colonne « Traitement » est complétée au fil
de la mission (voir `docs/RAPPORT-FONCTIONNEL.md` pour le bilan final).

## 1. Simulation globale

| # | Constat | Fichiers | Traitement |
|---|---|---|---|
| S1 | Repli « démonstration » : 25 requêtes et 25 mutations retombent sur `api/demo.ts` (568 lignes de données inventées) dès que l'API échoue, mutations « réussies » avec 450 ms de latence simulée. Activé par défaut dans `.env.example` et `.env.local`. | `api/resilient.ts`, `api/demo.ts`, tous les hooks | Suppression complète du mécanisme et du jeu factice ; les hooks appellent l'API sans repli. |
| S2 | Connexion sans backend : n'importe quel code OTP ouvre une session avec des jetons factices en mode démo. | `hooks/useAuth.ts` | Supprimé avec S1. |
| S3 | Paiement auto-confirmé au bout de 9 s, référence de transaction `Math.random`, instruction opérateur en dur. | `hooks/useBookings.ts` | Supprimé avec S1. |
| S4 | Statistiques du back-office fabriquées (séries, deltas, taux, axes) hors API. | `api/demo.ts` | Supprimé avec S1. |
| S5 | 22 commentaires « ATTENDU : » décrivant des endpoints supposés absents alors qu'ils existent tous côté backend. | hooks/* | Commentaires réécrits. |

## 2. Bugs de contrat frontend ↔ backend

| # | Constat | Fichiers | Traitement |
|---|---|---|---|
| C1 | Suspension d'un utilisateur : aucun corps envoyé alors que le backend exige `reason` → 500 systématique en production, masqué par le mode démo. | `hooks/useAdmin.ts`, `pages/admin/AdminUsers.tsx` | Dialogue avec motif obligatoire, corps `{ reason }`. |
| C2 | `useMarkPayoutPaid` typé `AdminPayoutResponse` alors que le backend renvoie `PayoutResponse` (statut `SETTLED`). | `hooks/useAdmin.ts`, `api/extended.ts` | Type corrigé, statut `SETTLED` accepté. |
| C3 | Le backend omet les champs nuls (`non_null`) : les tests `=== null` ne matchent jamais (délai médian, taux de fiabilité affichés `undefined`). | `AdminDashboard.tsx`, `AdminLiquidity.tsx`, `DriverProfilePage.tsx` | Tests `== null`, types `| null | undefined`. |
| C4 | `useUpdateProfile` écrit un `UserResponse` nu dans la clé de cache `['me']` qui contient une enveloppe → profil illisible après modification. | `hooks/useAccount.ts` | Disparaît avec la suppression de l'enveloppe. |
| C5 | Message optimiste envoyé avec `senderId` de l'utilisateur démo : les messages qu'on envoie s'affichent comme reçus. | `hooks/useMessages.ts` | Identifiant réel de l'utilisateur. |
| C6 | `CreateBookingRequest` déclare `paymentMethod` alors que le backend lit `paymentMode` ; les deux sont envoyés. | `api/types.ts`, `pages/BookingPage.tsx` | Type et envoi alignés sur `paymentMode`. |
| C7 | Le rôle de l'utilisateur n'est pas exposé par l'API : le lien « Back-office » est offert à tous, le 403 met ~7 s (3 réessais). | `api/types.ts`, `AppShell.tsx`, `AdminLayout.tsx` | Champ `role` ajouté à `UserResponse` (backend), garde `RequireAdmin`, lien conditionnel. |
| C8 | `/register` est un alias cosmétique de `/login` : aucun formulaire d'inscription, alors que la vérification OTP répond 404 « inscrivez-vous d'abord » pour un numéro inconnu. **Un nouvel utilisateur ne peut pas créer de compte.** | `pages/LoginPage.tsx` | Vraie inscription (prénom, nom, e-mail facultatif) → OTP → session ; endpoint backend `POST /auth/otp/register`. |
| C9 | Un compte suspendu peut se connecter par OTP (le contrôle de statut n'existe que sur la connexion par mot de passe). | backend `AuthService` | Contrôle ajouté sur OTP et rafraîchissement. |

## 3. Robustesse réseau et session

| # | Constat | Fichiers | Traitement |
|---|---|---|---|
| R1 | Aucun timeout : une requête peut pendre indéfiniment. | `api/client.ts` | `AbortSignal.timeout` (20 s). |
| R2 | Session expirée : les jetons sont effacés sans prévenir React, l'écran affiche « Chargement impossible » au lieu de renvoyer vers la connexion. | `api/client.ts`, `RequireAuth.tsx` | État d'authentification réactif + redirection vers `/login?next=` avec message. |
| R3 | `retry: 3` sans distinction de statut : 403/404 réessayés (7 s de squelette). | `lib/queryClient.ts` | Réessai uniquement sur erreur réseau / 5xx. |
| R4 | Déconnexion : pas de navigation depuis le menu, cache persisté (PII) réécrit avec 1 s de retard. | `hooks/useAuth.ts`, `AppShell.tsx` | Navigation systématique, purge immédiate du cache persisté. |
| R5 | `ErrorBoundary` monté hors du routeur : l'écran de secours contient un `<Link>` et plante à son tour (écran blanc) ; `error.message` brut affiché. | `main.tsx`, `SystemPages.tsx` | Frontière déplacée dans le routeur, message générique. |
| R6 | `useBookingQuote` avale toutes les erreurs (401/403/409) derrière une estimation locale. | `hooks/useBookings.ts` | Estimation uniquement sur erreur réseau, sinon erreur affichée. |
| R7 | `?next=` non validé sur la page de connexion. | `LoginPage.tsx` | N'accepte que les chemins internes. |

## 4. États manquants (chargement / vide / erreur)

| # | Écran | Manque | Traitement |
|---|---|---|---|
| E1 | Accueil | `popular` en erreur → section disparaît ; `recurring` en erreur → « Aucune navette » | États d'erreur avec réessai |
| E2 | Détail trajet | `stops`, `driver`, `reviews` en erreur → silencieux / « Aucun avis » | Idem |
| E3 | Profil conducteur | `reviews` en erreur → « Aucun avis » | Idem |
| E4 | Réservation | `stops`, `quote`, `me` en erreur → silencieux | Idem |
| E5 | Publication | `vehicles` en erreur → « Aucun véhicule » | Idem |
| E6 | Mes trajets | onglet « Passés » sans état d'erreur | Idem |
| E7 | Compte | `identity` en erreur → « non vérifiée » ; `preferences` en erreur → tout à OFF (et un clic écrase les vraies valeurs) | Idem |
| E8 | Messages | échec d'envoi silencieux | Toast + réessai |
| E9 | Notifications | « Tout marquer comme lu » sans retour ni chargement | Retour + chargement |
| E10 | Tableau de bord admin | squelette infini si la liquidité échoue ; tableau des axes sans état vide | Corrigé |
| E11 | Signalements / vérifications admin | boutons sans état de chargement, pas de confirmation avant classement | Corrigé |

## 5. Fonctionnalités incomplètes ou absentes malgré un backend prêt

| # | Fonctionnalité | Endpoint backend | Traitement |
|---|---|---|---|
| F1 | Laisser un avis après un trajet terminé | `POST /trips/{id}/reviews` | Dialogue d'avis sur les réservations terminées |
| F2 | Signaler un utilisateur ou un trajet | `POST /reports` | Dialogue de signalement (détail trajet, profil conducteur) |
| F3 | Modifier un trajet publié | `PATCH /trips/{id}` | Feuille de modification dans « Mes trajets » |
| F4 | Solde et historique de reversement du conducteur | `GET /me/payouts`, `/balance` | Section « Revenus » dans le compte |
| F5 | Abonnement conducteur (2 000 F/mois, 0 % de commission) | `GET/POST /me/subscription` | Section « Abonnement » + paiement widget + confirmation serveur |
| F6 | Journal d'audit du back-office | `GET /admin/audit-log` | Page `/admin/audit` |
| F7 | Lancer un lot de reversements | `POST /admin/payouts/run` | Bouton avec confirmation |
| F8 | Résoudre un signalement avec une note | `POST /admin/reports/{id}/resolve` | Dialogue avec note |
| F9 | Historique des vérifications d'identité (approuvées / refusées) | `GET /admin/verifications?status=` | Filtre de statut |
| F10 | Reprendre une réservation en attente de paiement (au lieu d'en créer une nouvelle) | `GET /bookings/{id}` + `/payments/deposit` | `/book/:tripId?booking=` reprend au paiement |
| F11 | Pagination des résultats de recherche (30 max en dur) et plafond de prix 20 000 F invisible | `GET /trips/search?page=` | « Voir plus » + plafond dérivé des résultats |
| F12 | Arrêts intermédiaires saisis en texte libre et perdus silencieusement si la ville n'est pas dans la liste locale | — | Autocomplétion géographique + validation |

## 6. Ce qui reste hors de portée du frontend seul

| # | Sujet | Détail |
|---|---|---|
| H1 | Photo de la pièce d'identité | Aucun stockage de fichiers côté backend : le formulaire n'envoie que type et numéro, le modérateur valide sans voir le document. |
| H2 | Photo de profil / de véhicule | `photoUrl` accepté mais aucun téléversement possible (même cause). |
| H3 | Révocation du jeton de rafraîchissement à la déconnexion | Jetons JWT sans état côté serveur : pas d'endpoint de révocation. |
| H4 | Messages « lus » | Aucun endpoint ne marque un message comme lu ; les compteurs de non-lus ne se vident pas. |
| H5 | Compte mobile money par défaut | Aucun endpoint pour changer le compte par défaut. |
| H6 | Prix conseillé à la publication | Heuristique locale (distance × tarif) présentée comme estimation ; pas d'endpoint serveur. |
| H7 | Carte réelle | `VITE_MAP_STYLE_URL` requis ; sinon tracé schématique (déjà signalé comme tel). |
| H8 | Temps réel | Messages et notifications par sondage (15 s / 60 s), pas de push. |
