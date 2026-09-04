#!/usr/bin/env bash
# ============================================================
# Ekuiseo — deploiement en production
# ============================================================
# Usage : ./scripts/deploy.sh
#
# Etapes : git pull -> construction des images -> redemarrage du backend (les
# migrations Flyway s'executent automatiquement au demarrage de l'application,
# voir spring.flyway.enabled=true dans application.yml, il n'y a pas de commande
# de migration separee) -> verification du healthcheck -> redemarrage du frontend
# -> rechargement de Caddy. Si le backend ne redevient pas "healthy" a temps,
# la derniere version qui fonctionnait est automatiquement restauree (rollback).
#
# Limite assumee : avec un seul conteneur backend (architecture adaptee a un VPS
# modeste, sans repliques ni equilibreur de charge), un redemarrage 100% sans
# coupure n'est pas possible avec Docker Compose seul. Ce script reduit la
# coupure au strict minimum en construisant la nouvelle image AVANT d'arreter
# l'ancien conteneur : l'indisponibilite se limite au temps de redemarrage de la
# JVM (quelques secondes a ~30s), pas au temps de build (souvent plusieurs
# minutes). Un vrai zero-coupure necessiterait plusieurs repliques du backend
# derriere un equilibreur de charge — hors de portee de ce depot de depart.
#
# Variables d'environnement requises (definies dans .env a la racine du depot) :
#   DB_NAME, DB_USER, DB_PASSWORD, JWT_SECRET, DOMAIN, ACME_EMAIL
#
# Variables optionnelles :
#   COMPOSE_FILE     defaut : docker-compose.prod.yml
#   HEALTH_TIMEOUT   secondes max d'attente d'un service "healthy" (defaut : 120)
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

log()  { echo "[deploy.sh] $(date '+%Y-%m-%d %H:%M:%S') - $*"; }
warn() { echo "[deploy.sh] ATTENTION : $*" >&2; }
die()  { echo "[deploy.sh] ERREUR : $*" >&2; exit 1; }

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"
[[ -f "$ROOT_DIR/$COMPOSE_FILE" ]] || die "fichier compose introuvable : $ROOT_DIR/$COMPOSE_FILE"

command -v git >/dev/null 2>&1 || die "git n'est pas installe ou pas dans le PATH."
command -v docker >/dev/null 2>&1 || die "docker n'est pas installe ou pas dans le PATH."
git -C "$ROOT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1 || \
  die "$ROOT_DIR n'est pas un depot git (deploy.sh suppose un clone git sur le serveur)."

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
: "${JWT_SECRET:?la variable JWT_SECRET doit etre definie dans .env}"
: "${DOMAIN:?la variable DOMAIN doit etre definie dans .env}"
: "${ACME_EMAIL:?la variable ACME_EMAIL doit etre definie dans .env}"

dc() { docker compose -f "$COMPOSE_FILE" "$@"; }

# Attend qu'un service compose devienne "healthy" (ou echoue au bout de HEALTH_TIMEOUT).
# Retourne 0 si healthy, 1 sinon.
wait_healthy() {
  local service="$1"
  local waited=0
  local cid status
  log "Attente que '$service' devienne 'healthy' (max ${HEALTH_TIMEOUT}s)..."
  while (( waited < HEALTH_TIMEOUT )); do
    cid="$(dc ps -q "$service" || true)"
    if [[ -n "$cid" ]]; then
      status="$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$cid" 2>/dev/null || echo "unknown")"
      if [[ "$status" == "healthy" ]]; then
        log "  '$service' est healthy (apres ${waited}s)."
        return 0
      elif [[ "$status" == "no-healthcheck" ]]; then
        log "  '$service' n'a pas de healthcheck defini, on considere le demarrage reussi."
        return 0
      fi
    fi
    sleep 3
    waited=$((waited + 3))
  done
  warn "'$service' n'est pas devenu healthy dans les ${HEALTH_TIMEOUT}s impartis."
  return 1
}

