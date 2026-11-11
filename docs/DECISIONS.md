# DECISIONS

Deviations from, and additions to, SEATSTORM-SPEC.md recorded while implementing Epic E0.
Spec ADRs 01-20 are unchanged except where noted below.

## D-01 Quarkus 3.33.4.1
Spec pins 3.33.4 and allows another 3.33.x patch. 3.33.4.1 is the latest 3.33.x on Maven Central
(platform micro-patch). Parent POM: `quarkus.platform.version=3.33.4.1`. Java release 21 although the
local JDK is 25.

## D-02 Native build conventions follow the user's reference repo (quarkus-project)
- Profile `native` (activated by `-Dnative`) toggles `skipITs=false` and `quarkus.native.enabled=true`.
- Profile `dynatrace-native` (use together with `-Dnative`): `dynatrace-native-maven-plugin` 2.1.0,
  env `DT_ENV_URL` and `DT_API_TOKEN` (SPK-04).
- **Builder/runtime images changed vs spec 3.4/10.7**: builder `quay.io/quarkus/ubi9-quarkus-graalvmce-builder-image:jdk-21`
  (`quarkus.native.builder-image` in every service) and runtime `quay.io/quarkus/ubi9-quarkus-micro-image:2.0`
  (spec said `quarkus-micro-image:2.0`): both UBI9 so the binary matches the runtime libc.
- `services/<svc>/src/main/docker/Dockerfile.native-micro` (spec name kept so CI paths hold) is multi-stage: builds
  the native binary in the builder image with `./mvnw package -pl services/<svc> -am -Dnative`, BuildKit secret
  `dt_api_token` (never in a layer), `ARG DT_ENV_URL` switches on `-Pdynatrace-native`, and copies
  `target/dynatrace` next to the binary. Build context = repository root.
- `services/<svc>/src/main/docker/Dockerfile.jvm`: JVM image (fast-jar), context = repository root.
- `scripts/build-native.sh [svc...]`: `-Dnative -Dquarkus.native.container-build=true`.
- HTTP: `quarkus.http.port=${PORT:8080}`, host `0.0.0.0`, access log on with
  `exclude-pattern=/q/health.*`, `quarkus.swagger-ui.always-include=true`, OpenAPI at `/q/openapi`.
  (Spec 7.0 had the access log disabled; changed to the reference convention.)
- surefire/failsafe set `java.util.logging.manager=org.jboss.logmanager.LogManager` (the plain `contracts` library
  overrides this because it has no JBoss LogManager on its classpath).

## D-03 SPK-01 result: Avro + Apicurio 3.1.7 + quarkus-messaging-kafka (DONE, working)
Verified three ways: `@QuarkusTest` with Dev Services (outbox -> relay -> Kafka -> consume as `SpecificRecord`),
and live against the compose stack (event-catalog fast-jar in a container, outbox row inserted with psql,
relay publishes, Kafka UI decodes the body). Kafka UI shows the Avro message as readable JSON with serde
`SchemaRegistry`. Final properties (all in `platform-lib/.../microprofile-config.properties`):

| Property (prefix `mp.messaging.outgoing.outbox-out.` / `mp.messaging.incoming.<ch>.`) | Value |
|---|---|
| `value.serializer` | `io.apicurio.registry.serde.avro.AvroKafkaSerializer` |
| `value.deserializer` | `io.apicurio.registry.serde.avro.AvroKafkaDeserializer` |
| `apicurio.registry.url` (via `mp.messaging.connector.smallrye-kafka.`) | `.../apis/registry/v3` |
| `apicurio.registry.auto-register` | `true` |
| `apicurio.registry.artifact-resolver-strategy` | `io.apicurio.registry.serde.avro.strategy.RecordIdStrategy` (**package is `serde.avro.strategy`**, not `serde.strategy`; spec said only the class name). Artifact = simple record name in group = Avro namespace `io.seatstorm.contracts` |
| `apicurio.registry.use-specific-avro-reader` | `true` (consumer) |
| `apicurio.registry.dereference-schema` | `true` (**required for Kafka UI**) |

Findings:
- Serde client is **v3** (`apicurio-registry-java-sdk` 3.1.7); extension version matches the registry image 3.1.7.
- Wire format is `0x00` magic byte + 4-byte id (Confluent compatible). Kafka UI reads it through
  `apis/ccompat/v7`.
