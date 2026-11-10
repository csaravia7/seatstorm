SHELL := bash
# Tag used to push/deploy images: `latest` unless you pass TAG=<version> (TAG ?= dev below is only for local compose builds)
DEPLOY_TAG := $(if $(filter undefined,$(origin TAG)),latest,$(TAG))
TAG ?= dev
CODESPACE_URL = https://$(CODESPACE_NAME)-8080.app.github.dev
.PHONY: bootstrap infra-up infra-down demo-up demo-down demo-reset dev build native test-web test-agent dev-agent smoke qr topics logs psql aks-deploy push-images push-native k8s-env k8s-deploy k8s-destroy codespaces-up codespaces-down

bootstrap:                ## one-time setup after the Codespace is created
	cp -n .env.example .env || true
	[ -z "$$DOCKER_PAT" ] || [ -z "$$DOCKER_ID" ] || echo "$$DOCKER_PAT" | docker login -u "$$DOCKER_ID" --password-stdin
	./mvnw -q -DskipTests install
	(cd web/spa && npm ci) && (cd web/bff && npm ci) && (cd web/bots && npm ci)
	(cd services/assistant-agent && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt)

infra-up:                 ## Kafka, Postgres, Apicurio, Kafka UI
	docker compose up -d
infra-down:
	docker compose down

demo-up:                  ## everything, from the images docker.io/$$DOCKER_ID/<svc>:$$TAG (default `seatstorm`: local images)
	docker compose --profile apps up -d
demo-down:
	docker compose --profile apps down
demo-reset:               ## event-driven reset through the console API
	curl -sf -X POST localhost:8080/admin/login -H 'content-type: application/json' -d "{\"pin\":\"$${CONSOLE_PIN:-4321}\"}" -c /tmp/ss.cookie
	curl -sf -X POST localhost:8080/admin/reset -b /tmp/ss.cookie

dev:                      ## make dev SVC=seat-inventory -> infra in compose, that service in quarkus:dev (JVM)
	docker compose stop $(SVC) || true
	$(MAKE) infra-up
	./mvnw -pl services/$(SVC) -am quarkus:dev

build:
	./mvnw -B verify
native:                   ## make native SVC=seat-inventory (container build; slow, needs 6-8 GB; not for the Codespace)
	scripts/build-native.sh $(SVC)
test-web:
	for d in spa bff bots; do (cd web/$$d && npm test --if-present); done
test-agent:
	cd services/assistant-agent && .venv/bin/pytest -q
dev-agent:                ## agent on localhost:8108 (needs ANTHROPIC_API_KEY or NVIDIA_API_KEY)
	cd services/assistant-agent && PORT=8108 QUERY_URL=http://localhost:8105 .venv/bin/uvicorn app.main:app --reload --port 8108
smoke:                    ## k6 smoke through the public URL
	docker run --rm -i --network host -e BASE_URL=http://localhost:8080 grafana/k6 run - < tests/k6/smoke.js

qr:                       ## QR with the public Codespace URL
	qrencode -t ANSIUTF8 "$(CODESPACE_URL)"

topics:
	docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
logs:
	docker compose --profile apps logs -f --tail=100 $(SVC)
psql:                     ## make psql DB=inventory_db
	docker compose exec postgres psql -U seatstorm -d $(DB)

push-native:              ## make push-native [TAG=<version>, default latest] [SVC=seat-inventory]  (GraalVM NATIVE images -> your Docker Hub; needs DOCKER_ID, DOCKER_PAT, ~8 GB Docker RAM)
	TAG=$(DEPLOY_TAG) scripts/push-native.sh $(SVC)

push-images:              ## make push-images [TAG=<version>, default latest]  (JVM images -> docker.io/$DOCKER_ID/<svc>:<tag>; needs DOCKER_ID, DOCKER_PAT)
	TAG=$(DEPLOY_TAG) scripts/push-dockerhub.sh

HELM_VALUES ?=
aks-deploy:               ## make aks-deploy [TAG=<version>, default latest] [HELM_VALUES=charts/seatstorm/values-jvm.yaml for JVM images]  (needs DOCKER_ID)
	@[ -n "$$DOCKER_ID" ] || { echo "ERROR: DOCKER_ID is required (your Docker Hub username)" >&2; exit 2; }
	helm upgrade --install platform charts/platform -n seatstorm-platform --create-namespace
	helm upgrade --install seatstorm charts/seatstorm -n seatstorm --create-namespace $(if $(HELM_VALUES),-f $(HELM_VALUES)) --set image.org=$$DOCKER_ID --set image.tag=$(DEPLOY_TAG) $(if $(filter latest,$(DEPLOY_TAG)),--set image.pullPolicy=Always)
	kubectl get svc web -n seatstorm

k8s-env:                  ## pide y exporta tokens/variables (usa: source k8s/scripts/setup-env.sh)
	@echo 'Ejecuta:  source k8s/scripts/setup-env.sh'
k8s-deploy:               ## make k8s-deploy [TAG=<version>, default latest] [OVERLAY=aks|aks-jvm]  (pide variables, despliega con manifiestos YAML)
	k8s/scripts/deploy.sh $(DEPLOY_TAG)

k8s-destroy:
	k8s/scripts/destroy.sh

codespaces-up:            ## build JVM images and start everything (Codespaces / any Docker host)
	scripts/codespaces-up.sh
codespaces-down:
	scripts/codespaces-down.sh
