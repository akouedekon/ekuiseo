# Application mobile — Android (et préparation iOS)

Ekuiseo est une application **web** (PWA) et le reste : ce document décrit l'**habillage
natif** qui l'installe depuis un magasin d'applications, pas une seconde application.
Le projet vit dans `mobile/` (Capacitor 6), le projet Android généré dans `mobile/android/`,
la construction dans `.github/workflows/mobile-android.yml`.

Pourquoi un habillage alors que la PWA s'installe déjà depuis le navigateur : une fiche sur
le Play Store rassure (« c'est une vraie application »), certains constructeurs d'entrée
de gamme bloquent l'installation d'une PWA, et les liens `https://ekuiseo.com/trips/…`
partagés sur WhatsApp peuvent s'ouvrir directement dans l'application (App Links).

## 1. Architecture

```
mobile/
├── capacitor.config.ts      appId bj.ekuiseo.app, mode serveur, écran de démarrage, barre d'état
├── package.json             @capacitor/{core,cli,android,ios,app,status-bar,splash-screen}, @capacitor/assets
├── www/index.html           page de secours (site injoignable au premier lancement)
├── assets/                  sources 1024/2732 px des icônes et écrans de démarrage (générées)
├── scripts/generate-sources.mjs   les dessine depuis le symbole de la marque (Logo.tsx)
└── android/                 projet natif généré par `cap add android`, versionné
    ├── app/src/main/AndroidManifest.xml   permissions, App Links
    ├── app/src/main/res/values/colors.xml couleurs de la charte (barre d'état verte)
    ├── app/src/main/res/{mipmap,drawable}-*/  icônes et écrans de démarrage générés
    └── app/build.gradle     signature release lue dans keystore.properties s'il existe
```

**Mode serveur.** `capacitor.config.ts` déclare `server.url = 'https://ekuiseo.com'` : le
WebView charge le site déployé, exactement comme Chrome. Conséquences :

- **une mise à jour de l'application = une mise à jour du site.** Le workflow
  `deploy-prod.yml` suffit ; on ne republie sur le Play Store que si le projet natif change
  (permissions, plugins, icônes, version d'Android visée) ;
- la session fonctionne telle quelle : le cookie `HttpOnly` de rafraîchissement
  (`SameSite=Strict`) est posé sur l'origine `https://ekuiseo.com`, qui est aussi celle du
  WebView. Rien à changer côté API ni CORS ;
- le hors-ligne est celui de la PWA : le service worker (`frontend/src/sw.ts`) précache la
  coque et sert les données publiques en cache, ni plus ni moins ;
- `mobile/www/index.html` n'est affichée que si le site est **injoignable** (`server.errorPath`),
  typiquement au tout premier lancement sans réseau, avant qu'un cache existe. Un bouton
  « Réessayer » recharge `https://ekuiseo.com/` ;
- les liens vers un autre domaine (WhatsApp `wa.me`, e-mail) s'ouvrent dans l'application
  du système ; les cadres tiers (widget Kkiapay) restent dans la page. Si un jour une page
  d'un autre hôte doit s'ouvrir *dans* le WebView, l'ajouter à `server.allowNavigation`.

**Alternative « paquet embarqué » (non retenue).** Copier `frontend/dist/*` dans `mobile/www/`,
retirer `server.url` et `server.errorPath`, puis `npx cap sync android`. L'application
démarre sans réseau, mais chaque évolution du front exige une nouvelle version dans les
magasins (délai de validation, parc d'utilisateurs à plusieurs versions), et l'origine
devient `https://localhost` : le cookie de session n'est plus envoyé et il faudrait rouvrir
CORS avec credentials côté API, ce que l'audit a précisément fermé. À ne considérer que si
le mode serveur pose un problème mesuré.

## 2. Outils et commandes locales

- Node 22, JDK 17 (Temurin), et **Android Studio** (ou le SDK en ligne de commande, avec la
  plateforme 34 et les build-tools 34) pour construire ou lancer sur un émulateur. Sans SDK,
  on ne peut que synchroniser le projet : la construction se fait alors dans la CI (§ 3).
- Docker n'est pas nécessaire.

```bash
cd mobile
npm ci                       # dépendances Capacitor (package-lock.json versionné)
npx cap sync android         # copie www/ + config dans android/, met à jour les plugins
npm run open:android         # ouvre android/ dans Android Studio (exécuter, déboguer)
npm run assets               # régénère icônes et écrans de démarrage (§ 6)
npx cap doctor               # diagnostic des versions
```

Sans Android Studio, un APK de débogage se construit depuis `mobile/android` avec
`./gradlew assembleDebug` (`gradlew.bat` sous Windows) dès qu'`ANDROID_HOME` pointe sur un
SDK ; le résultat est dans `app/build/outputs/apk/debug/app-debug.apk`.

## 3. Construire et installer un APK de test

Le workflow **Mobile Android** (`.github/workflows/mobile-android.yml`) se lance à la main
(onglet *Actions* → *Mobile Android* → *Run workflow*) et automatiquement à chaque push sur
`main` qui touche `mobile/`. Il publie deux artefacts, conservés 30 jours :

| Artefact                  | Contenu                                                                 | Usage                                    |
| ------------------------- | ----------------------------------------------------------------------- | ---------------------------------------- |
| `ekuiseo-android-debug`   | `app-debug.apk`, signé avec la clé de débogage                          | tests sur un téléphone, jamais publiable |
| `ekuiseo-android-release` | `app-release.apk` + `app-release.aab` si les secrets de signature existent (§ 4), sinon `app-release-unsigned.apk` | Play Store (AAB) / distribution directe (APK) |

Installer l'APK de débogage sur un téléphone Android :

1. télécharger l'artefact depuis la page du run, le décompresser (`app-debug.apk`) ;
2. l'envoyer sur le téléphone (câble USB, WhatsApp « Document », Google Drive) ;
3. ouvrir le fichier ; Android demande d'autoriser l'installation d'applications inconnues
   pour l'application d'où vient le fichier (Fichiers, WhatsApp…) : accepter, installer ;
4. avec un câble et les outils de développement : `adb install -r app-debug.apk`.

Un APK de débogage et un APK release n'ont pas la même signature : Android refuse
d'installer l'un par-dessus l'autre, il faut désinstaller d'abord.

## 4. Keystore de signature et secrets GitHub

La version release doit être signée avec une clé qui appartient au fondateur. **Cette clé est
l'identité de l'application** : la perdre interdit toute mise à jour de l'application déjà
installée (sauf via Play App Signing, § 5), la divulguer permet de publier une fausse
version. Elle ne doit jamais entrer dans le dépôt (`*.keystore`, `*.jks` et
`keystore.properties` sont ignorés).

Création, une seule fois, sur le poste du fondateur (le `keytool` du JDK 17) :

```bash
keytool -genkeypair -v \
  -keystore ekuiseo-release.keystore -storetype PKCS12 \
  -alias ekuiseo -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Ekuiseo, O=Ekuiseo, L=Cotonou, C=BJ"
# mots de passe demandés : magasin et clé (les noter dans le gestionnaire de mots de passe)
```

Conserver le fichier **hors du dépôt et hors du VPS**, en deux endroits (gestionnaire de mots
de passe qui accepte les pièces jointes, disque chiffré). Puis déclarer quatre secrets dans le
dépôt GitHub (*Settings → Secrets and variables → Actions → New repository secret*) :

| Secret                      | Valeur                                                                  |
| --------------------------- | ----------------------------------------------------------------------- |
| `ANDROID_KEYSTORE_BASE64`   | le fichier encodé : `base64 -w0 ekuiseo-release.keystore` (Linux/macOS) ou `[Convert]::ToBase64String([IO.File]::ReadAllBytes('ekuiseo-release.keystore'))` (PowerShell) |
| `ANDROID_KEYSTORE_PASSWORD` | mot de passe du magasin                                                 |
| `ANDROID_KEY_ALIAS`         | `ekuiseo` (l'alias choisi ci-dessus)                                    |
| `ANDROID_KEY_PASSWORD`      | mot de passe de la clé                                                  |

Tant que l'un des quatre manque, le workflow saute la signature et produit un APK release
**non signé** (utile seulement pour vérifier que le projet compile). Dès qu'ils existent,
il écrit `mobile/android/keystore.properties` le temps du job, signe l'APK **et** l'AAB,
puis efface le keystore du runner. Le `versionCode` de chaque build est le numéro de run
GitHub (`github.run_number`), strictement croissant comme l'exige le Play Store ; le
`versionName` est `1.0.<numéro de run>`.

Empreinte SHA-256 du certificat (nécessaire pour les App Links, § 7) :

```bash
keytool -list -v -keystore ekuiseo-release.keystore -alias ekuiseo | grep SHA256
# SHA256: AB:CD:12:…  → à copier telle quelle (majuscules, séparées par « : »)
```

Construire une release signée **en local** : créer `mobile/android/keystore.properties`
(`storeFile=app/ekuiseo-release.keystore`, `storePassword=…`, `keyAlias=ekuiseo`,
`keyPassword=…`), copier le keystore à l'emplacement indiqué, puis `./gradlew assembleRelease`
ou `bundleRelease`. Les deux fichiers sont ignorés par git.

## 5. Publication sur le Play Store

1. **Compte développeur Google Play** (frais uniques, environ 25 USD) au nom du fondateur ou de
   la structure qui portera le service. Depuis 2023, un compte personnel doit tester
   l'application avec au moins 12 testeurs pendant 14 jours (test fermé) avant l'accès en
   production ; un compte d'organisation en est dispensé mais exige un numéro D-U-N-S.
2. **Play App Signing** (activé par défaut à la création de l'application) : Google conserve
   la clé de signature finale et le keystore du § 4 devient la *clé d'upload*. C'est
   recommandé (une clé d'upload perdue se remplace), mais **l'empreinte à publier dans
   `assetlinks.json` est alors celle de la clé de signature de Google**, affichée dans
   *Play Console → Configuration → Intégrité de l'application → Signature d'application*,
   pas celle du keystore local. Pour un APK distribué hors magasin, c'est l'empreinte du
   keystore local : les deux peuvent figurer dans le tableau `sha256_cert_fingerprints`.
3. **Fiche** : nom, description courte et longue en français, icône 512 px
   (`mobile/assets/icon-only.png`), bannière 1024×500 à produire, captures d'écran
   téléphone (au moins deux), catégorie *Cartes et navigation* ou *Voyages*, adresse de
   contact `contact@ekuiseo.com`.
4. **Politique de confidentialité** : URL obligatoire, `https://ekuiseo.com/confidentialite`
   (page existante ; `https://ekuiseo.com/cgu` pour les conditions). Le questionnaire
   *Sécurité des données* doit déclarer : position (approximative et précise, facultative,
   pour la recherche et le suivi de trajet), identifiants (téléphone, e-mail), pièces
   d'identité (conducteurs, chiffrées, purgées 30 jours après décision — `docs/CONFORMITE.md`),
   messages entre utilisateurs, informations de paiement traitées par Kkiapay.
5. **Envoi** : télécharger `app-release.aab` depuis l'artefact `ekuiseo-android-release`
   d'un run signé, le déposer dans *Tests internes* d'abord, puis promouvoir. Chaque nouvel
   envoi exige un `versionCode` supérieur : relancer le workflow suffit.
6. Le point juridique de `CLAUDE.md` (statut du covoiturage rémunéré au Bénin) s'applique
   à la publication comme à l'ouverture du site : une fiche publique sur le Play Store est une
   ouverture au public.

## 6. Icônes et écran de démarrage

`npm run assets` (dans `mobile/`) enchaîne deux étapes :

1. `scripts/generate-sources.mjs` dessine, sans dépendance, les sources exigées par
   `@capacitor/assets` dans `mobile/assets/` (icône opaque 1024 px, couches avant/arrière de
   l'icône adaptative, écrans de démarrage clair et sombre 2732 px) depuis le tracé du symbole
   lu dans `frontend/src/components/layout/Logo.tsx` — le même rasteriseur que
   `frontend/scripts/icons.mjs`, qui produit les icônes de la PWA. Les couleurs sont celles de
   la charte (`docs/CHARTE-GRAPHIQUE.md`).
2. `capacitor-assets generate --android` produit les `mipmap-*` (icône adaptative : fond vert
   plein, « E » blanc dans la zone sûre) et les `drawable-*` (écrans de démarrage portrait,
   paysage, clair, sombre). Ces fichiers sont versionnés : la CI ne les régénère pas.

Le rendu a été vérifié sur les fichiers produits (icône ronde xxxhdpi, écran de démarrage
xhdpi) mais pas sur un appareil : à contrôler au premier APK installé, en particulier la
taille du symbole sur un lanceur à icônes rondes. Pour iOS, relancer avec `--ios` une fois la
plateforme ajoutée (§ 8).

## 7. App Links : ouvrir les liens de trajet dans l'application

Le manifeste déclare un filtre `android:autoVerify="true"` sur `https://ekuiseo.com`. Au
premier lancement (et à chaque installation), Android télécharge
`https://ekuiseo.com/.well-known/assetlinks.json` et n'associe le domaine à l'application
que si le fichier cite le nom de paquet `bj.ekuiseo.app` **et** l'empreinte SHA-256 du
certificat qui a signé l'APK installé. Tant que ce n'est pas le cas, les liens s'ouvrent dans
le navigateur (l'application reste utilisable, elle n'est simplement pas proposée).

Le gabarit est dans `frontend/public/.well-known/assetlinks.json` avec la valeur
`A REMPLACER` : y mettre l'empreinte (§ 4, ou celle de Play App Signing, § 5 ; plusieurs
valeurs possibles), puis déployer le front normalement. Vite copie `public/` tel quel dans
`dist/`, dossiers cachés compris ; le nginx du conteneur front sert le fichier (aucune règle
ne bloque les chemins en `.`), Caddy le relaie par sa route par défaut et le nginx de l'hôte
n'intercepte que `/.well-known/acme-challenge/` (certbot). Aucune route à ajouter.

Vérifications :

```bash
curl -sI https://ekuiseo.com/.well-known/assetlinks.json | grep -i "HTTP/\|content-type"
# HTTP/2 200, content-type: application/json

# Résultat de la vérification par Google (mêmes règles qu'Android) :
curl -s "https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://ekuiseo.com&relation=delegate_permission/common.handle_all_urls"

# Sur un téléphone relié en USB, état de l'association :
adb shell pm get-app-links bj.ekuiseo.app      # « verified » attendu
adb shell pm verify-app-links --re-verify bj.ekuiseo.app
```

Seul le domaine nu est déclaré : `www.ekuiseo.com` redirige vers lui et Android ne suit pas
les redirections pendant la vérification, un hôte en échec ferait échouer l'ensemble.
L'aperçu Open Graph des liens partagés (`/share/trips/{id}`, Caddyfile) n'est pas affecté :
WhatsApp le lit côté serveur, avant que l'utilisateur ne touche le lien.

## 8. iOS

Non généré dans ce dépôt : la plateforme iOS exige macOS et Xcode. Procédure, le jour venu :

1. un Mac avec Xcode (App Store) et CocoaPods (`sudo gem install cocoapods`) ;
2. un **compte Apple Developer** (99 USD/an), indispensable même pour installer sur son
   propre iPhone au-delà de sept jours ;
3. dans `mobile/` : `npm ci`, `npx cap add ios`, `npx capacitor-assets generate --ios`,
   `npx cap sync ios`, `npx cap open ios` ; dans Xcode, choisir l'équipe de signature
   (*Signing & Capabilities*), l'identifiant `bj.ekuiseo.app`, puis exécuter sur un appareil ;
4. les équivalents iOS de ce document : *Universal Links* (fichier
   `/.well-known/apple-app-site-association`, à ajouter à `frontend/public/.well-known/` avec
   l'identifiant d'équipe, et l'entitlement *Associated Domains* dans Xcode), permission de
   position (`NSLocationWhenInUseUsageDescription` dans `Info.plist`, texte en français),
   publication via App Store Connect et TestFlight ;
5. **risque de refus** : Apple rejette les applications qui ne sont « qu'un site web
   emballé » (directive 4.2, *Minimum functionality*). Une soumission iOS devra apporter
   quelque chose que Safari n'offre pas (notifications natives, par exemple) ou s'y préparer
   argumentairement. Sur iPhone, la PWA installée depuis Safari reste la voie sans friction.

**Sans Mac** : le workflow « Mobile iOS » (`.github/workflows/mobile-ios.yml`, déclenchement manuel
depuis l'onglet Actions, runner macOS) génère le projet iOS à la volée (`cap add ios`), le compile pour
le simulateur (artefact `ekuiseo-ios-simulator`, preuve que tout compile) et, si les secrets de
signature sont fournis (`IOS_CERTIFICATE_P12_BASE64`, `IOS_CERTIFICATE_PASSWORD`,
`IOS_PROVISIONING_PROFILE_BASE64`, `IOS_TEAM_ID`, `APPLE_KEYCHAIN_PASSWORD`, et `GOOGLE_SERVICE_INFO_PLIST`
pour Firebase), produit une archive `.ipa` (artefact `ekuiseo-ios-ipa`) à envoyer sur TestFlight. Les minutes
macOS coûtent dix fois celles d'Ubuntu : ne le lancer qu'à bon escient.

## 9. Notifications natives (Firebase Cloud Messaging)

L'application reçoit ses notifications par Firebase Cloud Messaging (FCM), l'équivalent natif du
Web Push de la PWA (V24). Rien n'est actif tant que les deux éléments ci-dessous ne sont pas
fournis ; l'application fonctionne sans, avec les e-mails.

1. **Projet Firebase** : console.firebase.google.com → créer un projet « Ekuiseo » → ajouter une
   application Android avec le nom de paquet `bj.ekuiseo.app` → télécharger `google-services.json`.
   Ne le commitez pas : encodez-le et placez-le dans le secret GitHub `GOOGLE_SERVICES_JSON` :

   ```bash
   base64 -w0 google-services.json
   ```

   Le workflow « Mobile Android » l'écrit dans `mobile/android/app/` avant la construction ;
   sans secret, l'APK se construit sans notifications.
2. **Compte de service** (pour que le serveur envoie) : Paramètres du projet → Comptes de service →
   « Générer une nouvelle clé privée » (JSON). Sur le VPS, dans `/opt/ekuiseo/.env` :

   ```bash
   FCM_SERVICE_ACCOUNT_JSON=$(base64 -w0 ekuiseo-firebase.json)
   ```

   puis `docker compose -f docker-compose.prod.yml up -d backend`. Le journal affiche
   « Notifications natives (FCM) actives pour le projet … ». Le fichier JSON ne doit vivre nulle part
   ailleurs que dans le .env et votre gestionnaire de mots de passe : c'est un secret.

Côté application, la demande de permission se fait au premier lancement (fenêtre « Bienvenue »),
puis depuis les réglages du compte ; le jeton est enregistré à `POST /api/v1/me/push-subscriptions`
avec `kind = FCM`, et retiré à la déconnexion. iOS demandera en plus un certificat APNs dans Firebase.

## 10. Fonctions natives de l'application

Le site détecte qu'il tourne dans l'application (`window.Capacitor`, voir `frontend/src/lib/native.ts`)
et adapte son comportement, sans seconde base de code :

| Fonction | Dans l'application | Dans un navigateur |
|---|---|---|
| Apparence | classe `app-native` : zones sûres (encoche, barre d'état), pas de surlignage au toucher ni de menu d'appui long, pas de rebond, pied de page et bandeau « installer » masqués, mise à jour appliquée sans demander | comportement web habituel |
| Barre d'état | couleurs du thème clair/sombre (`@capacitor/status-bar`) | — |
| Bouton Retour (Android) | recule dans l'historique ; quitte l'application depuis un écran racine (`@capacitor/app`) | — |
| Position | greffon `@capacitor/geolocation` et API du WebView ; permission demandée au premier usage | API Geolocation |
| Suivi en direct (V28) | `watchPosition` du WebView pendant le partage (conducteur ou passager confirmé), mis en pause quand l'application passe en arrière-plan et repris au retour (`App.addListener('appStateChange')`, `features/trips/usePositionSharing.ts`) ; flux SSE lu par `fetch` (pas d'EventSource) ; refus de permission et GPS coupé signalés en clair dans la fiche du trajet | même code, pause/reprise sur `visibilitychange` |
| Pièces d'identité | « Prendre la photo » et « Galerie » via `@capacitor/camera` (permissions CAMERA et lecture des images) | sélecteur de fichiers |
| Partage d'un trajet ou du suivi | feuille de partage native (`@capacitor/share`) | `navigator.share` ou copie du lien |
| Vibrations | confirmation de réservation, constat de trajet (`@capacitor/haptics`) | — |
| Clavier | le WebView se redimensionne avec le clavier (`@capacitor/keyboard`) | — |

Les greffons sont déclarés **des deux côtés** avec la même version majeure : dans `frontend/package.json`
(la partie JavaScript, importée à la demande) et dans `mobile/package.json` (la partie native, enregistrée
par `npx cap sync android`). Ajouter un greffon = les deux fichiers, puis `cap sync`.

## 11. Limites connues

- **Notifications** : pas de Web Push dans le WebView Android ; l'application passe par Firebase
  Cloud Messaging (§ 9), actif seulement une fois le projet Firebase et le compte de service fournis.
  Les **e-mails restent le canal principal**.
- **Hors-ligne** : rien au-delà du service worker de la PWA. Au premier lancement sans
  réseau, seule la page de secours s'affiche.
- **Mise à jour** : l'application affiche toujours le site en production ; un incident du
  site est un incident de l'application, et la version affichée dans l'application n'a aucun
  lien avec le `versionName` du magasin.
- **Position** : demandée par Android au premier usage de la géolocalisation dans la page ;
  l'utilisateur doit accepter deux fois (invite Android, puis — selon la version du WebView —
  invite du site). Aucun accès en arrière-plan.
- **Suivi en direct en arrière-plan (V28)** : le WebView ne reçoit plus de position dès que
  l'application n'est plus au premier plan (écran verrouillé, autre application, appel
  téléphonique). Le partage se met alors en pause (« Partage en pause : revenez sur
  l'application pour continuer ») et reprend seul au retour ; côté passagers, la position du
  conducteur devient « indisponible momentanément » après 90 s sans mise à jour, jamais
  présentée comme actuelle. Un vrai suivi en arrière-plan exigerait un service natif de
  premier plan (permission `ACCESS_BACKGROUND_LOCATION`, notification permanente, justification
  auprès du Play Store) : non retenu à ce stade — le conducteur garde l'écran allumé (Wake Lock
  demandé quand le WebView le permet) ou pose le téléphone sur un support. Le flux SSE, lui,
  se reconnecte seul (1 s → 30 s) au retour du réseau ou au premier plan. Non vérifié sur un
  appareil réel depuis ce poste (SDK Android absent) : le comportement exact de la pause/reprise
  et le délai de la première position après reprise sont à mesurer sur un téléphone d'entrée de
  gamme.
- **Photos de pièces d'identité** : prise de vue et galerie natives (voir § 9) ; la permission
  `CAMERA` est déclarée, à justifier dans le questionnaire de la fiche Play (vérification d'identité).
- **Bouton Retour** : navigue dans l'historique du WebView, puis quitte l'application à la
  racine (comportement Capacitor par défaut).
- **Paiement Kkiapay** : le widget s'ouvre dans la page comme dans Chrome ; à valider sur un
  appareil avec un paiement sandbox avant toute publication (`docs/DEPLOIEMENT.md` § 12).
- **Barre d'état** : verte (`#0E7C4A`) avec icônes claires, quel que soit le thème du site.
- **Non vérifié sur ce poste** (SDK Android absent) : la compilation Gradle, donc le premier
  run du workflow doit être lu attentivement ; l'affichage de la page de secours
  (`server.errorPath`) en mode serveur ; le rendu des icônes sur un vrai lanceur.