- Without `dereference-schema=true` the serializer registers nested records (`EventMeta`, `SeatChange`) as separate
  artifacts with references; the ccompat endpoint then fails resolving references
  (`No artifact with ID 'io.seatstorm.contracts.EventMeta' in group 'null'`) and Kafka UI shows raw bytes
  (`valueSerde: Fallback`). With the flag the message is readable. No fallback to JSON needed (plan B not used).
- Kafka UI needs the serde chosen once in the UI (messages view -> Value serde = SchemaRegistry) or via API
  `valueSerde=SchemaRegistry`; it does not auto-detect.
- `%test`/dev: `kafka.bootstrap.servers` and the registry URL are only set for `%prod` and `%dev`; if they were
  set unconditionally (as in the spec snippet) Quarkus Dev Services would be disabled in tests.
- The Apicurio serde resolves record classes by name from the context classloader. In `@QuarkusTest` this gives
  `ClassCastException ... SpecificRecord` unless the Avro classes and runtime share a loader:
  `%test.quarkus.class-loading.parent-first-artifacts=io.seatstorm:contracts,org.apache.avro:avro` (already in each
  service's `application.properties`). **Never set it for prod** (fast-jar then fails with NoClassDefFoundError
  for Jackson).
- `contracts` carries a Jandex index so Quarkus treats the generated Avro classes as application classes
  (native reflection registration by `quarkus-avro`).

## D-04 Relay uses a Vert.x timer, not `@Scheduled`
Quarkus `@Scheduled(every="200ms")` logs "every() value less than 1000 ms is not supported ... executed with a
delay" and in practice ticks every 1 s. The demo needs sub-second latency, so `OutboxRelay` starts a Vert.x
periodic timer (`seatstorm.outbox.relay.interval=200ms`, skip-concurrent via an `AtomicBoolean`, blocking work on a
Vert.x worker, ordered). Everything else matches 7.0: single active relay across replicas through
`pg_try_advisory_xact_lock(727001)`, at-least-once, batch of 200. On a send failure the relay stops the batch
(rows acked so far stay marked) and retries on the next tick. Cleanup keeps `@Scheduled(every=60s)`.

## D-05 `EventRegistry` shape
`EventRegistry` (contracts) is a final class with static `decode/encode/classOf/schemaOf/all()` and an instance
API (`fromJson`, `toJson`, `typeOf`) so it can be injected: `platform-lib` produces it as a `@Singleton`
(final class cannot be client-proxied). It registers the 25 IDL records and resolves classes from its own static
map through a `SpecificData` subclass (no `Class.forName`). Avro JSON means the standard Avro JSON encoding
(unions wrapped, e.g. `{"string":"x"}`; null union is `null`).

## D-06 `notification` does not depend on `platform-lib`
Spec 7.7: stateless, no database, no outbox. `platform-lib` carries JPA/Flyway/Outbox beans that need a datasource,
so `notification` depends only on `contracts` and repeats the few common settings it needs in its own
`application.properties`. It has no `demo-control` consumer yet (E6 adds it with `Record<String,String>` ui.events
output).

## D-07 `platform-lib` extras beyond the spec API
`Outbox.add(topic,key,event)` and `Outbox.addBySection(topic, sectionId, event)` (key = sectionId, partition =
`Sections.indexOf`), `ErrorCode` enum + `ApiException`, `OutboxRelay.relayOnce()` (public, for tests),
`OutboxCleanup.cleanup(...)`. `Outbox.add` throws `IllegalStateException` when no transaction is active.
Demo-control: channel `demo-control` and its config live in platform-lib (group `${quarkus.application.name}-demo-control`).
Services with state implement `ResetHandler` as a CDI bean (every bean found is called); the library clears the inbox
and enqueues `ResetCompleted` in the same transaction.
The common `seatstorm.hold.seconds` / `seatstorm.hold.payment-extension-seconds` keys (60 / 120) are defined there too.

