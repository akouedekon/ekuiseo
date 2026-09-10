#!/usr/bin/env bash
# ============================================================
# Ekuiseo — deploiement sur un VPS PARTAGE (un nginx occupe deja 80/443)
# ============================================================
# A executer SUR le serveur, en tant qu'utilisateur membre du groupe docker :
#   bash scripts/deploy-vps.sh                      # mode local : construit les images ici
#   EKUISEO_TAG=<sha> bash scripts/deploy-vps.sh    # mode registre : tire les images de la CI
#
# Idempotent : premier deploiement ou mise a jour, meme commande.
#  1. clone ou met a jour /opt/ekuiseo depuis GitHub (branche main) ;
#  2. verifie la presence de .env (ne le cree jamais avec des valeurs faibles) ;
#  3. choisit les images (constat F437) :
#       - EKUISEO_TAG defini (le workflow deploy-prod.yml passe le SHA teste par la CI) :
#         `docker compose pull` de ghcr.io/akouedekon/ekuiseo-{backend,frontend}:<tag>,
#         rien n'est construit sur le serveur ;
#       - sinon (deploiement manuel sans registre) : construction locale depuis le depot
#         (docker-compose.prod.yml), images retaguees sous le meme espace de noms avec un
#         tag local-<commit>-<horodatage> pour que la surcouche VPS et le retour arriere
#         fonctionnent a l'identique dans les deux modes ;
#  4. (re)demarre la pile Docker derriere le proxy de l'hote ;
#  5. attend que l'API reponde sur 127.0.0.1:${EKUISEO_HTTP_PORT} ; sinon relance le tag
#     precedent (fichier .deployed-tag, ou images :previous) ;
#  6. memorise le tag deploye dans .deployed-tag et purge les images inutilisees de plus
#     de 7 jours.
# Le site nginx de l'hote et le certificat TLS sont geres a part (voir
# deploy/nginx/ekuiseo.com.conf et docs/DEPLOIEMENT.md, section « serveur partage »).
# ============================================================
set -euo pipefail

REPO_URL="${EKUISEO_REPO_URL:-https://github.com/akouedekon/ekuiseo.git}"
APP_DIR="${EKUISEO_DIR:-/opt/ekuiseo}"
BRANCH="${EKUISEO_BRANCH:-main}"
# Espace de noms des images publiees par la CI (.github/workflows/ci.yml, job docker-images).
IMAGE_NS="${EKUISEO_IMAGE_NS:-ghcr.io/akouedekon}"
# Tag deploye avec succes en dernier : c'est la cible du retour arriere.
DEPLOYED_TAG_FILE=".deployed-tag"

log() { printf '\n\033[1;32m[ekuiseo]\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31m[ekuiseo] ERREUR :\033[0m %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null || die "docker est introuvable (voir docs/DEPLOIEMENT.md §4)."
docker compose version >/dev/null 2>&1 || die "docker compose v2 est requis."

if [ ! -d "$APP_DIR/.git" ]; then
  log "Clonage de $REPO_URL dans $APP_DIR"
  sudo mkdir -p "$APP_DIR" && sudo chown "$USER" "$APP_DIR"
  git clone --branch "$BRANCH" "$REPO_URL" "$APP_DIR"
elif [ -n "${EKUISEO_SHA:-}" ]; then
  # Constat F448 : le workflow a deja fige le commit teste par la CI ; on ne fait
  # jamais avancer le depot au-dela (un push survenu entre-temps n est pas valide).
  log "Deploiement du commit ${EKUISEO_SHA} (fige par le workflow)"
  git -C "$APP_DIR" fetch --quiet origin "$BRANCH"
  git -C "$APP_DIR" checkout --detach --quiet "$EKUISEO_SHA"
else
  log "Mise a jour de $APP_DIR (branche $BRANCH)"
  git -C "$APP_DIR" fetch --quiet origin "$BRANCH"
  git -C "$APP_DIR" checkout --quiet "$BRANCH"
  git -C "$APP_DIR" pull --ff-only --quiet origin "$BRANCH"
fi
cd "$APP_DIR"

[ -f .env ] || die ".env absent : cp .env.example .env puis renseignez DB_PASSWORD, JWT_SECRET, DOMAIN, ACME_EMAIL (voir docs/DEPLOIEMENT.md §6)."
# shellcheck disable=SC1091
set -a; . ./.env; set +a
PORT="${EKUISEO_HTTP_PORT:-8090}"

for var in DB_PASSWORD JWT_SECRET DOMAIN ACME_EMAIL; do
  [ -n "${!var:-}" ] || die "la variable $var est vide dans .env."
done
case "$JWT_SECRET" in
  change-me*|dev-only*) die "JWT_SECRET vaut encore une valeur d'exemple : generez-en une (openssl rand -base64 48)." ;;
esac
[ "${#JWT_SECRET}" -ge 32 ] || die "JWT_SECRET fait moins de 32 caracteres."
case "${CORS_ALLOWED_ORIGINS:-*}" in
  *"*"*) die "CORS_ALLOWED_ORIGINS contient * : restreignez a https://$DOMAIN (docs/DEPLOIEMENT.md §6)." ;;
