# NOTES — factual changelog

## Phase 2 — windows + Hacker News (2026-07-09)

- Ported `CoreTests.windows()` + `.keywords()` to JUnit 5 (`WindowsTest` 7,
  `KeywordsTest` 5) — verbatim, test-only commit; core classes untouched.
  Scoring/DeltaMath assertions stay in starter `CoreTests` until Phase 4.
- Adapters package: `SourceAdapter` interface, `RawPost` + `WindowResult`
  records, shared `AdapterException`.
- `HnAdapter` (Algolia, no auth, coverage always "full"): `nbHits` counts via
  `search_by_date&hitsPerPage=0`; 60 posts via `search`, sorted by score desc
  (null scores last); 17-day daily series; half-open `numericFilters` mirrors
  Windows; 429 backoff with jitter. Base URL injectable for WireMock.
- Jackson tree model for parsing (irregular story/comment shapes); field
  fallbacks title→story_title, story_text→comment_text, url→HN item URL.
- WireMock deps: `org.wiremock:wiremock-standalone:3.13.1` (shaded, avoids
  Jetty clash). `HnAdapterTest` = 9 tests over recorded fixtures in
  `src/test/resources/fixtures/hn/`. `./mvnw verify` green (25 tests, offline).
- `scripts/SmokeHn.java` (manual live check) under a `smoke` Maven profile
  (build-helper adds `scripts/` as source, exec-maven-plugin runs it) — not in
  the normal build or CI. Live run for "python": now=252 / week_ago=340 /
  two_weeks_ago=201, all coverage=full, believable 17-day series.

## Phase 0b — accounts / remote (2026-07-09)

- Remote `origin` = https://github.com/nguyenthequang/trend-explorer; pushed
  main (4 commits). CI runs on push.
- First CI run failed at workflow validation (0 jobs): the `secrets` context
  is not allowed inside `if:`. Fixed by mapping `DOCKERHUB_USERNAME` to a
  job-level env var and testing `env.*` — CI green on c023cf0.
- Docker Hub login step is currently `skipped` (secret name mismatch — needs
  the repo secrets named exactly `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN`).
  Not blocking: anonymous `postgres:16` pull still succeeds.

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
