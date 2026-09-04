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

log "Construction et demarrage (Caddy sur 127.0.0.1:$PORT, derriere le nginx de l'hote)"
docker compose -f docker-compose.prod.yml -f docker-compose.vps.yml up -d --build --remove-orphans

log "Attente de l'API"
for i in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:$PORT/api/v1/../actuator/health" >/dev/null 2>&1 \
     || curl -fsS "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1 \
     || curl -fsS -o /dev/null "http://127.0.0.1:$PORT/api/v1/trips/popular"; then
    log "Ekuiseo repond sur http://127.0.0.1:$PORT (essai $i)."
    docker compose -f docker-compose.prod.yml -f docker-compose.vps.yml ps
    exit 0
  fi
  sleep 5
done
die "l'API ne repond pas apres 200 s ; voir : docker compose -f docker-compose.prod.yml -f docker-compose.vps.yml logs --tail=100 backend caddy"
