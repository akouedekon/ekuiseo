# Ekuiseo — Cahier des charges

**Plateforme de covoiturage pour le Bénin — version du 10 septembre 2026, à jour du système réellement en production (https://ekuiseo.com).**

Ce document décrit ce que fait Ekuiseo, pour qui, selon quelles règles, et comment il est construit et exploité. Il est écrit pour être lu d'abord par un non-technicien (fondateur, associé, juriste, partenaire), avec des annexes techniques pour l'équipe qui reprend le code. Contrairement à un cahier des charges « d'intention », tout ce qui est décrit ici **existe et fonctionne** ; ce qui reste à faire est explicitement marqué comme tel dans la partie 9.

---

## 1. Le projet en une page

### 1.1 Le problème

Au Bénin, les déplacements interurbains (Cotonou–Bohicon, Cotonou–Parakou, Cotonou–Porto-Novo, Cotonou–Lomé…) et les trajets quotidiens domicile–travail (Abomey-Calavi–Cotonou) se font majoritairement en taxis collectifs pleins, en bus ou en zémidjan (moto-taxi), avec des horaires incertains, des prix négociés à chaque fois et aucune traçabilité. Beaucoup de voitures, motos et tricycles circulent avec des places vides.

### 1.2 La réponse

Ekuiseo met en relation des conducteurs qui ont des places libres et des passagers qui cherchent un trajet, **à l'avance** (trajets planifiés, pas de course à la demande). Le passager réserve, verse un petit acompte par mobile money, et paie le reste en espèces à bord. Le conducteur est payé de la part encaissée en ligne, moins la commission de la plateforme.

### 1.3 Ce qui la distingue

- **Deux modes dans un seul produit** : l'interurbain (quelques trajets par an et par personne) et le **quotidien** (navettes récurrentes, plusieurs fois par semaine). Le quotidien est le cœur du modèle économique : c'est lui qui crée la fréquence d'usage. L'interurbain seul ne rentabilise aucune acquisition — c'est ce qui a poussé le concurrent local RMobility à pivoter vers le VTC en 2024.
- **Paiement fractionné** : l'acompte en ligne engage le passager sans lui demander de payer tout à l'avance ; le solde en espèces à bord correspond à l'usage réel du pays.
- **Tous les véhicules du Bénin** : voiture, moto (zémidjan, 1 passager) et tricycle (jusqu'à 6 places).
- **Application web d'abord** (PWA installable), partageable par un lien dans un groupe WhatsApp — le vrai canal de distribution — et habillée en application Android/iOS par Capacitor.
- **L'argent suit la réalité de la course** : si la course n'a pas lieu, le passager est remboursé ; si elle a eu lieu, le conducteur est payé ; les litiges sont tranchés avec les deux versions.

### 1.4 Métrique de succès

**Places confirmées par mois.** Seuil de viabilité établi dans l'étude : **2 000 places confirmées par mois**. En dessous, le projet paie l'hébergement, pas un salaire. Le tableau de bord d'administration affiche cette métrique en premier, avec sa trajectoire.

---

## 2. Les acteurs

| Acteur | Qui | Ce qu'il fait |
|---|---|---|
| **Passager** | Toute personne avec un numéro béninois (+229 01 XX XX XX XX) et une adresse e-mail | Cherche, réserve, paie l'acompte, voyage, confirme le trajet, note le conducteur, signale un problème |
| **Conducteur** | Un utilisateur qui a déclaré un véhicule (et un compte mobile money pour être payé) | Publie des trajets (ponctuels ou navettes), accepte ou refuse les demandes, partage sa position, fait l'appel au départ, est reversé |
| **Modération / administration** | Le fondateur et les personnes qu'il habilite (rôle `ADMIN`) | Vérifie les identités, traite les signalements et les litiges, règle les reversements et les remboursements manuels, suspend les comptes, lit les indicateurs |
| **Le système** | Tâches automatiques du serveur | Expire les réservations impayées, fait vivre le cycle des trajets, rembourse, constitue les lots de reversement, envoie les notifications, purge les données |

Un même compte peut être passager et conducteur. Un conducteur ne peut pas réserver sur son propre trajet.

---

## 3. Parcours et fonctionnalités

### 3.1 Compte, connexion, confiance

**Inscription et connexion sans mot de passe.** L'utilisateur donne son numéro béninois (10 chiffres nationaux, format 01…), son prénom, son nom et une adresse e-mail obligatoire. Il reçoit un **code à usage unique par e-mail** (l'e-mail est le seul canal sortant retenu : les fournisseurs SMS testés au Bénin n'ont pas été concluants). Le compte est créé « en attente » et activé au premier code ; jamais activé sous 24 h, il est purgé et le numéro redevient libre. Les codes sont limités (3 demandes par 10 minutes et par numéro, 20 par jour, 5 essais par code).

**Session.** Jeton d'accès de courte durée en mémoire, jeton de rafraîchissement dans un cookie sécurisé, rotation à chaque usage, détection de réutilisation, révocation à la déconnexion et à la suspension, durée absolue 90 jours.

**Conditions générales.** Horodatées et versionnées : une nouvelle version bloque l'écran jusqu'à acceptation (rappel du casque obligatoire à moto, règles d'annulation, statut du covoiturage).

**Profil.** Photo, note moyenne, nombre d'avis, ancienneté, badges « identité vérifiée » et « conducteur », véhicules, préférences de notification.

**Identité vérifiée (facultative, mais requise pour certains droits).** Le conducteur téléverse recto, verso et selfie d'une pièce d'identité (les photos de téléphone sont réduites automatiquement avant envoi). Les fichiers sont chiffrés sur le serveur, lus uniquement par un administrateur (chaque consultation est journalisée), et supprimés 30 jours après la décision. Le badge peut être retiré avec motif ; une resoumission est possible dans une limite. **Seul un conducteur à identité vérifiée peut proposer le paiement en espèces.**

**Compte mobile money du conducteur.** Numéro et opérateur (MTN MoMo, Moov Money, Celtiis Cash). Vérifié d'office si c'est le numéro du compte, sinon par l'administration. C'est la **seule destination possible d'un reversement** — jamais le numéro de connexion.

**Véhicules.** Marque, modèle, couleur, confort, et **type** : voiture (jusqu'à 8 places), moto (1 passager), tricycle (jusqu'à 6). Supprimés logiquement (l'historique des trajets reste lisible).

**Suppression de compte.** Par code à usage unique ; anonymisation par l'administration possible. Export de ses données personnelles disponible.

### 3.2 Chercher un trajet

- Recherche **géographique** : origine et destination sont des points (villes, communes, quartiers, gares routières du référentiel béninois, avec alias et tolérance aux fautes). Un trajet remonte s'il passe à proximité des deux points, **arrêts intermédiaires compris** — un passager Bohicon → Parakou trouve le trajet Cotonou → Parakou qui s'arrête à Bohicon, avec le prix du tronçon.
- Filtres : date, nombre de places, type de véhicule, prix maximum, confort, conducteur vérifié, réservation immédiate ; tri par heure, prix ou note ; contrainte de sens (pas de trajet qui repart en arrière) ; rayon borné.
- **Autour de moi** : depuis la position du téléphone, les départs proches sur une carte et dans une liste, avec la distance jusqu'au point d'embarquement.
- Départs populaires et villes récentes sur l'accueil ; trajet retour en un geste.
- **Alertes de recherche** : « prévenez-moi quand un trajet Cotonou → Natitingou est publié pour telle date » (e-mail + notification), rayon paramétrable, expiration automatique.
- Fiche trajet partageable : lien avec aperçu (image, horaire, prix) dans WhatsApp, lisible sans compte.

### 3.3 Publier un trajet (conducteur)

- Trajet **ponctuel** ou **navette** (récurrence hebdomadaire : jours, date de fin ou nombre d'occurrences). Une navette est un modèle : ses occurrences sont générées sur 14 jours glissants, chaque nuit, dans le fuseau du Bénin. Une occurrence se gère comme un trajet.
- Origine, destination, **arrêts intermédiaires géolocalisés** avec prix par tronçon (prix strictement positifs, bornés, croissants le long du trajet).
- Nombre de places (borné par le type de véhicule), prix par place, véhicule, confort, options : bagages, réservation immédiate ou **sur accord du conducteur**.
- Modification possible jusqu'au départ ; un changement d'horaire ou de prix prévient tous les passagers et leur ouvre **24 h d'annulation gratuite**. Annulation du trajet par le conducteur : tous les passagers sont remboursés intégralement et prévenus.
- **Liste des passagers** au départ : places, solde à encaisser à bord, messagerie, demandes à accepter ou refuser, signalement d'un passager absent, notation, et depuis V25 la réponse à une déclaration d'absence (voir 4.4).
- **Position en direct** : d'une heure avant le départ à la fin du trajet, le conducteur peut partager sa position ; ses passagers voient le véhicule sur la carte avec la distance restante et l'heure d'arrivée estimée, et peuvent partager un lien public temporaire à un proche (prénom du conducteur seulement). Positions supprimées après 24 h.

### 3.4 Réserver et payer (passager)

1. **Devis** : le serveur calcule le prix total, les frais de service, l'acompte et le solde à bord. Le front n'invente jamais un montant pour une réservation existante ; il n'affiche des estimations locales qu'avant la création, signalées comme telles.
2. **Trois modes de paiement** :
   - `MOMO_DEPOSIT` (défaut) : acompte en mobile money maintenant, solde en espèces à bord ;
   - `MOMO_FULL` : tout en ligne ;
   - `CASH` : tout en espèces à bord — uniquement avec un conducteur à identité vérifiée.
3. **Acompte par Kkiapay** (agrégateur béninois : MTN, Moov, Celtiis, carte). Le widget s'ouvre dans l'application ; le serveur ne croit jamais le widget sur parole et **revérifie le statut et le montant auprès de Kkiapay** avant de confirmer, par deux voies indépendantes (retour du widget et webhook signé). Un acompte reçu après l'expiration est remboursé automatiquement.
4. **Réservation impayée** : les places sont bloquées 20 minutes ; passé ce délai, la réservation expire et les places sont libérées.
5. **Trajet « sur accord du conducteur »** : la réservation attend la réponse du conducteur (délai plafonné, au plus tard 2 h avant le départ). Refus ou silence = remboursement intégral automatique.
6. **Annulation par le passager**, sur l'acompte seul : gratuite à plus de 24 h du départ, 50 % retenus en deçà, 100 % après l'heure de départ. Une demande encore en attente d'accord s'annule sans frais.
7. **Places** : ressource concurrente ; deux passagers qui réservent la dernière place au même instant obtiennent une confirmation et un refus, jamais deux confirmations.

Mes trajets affiche chaque réservation avec son état, le compte à rebours de l'acompte, la messagerie, l'annulation, le constat après le départ, la notation, et **le sort de l'argent** (remboursement en cours, manuel, effectué).

### 3.5 Pendant et après le trajet

- **Cycle automatique** : le trajet passe « en cours » à l'heure de départ et « terminé » 6 h après ; un trajet parti ne se réserve ni ne s'annule plus.
- **Appel au départ** : le conducteur peut déclarer un passager absent jusqu'à 48 h après le départ ; l'acompte de ce passager lui reste acquis et lui est reversé.
- **Constat du passager** : après le départ, le passager confirme que le trajet a eu lieu ou déclare que **le conducteur n'est pas venu** (jusqu'à 24 h après le départ). Sans réponse sous 24 h, confirmation tacite. La suite de ce cas est décrite en 4.4.
- **Avis** : passager → conducteur et conducteur → passager, un seul par trajet et par cible, uniquement après un trajet partagé et une fois le constat donné ou tacite.
- **Messagerie** par réservation, fermée quand la réservation est close ; quotas anti-spam.
- **Signalements** : absence, conduite dangereuse, harcèlement, fraude, véhicule non conforme, autre — liés à un trajet partagé ; la personne visée est informée qu'un signalement existe, jamais de l'identité de l'auteur.

### 3.6 Notifications

Trois canaux, un seul texte par événement : **dans l'application** (cloche, compteur), **e-mail** (toujours pour les notifications critiques — réservation, annulation, argent, sécurité — selon préférence pour les autres), **push** (Web Push sur le site installé, Firebase Cloud Messaging dans l'application mobile). Événements couverts : réservation, demande d'accord, refus, confirmation, annulation, expiration, paiement reçu ou échoué, remboursement en cours ou effectué, rappel de départ, horaire modifié, absence signalée ou contestée, décision de litige, reversement préparé, réglé ou en échec, compte mobile money manquant, avis reçu, alerte de recherche, identité validée ou refusée, signalement reçu ou traité, abonnement, CGU, suspension.

### 3.7 Abonnement conducteur

2 000 FCFA par mois, payé par Kkiapay : pendant la période, **aucune commission** n'est prélevée sur les trajets du conducteur. Rappels à J-7 et J-3, expiration automatique, souscription en attente réutilisée.

### 3.8 Application mobile

- **PWA** : installable sur l'écran d'accueil (Android et iPhone), fonctionne en réseau dégradé (cache, bandeau hors ligne, réessais seulement sur erreur transitoire, délai de 20 s par requête), notifications Web Push sur le site installé.
- **Application Android / iOS (Capacitor)** : coque native qui charge le site (toute mise à jour du site est immédiatement dans l'application, sans passage par les stores), barre d'état et de navigation natives, retour matériel, clavier, partage natif, appareil photo et galerie pour les pièces d'identité, géolocalisation, notifications FCM, vibrations. Au premier lancement, l'application demande la position et, si connecté, les notifications. Les APK Android sont construits par l'intégration continue ; le projet iOS se construit à la demande sur macOS.

---

## 4. Règles d'argent — valeurs exactes

Ces valeurs sont **la référence** ; elles sont implémentées à un seul endroit côté serveur et côté application, et testées.

### 4.1 Montants et commission

1. Devise : **XOF (FCFA)**, montants entiers, tout arrondi **aux 5 FCFA supérieurs** (pas de pièce plus petite en circulation).
2. Commission de la plateforme : **8 %** du montant de la réservation, arrondis aux 5 FCFA supérieurs. 0 % pour un conducteur abonné.
3. Acompte = `min(total, arrondi_5_sup(max(1 000, frais de service)))` ; solde à bord = total − acompte. Le `max` garantit que la plateforme encaisse toujours au moins sa commission.

*Exemple* : 3 places à 2 500 F = 7 500 F ; frais 8 % = 600 F ; acompte = max(1 000, 600) = **1 000 F** en ligne ; **6 500 F** à bord. Le conducteur reçoit 1 000 − 600 = **400 F** par reversement, plus les 6 500 F en main propre.

### 4.2 Reversement au conducteur

La plateforme ne redistribue **que ce qu'elle a réellement encaissé** : `acompte − frais` en `MOMO_DEPOSIT`, `total − frais` en `MOMO_FULL`, rien en `CASH`. Une réservation est reversable 24 h après le départ, si son acompte a bien été encaissé et non remboursé, et si elle est confirmée, terminée ou « passager absent ». Seuil minimum d'inclusion dans un lot : 2 000 F. Destination : le compte mobile money vérifié du conducteur ; sans compte, il est exclu du lot et prévenu.

### 4.3 Remboursement du passager

| Cas | Remboursement | Automatique ? |
|---|---|---|
| Le conducteur annule le trajet | Intégral | Oui, immédiat |
| Le conducteur refuse la demande, ou ne répond pas dans le délai | Intégral | Oui |
| L'acompte arrive après l'expiration des 20 minutes | Intégral | Oui |
| Le passager annule à plus de 24 h du départ | Intégral | Oui |
| Le passager annule à moins de 24 h | 50 % | File manuelle (Kkiapay ne rembourse pas en partie) |
| Le passager annule après le départ | Rien | — |
| Le passager ne se présente pas (déclaré par le conducteur sous 48 h) | Rien, acompte reversé au conducteur | — |
| **Le conducteur ne se présente pas** (déclaré par le passager sous 24 h) | Intégral | **Oui, après 24 h sans contestation** (voir 4.4) |

Les remboursements partent vers Kkiapay après validation de la transaction, avec reprise automatique toutes les 5 minutes ; après 5 échecs, ou pour un montant partiel, ils passent dans la **file manuelle du back-office** (délai annoncé au passager : 5 jours ouvrés). Le passager est prévenu à la demande et à la confirmation.

### 4.4 Litige « conducteur absent » (V25)

1. Le passager déclare l'absence (entre l'heure de départ et 24 h après). La réservation sort des reversements ; un signalement est ouvert ; le conducteur reçoit une alerte critique avec l'**échéance de contestation : déclaration + 24 h**.
2. **Sans contestation à l'échéance**, l'acompte est remboursé intégralement au passager, automatiquement ; le signalement est clos « par le système » ; les deux parties sont prévenues.
3. **Si le conducteur conteste** (explication obligatoire, depuis sa liste de passagers) : le remboursement est gelé, le signalement passe « en examen », le passager est prévenu.
4. La modération tranche dans le back-office, avec les deux versions, les échanges de la messagerie et la trace du direct s'il existait : **Rembourser le passager** (acompte remboursé, place jamais reversée) ou **Trajet maintenu, payer le conducteur** (la réservation rejoint le prochain reversement). Note obligatoire, transmise aux deux parties. Une décision est définitive.

### 4.5 Ce qui reste manuel

- **Le virement mobile money aux conducteurs** : aucune API de décaissement Kkiapay n'a pu être confirmée. Chaque lundi à 6 h, le système constitue les lots ; le fondateur fait les virements depuis son compte marchand puis marque chaque lot « réglé » (référence) ou « en échec » dans le back-office. Le conducteur est prévenu à chaque étape.
- **La file des remboursements manuels** (partiels, échecs définitifs, paiements sans référence exploitable).
- **Les litiges contestés.**

---

## 5. Back-office (administration et modération)

Accessible sur `/admin` aux comptes de rôle `ADMIN`. Toute action est journalisée (qui, quoi, quand, sur quel objet) dans un **journal d'audit** consultable et exportable.

| Écran | Contenu |
|---|---|
| **Tableau de bord** | Métrique nord (places confirmées, trajectoire vers 2 000/mois), liquidité (taux de recherche aboutie, recherche → réservation, remplissage, trajets orphelins), rétention, files d'attente (signalements ouverts, identités à vérifier et ancienneté, reversements dus, remboursements à traiter, gros émetteurs de messages) |
| **Liquidité** | Par axe et par mode : recherches sans résultat (les corridors à démarcher), remplissage, délai publication → première réservation ; export CSV |
| **Rétention** | Conducteurs qui republient (S+1, S+4), passagers qui rebookent sous 30 j, part du quotidien, navettes actives, conversion acompte, expirées, échecs Kkiapay **par opérateur**, répartition des modes, panier moyen ; export CSV |
| **Utilisateurs** | Recherche, fiche complète (trajets, réservations, paiements, signalements), correction de contact, suspension motivée (cascade : trajets annulés, passagers remboursés), réactivation, anonymisation |
| **Vérifications d'identité** | File filtrable, consultation des pièces (journalisée), validation ou refus motivé, retrait du badge |
| **Signalements** | Prise en charge, échanges liés (accès journalisé), résolution ou classement avec note, **dossier « conducteur absent »** avec acompte en jeu, échéance, version du conducteur et les deux boutons de décision |
| **Paiements** | File des remboursements à traiter à la main, relance d'un remboursement, marquage « remboursé » avec référence |
| **Reversements** | Lots par conducteur (montant, période, destination), déclenchement manuel d'un lot hors calendrier, **Régler** avec référence, **Échec** avec motif |
| **Comptes mobile money** | Vérification de possession du numéro |
| **Journal d'audit** | Filtres par acteur, action, objet, période ; export |

---

## 6. Exigences non fonctionnelles

**Sécurité.** HTTPS partout (HSTS), jetons signés sans clé de repli, rôles, cookie de rafraîchissement inaccessible au script, CORS restreint, limitation de débit par adresse réelle (authentification 60/min, codes 10 par 10 min, recherche 60/min, messagerie 30 par 10 min, alertes 10 par 10 min, webhook 120/min), journaux sans codes ni identifiants, documentation d'API et sondes fermées en production, pièces d'identité chiffrées, secrets hors du dépôt, rotation documentée.

**Protection des données** (`docs/CONFORMITE.md`). Registre des traitements, durées de conservation implémentées : codes de connexion purgés, notifications et messages 180 jours, alertes 90 jours, traces de recherche 180 jours, pièces d'identité 30 jours après décision, positions en direct 24 h, comptes non activés 24 h. Droits : accès (export), rectification, suppression (par code), opposition aux notifications non critiques. La déclaration auprès de l'APDP est à faire par le fondateur.

**Fiabilité.** Toute écriture d'argent est en deux temps (décision dans la transaction, appel à l'agrégateur après validation, reprise planifiée). Tâches planifiées avec une transaction par élément : une erreur n'en bloque pas une autre. Une seule instance du backend. Sauvegardes quotidiennes chiffrées hors site (Backblaze via rclone), exercice de restauration mensuel documenté.

**Réseau dégradé.** Cache persistant des données publiques, bandeau hors ligne, réessais uniquement sur erreur transitoire, délai de 20 s ; aucune donnée factice : chaque écran lit l'API ou affiche une erreur avec réessai.

**Accessibilité et ergonomie.** Cibles tactiles de 44 px, contrastes vérifiés automatiquement à chaque build, titres par écran, régions vivantes pour les lecteurs d'écran, mode sombre, heures toujours dans le fuseau du Bénin, tout en français, mise en page téléphone d'abord (375 px) jusqu'au poste de travail (1 920 px).

**Observabilité.** Journaux structurés avec identifiant de requête, erreurs en RFC 7807 avec identifiant, collecte des erreurs du navigateur, sonde de santé, procédure d'exploitation (`docs/EXPLOITATION.md`).

---

## 7. Architecture technique (annexe)

| Couche | Choix |
|---|---|
| Backend | Java 17, Spring Boot 3.5, PostgreSQL 16 + PostGIS, Flyway (25 migrations), Spring Security JWT, JPA, MapStruct, OpenAPI |
| Frontend | React 19, TypeScript, Vite, Tailwind v4, TanStack Query, react-router, Radix UI, Motion, MapLibre, date-fns, vite-plugin-pwa |
| Mobile | Capacitor 6 (Android versionné, iOS généré à la demande), greffons : caméra, géolocalisation, push, partage, clavier, barre d'état |
| Paiement | Kkiapay (widget, vérification serveur, webhook signé, remboursement) |
| Cartes | MapTiler (tuiles), référentiel géographique béninois en base |
| Infra | Docker Compose, Caddy (TLS), VPS partagé (nginx hôte + certbot), déploiement automatique par GitHub Actions après CI verte, images Docker publiées |
| Qualité | 587 tests unitaires backend, 43 tests d'intégration sur base réelle, 186 tests front, 14 parcours de bout en bout Playwright (Chrome et WebKit), lint, contraste et jetons vérifiés, harnais de captures d'écran mobile |

Principes : recherche géographique (jamais textuelle), erreurs HTTP normalisées, migrations jamais modifiées après écriture, une seule source pour chaque règle d'argent, transactions courtes, tâches planifiées idempotentes.

---

## 8. Exploitation : la routine du fondateur

| Fréquence | Tâche | Où |
|---|---|---|
| Chaque jour | Traiter les identités en attente, les signalements ouverts, la file des remboursements manuels | `/admin` |
| **Chaque lundi** | Virer les lots de reversement constitués à 6 h, puis les marquer « réglé » | `/admin/payouts` |
| Sous 48 h | Trancher les litiges « conducteur absent » contestés | `/admin/reports` |
| Chaque mois | Exercice de restauration de sauvegarde ; lecture de la liquidité et de la rétention ; corridors à démarcher | `docs/EXPLOITATION.md`, `/admin/liquidity` |
| À chaque incident | Procédure incident (sécurité des personnes d'abord, traçabilité, action sur les comptes) | `docs/EXPLOITATION.md` |

---

## 9. Ce qui n'est pas fait, et ce qui dépend du fondateur

**Bloquant avant ouverture au public**
- **Statut juridique du covoiturage rémunéré au Bénin** : non tranché ; à confirmer par un juriste béninois (agrément du ministère des Transports ?). Déclaration APDP à déposer.

**Actions hors du code, attendues du fondateur**
- Compte Firebase (notifications de l'application mobile), clé de signature Android et compte Google Play, empreinte du certificat dans `assetlinks.json`, compte Apple Developer pour iOS.
- Test d'un paiement réel Kkiapay en production, puis rotation de toutes les clés qui ont circulé en clair pendant la construction (Kkiapay, MapTiler, boîte e-mail, SSH).
- Sonde de disponibilité externe ; décision sur l'accès direct à la base (port local) pour DBeaver ; arbitrage de la marque Ekuiseo (partagée avec un logiciel immobilier du même fondateur).

**Limites assumées**
- Le virement aux conducteurs est manuel (pas d'API de décaissement confirmée).
- Le mode espèces confirme sans validation du conducteur ; une validation reste à concevoir si le contournement de commission devient un problème.
- Pas de course à la demande façon VTC : Ekuiseo est une plateforme de trajets planifiés.
- Web Push n'existe pas dans le WebView Android ; l'application utilise FCM, le site installé utilise Web Push, l'e-mail reste le canal principal.

**Pistes non engagées** : trajets réservés aux femmes, validation conducteur du mode espèces, paiement automatique des reversements si Kkiapay ouvre un décaissement, indicateurs de no-show et de délai de traitement des signalements.

---

## 10. Glossaire

- **Acompte** : part du prix payée en ligne à la réservation ; couvre au moins la commission.
- **Solde à bord** : reste payé en espèces au conducteur.
- **Reversement** : ce que la plateforme vire au conducteur (encaissé en ligne moins commission), par lot hebdomadaire.
- **Navette** : trajet quotidien récurrent ; un modèle génère des occurrences.
- **Occurrence** : un trajet concret issu d'une navette, à une date donnée.
- **Constat** : réponse du passager après le départ (trajet effectué / conducteur absent).
- **No-show** : absence au départ, du passager (déclarée par le conducteur) ou du conducteur (déclarée par le passager).
- **Kkiapay** : agrégateur de paiement mobile money béninois.
- **PWA** : site web installable comme une application.
- **Capacitor** : coque native qui embarque le site dans une application Android/iOS.
- **Zémidjan** : moto-taxi ; dans Ekuiseo, un véhicule de type moto, 1 passager, casque obligatoire.
