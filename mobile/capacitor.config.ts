import type { CapacitorConfig } from '@capacitor/cli'

/*
 * Ekuiseo — configuration Capacitor.
 *
 * L application mobile est un HABILLAGE NATIF de la PWA existante, pas une seconde
 * application : le WebView charge https://ekuiseo.com (mode serveur) et affiche donc
 * toujours la derniere version deployee, sans passer par une mise a jour du magasin.
 * Le service worker de la PWA (frontend/src/sw.ts) gere le cache et le hors-ligne
 * exactement comme dans un navigateur.
 *
 * `www/` ne contient qu une page de secours (index.html), affichee par `server.errorPath`
 * si le site est injoignable au premier lancement (aucun cache encore constitue).
 *
 * Alternative « paquet embarque » (non retenue par defaut, voir docs/MOBILE.md) :
 *   1. `cd frontend && npm run build` puis copier `frontend/dist/*` dans `mobile/www/` ;
 *   2. retirer `server.url` (et `errorPath`) ci-dessous ;
 *   3. `npx cap sync android`.
 * L application charge alors ses fichiers localement (demarrage instantane, mais chaque
 * evolution du front exige une nouvelle version dans les magasins, et le cookie de
 * session HttpOnly `SameSite=Strict` de l API n est plus envoye depuis une origine
 * `https://localhost` : il faudrait rouvrir CORS avec credentials cote API).
 */
const config: CapacitorConfig = {
  appId: 'bj.ekuiseo.app',
  appName: 'Ekuiseo',
  webDir: 'www',
  server: {
    url: 'https://ekuiseo.com',
    // Jamais de HTTP en clair : l API pose un cookie `Secure` et HSTS est actif.
    cleartext: false,
    // Page locale (www/index.html) affichee si le chargement de l URL echoue.
    errorPath: 'index.html',
  },
  android: {
    allowMixedContent: false,
    // Fond du WebView avant le premier rendu : `--bg` de la charte (theme clair).
    backgroundColor: '#F6F6F3',
    // Inspection du WebView (chrome://inspect) reservee aux builds de debogage.
    webContentsDebuggingEnabled: false,
  },
  ios: {
    contentInset: 'automatic',
    backgroundColor: '#F6F6F3',
  },
  plugins: {
    SplashScreen: {
      // Le site se charge depuis le reseau : on laisse l ecran de demarrage un peu
      // plus longtemps que le defaut (500 ms) pour eviter un flash de page blanche.
      launchShowDuration: 1500,
      launchAutoHide: true,
      launchFadeOutDuration: 200,
      backgroundColor: '#F6F6F3',
      androidScaleType: 'CENTER_INSIDE',
      showSpinner: false,
      splashFullScreen: false,
      splashImmersive: false,
    },
    StatusBar: {
      // Barre d etat aux couleurs de la charte : fond vert Benin, icones claires.
      overlaysWebView: false,
      style: 'DARK',
      backgroundColor: '#0E7C4A',
    },
  },
}

export default config
