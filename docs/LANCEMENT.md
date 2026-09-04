# Lancement — checklist avant d'ouvrir au public

Checklist opérationnelle pour passer d'une instance techniquement déployée
(`docs/DEPLOIEMENT.md`) à une ouverture au public assumée. Cochez chaque point avant
toute communication publique (réseaux sociaux, presse, affichage physique...).

## 1. Vérifications techniques préalables

- [ ] `cd backend && mvn -B verify` passe sans erreur (rappel : jamais vérifié dans
      l'environnement où ce dépôt a été généré — voir README racine).
- [ ] `npm run build` du frontend passe sans erreur, `npm run lint` ne remonte aucune
      erreur bloquante.
- [ ] Les points listés dans `docs/DEPLOIEMENT.md` (section 12, "Points d'attention
      connus") sont traités : CORS restreint au(x) domaine(s) réel(s), route
      `GET /api/v1/trips/{id}` ne fuite pas les trajets `DRAFT`, `KKIAPAY_WEBHOOK_SECRET`
      bien configuré et `KKIAPAY_MODE=http`.
- [ ] `SMS_MODE=http` avec `SMS_HTTP_URL`/`SMS_PROVIDER_KEY` configurés pour un vrai
      fournisseur, adapté au contrat exact de ce fournisseur (voir la javadoc de
      `HttpSmsGateway`) — les OTP ne doivent plus être uniquement journalisés en clair
      dans les logs applicatifs en production.
- [ ] Sauvegardes automatiques en place et testées (`docs/EXPLOITATION.md`) —
      **testez une restauration réelle au moins une fois avant le lancement**, pas
      seulement la sauvegarde.

## 2. Comptes de test

- [ ] Au moins 2 comptes conducteur et 2 comptes passager de test, distincts des
      comptes de démonstration commerciale (`docs/donnees-demo.sql`), utilisés pour
      valider le parcours complet en conditions réelles (vrai numéro de téléphone,
      vrai OTP reçu, vrai paiement en sandbox puis en production).
- [ ] Le jeu de données de démonstration (`docs/donnees-demo.sql`) n'est **pas** chargé
      sur l'instance de production destinée au public (voir l'avertissement dans
      `scripts/seed-demo.sh`) — à réserver à un environnement de démonstration commerciale
      séparé ou à la recette.

## 3. Clés Kkiapay de production

- [ ] Compte marchand Kkiapay activé en mode production (pas seulement sandbox) —
      démarche à faire directement auprès de Kkiapay, généralement avec justificatifs
      d'identité de l'entité exploitant Ekuiseo.
- [ ] `KKIAPAY_PUBLIC_KEY`, `KKIAPAY_PRIVATE_KEY`, `KKIAPAY_SECRET`, `KKIAPAY_WEBHOOK_SECRET`
      mis à jour avec les valeurs de production, `KKIAPAY_MODE=http` et
      `KKIAPAY_SANDBOX=false` dans `.env` de production (sans `KKIAPAY_MODE=http`
      explicite, le backend reste en mode `stub` : aucun paiement réel n'est jamais
      vérifié auprès de Kkiapay, quelles que soient les clés renseignées).
- [ ] Un paiement réel de faible montant testé de bout en bout — rappel : depuis la
      migration `V7`, ce qui est débité en ligne pour une réservation `MOMO_DEPOSIT`
      (mode par défaut) n'est que l'**acompte** (`BOOKING_DEPOSIT_BASE_FCFA`, 1 000 FCFA
      par défaut, ou les frais de service si supérieurs — voir README et
      `.env.example`), pas le prix total du trajet ; le test doit vérifier ce montant
      exact prélevé, la confirmation reçue, **et** que le solde restant
      (`balance_due_on_board`) est bien annoncé au passager comme réglable en espèces
      au conducteur à bord.
- [ ] `BOOKING_DEPOSIT_BASE_FCFA` fixé en connaissance de cause (paramètre commercial,
      voir README) : un plancher trop bas expose la plateforme à percevoir un acompte
      inférieur à sa propre commission sur les petits trajets (le reversement au
      conducteur reste correct grâce à `FeePolicy#computeDepositAmount`, mais la
      trésorerie encaissée en ligne par transaction peut alors être quasi nulle).
- [ ] Modalités de reversement aux conducteurs (`driver_payouts` dans le schéma)
      clarifiées et testées : fréquence, seuil minimal, canal (MoMo vers quel numéro).
      **Point à ne pas sous-estimer côté communication conducteurs** : depuis le
      paiement fractionné (règle métier n°21), un reversement ne porte plus que sur
      l'acompte encaissé en ligne diminué de la commission — jamais sur le solde en
      espèces perçu directement à bord, qui ne transite plus du tout par la
      plateforme. Un conducteur qui compare un reversement à l'ancien montant intégral
      qu'il recevait (ou s'attend à recevoir la totalité de la course) doit être
      informé **avant** le lancement que l'essentiel de sa rémunération sur une
      réservation `MOMO_DEPOSIT` lui est désormais versé en espèces par le passager
      pendant le trajet, et non par un virement ultérieur de la plateforme.

## 4. Numéro d'assistance / support

