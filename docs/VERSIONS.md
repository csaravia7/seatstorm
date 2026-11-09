# VERSIONS

Versions actually resolved while building Epic E0 (2026-11-09). Re-verify when bumping.

## Toolchain

| Item | Version | Notes |
|---|---|---|
| JDK used locally | Temurin 25.0.2 | Code is compiled with `maven.compiler.release=21`; CI and the devcontainer use Temurin 21 |
| Maven (wrapper) | 3.9.14 | `./mvnw`, wrapper 3.3.4, distribution type `only-script` (no jar committed) |
| Quarkus Platform | **3.33.4.1** (`io.quarkus.platform:quarkus-bom` and `quarkus-maven-plugin`) | Latest 3.33.x on Maven Central (3.33.4.1 is a platform micro-patch of the 3.33.4 LTS) |
| Local Docker | Colima (Docker Engine, 4 CPU / 7.7 GB) | Local `make` is GNU Make 3.81: no `.RECIPEPREFIX`; use `gmake` 4.x or run the recipes by hand. Linux/Codespaces is fine |

## Resolved by the Quarkus BOM (`./mvnw dependency:tree`)

| Library | Version |
|---|---|
| Avro (`org.apache.avro:avro`) and `avro-maven-plugin` | 1.12.1 |
| Apicurio serde (`apicurio-registry-avro-serde-kafka`, `-serde-common-avro`, `-schema-resolver`) | 3.1.7 (matches the registry image) |
| `kafka-clients` (transitive only, never declared) | 4.1.2 |
| SmallRye Reactive Messaging Kafka | 4.33.0 |
| Vert.x core | 4.5.35 |
| Hibernate ORM | 7.2.25.Final |
| Flyway core | 12.0.0 |
| PostgreSQL JDBC | 42.7.13 |
| Agroal | 3.0.1 |
| RestAssured | 5.5.6 |
| Awaitility | 4.3.0 |
| Jandex | 3.5.3 |

## Maven plugins (pinned in the parent POM)

| Plugin | Version |
|---|---|
| maven-compiler-plugin | 3.14.0 |
| maven-surefire / failsafe | 3.5.3 |
| jandex-maven-plugin | 3.5.3 |
| dynatrace-native-maven-plugin (profile `dynatrace-native`) | 2.1.0 |

Note: the test artifact `quarkus-junit5` is relocated to `quarkus-junit` in 3.31+; the POMs use `quarkus-junit`.

## Images (verified to exist / pulled)

| Image | Tag | Verified |
|---|---|---|
| `apache/kafka` | 4.0.0 | pulled, KRaft healthy, 17 topics created |
| `postgres` | 16 | pulled, 7 databases created |
| `apicurio/apicurio-registry` | 3.1.7 | pulled, SQL storage on `apicurio_db` (Dev Services use `quay.io/apicurio/apicurio-registry:3.1.7`) |
| `kafbat/kafka-ui` | v1.2.0 | pulled, decodes Avro through ccompat (see SPK-01 in DECISIONS.md) |
| `nginxinc/nginx-unprivileged` | 1.28-alpine | manifest exists |
| `grafana/k6` | 1.0.0 | manifest exists |
| `quay.io/quarkus/ubi9-quarkus-graalvmce-builder-image` | jdk-21 | manifest exists (native builder, from the reference repo) |
| `quay.io/quarkus/ubi9-quarkus-micro-image` | 2.0 | manifest exists (native runtime) |
| `eclipse-temurin` | 21-jre | pulled (JVM Dockerfile runtime, smoke test) |
| `maven` | 3.9-eclipse-temurin-21 | JVM Dockerfile build stage (not pulled locally) |
