# Developer entry points. Run `make help` for the list.
SHELL := /bin/bash
COMPOSE := docker compose

.PHONY: help
help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

.env:
	@cp .env.example .env && echo "Created .env from .env.example — replace every 'change-me' value."

.PHONY: up
up: .env ## Start local infrastructure (databases, broker, IdP, gateway, processing)
	$(COMPOSE) up -d

.PHONY: up-all
up-all: .env ## Start infrastructure + observability + containerised apps
	$(COMPOSE) --profile observability --profile app up -d --build

.PHONY: down
down: ## Stop all containers (keeps volumes)
	$(COMPOSE) --profile observability --profile app down

.PHONY: clean-volumes
clean-volumes: ## Stop containers AND delete local data volumes
	$(COMPOSE) --profile observability --profile app down -v

.PHONY: keycloak-bootstrap
keycloak-bootstrap: ## Set the admin-api client secret and create local admin/PM users from .env
	infrastructure/docker/scripts/keycloak-bootstrap.sh

.PHONY: backend-run
backend-run: .env ## Run the backend on the host against compose infrastructure
	set -a && source .env && set +a && cd apps/backend && \
	SPRING_PROFILES_ACTIVE=local SERVER_PORT=$${BACKEND_PORT:-8080} MANAGEMENT_SERVER_PORT=$${BACKEND_MANAGEMENT_PORT:-8081} \
	DB_URL=jdbc:postgresql://localhost:5432/$$POSTGRES_DB DB_USERNAME=$$POSTGRES_USER DB_PASSWORD=$$POSTGRES_PASSWORD \
	RABBITMQ_USERNAME=$$RABBITMQ_USERNAME OIDC_ISSUER_URI=http://localhost:8180/realms/diligence \
	S3_ENDPOINT=http://localhost:$${OBJECT_STORAGE_PORT:-9000} AWS_ACCESS_KEY_ID=$$S3_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY=$$S3_SECRET_ACCESS_KEY \
	./gradlew bootRun

.PHONY: backend-test
backend-test: ## Backend unit + modularity tests
	cd apps/backend && ./gradlew build

.PHONY: backend-it
backend-it: ## Backend integration tests (requires Docker)
	cd apps/backend && ./gradlew integrationTest

.PHONY: frontend-install
frontend-install: ## Install frontend dependencies
	cd apps/frontend && npm ci

.PHONY: frontend-run
frontend-run: ## Run the frontend dev server
	cd apps/frontend && npm run dev

.PHONY: frontend-check
frontend-check: ## Format check, lint, typecheck, unit tests and build the frontend
	cd apps/frontend && npm run format:check && npm run lint && npm run typecheck && npm test && npm run build

.PHONY: api-types
api-types: ## Regenerate frontend API types from the running backend's OpenAPI spec
	cd apps/frontend && npm run generate:api

.PHONY: zap-baseline
zap-baseline: ## Passive OWASP ZAP scan of the running frontend (host network, so the redirect to Keycloak on localhost resolves); ZAP_COOKIE='diligence_sid=…' scans signed in
	mkdir -p tests/security/reports
	docker run --rm --network host -v "$(CURDIR)/tests/security:/zap/wrk:rw" \
		ghcr.io/zaproxy/zaproxy:2.16.1 zap-baseline.py -t $${ZAP_TARGET:-http://host.docker.internal:3000} \
		-c baseline.conf -r reports/zap-baseline.html -J reports/zap-baseline.json -I \
		$${ZAP_COOKIE:+-z "-config replacer.full_list(0).description=session -config replacer.full_list(0).enabled=true \
		-config replacer.full_list(0).matchtype=REQ_HEADER -config replacer.full_list(0).matchstr=Cookie \
		-config replacer.full_list(0).regex=false -config replacer.full_list(0).replacement=$$ZAP_COOKIE"}

.PHONY: verify
verify: backend-test frontend-check ## Everything CI runs without Docker
	kubectl kustomize infrastructure/kubernetes/overlays/prod > /dev/null
	@echo "All checks passed."
