#!/usr/bin/env bash
# ============================================================
# Ekuiseo — sauvegarde de la base PostgreSQL/PostGIS
# ============================================================
# Usage : ./scripts/backup.sh
#
# Produit un dump compresse (pg_dump --format=custom, deja compresse par pg_dump
# lui-meme) horodate dans backups/daily/, et le duplique dans backups/weekly/ le
# dimanche. Applique ensuite une retention :
#   - 7 derniers jours glissants dans backups/daily/
#   - 4 dernieres semaines dans backups/weekly/
# puis, si BACKUP_REMOTE est defini, envoie le dump HORS SITE avec rclone (un dump
# qui reste sur le disque du VPS ne protege ni d'une panne disque, ni d'une
# compromission, ni d'une erreur de manipulation sur l'hote).
#
# Variables d'environnement requises (definies dans .env a la racine du depot) :
#   DB_NAME, DB_USER, DB_PASSWORD
#
# Variables optionnelles :
#   COMPOSE_FILE     fichier docker-compose a utiliser (defaut : docker-compose.prod.yml
#                    si present, sinon docker-compose.yml)
#   BACKUP_DIR       dossier de destination des sauvegardes (defaut : ./backups)
#   BACKUP_REMOTE    destination rclone hors site, ex. "b2:ekuiseo-backups" ou
#                    "s3:ekuiseo-backups/prod" (rclone config a faire une fois sur
#                    l'hote, voir docs/EXPLOITATION.md). Si defini, un echec d'envoi
#                    fait echouer le script (et donc remonte dans le journal cron).
#   BACKUP_REMOTE_KEEP_DAYS  retention cote distant (defaut : 30 jours)
#
# Le cron est installe par scripts/deploy-vps.sh (/etc/cron.d/ekuiseo-backup, 03:15) ;
# le fichier backups/last-success contient l'horodatage de la derniere reussite
# (surveille par docs/EXPLOITATION.md).
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

log() { echo "[backup.sh] $(date '+%Y-%m-%d %H:%M:%S') - $*"; }
die() { echo "[backup.sh] ERREUR : $*" >&2; exit 1; }

# --- Chargement du .env -----------------------------------------------------
if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT_DIR/.env"
  set +a
else
  die "fichier .env introuvable a la racine du depot ($ROOT_DIR/.env). Copiez .env.example vers .env et completez-le."
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
BACKUP_DIR="${BACKUP_DIR:-$ROOT_DIR/backups}"
BACKUP_REMOTE="${BACKUP_REMOTE:-}"
BACKUP_REMOTE_KEEP_DAYS="${BACKUP_REMOTE_KEEP_DAYS:-30}"

command -v docker >/dev/null 2>&1 || die "docker n'est pas installe ou pas dans le PATH."

DAILY_DIR="$BACKUP_DIR/daily"
WEEKLY_DIR="$BACKUP_DIR/weekly"
mkdir -p "$DAILY_DIR" "$WEEKLY_DIR"

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
FILENAME="ekuiseo_${TIMESTAMP}.dump"
DEST_PATH="$DAILY_DIR/$FILENAME"
TMP_PATH="$DEST_PATH.tmp"

log "Verification que le service postgis est demarre (fichier compose: $COMPOSE_FILE)..."
if ! docker compose -f "$COMPOSE_FILE" ps postgis --status running >/dev/null 2>&1; then
  die "le service 'postgis' ne semble pas demarre (docker compose -f $COMPOSE_FILE up -d postgis)."
fi

log "Sauvegarde de la base '$DB_NAME' en cours -> $DEST_PATH"
if ! docker compose -f "$COMPOSE_FILE" exec -T \
    -e PGPASSWORD="$DB_PASSWORD" \
    postgis pg_dump -U "$DB_USER" -d "$DB_NAME" --format=custom --compress=6 \
    > "$TMP_PATH"; then
  rm -f "$TMP_PATH"
  die "pg_dump a echoue, aucune sauvegarde n'a ete conservee."
fi

