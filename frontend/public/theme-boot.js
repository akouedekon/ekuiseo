/*
 * Theme memorise (localStorage « ekuiseo.theme », voir src/lib/theme.ts) pose sur
 * <html> AVANT le premier rendu : sans cela, un utilisateur en mode sombre voyait
 * l'ecran de demarrage clair puis un basculement (audit F332). Fichier externe et
 * synchrone plutot qu'un script inline : la CSP de production (Caddyfile,
 * `script-src 'self'`) refuse l'inline. React rejoue la meme regle ensuite (applyTheme).
 */
;(function () {
  try {
    var stored = localStorage.getItem('ekuiseo.theme')
    var dark =
      stored === 'dark' ||
      ((stored === null || stored === 'system') &&
        window.matchMedia &&
        window.matchMedia('(prefers-color-scheme: dark)').matches)
    if (dark) document.documentElement.classList.add('dark')
  } catch {
    /* stockage indisponible : theme systeme via prefers-color-scheme, applique par React */
  }
})()