rollback() {
  local previous_commit="$1"
  warn "=== DEBUT DU ROLLBACK vers $previous_commit ==="
  git -C "$ROOT_DIR" reset --hard "$previous_commit" || { warn "impossible de revenir au commit precedent avec git reset --hard. Intervention manuelle requise."; return 1; }
  log "Reconstruction des images de la version precedente..."
  dc build backend frontend
  log "Redemarrage du backend (version precedente)..."
  dc up -d --no-deps backend
  if wait_healthy backend; then
    dc up -d --no-deps frontend
    warn "=== ROLLBACK REUSSI : la version precedente ($previous_commit) est de nouveau active. ==="
    return 0
  else
    warn "=== ROLLBACK ECHOUE : le backend ne redevient pas healthy meme avec l'ancienne version. ==="
    warn "Intervention manuelle urgente requise (voir docs/EXPLOITATION.md). Logs : docker compose -f $COMPOSE_FILE logs backend --tail=200"
    return 1
  fi
}

# --- 1. git pull -------------------------------------------------------------
if [[ -n "$(git -C "$ROOT_DIR" status --porcelain)" ]]; then
  die "des modifications locales non commitees sont presentes dans $ROOT_DIR. Nettoyez ou committez-les avant de deployer (git status)."
fi

PREVIOUS_COMMIT="$(git -C "$ROOT_DIR" rev-parse HEAD)"
log "Commit actuel (point de restauration en cas de rollback) : $PREVIOUS_COMMIT"

log "git pull (fast-forward uniquement)..."
git -C "$ROOT_DIR" fetch --prune
git -C "$ROOT_DIR" pull --ff-only || die "git pull --ff-only a echoue (l'historique local a peut-etre diverge). Resolvez manuellement puis relancez."

NEW_COMMIT="$(git -C "$ROOT_DIR" rev-parse HEAD)"
if [[ "$NEW_COMMIT" == "$PREVIOUS_COMMIT" ]]; then
  log "Aucun nouveau commit : le depot est deja a jour. On reconstruit et redemarre quand meme (utile apres un changement de .env)."
fi

# --- 2. Construction des images -----------------------------------------------
log "Construction des images backend et frontend..."
dc build backend frontend

# --- 3. Base de donnees : demarrage + attente (les migrations Flyway ---------
#        s'executent ensuite automatiquement au demarrage du backend) ----------
log "Demarrage/verification de postgis..."
dc up -d postgis
wait_healthy postgis || die "postgis n'est pas devenu healthy, deploiement interrompu (rien n'a encore ete redemarre en prod)."

# --- 4. Redemarrage du backend (migration Flyway incluse) --------------------
log "Redemarrage du backend avec la nouvelle image..."
dc up -d --no-deps backend

if ! wait_healthy backend; then
  warn "Le backend n'est pas devenu healthy apres le deploiement."
  if [[ "$NEW_COMMIT" != "$PREVIOUS_COMMIT" ]]; then
    rollback "$PREVIOUS_COMMIT"
    die "deploiement echoue, rollback tente (voir logs ci-dessus pour son resultat)."
  else
    die "deploiement echoue, mais aucun nouveau commit n'a ete recupere : un rollback git n'apporterait rien. Inspectez les logs (docker compose -f $COMPOSE_FILE logs backend --tail=200)."
  fi
fi

# --- 5. Redemarrage du frontend ----------------------------------------------
log "Redemarrage du frontend avec la nouvelle image..."
dc up -d --no-deps frontend
wait_healthy frontend || warn "le frontend n'est pas devenu healthy, mais le backend est OK : verifiez manuellement (docker compose -f $COMPOSE_FILE logs frontend)."

# --- 6. Rechargement de Caddy (au cas ou le Caddyfile a change) ---------------
log "Rechargement de la configuration Caddy (au cas ou)..."
dc up -d caddy
if ! dc exec caddy caddy reload --config /etc/caddy/Caddyfile 2>/dev/null; then
  warn "le rechargement a chaud de Caddy a echoue (non bloquant) ; verifiez avec : docker compose -f $COMPOSE_FILE logs caddy"
fi

log "Deploiement termine avec succes (commit $NEW_COMMIT)."
log "Verification manuelle recommandee : curl -I https://${DOMAIN}/api/v1/... et un test de bout en bout sur https://${DOMAIN}"