# --- Verification que la sauvegarde n'est pas vide --------------------------
DUMP_SIZE="$(stat -c%s "$TMP_PATH" 2>/dev/null || stat -f%z "$TMP_PATH")"
if [[ "$DUMP_SIZE" -lt 1000 ]]; then
  rm -f "$TMP_PATH"
  die "la sauvegarde produite ne fait que ${DUMP_SIZE} octets : trop petite pour etre valide, elle est rejetee. Verifiez que la base contient bien des donnees et que pg_dump n'a pas echoue silencieusement."
fi

# Le dump doit etre lisible par pg_restore (en-tete custom valide) avant d'etre conserve.
if ! docker compose -f "$COMPOSE_FILE" exec -T postgis pg_restore --list /dev/stdin < "$TMP_PATH" >/dev/null 2>&1; then
  rm -f "$TMP_PATH"
  die "pg_restore ne parvient pas a lire la sauvegarde produite : fichier rejete."
fi

mv "$TMP_PATH" "$DEST_PATH"
log "Sauvegarde ecrite avec succes ($DUMP_SIZE octets, catalogue pg_restore valide)."

# Copie hebdomadaire le dimanche (date +%u = 7 pour dimanche).
if [[ "$(date +%u)" == "7" ]]; then
  cp "$DEST_PATH" "$WEEKLY_DIR/$FILENAME"
  log "Copie hebdomadaire conservee -> $WEEKLY_DIR/$FILENAME"
fi

# --- Retention : 7 jours glissants pour les sauvegardes quotidiennes --------
log "Application de la retention (7 jours glissants pour daily/)..."
find "$DAILY_DIR" -maxdepth 1 -name 'ekuiseo_*.dump' -mtime +7 -print -delete | while read -r f; do
  log "  supprime (>7 jours) : $f"
done

# --- Retention : 4 dernieres semaines pour les sauvegardes hebdomadaires ----
log "Application de la retention (4 dernieres semaines pour weekly/)..."
mapfile -t WEEKLY_FILES < <(find "$WEEKLY_DIR" -maxdepth 1 -name 'ekuiseo_*.dump' -printf '%T@ %p\n' 2>/dev/null | sort -rn | cut -d' ' -f2-)
if [[ "${#WEEKLY_FILES[@]}" -gt 4 ]]; then
  for ((i = 4; i < ${#WEEKLY_FILES[@]}; i++)); do
    log "  supprime (au-dela des 4 dernieres semaines) : ${WEEKLY_FILES[$i]}"
    rm -f "${WEEKLY_FILES[$i]}"
  done
fi

# --- Copie hors site (rclone) -------------------------------------------------
if [[ -n "$BACKUP_REMOTE" ]]; then
  command -v rclone >/dev/null 2>&1 || die "BACKUP_REMOTE est defini mais rclone est introuvable (apt install rclone, puis rclone config)."
  log "Envoi hors site vers $BACKUP_REMOTE ..."
  if ! rclone copyto "$DEST_PATH" "$BACKUP_REMOTE/daily/$FILENAME" --retries 3 --low-level-retries 5; then
    die "l'envoi hors site vers $BACKUP_REMOTE a echoue : la sauvegarde n'existe QUE sur ce serveur."
  fi
  # Retention distante : on garde BACKUP_REMOTE_KEEP_DAYS jours ; un echec ici n'est pas bloquant.
  rclone delete "$BACKUP_REMOTE/daily" --min-age "${BACKUP_REMOTE_KEEP_DAYS}d" 2>/dev/null \
    || log "AVERTISSEMENT : retention distante non appliquee (rclone delete a echoue)."
  log "Copie hors site terminee."
else
  log "AVERTISSEMENT : BACKUP_REMOTE non defini, la sauvegarde reste sur le disque de ce serveur uniquement (voir docs/EXPLOITATION.md)."
fi

date '+%Y-%m-%dT%H:%M:%S%z' > "$BACKUP_DIR/last-success"
log "Sauvegarde terminee : $DEST_PATH"
