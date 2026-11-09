.RECIPEPREFIX := >
SHELL := bash
TAG ?= dev
CODESPACE_URL = https://$(CODESPACE_NAME)-8080.app.github.dev
.PHONY: bootstrap infra-up infra-down demo-up demo-down demo-reset dev build native test-web test-agent dev-agent smoke qr topics logs psql aks-deploy k8s-deploy k8s-destroy codespaces-up codespaces-down

bootstrap:                ## one-time setup after the Codespace is created
> cp -n .env.example .env || true
> [ -z "$$DOCKERHUB_TOKEN" ] || echo "$$DOCKERHUB_TOKEN" | docker login -u "$$DOCKERHUB_USER" --password-stdin
> ./mvnw -q -DskipTests install
> (cd web/spa && npm ci) && (cd web/bff && npm ci) && (cd web/bots && npm ci)
> (cd services/assistant-agent && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt)

infra-up:                 ## Kafka, Postgres, Apicurio, Kafka UI
> docker compose up -d
infra-down:
> docker compose down

demo-up:                  ## everything, from Docker Hub images (same as AKS)
> docker compose --profile apps up -d
demo-down:
> docker compose --profile apps down
demo-reset:               ## event-driven reset through the console API
> curl -sf -X POST localhost:8080/admin/login -H 'content-type: application/json' -d "{\"pin\":\"$${CONSOLE_PIN:-4321}\"}" -c /tmp/ss.cookie
> curl -sf -X POST localhost:8080/admin/reset -b /tmp/ss.cookie

dev:                      ## make dev SVC=seat-inventory -> infra in compose, that service in quarkus:dev (JVM)
> docker compose stop $(SVC) || true
> $(MAKE) infra-up
> ./mvnw -pl services/$(SVC) -am quarkus:dev

build:
> ./mvnw -B verify
native:                   ## make native SVC=seat-inventory (container build; slow, needs 6-8 GB; not for the Codespace)
> scripts/build-native.sh $(SVC)
test-web:
> for d in spa bff bots; do (cd web/$$d && npm test --if-present); done
test-agent:
> cd services/assistant-agent && .venv/bin/pytest -q
dev-agent:                ## agent on localhost:8108 (needs ANTHROPIC_API_KEY or NVIDIA_API_KEY)
> cd services/assistant-agent && PORT=8108 QUERY_URL=http://localhost:8105 .venv/bin/uvicorn app.main:app --reload --port 8108
smoke:                    ## k6 smoke through the public URL
> docker run --rm -i --network host -e BASE_URL=http://localhost:8080 grafana/k6 run - < tests/k6/smoke.js

qr:                       ## QR with the public Codespace URL
> qrencode -t ANSIUTF8 "$(CODESPACE_URL)"

topics:
> docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
logs:
> docker compose --profile apps logs -f --tail=100 $(SVC)
psql:                     ## make psql DB=inventory_db
> docker compose exec postgres psql -U seatstorm -d $(DB)

aks-deploy:               ## make aks-deploy TAG=<git-sha>
> helm upgrade --install platform charts/platform -n seatstorm-platform --create-namespace
> helm upgrade --install seatstorm charts/seatstorm -n seatstorm --create-namespace --set image.tag=$(TAG)
> kubectl get svc web -n seatstorm

k8s-deploy:               ## make k8s-deploy TAG=<git-sha>  (manifiestos YAML, sin Helm)
> k8s/scripts/deploy.sh $(TAG)

k8s-destroy:
> k8s/scripts/destroy.sh

codespaces-up:            ## build JVM images and start everything (Codespaces / any Docker host)
> scripts/codespaces-up.sh
codespaces-down:
> scripts/codespaces-down.sh
