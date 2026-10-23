SHELL := /bin/bash
COMPOSE := docker compose --env-file .env -f deploy/docker-compose.yml
# Load .env into the environment of the command that follows (for `mvn spring-boot:run`).
WITH_ENV := set -a; source .env; set +a;

.PHONY: obs-up obs-down stack-up stack-down smoke images loadtest-reset loadtest help init infra-up infra-down infra-reset infra-logs build test run-booking run-payment run-notification health

help:            ## list targets
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | sed 's/:.*##/ -/'

init:            ## create .env from the template (does not overwrite an existing one)
	@test -f .env && echo ".env already exists" || (cp .env.example .env && echo "created .env - edit the CHANGE_ME values")

infra-up:        ## start MySQL, Redis, Kafka and wait until healthy
	$(COMPOSE) up -d --wait

infra-down:      ## stop containers, keep data
	$(COMPOSE) down

infra-reset:     ## stop containers and DELETE all data (fresh databases on next up)
	$(COMPOSE) down -v

infra-logs:      ## follow infra logs
	$(COMPOSE) logs -f

build:           ## compile + test everything
	mvn -B clean verify

test:            ## unit tests only
	mvn -B test

run-booking:     ## run booking-service on :8081 (needs infra-up)
	mvn -B -q -DskipTests -pl common install && $(WITH_ENV) mvn -B -pl booking-service spring-boot:run

run-payment:     ## run payment-service on :8082
	mvn -B -q -DskipTests -pl common install && $(WITH_ENV) mvn -B -pl payment-service spring-boot:run

run-notification: ## run notification-service on :8083
	mvn -B -q -DskipTests -pl common install && $(WITH_ENV) mvn -B -pl notification-service spring-boot:run

health:          ## probe all three services
	@for p in 8081 8082 8083; do printf "%s: " $$p; curl -s localhost:$$p/actuator/health | head -c 200; echo; done

loadtest-reset:  ## reset the demo event to 100 free seats (LOCAL DEV ONLY)
	@$(WITH_ENV) docker exec -i fs-mysql mysql -ubooking -p"$$BOOKING_DB_PASSWORD" booking_db < loadtest/reset.sql

loadtest:        ## run the k6 flash-sale test in Docker against booking-service on :8081
	docker run --rm -v "$(PWD)/loadtest/k6:/scripts" -e BASE_URL=http://host.docker.internal:8081 grafana/k6 run /scripts/flash-sale.js

STACK := docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.app.yml

images:          ## build the three service images
	docker build -f booking-service/Dockerfile      -t flash-sale/booking-service:local .
	docker build -f payment-service/Dockerfile      -t flash-sale/payment-service:local .
	docker build -f notification-service/Dockerfile -t flash-sale/notification-service:local .

stack-up:        ## whole stack (infra + 3 services + nginx) in Docker, waits until healthy
	bash deploy/check-env.sh
	$(STACK) up -d --build --wait

stack-down:      ## stop the whole stack (add -v by hand to wipe data)
	$(STACK) down

smoke:           ## end-to-end check against the running stack on http://localhost:8080
	bash deploy/smoke-test.sh

OBS := $(STACK) -f deploy/docker-compose.observability.yml

obs-up:          ## full stack + Prometheus (:9090) + Grafana (:3000); needs GRAFANA_ADMIN_PASSWORD in .env
	bash deploy/check-env.sh
	@grep -qE '^GRAFANA_ADMIN_PASSWORD=.+' .env || (echo "FAIL: add GRAFANA_ADMIN_PASSWORD=<choose one> to .env"; exit 1)
	$(OBS) up -d --build --wait

obs-down:        ## stop everything started by obs-up (data volumes are kept)
	$(OBS) down