- [ ] Un numéro de téléphone et/ou un contact (WhatsApp, e-mail) dédié au support
      utilisateur est choisi, testé, et surveillé activement dès le jour du
      lancement (les premiers jours concentrent le plus d'incidents et de questions).
- [ ] Ce contact est affiché de façon visible dans l'application et/ou sur les
      supports de communication du lancement.
- [ ] Une procédure minimale de traitement des demandes de support est définie (qui
      répond, sous quel délai, comment une demande urgente — incident de sécurité,
      accident — est escaladée).

## 5. CGU et politique de confidentialité

- [ ] **Conditions Générales d'Utilisation** rédigées et publiées, couvrant a minima :
      la nature de la mise en relation (Ekuiseo est un intermédiaire, pas un
      transporteur — à formuler précisément selon la qualification retenue après
      consultation juridique, voir `docs/CONFORMITE.md` section 6), les obligations
      respectives conducteur/passager, la politique d'annulation
      (`CancellationPolicy`, déjà implémentée côté backend — les CGU doivent en
      refléter exactement les paliers), les modalités de paiement et de remboursement,
      les sanctions possibles (suspension de compte).
- [ ] **Politique de confidentialité** rédigée et publiée, cohérente avec le travail
      fait dans `docs/CONFORMITE.md` : données collectées, finalités, durées de
      conservation, sous-traitants (Kkiapay, fournisseur SMS, hébergeur), droits des
      personnes et comment les exercer concrètement (adresse de contact).
- [ ] Les deux documents sont **rédigés ou validés par un juriste béninois**, pas
      uniquement générés automatiquement.
- [ ] Un mécanisme d'acceptation explicite (case à cocher à l'inscription, horodatée)
      est en place — à vérifier côté frontend/backend.

## 6. Procédure de modération

- [ ] Un processus de traitement des signalements est défini (voir
      `docs/EXPLOITATION.md`, section incidents) : qui est notifié, sous quel délai,
      quelles actions possibles (avertissement, suspension temporaire, suspension
      définitive), et une trace écrite de chaque décision.
- [ ] Des critères clairs de suspension de compte sont définis à l'avance (fraude au
      paiement, comportement dangereux signalé, faux profil, non-respect répété des
      annulations...) pour éviter des décisions arbitraires en situation de tension.
- [ ] Un canal de recours minimal existe pour un utilisateur suspendu qui conteste la
      décision.
- [ ] Le circuit de vérification d'identité (`identity_verifications`, back-office :
      un humain approuve/rejette chaque soumission) est bien suivi en pratique avant
      qu'un nouveau conducteur ne publie son premier trajet — le type et le numéro de
      document sont déclaratifs (aucune pièce jointe n'est actuellement demandée ni
      stockée, voir README), donc une vérification humaine complémentaire (photo de la
      pièce envoyée par un autre canal, permis de conduire) reste nécessaire tant
      qu'aucun upload n'existe côté produit.

## 7. Corridor de lancement

Recommandation : ne pas ouvrir toutes les liaisons du premier coup. Un lancement
progressif limite le risque opérationnel et permet de roder le support avec un volume
gérable.

- [ ] Choisir **un corridor prioritaire** pour le lancement (candidats naturels vu le
      jeu de données de démonstration : Cotonou–Bohicon pour l'interurbain, ou
      Abomey-Calavi–Cotonou pour le quotidien — ce dernier a l'avantage d'un volume de
      trajets élevé et récurrent, utile pour roder rapidement le produit).
- [ ] Recruter une masse critique de conducteurs sur ce corridor **avant** d'ouvrir aux
      passagers (un passager qui ne trouve aucun trajet disponible à son premier essai
      ne revient généralement pas).
- [ ] Élargir aux autres corridors (Parakou, Natitingou, Porto-Novo, Lomé) seulement
      une fois le premier corridor stable (support, paiements, taux d'annulation sous
      contrôle).
- [ ] Pour le corridor transfrontalier Cotonou–Lomé : vérifier spécifiquement les
      implications douanières/frontalières et l'assurance (traversée de frontière) avant
      de le promouvoir activement — distinct des questions de statut du covoiturage
      rémunéré déjà signalées en `docs/CONFORMITE.md`.

## 8. Indicateurs à suivre les 30 premiers jours

À instrumenter dès le lancement (tableur manuel acceptable au départ, à automatiser
ensuite) :

| Indicateur | Pourquoi le suivre |
|---|---|
| Nombre d'inscriptions (conducteurs / passagers), quotidien | Mesurer l'acquisition et l'équilibre offre/demande |
| Nombre de trajets publiés, quotidien | Mesurer l'offre réelle disponible |
| Taux de conversion recherche → réservation | Identifie un problème de pertinence des résultats ou de friction au paiement |
| Taux de réservations `PENDING_PAYMENT` expirées sans paiement | Signal de friction ou de panne côté paiement (voir `docs/EXPLOITATION.md`) |
| Taux d'annulation (par le passager / par le conducteur) | Signal de qualité de service ; un taux élevé côté conducteur est préoccupant |
| Taux de `NO_SHOW` | Signal de fiabilité des passagers, peut nécessiter une politique dédiée |
| Note moyenne (`rating_avg`) glissante sur 7 jours | Signal de qualité perçue |
| Délai moyen de première réponse du support | Qualité perçue du support, surtout critique les 30 premiers jours |
| Nombre de signalements/incidents | Volume de charge de modération, à comparer au volume de trajets |
| Volume de transactions Kkiapay réussies vs échouées | Fiabilité du parcours de paiement |
| Répartition des trajets par corridor | Valide (ou invalide) le choix du corridor de lancement |

Revoir ces indicateurs de façon rapprochée (hebdomadaire, voire quotidienne au tout
début) pendant les 30 premiers jours, puis passer à un suivi mensuel une fois la
plateforme stabilisée.
