COMPOSE ?= docker compose
MVN ?= mvn

.DEFAULT_GOAL := help

help: ## show available targets
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | sort | \
	  awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-18s\033[0m %s\n",$$1,$$2}'

## ---- middleware ----
up: ## start middleware (postgres, kafka, wiremock) and wait until ready
	$(COMPOSE) up -d
	@$(MAKE) wait

wait: ## block until postgres + kafka are reachable
	@echo "waiting for postgres..."; \
	until $(COMPOSE) exec -T postgres pg_isready -U postgres -d samples >/dev/null 2>&1; do sleep 1; done; \
	echo "waiting for kafka..."; \
	until $(COMPOSE) exec -T kafka /opt/bitnami/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1; do sleep 1; done; \
	echo "middleware ready (postgres:5432, kafka:9092, wiremock:8089)."

down: ## stop middleware, keep volumes
	$(COMPOSE) down

reset: ## stop middleware and WIPE volumes (clean slate, no dirty data)
	$(COMPOSE) down -v

ps: ## middleware status
	$(COMPOSE) ps

logs: ## tail middleware logs
	$(COMPOSE) logs -f

events: ## consume a topic, e.g. make events TOPIC=s1.order-placed
	$(COMPOSE) exec -T kafka /opt/bitnami/kafka/bin/kafka-console-consumer.sh \
	  --bootstrap-server localhost:9092 --topic $(TOPIC) --from-beginning --timeout-ms 5000

## ---- structure 1: modular monolith, logical BCs (Spring Modulith), port 8081 ----
s1-build: ## compile structure-1
	$(MVN) -f structure-1-modulith/pom.xml -q verify
s1: s1-build ## build + run structure-1 (8081)
	$(MVN) -f structure-1-modulith/pom.xml spring-boot:run

## ---- structure 2: modular monolith, physical BCs (multi-module), port 8082 ----
s2-build: ## compile structure-2 (all modules)
	$(MVN) -f structure-2-multimodule/pom.xml -q verify
s2: s2-build ## build + run structure-2 start module (8082)
	$(MVN) -f structure-2-multimodule/start/pom.xml spring-boot:run

## ---- structure 3: service per BC (COLA), ports 8083 / 8084 ----
s3-build: ## compile structure-3 (both services)
	$(MVN) -f structure-3-microservices/pom.xml -q verify
s3-ordering: s3-build ## run ordering-service (8083)
	$(MVN) -f structure-3-microservices/ordering-service/pom.xml spring-boot:run
s3-inventory: s3-build ## run inventory-service (8084)
	$(MVN) -f structure-3-microservices/inventory-service/pom.xml spring-boot:run

.PHONY: help up wait down reset ps logs events \
        s1-build s1 s2-build s2 s3-build s3-ordering s3-inventory
