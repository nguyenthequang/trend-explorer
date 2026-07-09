# Trend Explorer

Type any word; get what Hacker News, Reddit, and Bluesky said about it in the
last 3 days — volume, sentiment, themes, and an LLM-written cited brief — and
how that differs from 1 and 2 weeks ago.

Java 21 · Spring Boot 3 (MVC + virtual threads) · Postgres (Neon) · Flyway ·
Testcontainers/WireMock · Cloud Run (us-central1) · Next.js on Vercel ·
$0/month.

> Work in progress — built phase by phase per
> `Trend_Explorer_Build_Plan_Java_version1.md`. See `NOTES.md` for the
> changelog and `TUTORIALS/` for beginner-friendly write-ups of each phase.

## Run tests

```bash
./mvnw verify   # needs Docker running (Testcontainers Postgres 16)
```

## Run locally

```bash
# needs DATABASE_URL / DATABASE_USERNAME / DATABASE_PASSWORD in the env
./mvnw spring-boot:run
curl localhost:8080/health   # {"status":"ok"}
```