esac
# Valeurs de l exemple qui ne conviennent qu au developpement (audit securite 2026-09-10).
case "$DB_PASSWORD" in
  ekuiseo_dev_change_me|change-me*) die "DB_PASSWORD vaut encore la valeur d'exemple : generez-en un (openssl rand -base64 24)." ;;
esac
[ "${SPRINGDOC_ENABLED:-false}" = "false" ] || die "SPRINGDOC_ENABLED=true expose la documentation de l'API au public : mettez false."
[ "${KKIAPAY_MODE:-stub}" = "http" ] || die "KKIAPAY_MODE=${KKIAPAY_MODE:-stub} : en production, seul le mode http (vraie passerelle Kkiapay) est admis."
[ "${KKIAPAY_SANDBOX:-true}" = "false" ] || log "AVERTISSEMENT : KKIAPAY_SANDBOX=true, les paiements sont simules par le bac a sable Kkiapay (aucun encaissement reel)."

# Sauvegarde quotidienne : cron installe de facon idempotente (03:15, heure du serveur),
# journal dans /var/log/ekuiseo-backup.log ; copie hors site si BACKUP_REMOTE est defini
# dans .env (voir scripts/backup.sh et docs/EXPLOITATION.md).
CRON_FILE=/etc/cron.d/ekuiseo-backup
CRON_LINE="15 3 * * * $USER cd $APP_DIR && COMPOSE_FILE=docker-compose.prod.yml ./scripts/backup.sh >> /var/log/ekuiseo-backup.log 2>&1"
if ! sudo -n true 2>/dev/null; then
  log "AVERTISSEMENT : sudo indisponible, cron de sauvegarde non installe ($CRON_FILE)."
elif [ "$(sudo cat "$CRON_FILE" 2>/dev/null)" != "$CRON_LINE" ]; then
  printf '%s\n' "$CRON_LINE" | sudo tee "$CRON_FILE" >/dev/null
  sudo chmod 644 "$CRON_FILE"
  sudo touch /var/log/ekuiseo-backup.log && sudo chown "$USER" /var/log/ekuiseo-backup.log
  log "Cron de sauvegarde installe : $CRON_FILE"
fi

# Exercice de restauration mensuel (constat F127) : la derniere sauvegarde est rejouee
# dans une base jetable le 1er du mois a 04:00 ; resultat dans backups/last-drill.
DRILL_FILE=/etc/cron.d/ekuiseo-restore-drill
DRILL_LINE="0 4 1 * * $USER cd $APP_DIR && COMPOSE_FILE=docker-compose.prod.yml ./scripts/restore-drill.sh >> /var/log/ekuiseo-backup.log 2>&1"
if sudo -n true 2>/dev/null && [ "$(sudo cat "$DRILL_FILE" 2>/dev/null)" != "$DRILL_LINE" ]; then
  printf '%s\n' "$DRILL_LINE" | sudo tee "$DRILL_FILE" >/dev/null
  sudo chmod 644 "$DRILL_FILE"
  log "Cron d exercice de restauration installe : $DRILL_FILE"
fi

COMPOSE="docker compose -f docker-compose.prod.yml -f docker-compose.vps.yml"

# --- Choix des images (constat F437) -----------------------------------------------------
# docker-compose.vps.yml pointe backend et frontend sur $IMAGE_NS/ekuiseo-*:${EKUISEO_TAG}
# (section build effacee) : tout ce qui suit, y compris le retour arriere, ne manipule
# que des tags. EKUISEO_TAG est exporte pour que chaque appel compose interpole le meme.
if [ -n "${EKUISEO_TAG:-}" ]; then
  MODE=registre
  TAG="$EKUISEO_TAG"
else
  MODE=local
  TAG="local-$(git rev-parse --short HEAD)-$(date +%Y%m%d%H%M%S)"
fi
export EKUISEO_TAG="$TAG"
PREVIOUS_TAG="$(cat "$DEPLOYED_TAG_FILE" 2>/dev/null || true)"
log "Mode $MODE : images $IMAGE_NS/ekuiseo-{backend,frontend}:$TAG (precedent : ${PREVIOUS_TAG:-aucun})"

# Sauvegarde prealable (constat F436) : un deploiement qui migre le schema ne part jamais
# sans dump frais. Echec = arret, sauf au tout premier deploiement (base absente).
if docker ps --format '{{.Names}}' | grep -qx ekuiseo-postgis; then
  log "Sauvegarde prealable de la base (scripts/backup.sh)"
  COMPOSE_FILE=docker-compose.prod.yml ./scripts/backup.sh || die "sauvegarde prealable impossible : deploiement annule."
fi

