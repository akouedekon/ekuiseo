#!/usr/bin/env bash
# ============================================================
# Ekuiseo — deploiement sur un VPS PARTAGE (un nginx occupe deja 80/443)
# ============================================================
# A executer SUR le serveur, en tant qu'utilisateur membre du groupe docker :
#   bash scripts/deploy-vps.sh
#
# Idempotent : premier deploiement ou mise a jour, meme commande.
#  1. clone ou met a jour /opt/ekuiseo depuis GitHub (branche main) ;
#  2. verifie la presence de .env (ne le cree jamais avec des valeurs faibles) ;
#  3. construit et (re)demarre la pile Docker derriere le proxy de l'hote ;
#  4. attend que l'API reponde sur 127.0.0.1:${EKUISEO_HTTP_PORT}.
# Le site nginx de l'hote et le certificat TLS sont geres a part (voir
# deploy/nginx/ekuiseo.com.conf et docs/DEPLOIEMENT.md, section « serveur partage »).
# ============================================================
set -euo pipefail

REPO_URL="${EKUISEO_REPO_URL:-https://github.com/akouedekon/ekuiseo.git}"
APP_DIR="${EKUISEO_DIR:-/opt/ekuiseo}"
BRANCH="${EKUISEO_BRANCH:-main}"

log() { printf '\n\033[1;32m[ekuiseo]\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31m[ekuiseo] ERREUR :\033[0m %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null || die "docker est introuvable (voir docs/DEPLOIEMENT.md §4)."
docker compose version >/dev/null 2>&1 || die "docker compose v2 est requis."

if [ ! -d "$APP_DIR/.git" ]; then
  log "Clonage de $REPO_URL dans $APP_DIR"
  sudo mkdir -p "$APP_DIR" && sudo chown "$USER" "$APP_DIR"
  git clone --branch "$BRANCH" "$REPO_URL" "$APP_DIR"
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
  *"*"*) log "AVERTISSEMENT : CORS_ALLOWED_ORIGINS contient * ; restreignez a https://$DOMAIN (docs/DEPLOIEMENT.md §6)." ;;
esac

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

log "Construction et demarrage (Caddy sur 127.0.0.1:$PORT, derriere le nginx de l'hote)"
docker compose -f docker-compose.prod.yml -f docker-compose.vps.yml up -d --build --remove-orphans

# Caddyfile.proxied est monte en bind sur un FICHIER : quand git le remplace (nouvel
# inode), le conteneur garde l'ancienne version et `caddy reload` relit... l'ancienne.
# On recree donc Caddy si sa configuration a change (coupure < 2 s, healthcheck actif).
if ! docker exec ekuiseo-caddy cat /etc/caddy/Caddyfile 2>/dev/null | cmp -s - Caddyfile.proxied; then
  log "Caddyfile.proxied a change : recreation du conteneur Caddy"
  docker compose -f docker-compose.prod.yml -f docker-compose.vps.yml up -d --force-recreate --no-deps caddy
fi

log "Attente de l'API (sonde JSON /actuator/health via Caddy, puis un endpoint metier)"
for i in $(seq 1 40); do
  health="$(curl -sS -o /dev/null -w '%{http_code} %{content_type}' "http://127.0.0.1:$PORT/actuator/health" 2>/dev/null || true)"
  popular="$(curl -sS -o /dev/null -w '%{http_code} %{content_type}' "http://127.0.0.1:$PORT/api/v1/trips/popular" 2>/dev/null || true)"
  case "$health|$popular" in
    "200 application/"*"|200 application/json"*) ;;
    *) sleep 5; continue ;;
  esac
  if true; then
    log "Ekuiseo repond sur http://127.0.0.1:$PORT (essai $i) : $health ; $popular."
    docker compose -f docker-compose.prod.yml -f docker-compose.vps.yml ps
    exit 0
  fi
  sleep 5
done
die "l'API ne repond pas apres 200 s ; voir : docker compose -f docker-compose.prod.yml -f docker-compose.vps.yml logs --tail=100 backend caddy"
