# Déploiement — du VPS nu à la production

Ce guide part d'un VPS Hostinger tout neuf (Ubuntu 22.04/24.04 LTS recommandé) et va
jusqu'à une instance Ekuiseo accessible en HTTPS avec un compte administrateur créé.

> **Avant de commencer** : le backend n'a **jamais été compilé** dans l'environnement où
> ce dépôt de démarrage a été généré (Maven Central y était bloqué par la politique
> réseau). La toute première chose à faire, avant tout déploiement, est de vérifier que
> `cd backend && mvn -B verify` passe sur une machine avec accès réseau normal (votre
> poste, ou le VPS lui-même). Voir aussi le README racine et
> `backend/BUILD_NOTES.md` s'il existe.

## 1. Prérequis

- Un VPS avec au moins 2 vCPU / 4 Go de RAM (les limites de ressources de
  `docker-compose.prod.yml` sont calibrées pour ce gabarit ; ajustez-les si votre VPS
  est plus petit ou plus grand).
- Accès `root` ou `sudo` en SSH.
- Un **nom de domaine** (ex. `ekuiseo.bj` ou `app.ekuiseo.bj`) dont vous pouvez modifier
  les enregistrements DNS.
- Des clés Kkiapay (au minimum les clés de **test/sandbox** pour commencer — voir
  `docs/LANCEMENT.md` pour le passage en clés de production).

## 2. DNS

Créez un enregistrement DNS de type `A` (et `AAAA` si le VPS a une IPv6) pointant votre
domaine vers l'adresse IP publique du VPS :

```
A     ekuiseo.bj          -> <IP_DU_VPS>
A     www.ekuiseo.bj      -> <IP_DU_VPS>
```

**Attendez la propagation DNS avant de démarrer Caddy** (vérifiez avec
`dig +short ekuiseo.bj` depuis une machine externe) : Caddy tente d'obtenir un certificat
Let's Encrypt dès son démarrage, et échoue si le domaine ne pointe pas encore vers le
serveur. La propagation prend généralement de quelques minutes à quelques heures selon
le registraire.

## 3. Pare-feu

N'ouvrez que le strict nécessaire :

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH        # ou le port SSH que vous utilisez si différent de 22
sudo ufw allow 80/tcp          # HTTP (redirection + challenge Let's Encrypt)
sudo ufw allow 443/tcp         # HTTPS
sudo ufw allow 443/udp         # HTTP/3 (QUIC), optionnel mais recommandé
sudo ufw enable
```

Ne publiez **jamais** le port 5432 (PostgreSQL) sur l'interface publique : en
production (`docker-compose.prod.yml`), la base n'a de toute façon aucune entrée
`ports:` et n'est même pas sur le même réseau Docker que Caddy — elle est
structurellement injoignable depuis l'extérieur.

## 4. Installation de Docker

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"
# reconnectez-vous (ou `newgrp docker`) pour que l'appartenance au groupe prenne effet
docker compose version   # doit afficher une version Compose v2
```

## 5. Récupération du code

```bash
sudo mkdir -p /opt/ekuiseo && sudo chown "$USER" /opt/ekuiseo
git clone <URL_DE_VOTRE_DEPOT> /opt/ekuiseo
cd /opt/ekuiseo
```

`scripts/deploy.sh` suppose que ce répertoire reste un clone git propre (voir
`docs/EXPLOITATION.md` pour les mises à jour ultérieures).

## 6. Configuration (`.env`)

```bash
cp .env.example .env
nano .env   # ou votre éditeur préféré
```

Au minimum, renseignez et **changez les valeurs par défaut** de :