# Retour arriere (constats F436/F437) : les images actuellement EN SERVICE sont retaguees
# :previous avant tout changement. C'est le secours quand .deployed-tag est absent (premier
# deploiement avec le registre, images encore nommees ekuiseo-*:latest) ou que son tag a
# ete purge et n'est plus tirable.
for img in ekuiseo-backend ekuiseo-frontend; do
  current="$(docker inspect --format '{{.Config.Image}}' "$img" 2>/dev/null || true)"
  if [ -n "$current" ] && docker image inspect "$current" >/dev/null 2>&1; then
    docker tag "$current" "$IMAGE_NS/$img:previous"
  fi
done

rollback() {
  log "RETOUR ARRIERE : la version $TAG ne repond pas"
  local target=""
  if [ -n "$PREVIOUS_TAG" ]; then
    target="$PREVIOUS_TAG"
    if ! docker image inspect "$IMAGE_NS/ekuiseo-backend:$target" >/dev/null 2>&1; then
      log "Image $target absente localement : tentative de pull depuis le registre"
      EKUISEO_TAG="$target" $COMPOSE pull --quiet backend frontend || target=""
    fi
  fi
  if [ -z "$target" ] && docker image inspect "$IMAGE_NS/ekuiseo-backend:previous" >/dev/null 2>&1; then
    target=previous
  fi
  [ -n "$target" ] || die "aucune version precedente disponible : intervention manuelle (docs/EXPLOITATION.md, § Mise a jour)."
  log "Remise en service du tag $target"
  EKUISEO_TAG="$target" $COMPOSE up -d --no-build --remove-orphans
  die "le deploiement de $TAG a echoue ; la version $target a ete relancee (les migrations Flyway deja appliquees restent en place : regle expand/contract)."
}

if [ "$MODE" = registre ]; then
  log "Recuperation des images $TAG depuis le registre"
  if ! $COMPOSE pull --quiet backend frontend; then
    # Paquet encore prive, premiere publication manquante ou registre injoignable : on
    # retombe sur la construction locale plutot que de bloquer le deploiement (le tag
    # reste celui de la CI pour que le retour arriere et .deployed-tag restent coherents).
    log "AVERTISSEMENT : images $TAG introuvables sur $IMAGE_NS (paquet prive ? voir docs/DEPLOIEMENT.md §14) : construction locale de secours"
    MODE=local-secours
  fi
fi
if [ "$MODE" != registre ]; then
  log "Construction locale des images depuis le depot (docker-compose.prod.yml)"
  docker compose -f docker-compose.prod.yml build backend frontend \
    || die "construction impossible ; la pile en service n'a pas ete touchee."
  docker tag ekuiseo-backend:latest "$IMAGE_NS/ekuiseo-backend:$TAG"
  docker tag ekuiseo-frontend:latest "$IMAGE_NS/ekuiseo-frontend:$TAG"
fi

log "Demarrage (Caddy sur 127.0.0.1:$PORT, derriere le nginx de l'hote)"
$COMPOSE up -d --no-build --remove-orphans || rollback

# Caddyfile.proxied est monte en bind sur un FICHIER : quand git le remplace (nouvel
# inode), le conteneur garde l'ancienne version et `caddy reload` relit... l'ancienne.
# On recree donc Caddy si sa configuration a change (coupure < 2 s, healthcheck actif).
if ! docker exec ekuiseo-caddy cat /etc/caddy/Caddyfile 2>/dev/null | cmp -s - Caddyfile.proxied; then
  log "Caddyfile.proxied a change : recreation du conteneur Caddy"
  $COMPOSE up -d --force-recreate --no-deps caddy
fi

log "Attente de l'API (sonde JSON /actuator/health via Caddy, puis un endpoint metier)"
for i in $(seq 1 40); do
  health="$(curl -sS -o /dev/null -w '%{http_code} %{content_type}' "http://127.0.0.1:$PORT/actuator/health" 2>/dev/null || true)"
  popular="$(curl -sS -o /dev/null -w '%{http_code} %{content_type}' "http://127.0.0.1:$PORT/api/v1/trips/popular" 2>/dev/null || true)"
  case "$health|$popular" in
    "200 application/"*"|200 application/json"*)
      log "Ekuiseo repond sur http://127.0.0.1:$PORT (essai $i) : $health ; $popular."
      $COMPOSE ps
      # Le tag n'est memorise qu'apres une sonde reussie : en cas d'echec, .deployed-tag
      # pointe toujours sur la derniere version qui a fonctionne.
      printf '%s\n' "$TAG" > "$DEPLOYED_TAG_FILE"
      # Menage : images inutilisees de plus de 7 jours (anciens tags de la CI, builds
      # locaux). Les images en service et :previous fraichement retaguees sont conservees ;
      # un tag purge reste tirable depuis le registre pour un retour arriere.
      log "Purge des images inutilisees de plus de 7 jours"
      docker image prune -af --filter "until=168h" >/dev/null 2>&1 || log "AVERTISSEMENT : purge des images impossible (sans consequence)."
      exit 0
      ;;
    *) sleep 5 ;;
  esac
done
log "l'API ne repond pas apres 200 s ; voir : $COMPOSE logs --tail=100 backend caddy"
rollback
