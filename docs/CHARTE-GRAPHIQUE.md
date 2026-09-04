# Charte graphique Ekuiseo — « Route ouverte »

Référence pour toute personne qui dessine ou code un écran d'Ekuiseo. La version
vivante, rendue avec les vrais composants dans les deux thèmes, est dans l'application
à l'adresse `/charte`. Source de vérité des valeurs : `frontend/src/index.css`. Aucune
couleur, taille ou ombre ne doit être écrite ailleurs.

## 1. Concept et principes

Les **couleurs du drapeau béninois** deviennent les teintes de signal : le vert en
primaire (l'action, la route, le « go »), le jaune soleil en accent, le rouge en danger.
Elles sont posées sur des **neutres graphite** sans la moindre nuance bleue. Le filet
tricolore, inspiré des bannières appliquées d'Abomey, reprend le même ordre que le drapeau.

1. **La couleur porte du sens, jamais du décor.** Quatre teintes, chacune avec un rôle
   fixe. Un élément coloré appelle une lecture ou une action ; le reste est neutre.
2. **Une action principale par écran**, en indigo plein, 52 px de haut sur mobile.
3. **Lisible sur un téléphone d'entrée de gamme, au soleil.** Contraste ≥ 4,5:1 pour
   tout texte, cibles tactiles ≥ 44 px, chiffres tabulaires pour tout ce qui s'aligne.
4. **Une seule matière décorative** : la nappe lumineuse et la grille pointillée
   derrière le héro de l'accueil et les états vides. Nulle part ailleurs.
5. **Le mouvement oriente, il ne décore pas.** Deux durées, une courbe, rien sous
   `prefers-reduced-motion`.

## 2. Marque

- **Symbole** : carré vert Bénin (`--primary`) à coins 28 % (rx 9/32) portant un « E »
  à traits réguliers (épaisseur 3,5/32, marges 8/32) dont le bras central se prolonge en
  flèche : l'initiale devient une route qui avance. Découpé en négatif, tracé dans
  `LOGO_MARK_PATH` (`Logo.tsx`), repris tel quel dans `favicon.svg` et l'écran de
  démarrage. Il garde le vert plein dans les deux thèmes.
- **Logotype** (`variant="full"`) : symbole + « Ekuiseo » en Archivo 800, interlettrage
  −0,045 em, souligné d'un filet tricolore de 2 px. Version `stacked` pour les écrans
  système.
- **Filet tricolore** (`.banner-rule`) : 3 px sous l'en-tête, motif primaire 16 /
  accent 8 / danger 8 / vide 16. Signature de l'interface ; il n'apparaît nulle part
  ailleurs, sauf le bandeau « données de démonstration » qui reprend les hachures.
- **Favicon** : symbole seul (`public/favicon.svg`). Les icônes PNG du manifeste
  (`public/icons/*.png`) datent de l'ancienne marque et sont **à régénérer** depuis ce SVG
  (192, 512 et maskable 512 avec marge de sécurité de 20 %).
- Zone de protection : la moitié de la hauteur du symbole. Ne jamais l'étirer, le
  recolorer ni lui ajouter d'ombre portée hors de l'écran de connexion.

## 3. Couleurs

### 3.1 Neutres

| Rôle | Token | Clair | Sombre | Usage |
|---|---|---|---|---|
| Fond principal | `--bg` | `#f6f6f3` | `#0d0d0f` | Fond de page |
| Surface | `--surface` | `#ffffff` | `#151518` | Cartes, champs, menus |
| Fond secondaire | `--surface-2` | `#f0efeb` | `#1c1c20` | Zones secondaires, survol des lignes, onglets |
| Creux | `--surface-sunk` | `#e9e8e3` | `#08080a` | Champs désactivés |
| Filet | `--rule` | `#e6e5e0` | `#27272c` | Séparateurs, bordures de carte |
| Bordure de contrôle | `--rule-strong` | `#d1d0c9` | `#373741` | Champs, boutons secondaires |
| Texte principal | `--ink` | `#15161a` | `#f0efeb` | |
| Texte secondaire | `--ink-2` | `#45464b` | `#c5c4bd` | |
| Texte atténué | `--muted` | `#6b6b70` | `#979690` | Libellés, aides. 4,9:1 sur le fond, 4,6:1 sur `surface-2`. **Ne jamais éclaircir.** |

### 3.2 Teintes de signal

Chaque teinte existe en six rôles : pleine, `-hover`, `-active` (boutons pleins), `-soft`
et `-soft-2` (fonds pâles), `-ink` (texte sur fond pâle). **Un texte posé sur un fond
`-soft` utilise toujours la version `-ink`**, jamais la couleur pleine (qui plafonne à
3,8:1 sur fond pâle).

| Rôle | Token | Pleine | Survol | Appui | Fond pâle | Encre |
|---|---|---|---|---|---|---|
| Primaire (vert Bénin) — action, navigation, confirmation, revenu | `--primary` | `#0e7c4a` | `#0c6b40` | `#0a5836` | `#e4f4ec` | `#0b5a37` |
| Accent (jaune soleil) — attente, avertissement, notation | `--accent` | `#e5b100` | `#cf9f00` | `#b88d00` | `#fbf3d0` | `#785c00` |
| Danger (rouge) — destination, erreur, perte | `--danger` | `#d63b2a` | `#bf3424` | `#a62c1e` | `#fcebe8` | `#ad2f20` |

`--success` est un alias de `--primary` : confirmer, c'est avancer. Valeurs sombres dans
`index.css` (`:root.dark`), primaire `#4ccb8f`, accent `#f0c531`, danger `#f4725b`. Le texte
sur couleur pleine utilise `--<teinte>-contrast`. Les anciens noms (`--indigo`,
`--vermillon`, `--ocre`, `--vert`, `--paper`, `--surface-calm`) restent définis comme alias
vers les nouveaux rôles : ne pas les utiliser dans du code neuf. **Aucun bleu, nulle part.**

Correspondances fixes dans le produit :

- Origine d'un trajet : rond primaire. Destination : carré danger. Arrêt : point neutre.
- Réservation confirmée : primaire. Acompte attendu : accent. Annulée / non présenté : danger.
- Graphiques du back-office : graphite (`--ink-2`) = volume, primaire = revenu ou bon
  signe, accent = attente, danger = perte. Une seule série par axe.

### 3.3 Voile, focus, matière

- `--overlay` : voile des dialogues, `rgb(16 18 24 / 0.55)` clair, `rgb(0 0 0 / 0.68)`
  sombre, flou de 2 px.
- `--focus-ring` : primaire. Anneau de 2 px décollé de 2 px sur tout élément
  focalisable ; les champs portent un anneau intérieur pâle (`--primary-soft-2`, 3 px).
- `.ek-glass` : en-tête et barres collantes, fond à 82 % avec flou de 14 px.
- `.ek-glow` / `.ek-dots` : nappe lumineuse radiale et grille pointillée, réservées au
  héro de l'accueil et aux états vides.

## 4. Typographie

- **Archivo** (700 / 800) pour ce qui se lit de loin : titres, prix, heures, compteurs.
  Interlettrage −0,02 em, −0,035 em au-delà de 24 px.
- **Inter** (400 / 500 / 600 / 700) pour ce qui se lit de près : corps, libellés,
  navigation, boutons. Variantes `cv11` et `ss01` activées pour un dessin plus ouvert.
- Polices auto-hébergées, sous-ensemble latin uniquement.
- `.tnum` obligatoire sur tout nombre qui s'aligne : prix, heures, comptes à rebours,
  colonnes de tableau.

| Rôle | Classe | Taille / interligne | Police, graisse |
|---|---|---|---|
| H1 accueil | `text-hero` | 44 / 46 | Archivo 800 |
| H1 écran | `text-display-lg` | 32 / 36 | Archivo 800 |
| H2 / valeur clé | `text-display` | 24 / 28 | Archivo 800 |
| H3 | `text-heading` | 20 / 26 | Archivo 700 |
| H4 / titre de carte | `text-title` | 17 / 22 | Archivo 700 |
| Chapeau | `text-lead` | 16 / 24 | Inter 500 |
| Corps, champs | `text-base` | 15 / 22 | Inter 400 |
| Corps dense, navigation, boutons | `text-body` | 14 / 21 | Inter 400 / 600 |
| Libellés | `text-label` | 13 / 18 | Inter 500 |
| Mentions, puces, en-têtes de tableau | `text-caption` | 12 / 16 | Inter 600 |

Minimum absolu 12 px (11 px toléré dans la seule barre de navigation basse). Une taille
arbitraire (`text-[13px]`) signale qu'il manque un cran, pas une exception.

## 5. Espacement, rayons, ombres

- **Grille** de 4 px. Marges d'écran 16 px sur mobile, 24 px au-delà de 640 px. Colonne
  de contenu : 512 / 768 / 1 200 px. Sections espacées de 40 à 48 px, cartes à 20 px de
  padding.
- **Rayons** : `--radius-chip` 6 px (puces, cases), `--radius-control` 10 px (boutons,
  champs, menus), `--radius-card` 14 px (cartes), `--radius-panel` 18 px (feuilles,
  dialogues), `--radius-pill` pour les pastilles.
- **Ombres** : un hairline quasi invisible plus une ombre courte. `e1` cartes et boutons
  pleins, `e2` survol d'une carte cliquable (`.ek-lift`, translation de −2 px), `e3`
  menus, dialogues, panneau de recherche. Jamais d'ombre diffuse ou colorée.
- **Filets latéraux d'état** : 3 px à gauche d'une carte (accent = en attente, succès =
  confirmé, danger = annulé, primaire = à venir).

## 6. États des composants

| État | Boutons pleins | Champs | Lignes et cartes cliquables |
|---|---|---|---|
| Repos | teinte pleine, reflet intérieur 1 px, ombre e1 | filet `rule-strong`, fond `surface` | fond `surface`, ombre e1 |
| Survol | teinte `-hover` | filet assombri | −2 px, ombre e2, filet fort |
| Appui | teinte `-active`, échelle 0,98 | — | échelle 0,995 |
| Focus clavier | anneau `--focus-ring` décollé | filet primaire + anneau `primary-soft-2` 3 px | anneau décollé |
| Erreur | — | filet danger + anneau `danger-soft-2`, message sous le champ | — |
| Désactivé | opacité 50 %, pointeur inactif | fond `surface-sunk`, texte `muted` | opacité 70 % |
| Chargement | spinner + `aria-busy`, texte conservé | — | squelette `.shimmer` |

Tailles de bouton : `sm` 36 px, `md` 44 px, `lg` 52 px. Ces règles vivent dans
`button.tsx`, `card.tsx` (`interactive`) et la classe `.ek-field` de `index.css`,
partagée par `Input`, `Textarea`, `Select` et l'autocomplétion des villes.

## 7. Navigation et layout

- **En-tête** : 64 px, verre dépoli, logotype à gauche, onglets en pilule (fond
  `primary-soft`, animation partagée), bouton primaire « Publier un trajet », cloche,
  avatar. Entre 768 et 1024 px, les onglets passent en icônes avec libellé accessible.
- **Barre basse mobile** : quatre destinations et, au centre, « Publier » en bouton plein
  surélevé de 12 px avec anneau de fond. Les barres d'action collantes se placent à
  68 px du bas.
- **Back-office** : panneau latéral de 232 px avec bloc de marque et navigation groupée
  (Pilotage, Opérations), réductible en rail de 64 px ; barre d'onglets défilante sous
  1 024 px. Chaque écran commence par `AdminPageHeader` (titre, compteur, action).

## 8. Iconographie

- **Lucide React** exclusivement. 14 px dans une puce, 16 px dans un bouton compact,
  18 px dans un bouton ou une navigation, 22 px dans la barre basse, 24 px dans un état
  vide. Trait 2 (2,3 pour l'onglet actif de la barre basse, 2,4 pour le bouton
  « Publier »).
- Une icône seule porte toujours un `aria-label` ; une icône décorative `aria-hidden`.
- Pas d'emoji, pas d'illustration importée.

## 9. Mouvement

- Durées : `--duration-fast` 150 ms (survol, bascule), `--duration-base` 220 ms
  (apparition, transition d'écran, élévation). Courbe `--ease-standard`
  `cubic-bezier(0.22, 1, 0.36, 1)`, qui remplace le `ease-out` de Tailwind.
- Vocabulaire dans `lib/motion.ts` : transition d'écran directionnelle, cascade de liste
  (45 ms entre éléments), feuille de bas d'écran, fondu.
- Tout est neutralisé sous `prefers-reduced-motion`.

## 10. Ton rédactionnel

- Français, vouvoiement, phrases courtes. Montants en `1 000 FCFA`, heures en `06:30`.
- Un bouton dit ce qu'il fait : « Bloquer ma place pour 1 000 FCFA », pas « OK ».
- Une erreur dit quoi faire : « Numéro de téléphone incomplet », jamais un code.
- Les états vides proposent une action. Les confirmations rappellent la conséquence.

## 11. À ne pas faire

- Écrire une couleur en dur, un rayon ou une ombre hors de `index.css`.
- Poser la couleur pleine d'une teinte en texte sur son fond pâle.
- Ajouter un dégradé, une ombre colorée ou une animation décorative hors du héro.
- Mélanger deux bibliothèques d'icônes, ou une icône seule sans libellé accessible.
- Descendre sous 12 px de texte ou 44 px de cible tactile.
- Empiler deux actions principales sur un même écran.