- `DB_PASSWORD` — générez-en un : `openssl rand -base64 32`
- `JWT_SECRET` — générez-en un : `openssl rand -base64 48`
- `DOMAIN` — votre nom de domaine (ex. `ekuiseo.bj`)
- `ACME_EMAIL` — une adresse courriel que vous surveillez (alertes Let's Encrypt)
- `KKIAPAY_PUBLIC_KEY`, `KKIAPAY_PRIVATE_KEY`, `KKIAPAY_SECRET`, `KKIAPAY_WEBHOOK_SECRET`
  — au moins les clés de test pour commencer (voir le tableau de bord Kkiapay) ; sans
  `KKIAPAY_MODE=http` explicite, le backend simule un paiement toujours réussi sans
  jamais appeler Kkiapay (mode `stub`, voir `.env.example`)
- `SMS_MODE=http` et `SMS_PROVIDER=smspartner` (`SMSPARTNER_API_KEY`, `SMSPARTNER_SENDER`,
  inscription libre-service sans dossier d'entreprise, SMS offerts, paiement carte ou PayPal),
  ou `SMS_PROVIDER=twilio` (`TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`,
  `TWILIO_FROM`) ou `SMS_PROVIDER=africastalking` (`AT_USERNAME`, `AT_API_KEY`,
  `AT_SENDER_ID`, `AT_SANDBOX`) — sans cela (mode `log` par défaut), les codes OTP sont
  uniquement journalisés dans les logs du backend, ce qui est **inacceptable en
  production** (voir `docs/CONFORMITE.md` et `docs/LANCEMENT.md`). Activation sur le
  VPS : renseigner les variables dans `/opt/ekuiseo/.env` puis
  `docker compose -f docker-compose.prod.yml -f docker-compose.vps.yml up -d backend` ;
  le backend refuse de démarrer si la configuration du fournisseur est incomplète.

`docker-compose.prod.yml` refuse de démarrer si `DB_PASSWORD`, `JWT_SECRET`, `DOMAIN` ou
`ACME_EMAIL` sont absents (erreur explicite au lieu d'une valeur par défaut faible).

## 7. Premier démarrage

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

Cette commande, dans l'ordre (géré par les `depends_on`/`healthcheck`) :

1. démarre `postgis` et attend qu'il soit `healthy` ;
2. construit puis démarre `backend` — **Flyway applique automatiquement les migrations**
   (`V1__init.sql`, et toute migration ultérieure `V2__...`, etc.) au démarrage de
   l'application, il n'y a rien à lancer manuellement ;
3. construit puis démarre `frontend` (build statique servi par nginx) ;
4. démarre `caddy`, qui obtient alors son certificat Let's Encrypt pour `${DOMAIN}`.

Suivez les logs du premier démarrage (utile pour repérer un échec d'obtention de
certificat ou une migration Flyway en erreur) :

```bash
docker compose -f docker-compose.prod.yml logs -f
```

## 8. Obtention du certificat TLS

Caddy s'en charge automatiquement au démarrage (voir `Caddyfile`). Si l'obtention
échoue (DNS pas encore propagé, port 80 non joignable depuis internet...), Caddy
réessaie automatiquement avec un backoff ; vérifiez avec :

```bash
docker compose -f docker-compose.prod.yml logs caddy | grep -i cert
```

Pour tester la procédure sans consommer le quota strict de Let's Encrypt (5 certificats
par domaine par semaine), décommentez la ligne `acme_ca` (environnement de test) en
haut du `Caddyfile`, testez, puis recommentez-la et redémarrez caddy avant la mise en
production réelle :

```bash
docker compose -f docker-compose.prod.yml restart caddy
```

## 9. Création du compte administrateur

Depuis la migration `V2`, `users.role` distingue `USER` de `ADMIN` : seul `ADMIN` peut
appeler `/api/v1/admin/**` (back-office — recherche d'utilisateurs, suspension/
réactivation, validation de vérification d'identité, etc., voir
`AdminUserController`). **Aucun endpoint ne permet de s'auto-promouvoir `ADMIN`** (et
c'est volontaire) : le tout premier compte administrateur doit être créé directement en
base.

1. Inscrivez-vous normalement via l'application (`/api/v1/auth/register` puis
   vérification OTP) avec le numéro de téléphone qui servira de compte back-office.
2. Passez ce compte en `ADMIN` par une commande SQL ciblée (jamais via l'API publique) :
   ```bash
   docker compose -f docker-compose.prod.yml exec postgis \
     psql -U "$DB_USER" -d "$DB_NAME" -c \
     "UPDATE users SET role = 'ADMIN' WHERE phone = '+229XXXXXXXX';"
   ```
3. Vérifiez que le compte accède bien à un endpoint admin (ex. `GET /api/v1/admin/users?q=`
   avec son jeton) avant de considérer l'opération terminée.
4. Documentez ce compte dans votre gestionnaire de mots de passe d'équipe, jamais dans
   le dépôt git. Toute promotion `ADMIN` ultérieure suit la même procédure manuelle —
   c'est un choix de sécurité délibéré, pas un oubli.

## 10. Chargement des données de démonstration (optionnel)

Pour une démo commerciale ou un environnement de recette :

```bash
COMPOSE_FILE=docker-compose.prod.yml ./scripts/seed-demo.sh
```

**Ne faites jamais cela sur une instance de production destinée à de vrais
utilisateurs** : voir l'avertissement dans `scripts/seed-demo.sh`.

## 11. Vérifications post-déploiement

- [ ] `https://<votre-domaine>/` affiche bien le frontend (cadenas HTTPS valide).
- [ ] `https://<votre-domaine>/swagger-ui.html` (ou `/swagger-ui/index.html` selon la
      version de springdoc) affiche la documentation de l'API.
- [ ] `curl -s https://<votre-domaine>/api/v1/...` répond (adapter la route à un
      endpoint public existant).
- [ ] Tous les services sont `healthy` :
      `docker compose -f docker-compose.prod.yml ps`
- [ ] Le certificat est valide et se renouvellera (Caddy gère cela seul, rien à
      planifier) : `echo | openssl s_client -connect <votre-domaine>:443 2>/dev/null | openssl x509 -noout -dates`
- [ ] Les en-têtes de sécurité sont bien présents :
      `curl -I https://<votre-domaine>/` (cherchez `Strict-Transport-Security`,
      `Content-Security-Policy`, `X-Content-Type-Options`).
- [ ] Une inscription + connexion de bout en bout fonctionne (avec un vrai numéro,
      si `SMS_PROVIDER_KEY` est configuré).
- [ ] Un paiement Kkiapay en mode sandbox aboutit et confirme bien une réservation.
      Le sandbox n'accepte que ses numéros de test : `97000000` / `61000000` (MTN, succès),
      `95000000` / `68000000` (Moov, succès), `97000001` (erreur), `97000002` (solde
      insuffisant), `97000003` (refus). Tout autre numéro est rejeté par le widget
      (« Le numéro n'est pas valide »). Vérifié le 2026-09-04 sur ekuiseo.com : widget
      ouvert, 1 000 F + 19 F de frais débités, réservation confirmée via
      `POST /api/v1/payments/{id}/confirm` (`raw_payload.source = widget-confirm`).
- [ ] Une sauvegarde manuelle réussit : `./scripts/backup.sh` (voir
      `docs/EXPLOITATION.md`).
- [ ] Une tâche planifiée (cron) exécute `scripts/backup.sh` quotidiennement (voir
      l'exemple de crontab dans ce script).

## 12. Points d'attention connus avant une vraie mise en production

Ces points sont documentés dans le README racine ("Points relevés en relecture") et
doivent être traités avant d'ouvrir la plateforme à de vrais utilisateurs payants ; ils
concernent le code du backend (hors du périmètre de ce dépôt d'infrastructure) :

- CORS actuellement en `allowedOriginPatterns("*")` avec `allowCredentials(true)` — à
  restreindre au(x) domaine(s) réel(s) avant la production.
- `GET /api/v1/trips/{id}` public sans filtrer le statut `DRAFT`.
- Le webhook Kkiapay refuse tout appel si `KKIAPAY_WEBHOOK_SECRET` est vide — assurez-vous
  qu'il est bien renseigné, et que `KKIAPAY_MODE=http`, avant d'activer des paiements réels.
  Dans le tableau de bord Kkiapay (menu Webhook), déclarez l'URL
  `https://<votre-domaine>/api/v1/payments/kkiapay/webhook` avec ce même secret. Même sans
  webhook, la réservation se confirme dès que le widget signale le succès (le serveur
  reverifie alors la transaction lui-même) ; le webhook couvre le cas du navigateur fermé.
- Passage sandbox → réel : `KKIAPAY_SANDBOX=false` et les clés *live* (`KKIAPAY_PUBLIC_KEY`,
  `KKIAPAY_PRIVATE_KEY`, `KKIAPAY_SECRET`) dans `.env`, puis
  `docker compose -f docker-compose.prod.yml -f docker-compose.vps.yml up -d backend`.
  La clé publique et le drapeau sandbox sont transmis au front par l'API à chaque
  paiement : aucune reconstruction du front n'est nécessaire.
- Voir `docs/LANCEMENT.md` pour la liste complète de la checklist avant ouverture au
  public.

## 13. Variante : serveur partagé (un nginx occupe déjà les ports 80/443)

Cas du VPS OVH actuel : un nginx de l'hôte sert déjà une autre application sur 80/443.
Le Caddy du dépôt ne doit alors **ni** prendre ces ports **ni** demander de certificat.
Ekuiseo tourne derrière le nginx existant, sur un port local :

```
Internet --443--> nginx (hôte, TLS via certbot) --127.0.0.1:8090--> caddy (conteneur) --> backend / frontend
```

1. **Pile Docker** — même `.env` qu'en §6 (`DOMAIN=ekuiseo.com`, `VITE_API_URL=` vide :
   l'API est servie sur le même domaine sous `/api`), plus `EKUISEO_HTTP_PORT=8090` si le
   port 8090 est déjà pris. Puis :

   ```bash
   bash scripts/deploy-vps.sh
   ```

   Ce script clone ou met à jour `/opt/ekuiseo`, vérifie `.env`, et lance
   `docker compose -f docker-compose.prod.yml -f docker-compose.vps.yml up -d --build`.
   La surcouche `docker-compose.vps.yml` remplace les ports publics de Caddy par
   `127.0.0.1:8090:80` et monte `Caddyfile.proxied` (HTTP simple, mêmes routes et
   en-têtes, HSTS laissé au nginx amont). Rien d'autre ne change : réseaux, volumes et
   conteneurs restent préfixés `ekuiseo`, sans collision avec l'autre application.

2. **Site nginx de l'hôte** — un fichier dédié, qui ne touche pas aux autres sites :

   ```bash
   sudo cp /opt/ekuiseo/deploy/nginx/ekuiseo.com.conf /etc/nginx/sites-available/ekuiseo.com
   sudo ln -s /etc/nginx/sites-available/ekuiseo.com /etc/nginx/sites-enabled/
   sudo nginx -t && sudo systemctl reload nginx
   ```

3. **DNS puis TLS** — chez le registrar (Hostinger pour `ekuiseo.com`), créer les
   enregistrements `A ekuiseo.com -> <IP_DU_VPS>` et `A www.ekuiseo.com -> <IP_DU_VPS>`,
   attendre la propagation (`dig +short ekuiseo.com`), puis :

   ```bash
   sudo certbot --nginx -d ekuiseo.com -d www.ekuiseo.com
   ```

   Certbot ajoute le bloc `listen 443 ssl` et la redirection HTTP → HTTPS au fichier du
   site, et programme le renouvellement.

4. **Mises à jour** — relancer `bash scripts/deploy-vps.sh` : `git pull` puis reconstruction
   des images, sans interruption de l'autre application.

## 14. Déploiement automatique à chaque push

`.github/workflows/deploy-prod.yml` se déclenche à la fin du workflow **CI** sur `main`,
uniquement s'il a réussi (tests backend contre PostGIS, build du frontend). Il se connecte
au VPS avec une clé SSH dédiée (secret `DEPLOY_SSH_KEY`, variables `DEPLOY_HOST`,
`DEPLOY_USER`, `DEPLOY_KNOWN_HOSTS`), cale `/opt/ekuiseo` sur le commit testé, lance
`scripts/deploy-vps.sh` puis vérifie `https://ekuiseo.com/actuator/health`.

- Un commit qui casse les tests n'est jamais déployé.
- Le déploiement peut aussi être lancé à la main : onglet *Actions* → *Production (VPS)* →
  *Run workflow*.
- Les secrets applicatifs (`.env` du serveur : base, JWT, Kkiapay, SMS) ne transitent
  jamais par GitHub ; ils restent sur le VPS.
- Pour révoquer l'accès : retirer la ligne `github-actions-ekuiseo-deploy` de
  `~deploy/.ssh/authorized_keys` sur le serveur et supprimer le secret `DEPLOY_SSH_KEY`.
