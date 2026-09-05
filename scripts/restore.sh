#!/usr/bin/env bash
# ============================================================
# Ekuiseo — restauration de la base PostgreSQL/PostGIS depuis une sauvegarde
# ============================================================
# Usage : ./scripts/restore.sh <chemin-vers-fichier.dump>
#
# ATTENTION : cette operation ECRASE le contenu actuel de la base ('DB_NAME').
# Une confirmation explicite est demandee avant toute action destructive.
#
# Variables d'environnement requises (definies dans .env a la racine du depot) :
#   DB_NAME, DB_USER, DB_PASSWORD
#
# Variables optionnelles :
#   COMPOSE_FILE   fichier docker-compose a utiliser (defaut : docker-compose.prod.yml
#                  si present, sinon docker-compose.yml)
#   FORCE=1        ignore la confirmation interactive (usage scripte uniquement,
#                  ex. tests automatises — a eviter en production)
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

log() { echo "[restore.sh] $(date '+%Y-%m-%d %H:%M:%S') - $*"; }
die() { echo "[restore.sh] ERREUR : $*" >&2; exit 1; }

if [[ $# -ne 1 ]]; then
  die "usage : $0 <chemin-vers-fichier.dump>"
fi
DUMP_PATH="$1"

[[ -f "$DUMP_PATH" ]] || die "le fichier de sauvegarde '$DUMP_PATH' n'existe pas."
[[ -s "$DUMP_PATH" ]] || die "le fichier de sauvegarde '$DUMP_PATH' est vide."

if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT_DIR/.env"
  set +a
else
  die "fichier .env introuvable a la racine du depot ($ROOT_DIR/.env)."
fi

: "${DB_NAME:?la variable DB_NAME doit etre definie dans .env}"
: "${DB_USER:?la variable DB_USER doit etre definie dans .env}"
: "${DB_PASSWORD:?la variable DB_PASSWORD doit etre definie dans .env}"

if [[ -f "$ROOT_DIR/docker-compose.prod.yml" ]]; then
  DEFAULT_COMPOSE_FILE="docker-compose.prod.yml"
else
  DEFAULT_COMPOSE_FILE="docker-compose.yml"
fi
COMPOSE_FILE="${COMPOSE_FILE:-$DEFAULT_COMPOSE_FILE}"

command -v docker >/dev/null 2>&1 || die "docker n'est pas installe ou pas dans le PATH."

if ! docker compose -f "$COMPOSE_FILE" ps postgis --status running >/dev/null 2>&1; then
  die "le service 'postgis' ne semble pas demarre (docker compose -f $COMPOSE_FILE up -d postgis)."
fi

echo
echo "==================================================================="
echo " ATTENTION : vous allez ECRASER la base '${DB_NAME}' avec le contenu"
echo " de : ${DUMP_PATH}"
echo " Toutes les donnees actuelles de cette base seront PERDUES."
echo " Fichier compose : ${COMPOSE_FILE}"
echo "==================================================================="
echo

if [[ "${FORCE:-0}" != "1" ]]; then
  read -r -p "Tapez exactement 'RESTAURER ${DB_NAME}' pour confirmer : " CONFIRMATION
  if [[ "$CONFIRMATION" != "RESTAURER ${DB_NAME}" ]]; then
    die "confirmation incorrecte, restauration annulee. Aucune modification n'a ete faite."
  fi
else
  log "FORCE=1 : confirmation interactive ignoree."
fi

log "Sauvegarde de securite de l'etat actuel avant restauration (au cas ou)..."
SAFETY_DIR="$ROOT_DIR/backups/pre-restore"
mkdir -p "$SAFETY_DIR"
SAFETY_FILE="$SAFETY_DIR/avant_restauration_$(date +%Y%m%d_%H%M%S).dump"
if docker compose -f "$COMPOSE_FILE" exec -T -e PGPASSWORD="$DB_PASSWORD" \
    postgis pg_dump -U "$DB_USER" -d "$DB_NAME" --format=custom --compress=6 \
    > "$SAFETY_FILE" 2>/dev/null && [[ -s "$SAFETY_FILE" ]]; then
  log "  etat actuel sauvegarde -> $SAFETY_FILE"
else
  log "  (pas de sauvegarde de securite possible, la base est peut-etre deja vide — on continue)"
  rm -f "$SAFETY_FILE"
fi

log "Suppression et recreation du schema public (pour repartir d'une base vierge)..."
docker compose -f "$COMPOSE_FILE" exec -T -e PGPASSWORD="$DB_PASSWORD" \
  postgis psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"

log "Restauration du dump en cours (peut prendre plusieurs minutes selon la taille)..."
docker compose -f "$COMPOSE_FILE" exec -T -e PGPASSWORD="$DB_PASSWORD" \
  postgis pg_restore -U "$DB_USER" -d "$DB_NAME" --no-owner --role="$DB_USER" \
  < "$DUMP_PATH"

log "Restauration terminee. Redemarrez le backend pour repartir sur une connexion propre :"
log "  docker compose -f $COMPOSE_FILE restart backend"