## D-08 Local test environment notes
- Docker is provided by Colima; Testcontainers needs
  `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
- `docker compose` (plugin) was broken locally; `docker-compose` v5 worked. Compose file is standard.
- Host port 5432 may be taken by a local Postgres; compose then maps but the host client hits the wrong server.
  Use `docker compose exec postgres psql` or free the port.
- `make` on macOS is 3.81 and does not support `.RECIPEPREFIX`. The Makefile was converted to standard tab-indented recipes so it works with any GNU Make (including 3.81).
- `./mvnw -B verify` runs Dev Services containers (Kafka, Postgres 16, Apicurio): Docker must be running.

## D-09 SPK-02 (native compatibility): NOT executed
Native compilation is not feasible locally (spec 3.4: 6-8 GB and 3-6 min per service; Colima has 7.7 GB) and is
validated in CI. Prepared groundwork: `contracts` is Jandex-indexed (so `quarkus-avro` registers the generated
`SpecificRecord` classes for reflection); `EventRegistry` avoids `Class.forName`; Panache entity `OutboxEntry` is
indexed through platform-lib's Jandex; Flyway locations are `classpath:db/common` and `classpath:db/migration`.
Plan for the CI run (`./mvnw -B package -pl services/seat-inventory -am -Dnative`, then start the binary against
compose and push one outbox row): if reflection errors appear, add `@RegisterForReflection` on `OutboxEntry` and
the offending Avro/Apicurio classes (`reflection-config.json` in platform-lib); if Flyway cannot find migrations,
list the resources in `quarkus.native.resources.includes=db/**`; if a service is unrecoverable, ship it in JVM and
record an ADR (Dockerfile.jvm already exists for every service).

## D-10 SPK-05 / SPK-03 / SPK-04 / SPK-06
Not part of E0; untouched.

## E6 seatmap-query and notification
- seatmap-query uses plain JDBC (Agroal, same JTA transaction as the inbox insert) instead of Panache entities: no entity
  registration for native, array/jsonb columns are simple. `order_view` has an extra `last_event_ms` column (V1 migration) so
  stale `OrderStatusChanged` events never regress a status. `TicketIssued` arriving before the order events creates a stub row
  that `OrderPlaced`/`OrderConfirmed` later fill (upserts).
- `sale_view` has no row until the first Sale event: `/api/stats` reports `saleStatus=CLOSED` then. Reset does not touch `sale_view`
  (event-catalog publishes `SaleClosed` on reset).
- `/api/seats/search` returns non-overlapping groups, round-robin across sections (max 5); `adjacent=true` = consecutive numbers in one row.
- notification feed `detail` does not carry the nickname (stateless, `SeatsRejected` has only buyerId); `PaymentFailed` feed line is derived
  from `OrderStatusChanged(CANCELLED, PAYMENT_FAILED)`. `ui.events` JSON is built with Jackson from ordered maps; null `reason`/`heldUntilMs` are kept as JSON null.
- "JSON Schema" tests are hand-written structural assertions (required fields/types per message type), no schema library.

## E3/E4/E5 (order-service, payment-sim, ticketing)
- **Idempotent create (R5/R2):** replay lookup runs before the sale check; the insert runs in its own
  `QuarkusTransaction.requiringNew()` so a unique violation (concurrent same key, or `one_active_order_per_buyer`)
  can be followed by re-queries. `ACTIVE_ORDER_EXISTS` is also pre-checked to avoid noisy constraint errors.
- **Duplicate `PaymentAuthorized`:** payment-sim re-emits the stored outcome (new `eventId`) on redelivery. If the order
  already stored that `paymentId`, order-service ignores it instead of refunding (spec table only says "other state ->
  RefundPayment"; a literal reading would refund a confirmed payment). A different `paymentId` still refunds.
- **Unknown order** in any reaction is ignored (after a reset in-flight events may reference truncated orders).
- `pay` sets `held_until = max(held_until, now+120s)` optimistically (and reports it in `OrderStatusChanged(PAYING)`);
  `SeatsHoldExtended` later overwrites it with the authoritative value. `SeatsHoldExtended` itself emits nothing.
- `GET/POST /api/orders/{id}...` enforce `X-Buyer-Id` ownership (403 `NOT_YOUR_ORDER`); on GET only when the header is sent.
  `pay`/`cancel`/`create` without `X-Buyer-Id` -> 401 `NO_SESSION`; `create` without `Idempotency-Key` -> 400.
- `sale_state` upsert ignores events older than the stored `updated_at` (set from `meta.occurredAtMs`), since relay order
  is not commit order.
- **payment-sim pausing** uses a Vert.x periodic timer (500 ms), same reason as D-04 (`@Scheduled` < 1 s unsupported),
  and `ChannelRegistry#getPausable("payment-commands")` (smallrye-reactive-messaging 4.33). Config:
  `mp.messaging.incoming.payment-commands.pausable=true`. Messages already handed to the handler when the mode flips
  to PAUSED are processed (treated as OK). `SLOW` sleeps before opening the DB transaction.
- `RefundPayment` for an unknown/failed payment still answers `PaymentRefunded` so the saga cannot hang.
- ticketing: the QR signer is `QrSigner` (`v1.<ticketId>.<orderId>.<base64url-nopad HMAC>`), secret from
  `seatstorm.ticket.secret=${TICKET_SECRET:change-me-ticket-secret}`.
- Tests of the three services use Dev Services; Kafka topics with explicit partitions are pre-created through
  `%test.quarkus.kafka.devservices.topic-partitions."<topic>"=N`. Tests inject "other service" events through the
  outbox/relay (real Avro serializer) or call the handler bean directly.

## E1/E2 decisions (event-catalog, seat-inventory)

### D-11 event-catalog
- REST: `GET /api/concert` returns `{concertId, name, venue, startsAt (ISO-8601 UTC), saleStatus, sections:[{sectionId, index, label, priceCents, rows, seatsPerRow}]}`.
  `POST /admin/sale/open|close` and `POST /admin/reset` return `202` (`{saleStatus}` / `{resetId}`); `GET /admin/reset/{id}` returns `{done, pending:[...]}` (404 `NOT_FOUND` if unknown).
- Opening an already open sale (or closing a closed one) is a no-op: `202`, no event (conditional `UPDATE ... WHERE sale_status <> :new`).
- Two Kafka channels read `demo.control`: `demo-control` (platform-lib, `ResetRequested` -> `CatalogResetHandler`: sale `CLOSED` + `SaleClosed`, always published)
  and `reset-completed` (group `event-catalog-reset-completed`, drains `reset_run.pending`, uses the inbox). The catalog's own `ResetCompleted(event-catalog)` is not in `pending` and is ignored.
- `reset_run` is intentionally not cleared by the catalog reset; `V1000__seed.sql` is static SQL (`ON CONFLICT DO NOTHING`), a test checks it against `Sections`.

### D-12 seat-inventory
- Added nullable column `seat.buyer_id` (spec 6.3 has none): `ConfirmSeats` carries no buyer but `SeatsSold` requires `buyerId`. Set on hold, cleared on release/expiry/reset. CHECK constraints unchanged.
- Data access is plain JDBC (`SeatDao`) in the handler's JTA transaction (same pooled connection as the Hibernate outbox/inbox rows). All timestamps use the DB clock (`clock_timestamp()`).
- Layer 3: every transition is `UPDATE ... WHERE state=:s AND version=:v AND order_id IS NOT DISTINCT FROM :o` (stricter than spec: includes the version read under `FOR UPDATE`); 0 rows => exception => rollback + DLQ. Locks are taken `ORDER BY seat_id` (no deadlocks between overlapping holds).
- Expired-but-unreleased holds: a competing `HoldSeats` takes the seat over in two transitions (HELD->AVAILABLE v+1, ->HELD v+2) and first emits `SeatsReleased(HOLD_EXPIRED)` for the previous order, so order-service does not keep a stale HELD order.
- `ExtendHold` moves `held_until` without bumping `version` (no state transition; `SeatsHoldExtended` has no changes). No event if the order holds nothing.
- `ConfirmSeats` considers a seat confirmable while it is still `HELD` by the order, even if `held_until` has passed but the job has not released it (R6/refund covers the rest).
- `ReleaseSeats` emits no event when the order has no `HELD` seats (no phantom events). `SeatsConfirmFailed` is emitted when the order owns no seats or a mix.
- Reset: `SeatsReleased(RESET)` per section with `orderId = resetId`, 30 changes, `version+1` on all 360 seats.
- Validation failures (`unknown seat`, seat outside the declared section, empty list) throw `IllegalArgumentException` -> `seat.commands.dlq`. No size limit (1..4) in inventory (spec tests use 5 seats).
- DLQ config: the DLQ producer reuses the channel config, so `seat-commands` also sets `dead-letter-queue.value.serializer=AvroKafkaSerializer`, `apicurio.registry.auto-register`, `RecordIdStrategy`, `dereference-schema`. (Without them the DLQ write fails on the Avro serializer.)
- `seat-commands` has `concurrency=${SEAT_CONSUMER_CONCURRENCY:1}`. The expiry job cadence is `seatstorm.expiry.every` (default `1s`; `off` in `%test`, tests call `ExpiryJob.expireOnce()`; `ExpirySchedulerTest` runs the real schedule through a test profile).
- Tests create `seat.commands`/`seat.events` with 12 partitions via `%test.quarkus.kafka.devservices.topic-partitions."<topic>"=12` (explicit partitions need them).
- Local Colima note: if the host docker socket stops answering while the VM is healthy (`colima ssh -- docker ps` works), open a tunnel with
  `ssh -F ~/.colima/_lima/colima/ssh.config -o ControlMaster=no -o ControlPath=none -fN -L <path>/dk.sock:/var/run/docker.sock lima-colima` and point `DOCKER_HOST` at it.

## Integration

Validated on Colima with JVM images (`scripts/build-local-images.sh` + `compose.local.yaml`), whole stack through nginx/BFF.

- **bot-swarm never reached the BFF**: the code read `BFF_URL`, but compose, k8s and the Helm chart (and the spec) set `TARGET_URL`.
  Every bot ended in `errors`. Fix: `web/bots/src/server.ts` reads `TARGET_URL ?? BFF_URL`.
- **bot-swarm in compose had no `ADMIN_TOKEN`** (it presents it in `X-Bot-Token` to skip the per-IP session throttle); it only worked with the default token.
  Fix: `compose.yaml` passes `ADMIN_TOKEN` to `bot-swarm`. (k8s gets it through `seatstorm-secrets` envFrom.)
- **JVM images are OOM-killed at `mem_limit: 256m`** (the limit is sized for native). New `compose.local.yaml`: `pull_policy: never`, 512m for the six Quarkus services with a DB, 384m for notification.
  `scripts/build-local-images.sh` builds all 11 images (fast-jar copied into a thin `eclipse-temurin:21-jre` image; the per-service `Dockerfile.jvm` runs Maven inside Docker and is slower/heavier). The assistant-agent image needs the repo root as build context (`COPY seed/docs`).
- Verified end to end: sale open, session, order -> HELD -> pay -> CONFIRMED with `ticketId`/`ticketQr` (appears in `/api/my/orders` ~1 s after CONFIRMED), SSE `hello/me/seat/notify` through nginx+BFF,
  duel on one seat (one HELD, one `REJECTED/SEAT_TAKEN`), payment `PAUSED` (order stays PAYING, recovers after `OK`), hold expiry (`EXPIRED/HOLD_EXPIRED`, seat AVAILABLE again),
  demo reset (`done:true`, sale CLOSED, 360 available), assistant-agent no-key fallback message, k6 smoke (20 VUs, thresholds pass), 100 bots, `tests/invariants.sh` OK.
- Note: the sale is CLOSED after a demo reset, so bots/buyers need the sale re-opened first.
- `tests/invariants.sh` calls `docker compose`; with only the standalone `docker-compose` binary, put a `docker` shim first in PATH.

## Registry: personal Docker Hub, `DOCKER_ID` / `DOCKER_PAT`; model `openai/gpt-oss-20b`
- There is no `seatstorm` Docker Hub organization and no Azure-hosted registry. Images live in the user's personal Docker Hub account: `docker.io/${DOCKER_ID}/<svc>:<tag>`.
  Variables `DOCKER_ID` (username) and `DOCKER_PAT` (personal access token) replace `DOCKERHUB_USER`/`DOCKERHUB_TOKEN` everywhere (scripts, Makefile, devcontainer secrets, GitHub secrets).
  Compose/build scripts default to `seatstorm/<svc>` only so local builds work without an account. Kustomize cannot read env vars: base apps carry the placeholder `docker.io/DOCKER_ID/<svc>`, overlays set `images[].newName`, and `k8s/scripts/deploy.sh` rewrites `newName`+`newTag` in a temporary overlay copy (CI renders with `myuser`). Helm uses `image.org=$DOCKER_ID`.
- Two image sources, two memory profiles: native images from CI (`aks` overlay, 256Mi Quarkus limits) and JVM images from `make push-images` (`aks-jvm` overlay / `charts/seatstorm/values-jvm.yaml`, 512Mi for the six DB services and 384Mi for notification, same as `compose.local.yaml`); JVM images at 256Mi are OOM-killed.
- Assistant agent NVIDIA default model is now `openai/gpt-oss-20b` with `temperature=1`, `top_p=1`, `max_tokens=4096` (`NVIDIA_TEMPERATURE`, `NVIDIA_TOP_P`, `NVIDIA_MAX_TOKENS`). The key is read from the `NVIDIA_API_KEY` environment variable only.
  The model's `reasoning_content` is discarded (never streamed to users; only its length is logged at debug level). Tool calling with streaming for this model must be verified on build.nvidia.com (unverified).

## Arize AX tracing and chat guardrails (assistant-agent) — amends ADR-20
- **Guardrails** (`app/guardrails.py`, `GUARDRAILS_MODE=enforce|monitor|off`, default enforce): deterministic, no extra LLM call.
  Input: prompt_injection, pii_leakage (cards by Luhn, email, phone, ID, IBAN, secrets), toxicity, off_topic. A blocked message never reaches the model nor the session memory.
  Output (released per sentence, so streaming is per sentence instead of per token): PII redaction, toxicity and system-prompt-leak blocking, hallucination grounding flag (log only).
  Each trigger logs `guardrail_triggered` (`guardrail`, `direction`, `action`, `mode`, `sessionId`) and is recorded as a GUARDRAIL span.
- **Tracing** (`app/telemetry.py`): OpenInference auto-instrumentation of the `anthropic` and `openai` SDKs plus manual spans (root AGENT span per turn with `input.value`/`output.value`/`session.id`/`user.id`, GUARDRAIL spans, TOOL spans), exported with `arize-otel` over gRPC (Arize's default transport) to `ARIZE_OTLP_ENDPOINT` as documented (default `https://otlp.arize.com/v1`, no `/traces`). `ARIZE_TRANSPORT=http` is a fallback and then `/traces` is appended: OTLP/HTTP posts to the exact URL given, and `POST /v1` answers 200 "ok" without checking credentials while discarding the data (spans vanish silently).
  Enabled only when `ARIZE_SPACE_ID` and `ARIZE_API_KEY` are set (k8s: `seatstorm-secrets` via `create-secrets.sh`; local: `.env`). `ARIZE_ENABLED=false` is the kill switch. Messages blocked for PII are recorded as `[REDACTED...]`.
- **ADR-20 amended.** `opentelemetry*`, `openinference-*` and `arize-otel` are now allowed; `langchain*`, `traceloop*` and `openlit` stay forbidden (`tests/test_forbidden_deps.py`). The `opentelemetry-api==0.0.0` pin in `constraints.txt` was removed and `requirements.txt` regenerated (no existing pin changed; only additions).
- **Version constraint:** `openinference-instrumentation-anthropic` 2.x/3.x require `anthropic>=1.x`; the repo pins `anthropic==0.125.0`, so it is held at `>=1.1,<2` (1.1.2 instruments `anthropic>=0.84`). `tests/test_telemetry.py` fails if the instrumentor stops instrumenting the pinned SDK.
- **Open risk (unverified in a cluster):** Dynatrace OneAgent also hooks the AI SDKs, so LLM calls may appear twice (once per tool). If so, set `ARIZE_ENABLED=false` or disable the OneAgent AI sensor for this service.
- **Evals:** the root span carries one code-based evaluation per guardrail (`guardrail.toxicity|pii_leakage|prompt_injection|off_topic|hallucination`, score 1.0 = triggered) as OpenInference `evaluations.N.evaluation.*` plus flat `eval.<name>.*` attributes, and `assistant.context` (tool outputs) as the reference for judge evaluators. LLM-judge evaluators (Relevance, Fluency, ...) are configured in Arize: see `docs/ARIZE-EVALS.md`. Unverified: whether Arize AX renders the attribute-based evals natively.

## Asistente con herramientas de acción (reserva, navegación, admin opcional)

- El asistente puede **reservar** (`reserve_seats` → order-service con `X-Buyer-Id`/`X-Buyer-Nickname` de la sesión; el buyerId nunca lo decide el modelo), **cancelar**, consultar evento/mapa/ranking/órdenes y **navegar** (`navigate_to`). No paga: tras reservar emite un evento SSE `action` (`{type:"navigate", path:"/reservar", orderId}`) y el front carga esa orden y muestra la pantalla de pago.
- Herramientas de organizador (`open_sale`, `close_sale`, `reset_demo`, `get/set_payment_mode`, `start/stop_bots`, `get_bots_status`) solo existen con `ASSISTANT_ADMIN_TOOLS=true` (usan `ADMIN_TOKEN`). Por defecto **off**: la URL del chat es pública y cualquier usuario podría reiniciar la demo con un mensaje.
- `PROMPT_PROFILE=strict|open` y `GUARDRAILS_MODE=enforce|monitor|off` permiten la demo "la IA alucina / se pone tóxica / no responde": con `open` + `monitor` nada se bloquea y Arize evalúa (guardrails por reglas y evaluadores LLM-judge). Volver a `strict` + `enforce` para proteger.
