#!/usr/bin/env bash
# ============================================================
# Ekuiseo - exercice de restauration automatique (constat F127 de l audit)
# ============================================================
# Restaure la derniere sauvegarde quotidienne dans une base JETABLE
# (ekuiseo_restore_test), compte quelques tables, puis detruit la base.
# Ne touche jamais a la base de production. A planifier chaque mois (cron
# installe par scripts/deploy-vps.sh) ; le resultat est journalise dans
# backups/last-drill (date + comptes) et le script sort en erreur si la
# restauration echoue, ce qui doit etre surveille (docs/EXPLOITATION.md).
#
# Usage : ./scripts/restore-drill.sh [chemin/vers/un.dump]
# Variables : COMPOSE_FILE (defaut docker-compose.prod.yml), BACKUP_DIR (defaut ./backups)
# ============================================================
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
TEST_DB="ekuiseo_restore_test"

log() { printf '[restore-drill %s] %s\n' "$(date +'%Y-%m-%d %H:%M:%S')" "$*"; }
die() { log "ERREUR : $*" >&2; exit 1; }

[ -f .env ] || die ".env introuvable : lancez le script depuis la racine du depot."
# shellcheck disable=SC1091
set -a; . ./.env; set +a
DB_USER="${DB_USER:-ekuiseo}"

DUMP="${1:-$(ls -1t "$BACKUP_DIR"/daily/*.dump 2>/dev/null | head -1 || true)}"
[ -n "$DUMP" ] && [ -f "$DUMP" ] || die "aucun dump quotidien dans $BACKUP_DIR/daily."
log "Dump utilise : $DUMP ($(du -h "$DUMP" | cut -f1))"

psql_admin() {
  docker compose -f "$COMPOSE_FILE" exec -T -e PGPASSWORD="$DB_PASSWORD" \
    postgis psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 -Atc "$1"
}

cleanup() {
  psql_admin "DROP DATABASE IF EXISTS $TEST_DB;" >/dev/null 2>&1 || true
}
trap cleanup EXIT

cleanup
psql_admin "CREATE DATABASE $TEST_DB OWNER \"$DB_USER\";" >/dev/null
log "Restauration dans $TEST_DB..."
docker compose -f "$COMPOSE_FILE" exec -T -e PGPASSWORD="$DB_PASSWORD" \
  postgis pg_restore -U "$DB_USER" -d "$TEST_DB" --no-owner --role="$DB_USER" \
  --single-transaction --exit-on-error < "$DUMP" \
  || die "pg_restore a echoue sur $DUMP : la sauvegarde n est PAS restaurable."

COUNTS="$(docker compose -f "$COMPOSE_FILE" exec -T -e PGPASSWORD="$DB_PASSWORD" \
  postgis psql -U "$DB_USER" -d "$TEST_DB" -Atc \
  "select 'users='||count(*) from users union all select 'trips='||count(*) from trips union all select 'bookings='||count(*) from bookings union all select 'payments='||count(*) from payments union all select 'flyway='||max(version) from flyway_schema_history;" \
  | tr '\n' ' ')"
[ -n "$COUNTS" ] || die "base restauree mais vide ou illisible."

mkdir -p "$BACKUP_DIR"
printf '%s dump=%s %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$(basename "$DUMP")" "$COUNTS" > "$BACKUP_DIR/last-drill"
log "Restauration verifiee : $COUNTS"
