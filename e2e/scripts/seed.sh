#!/usr/bin/env bash
# ============================================================
# Ekuiseo — jeu de donnees des tests de bout en bout
# ============================================================
# Usage : bash e2e/scripts/seed.sh   (la pile docker-compose.yml + docker-compose.e2e.yml
#                                     doit tourner ; voir wait-for-stack.sh)
#  1. scripts/seed-demo.sh : jeu de demonstration (docs/donnees-demo.sql, idempotent),
#     dont le conducteur verifie et son vehicule utilises par les parcours ;
#  2. e2e/seed-e2e.sql : trajet Cotonou -> Bohicon dedie, remis a J+3 a chaque passage.
# Un .env est cree depuis .env.example s'il manque (valeurs de developpement uniquement).
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

log() { echo "[e2e/seed.sh] $(date '+%H:%M:%S') - $*"; }
die() { echo "[e2e/seed.sh] ERREUR : $*" >&2; exit 1; }

if [ ! -f .env ]; then
  log ".env absent : copie de .env.example (configuration de developpement)."
  cp .env.example .env
fi
set -a
# shellcheck disable=SC1091
. ./.env
set +a
: "${DB_NAME:?DB_NAME doit etre defini dans .env}"
: "${DB_USER:?DB_USER doit etre defini dans .env}"
: "${DB_PASSWORD:?DB_PASSWORD doit etre defini dans .env}"

# seed-demo.sh refuse une instance de production (fichier compose *prod* ou DOMAIN renseigne) :
# on lui impose explicitement le fichier de developpement.
log "Jeu de demonstration (scripts/seed-demo.sh)"
COMPOSE_FILE=docker-compose.yml ./scripts/seed-demo.sh

log "Trajet E2E (e2e/seed-e2e.sql)"
docker compose -f docker-compose.yml -f docker-compose.e2e.yml exec -T \
  -e PGPASSWORD="$DB_PASSWORD" postgis \
  psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 < e2e/seed-e2e.sql \
  || die "chargement de e2e/seed-e2e.sql impossible."

log "Jeu de donnees E2E charge."
