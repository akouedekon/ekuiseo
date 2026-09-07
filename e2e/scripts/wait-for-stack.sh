#!/usr/bin/env bash
# ============================================================
# Ekuiseo — attente de la pile de test (API puis front Vite)
# ============================================================
# Usage : bash e2e/scripts/wait-for-stack.sh
#   E2E_API_URL      (defaut http://localhost:8080)   sonde /actuator/health, statut UP
#   E2E_BASE_URL     (defaut http://localhost:5173)   page d'accueil servie par Vite
#   E2E_WAIT_SECONDS (defaut 480)                     delai total avant abandon
# Le premier demarrage en CI est long : construction de l'image backend (Maven), puis
# `npm ci` du front dans son conteneur avant que Vite n'ecoute.
# ============================================================
set -euo pipefail

API="${E2E_API_URL:-http://localhost:8080}"
FRONT="${E2E_BASE_URL:-http://localhost:5173}"
TIMEOUT="${E2E_WAIT_SECONDS:-480}"

log() { echo "[wait-for-stack] $(date '+%H:%M:%S') - $*"; }

deadline=$((SECONDS + TIMEOUT))

log "Attente de l'API sur $API/actuator/health (jusqu'a ${TIMEOUT}s)"
until curl -fsS --max-time 5 "$API/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    log "ERREUR : l'API ne repond pas. Journaux : docker logs --tail 200 ekuiseo-backend"
    exit 1
  fi
  sleep 5
done
log "API prete."

log "Attente du front sur $FRONT/"
until curl -fsS --max-time 5 -o /dev/null "$FRONT/" 2>/dev/null; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    log "ERREUR : le front ne repond pas. Journaux : docker logs --tail 200 ekuiseo-frontend"
    exit 1
  fi
  sleep 5
done
log "Front pret."
