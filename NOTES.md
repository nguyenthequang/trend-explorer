# NOTES — factual changelog

## Phase 1 — skeleton (2026-07-09)

- Scaffolded Spring Boot 3.5.16 / Java 21 Maven project from start.spring.io
  (web, jdbc, flyway, postgresql, testcontainers).
- First commit was `.gitignore` alone; `.env` and `application-local.yml` are
  ignored so secrets can never be committed.
- Moved the four verified starter classes (`Windows`, `Keywords`, `Scoring`,
  `DeltaMath`) unchanged into `src/main/java/com/quang/trend/core/`.
  `CoreTests` stays in `trend-core-starter/` until its assertions are ported
  to JUnit 5 in Phases 2 and 4.
- `application.yml`: `spring.threads.virtual.enabled: true`, datasource from
  `DATABASE_URL`/`DATABASE_USERNAME`/`DATABASE_PASSWORD` env vars, Flyway on.
- `V1__init.sql`: `analyses` + `search_log` tables and indexes, verbatim from
  build plan §4.
- `GET /health` → `{"status":"ok"}` (`HealthController`, tested with
  `@WebMvcTest`).
- `SchemaSmokeTest`: boots against Testcontainers Postgres 16 via
  `@ServiceConnection`, proving the Flyway migration applies and the
  `?::jsonb` write / `String` read pattern works.
- `.github/workflows/ci.yml`: Temurin 21 + Maven cache → `./mvnw -q verify`
  (Docker is available on GitHub runners; optional Docker Hub login step).
- `./mvnw verify` green locally.
