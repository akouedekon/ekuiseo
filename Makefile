# ============================================================
# Ekuiseo — raccourcis de developpement et d'exploitation
# ============================================================
# `make` (ou `make help`) affiche cette aide. Chaque cible appelle simplement
# docker compose ou l'un des scripts de scripts/ — voir ces fichiers pour le detail.
# ============================================================

COMPOSE_DEV  := docker compose -f docker-compose.yml
COMPOSE_PROD := docker compose -f docker-compose.prod.yml

.DEFAULT_GOAL := help

.PHONY: help dev build test seed backup deploy logs clean

help: ## Affiche cette aide
	@echo "Cibles disponibles :"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

dev: ## Demarre l'environnement de developpement (postgis + backend + frontend dev + adminer)
	$(COMPOSE_DEV) up --build

build: ## Construit les images de production (backend + frontend) sans les demarrer
	$(COMPOSE_PROD) build backend frontend

test: ## Lance les tests backend (mvn verify) et le lint + build frontend
	cd backend && mvn -B verify
	cd frontend && npm ci && npm run lint && npm run build

seed: ## Charge le jeu de donnees de demonstration (docs/donnees-demo.sql) en dev
	./scripts/seed-demo.sh

backup: ## Sauvegarde la base de donnees (voir scripts/backup.sh)
	./scripts/backup.sh

deploy: ## Deploie la derniere version en production (voir scripts/deploy.sh)
	./scripts/deploy.sh

logs: ## Suit les logs de l'environnement de production
	$(COMPOSE_PROD) logs -f

clean: ## Arrete les conteneurs de dev et de prod (sans jamais toucher aux volumes nommes : donnees et certificats TLS conserves)
	$(COMPOSE_DEV) down --remove-orphans
	$(COMPOSE_PROD) down --remove-orphans
	docker system prune -f
