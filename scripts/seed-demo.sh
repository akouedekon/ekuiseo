#!/usr/bin/env bash
# ============================================================
# Ekuiseo — chargement du jeu de donnees de demonstration
# ============================================================
# Usage : ./scripts/seed-demo.sh [--reset]
#
# Charge docs/donnees-demo.sql (utilisateurs, vehicules, trajets, reservations,
# paiements, avis beninois realistes) dans la base configuree par .env.
#
# Le script SQL est idempotent (identifiants fixes, "ON CONFLICT DO NOTHING") : le
# relancer sans --reset ne duplique pas les donnees mais ne met pas non plus a jour
# les lignes deja presentes.
#
#   --reset   vide d'abord les tables applicatives (TRUNCATE ... CASCADE) avant de
#             recharger le jeu de demonstration. DESTRUCTEUR : demande confirmation.
#             A n'utiliser que sur une base de demonstration/test, jamais sur une
#             base contenant de vraies donnees utilisateur.
#
# Variables d'environnement requises (definies dans .env a la racine du depot) :
#   DB_NAME, DB_USER, DB_PASSWORD
#
# Variables optionnelles :
#   COMPOSE_FILE   defaut : docker-compose.yml (le jeu de demo est destine au
#                  developpement/demo commerciale, PAS a une prod avec de vrais
#                  utilisateurs ; passez COMPOSE_FILE=docker-compose.prod.yml
#                  explicitement si vous savez ce que vous faites)
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

log()  { echo "[seed-demo.sh] $(date '+%Y-%m-%d %H:%M:%S') - $*"; }
die()  { echo "[seed-demo.sh] ERREUR : $*" >&2; exit 1; }

RESET=0
for arg in "$@"; do
  case "$arg" in
    --reset) RESET=1 ;;
    *) die "argument inconnu : $arg (usage : $0 [--reset])" ;;
  esac
done

SEED_FILE="$ROOT_DIR/docs/donnees-demo.sql"
[[ -f "$SEED_FILE" ]] || die "fichier introuvable : $SEED_FILE"

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

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml}"
[[ -f "$ROOT_DIR/$COMPOSE_FILE" ]] || die "fichier compose introuvable : $ROOT_DIR/$COMPOSE_FILE"

# Garde-fou : le jeu de demonstration (comptes publics, hash de mot de passe commun) ne
# doit jamais atteindre une base de production. Une instance de production se reconnait
# a son fichier compose ou a la presence de DOMAIN dans .env (constat F029 de l audit).
if [[ "$COMPOSE_FILE" == *prod* || -n "${DOMAIN:-}" ]] && [[ "${EKUISEO_ALLOW_SEED_PROD:-}" != "1" ]]; then
  die "refus de charger le jeu de demonstration sur une instance de production (COMPOSE_FILE=$COMPOSE_FILE, DOMAIN=${DOMAIN:-}). Pour une recette assumee : EKUISEO_ALLOW_SEED_PROD=1."
fi

command -v docker >/dev/null 2>&1 || die "docker n'est pas installe ou pas dans le PATH."

if ! docker compose -f "$COMPOSE_FILE" ps postgis --status running >/dev/null 2>&1; then
  die "le service 'postgis' ne semble pas demarre (docker compose -f $COMPOSE_FILE up -d postgis)."
fi

psql_exec() {
  docker compose -f "$COMPOSE_FILE" exec -T -e PGPASSWORD="$DB_PASSWORD" \
    postgis psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

if [[ "$RESET" == "1" ]]; then
  echo
  echo "==================================================================="
  echo " ATTENTION : --reset va VIDER les tables applicatives de '${DB_NAME}'"
  echo " (utilisateurs, vehicules, trajets, reservations, paiements, avis...)"
  echo " avant de recharger le jeu de demonstration. Fichier compose : ${COMPOSE_FILE}"
  echo "==================================================================="
  echo
  read -r -p "Tapez exactement 'RESET DEMO' pour confirmer : " CONFIRMATION
  [[ "$CONFIRMATION" == "RESET DEMO" ]] || die "confirmation incorrecte, operation annulee."

  log "Videment des tables applicatives..."
  psql_exec -c "
    TRUNCATE TABLE
      search_events, notifications, search_alerts, messages, conversations, reviews,
      driver_payouts, payments, bookings, trip_stops, trips, vehicles,
      otp_codes, users
    RESTART IDENTITY CASCADE;
  "
fi

log "Chargement de docs/donnees-demo.sql dans '${DB_NAME}'..."
psql_exec < "$SEED_FILE"

log "Jeu de donnees de demonstration charge avec succes."
log "Tous les comptes de demonstration utilisent le mot de passe : Demo1234!"
