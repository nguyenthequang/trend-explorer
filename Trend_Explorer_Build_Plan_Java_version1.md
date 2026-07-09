# Trend Explorer — Build Plan (Java Edition)

**Goal:** A website (phone + PC) with one search bar. The user types any word; the system fetches all matching public posts from Hacker News, Reddit, and Bluesky from the **last 3 days**, analyzes the consensus (volume, sentiment, themes, an LLM-written cited summary), then compares it against the same-length windows **1 week ago and 2 weeks ago** and narrates the trend shift. Fully free-tier; results cached so repeat searches are instant.

**This is the Java edition** of `Trend_Explorer_Build_Plan_version1.md`. The architecture, math, API contracts, database schema, frontend, and hosting are identical; the backend language and toolchain change. Purpose of the switch: demonstrate production Java/Spring proficiency for the enterprise SWE market.

**Tech stack:** Java 21 (LTS, virtual threads), Spring Boot 3.x (MVC + `SseEmitter`), `java.net.http.HttpClient`, hand-rolled Reddit OAuth2 client, Flyway migrations, Postgres 16 on Neon, JUnit 5 + Mockito + WireMock + Testcontainers, Maven (wrapper), Docker on Google Cloud Run (**us-central1**), Gemini (Groq fallback), Next.js + Tailwind + Recharts on Vercel, GitHub Actions CI with eval gates.

**Cost: $0/month.** Optional only: ~$10/yr domain.

---

## FOR CLAUDE CODE — read before writing any code

1. **Environment preflight comes first (Phase 0a).** Before asking the user to install *anything*, check what is already on the machine and report a table of found/missing. Only ask the user to install items that are actually missing, one clear instruction each. Checks:

```bash
java -version          # need 21+ (Temurin/any OpenJDK). 25 also fine.
git --version
docker --version && docker info   # daemon must be RUNNING (Testcontainers needs it)
node -v                # need 20+ (frontend only)
gcloud --version       # only needed from Phase 7; defer install until then if missing
code --list-extensions 2>/dev/null | grep -iE 'vscjava|spring'   # best-effort; skip if `code` CLI absent
```

   - Missing JDK → ask the user to install **Temurin JDK 21** from adoptium.net (or via SDKMAN on Mac/Linux). Verify again after.
   - Do **not** ask for a global Maven or Gradle — the project uses the Maven wrapper (`./mvnw`), which self-provisions.
   - Do **not** ask for a local Postgres — Testcontainers (tests) and Neon (runtime) cover it.
   - Docker installed but daemon stopped → ask the user to start Docker Desktop; re-check with `docker info`.
2. **Work one phase at a time**, in order (§12). After finishing a phase, run `./mvnw -q verify` (and `npm test`/lint in `web/` when it exists), show the results, and **stop for review** before the next phase.
3. **Human-only steps are marked `[HUMAN]`** (account signups, tokens, dashboard clicks, hand-labeling). Stop and ask; never work around a missing credential.
4. **Secrets:** first commit adds `.gitignore` containing `.env` and `application-local.yml`. Real values live in local env / GitHub Actions secrets / Cloud Run env vars. `.env.example` has names only. Never print secret values.
5. **Do not invent API fields or Maven coordinates.** External API endpoints/params are specified in §5 — if a response doesn't match, print it and ask. For dependencies, look up current versions on Maven Central; if an artifact can't be found (see the VADER note in §6), follow the specified fallback instead of substituting a random library.
6. **Cloud Run region is `us-central1` and is not negotiable** — the always-free tier applies only in us-central1/us-east1/us-west1.
7. **All analysis work happens inside the HTTP request** (§7), executed on virtual threads. Do not refactor to background queues/schedulers: Cloud Run's request-based billing throttles CPU outside requests, so detached work stalls. Do not switch to WebFlux — Spring MVC + virtual threads is the deliberate design.
8. **Never add a paid service.** No paid observability, queues, or vector DBs. If something seems to need one, ask.
9. **Java style for a learner:** prefer `record` types for DTOs/payloads; avoid Lombok (no annotation magic); constructor injection only; small classes. The user is learning Spring for interviews — after each phase, append a plain-language explanation of what was built, why, and the key Spring concept used (DI, beans, profiles, etc.) to `NOTES.md`.
10. Tests use **recorded JSON fixtures via WireMock** (no live network in CI). Live "smoke" runs are small `main` classes under `scripts/` the user triggers manually. Conventional commits.

`CLAUDE.md` for the repo root (copy verbatim):

```markdown
# Project: Trend Explorer (Java)
Read Trend_Explorer_Build_Plan_Java_version1.md fully before any task.
Rules: run the Phase 0a environment preflight before requesting any install,
and only ask for what is missing; work one phase at a time and stop for review;
never commit secrets (.env and application-local.yml are gitignored); never
invent API fields or Maven coordinates — follow §5/§6 exactly; Cloud Run region
is us-central1; all analysis runs inside the HTTP request on virtual threads
(no background jobs, no WebFlux); no paid services; prefer records, avoid
Lombok, constructor injection only; ./mvnw verify must pass before a phase is
done; append a plain-language explanation of each phase to NOTES.md.
```

---

## 1. Ground rules

1. **Official APIs only, non-commercial.** Reddit's free tier and Vercel's Hobby plan both require it: no ads, no monetization, ever. Register the Reddit app as a personal "script" app for research.
2. **Free tiers are protected by design:** 24 h result cache, per-IP budget (default 5 fresh analyses/day), global budget (default 75 fresh analyses/day), hard caps on posts fetched per source. When the global budget is spent, degrade gracefully to cached results.
3. **Honest coverage labels.** Reddit's free search cannot always reach 1–2 weeks back for busy terms (§5.3); the UI says so per source.
4. **The code does the math, the LLM does the words.** The model never computes a trend; it narrates trends already computed in Java + SQL.
5. **Every resume number comes from this system's own output.**

---

## 2. How one search flows

```
user types "langchain" ──▶ GET /api/analyses/stream?keyword=langchain  (SSE)
        │
        ├─ cache hit (<24h)? ──▶ emit single `result` event, done
        │
        └─ budget ok? ──▶ emit progress while, in-request on virtual threads:
              1. HN, Bluesky, Reddit fetched CONCURRENTLY (one virtual thread each)
              2. Score: VADER sentiment per post; theme terms via log-odds
              3. LLM: one call → current consensus + shift narrative, citing [#ids]
              4. Verify: one batched call → every claim–citation pair checked
              5. Persist to `analyses`, log to `search_log`, emit `result`
```

Windows at request time $T$ (same 3-day width, directly comparable):

| Window | Range |
|---|---|
| `now` | $[T-3\text{d},\; T]$ |
| `week_ago` | $[T-10\text{d},\; T-7\text{d}]$ |
| `two_weeks_ago` | $[T-17\text{d},\; T-14\text{d}]$ |

---

## 3. Repo layout (standard Maven)

```
trend-explorer/
├── .github/workflows/ci.yml          # mvnw verify + evals → deploy on green
├── pom.xml  ─ mvnw ─ .mvn/
├── src/main/java/com/quang/trend/
│   ├── TrendExplorerApplication.java
│   ├── api/        AnalysisController.java (SSE), PopularController.java,
│   │               HealthController.java, BudgetGuard.java
│   ├── core/       Windows.java, Scoring.java (VADER+log-odds), DeltaMath.java,
│   │               BriefService.java, VerifyService.java, LlmClient.java,
│   │               AnalysisOrchestrator.java, Coalescer.java
│   ├── adapters/   SourceAdapter.java (interface), RawPost.java (record),
│   │               WindowResult.java (record), HnAdapter.java,
│   │               BlueskyAdapter.java, RedditAdapter.java, RedditAuth.java
│   └── store/      AnalysisRepository.java, SearchLogRepository.java (JdbcClient)
├── src/main/resources/
│   ├── application.yml               # spring.threads.virtual.enabled: true
│   └── db/migration/V1__init.sql    # Flyway
├── src/test/java/…                   # JUnit 5, Mockito, WireMock, Testcontainers
├── src/test/resources/fixtures/      # recorded API JSON
├── eval/                             # golden_sentiment.jsonl [HUMAN labels],
│                                     # fixtures/, champion_metrics.json,
│                                     # SentimentEval.java, FaithfulnessEval.java
├── web/                              # Next.js (identical to Python plan §10)
├── scripts/                          # SmokeHn.java etc. (manual live checks)
├── Dockerfile                        # multi-stage: mvnw package → temurin 21-jre
├── .env.example ─ CLAUDE.md ─ NOTES.md ─ README.md
```

---

## 4. Database schema — Flyway `V1__init.sql` (identical to Python edition)

```sql
CREATE TABLE analyses (
    id          BIGSERIAL PRIMARY KEY,
    keyword     TEXT NOT NULL,                 -- lowercased, trimmed
    result      JSONB NOT NULL,                -- full payload (§8)
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    fresh_until TIMESTAMPTZ NOT NULL           -- created_at + 24h
);
CREATE INDEX idx_analyses_kw ON analyses (keyword, created_at DESC);

CREATE TABLE search_log (
    id         BIGSERIAL PRIMARY KEY,
    keyword    TEXT NOT NULL,
    ip_hash    TEXT NOT NULL,                  -- sha256(ip + SALT env), never raw IP
    fresh      BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_log_budget ON search_log (ip_hash, created_at);
CREATE INDEX idx_log_day    ON search_log (created_at) WHERE fresh;
```

Persistence stays deliberately thin: Spring's `JdbcClient` with explicit SQL (write `result` with a `?::jsonb` cast; read it as `String`, parse with Jackson). No JPA — fewer moving parts, and "I chose JdbcClient over JPA because the model is two tables and JSON" is a defensible interview answer. Budgets are counts over `search_log` for the current UTC day. Cleanup is opportunistic on the first request of each day (delete analyses > 30 d, logs > 90 d). Storage ≈ 150–250 MB worst case — inside Neon's free 0.5 GB.

---

## 5. Source adapters — exact contracts

```java
public record RawPost(String source, String sourceId, String author, String title,
                      String body, String url, Integer score, Instant postedAt) {}

public record WindowResult(List<RawPost> posts,      // capped, sorted by score desc
                           Integer totalCount,        // exact if API counts; else null
                           String coverage) {}        // "full" | "partial" | "none"

public interface SourceAdapter {
    String name();
    WindowResult fetchWindow(String keyword, Instant start, Instant end);
}
```

All three adapters use one shared `java.net.http.HttpClient`; each handles its own pagination and backs off with jitter on HTTP 429. Per-window post cap: **60** (top by engagement). The orchestrator runs the three adapters concurrently on virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`), so a fresh analysis is bounded by the slowest source, not the sum.

### 5.1 Hacker News (Algolia) — no auth, full archive
- **Counts:** `GET https://hn.algolia.com/api/v1/search_by_date?query=<kw>&tags=(story,comment)&numericFilters=created_at_i>=<start>,created_at_i<<end>&hitsPerPage=0` → read `nbHits`. Used for window totals **and** a 17-point daily series (one call/day) powering the sparkline.
- **Posts:** `GET https://hn.algolia.com/api/v1/search?query=<kw>&tags=(story,comment)&numericFilters=<same>&hitsPerPage=60`.
- ≤ ~1 req/s politeness. Coverage always `full`.

### 5.2 Bluesky — no auth for public reads
- `GET https://public.api.bsky.app/xrpc/app.bsky.feed.searchPosts?q=<kw>&since=<ISO>&until=<ISO>&sort=top&limit=100`, follow `cursor` up to **3 pages per window**. Treat sample size as the count (label "sampled"); coverage `full`; backoff on 429.

### 5.3 Reddit — hand-rolled OAuth2 (no maintained Java wrapper exists; that's a feature)
- **Token (`RedditAuth`):** `POST https://www.reddit.com/api/v1/access_token` with HTTP Basic auth = `client_id:client_secret`, form body `grant_type=client_credentials` → app-only bearer token (~1 h). Cache it; refresh on expiry or 401. Mandatory header on every call: `User-Agent: trend-explorer/0.1 personal research by u/<username>` (Reddit blocks default agents).
- **Newest sweep:** `GET https://oauth.reddit.com/r/all/search?q=<kw>&sort=new&limit=100&raw_json=1` paginating with `after=<fullname>` up to **800 posts**; bucket each by `created_utc` into the three windows; stop once older than $T-17\text{d}$.
- **Coverage rule:** if the sweep's oldest post is newer than a window's start → that window is `partial` (or `none` if empty). The UI must render the label.
- **Representative sample (LLM only, not counts):** `GET https://oauth.reddit.com/r/all/search?q=<kw>&sort=top&t=month&limit=60&raw_json=1`, bucketed by timestamp.
- Respect `X-Ratelimit-Remaining` / `X-Ratelimit-Reset` response headers: if remaining < 2, sleep until reset. Free tier ≈ 100 req/min; a global semaphore (permits 2) serializes Reddit calls across concurrent analyses since the quota is account-wide.

---

## 6. Scoring — consensus math per window

1. **Sentiment:** VADER compound score per post over `title + body`; keep the mean plus shares of posts $> 0.05$ (positive) and $< -0.05$ (negative).
   **Dependency note for Claude Code:** search Maven Central for the VADER Java port (commonly `com.github.apanimesh061:vader-sentiment-analyzer`). If it resolves, use it. If it doesn't (or looks broken), **do not substitute a different sentiment library** — instead vendor VADER's published lexicon file (MIT-licensed) into `src/main/resources/` and implement the compound scoring in `Scoring.java` (~300 lines, well-documented algorithm, fully unit-testable against the golden set). Either path is free; the second is a better interview story.
2. **Themes:** distinguishing unigrams/bigrams between windows via smoothed log-odds. For token $w$ with counts $f_{\text{now}}(w)$, $f_{\text{base}}(w)$, totals $N$, vocabulary size $V$:

$$\mathrm{lo}(w) = \log\frac{f_{\text{now}}(w) + \alpha}{N_{\text{now}} + \alpha V} \;-\; \log\frac{f_{\text{base}}(w) + \alpha}{N_{\text{base}} + \alpha V}, \qquad \alpha = 0.5$$

Top-10 positive $\mathrm{lo}$ = emerging terms; top-10 negative = fading. Plain Java (`HashMap` counting, a small stop-word set) — deterministic, unit-tested with hand-computed expectations.
3. **Deltas:** volume ratio $\frac{n_{\text{now}}}{\max(n_{\text{base}},1)}$ per source and overall; sentiment delta $\bar{s}_{\text{now}} - \bar{s}_{\text{base}}$; against both baselines.

---

## 7. The SSE endpoint — analysis inside the request

`GET /api/analyses/stream?keyword=<kw>` → `text/event-stream` via Spring MVC `SseEmitter` (timeout 120 000 ms):

```
event: progress   data: {"stage":"hn","detail":"counting 17 days"}
event: progress   data: {"stage":"reddit","detail":"312 posts bucketed"}
event: progress   data: {"stage":"scoring","detail":"sentiment + themes"}
event: progress   data: {"stage":"llm","detail":"writing brief"}
event: result     data: {…full payload §8…}
```

Rules (identical semantics to the Python edition):
- **Fast path:** cached analysis with `fresh_until > now()` → emit `result` immediately with `"cached": true`; cached hits don't touch budgets.
- **Budget check** before any fetching; over budget → `event: error`, `{"code":"budget","popular":[…cached keywords…]}`.
- **Coalescing (`Coalescer`):** `ConcurrentHashMap<String, ReentrantLock>` per keyword — a simultaneous duplicate search blocks briefly, then hits the fresh cache.
- Keyword normalization: trim + lowercase, length 2–64, reject URLs/whitespace.
- Heartbeat: send an SSE comment every 10 s so proxies keep the stream open. `spring.threads.virtual.enabled: true` so the request thread is virtual; the three adapter fetches fan out on their own virtual threads.
- Set Cloud Run request timeout 120 s; a fresh analysis takes ~10–30 s. Frontend consumes with `EventSource` (plain GET).

Why in-request (the #1 refactor temptation): Cloud Run's request-based billing guarantees CPU only **during** a request; `@Async`/`@Scheduled` background work gets throttled when no request is in flight. In-request SSE is the design, not a shortcut.

---

## 8. LLM layer — one generate call, one verify call

**Payload** (assembled by `BriefService`, serialized by Jackson; also stored as `result.stats`) — identical JSON shape to the Python edition:

```json
{
  "keyword": "langchain",
  "windows": {
    "now":           {"counts": {"hn": 12, "reddit": 84, "bluesky": 41},
                      "coverage": {"hn":"full","reddit":"full","bluesky":"full"},
                      "avg_sentiment": -0.18, "pos_share": 0.21, "neg_share": 0.44},
    "week_ago":      {"counts": {"hn": 7, "reddit": 61, "bluesky": 30},
                      "coverage": {"hn":"full","reddit":"partial","bluesky":"full"},
                      "avg_sentiment": 0.05, "pos_share": 0.38, "neg_share": 0.19},
    "two_weeks_ago": {"counts": {"hn": 6, "reddit": null, "bluesky": 28},
                      "coverage": {"hn":"full","reddit":"none","bluesky":"full"},
                      "avg_sentiment": 0.02, "pos_share": 0.30, "neg_share": 0.22}
  },
  "themes": {"emerging": ["0.4 release", "breaking changes"], "fading": ["tutorial"]},
  "top_posts": {
    "now":      [{"id": "hn:44120", "title": "…", "snippet": "…", "score": 96}, "… ≤12"],
    "baseline": ["… ≤8 across both baseline windows"]
  }
}
```

**Generate (1 call).** `LlmClient` is provider-agnostic over plain `HttpClient`, switched by env (`LLM_PROVIDER`, `LLM_MODEL`, `LLM_API_KEY`):
- Gemini: `POST https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent` with header `x-goog-api-key: <key>`, body `{"contents":[{"parts":[{"text": …}]}]}`.
- Groq (OpenAI-compatible): `POST https://api.groq.com/openai/v1/chat/completions` with `Authorization: Bearer <key>`.

Prompt contract: two sections — `## Consensus (last 3 days)` (≤120 words) and `## Trend shift` (≤120 words); every factual claim cites post ids like `[hn:44120]`; only provided posts/stats may be used; where coverage is `partial`/`none`, the text must say the comparison is limited; if the data doesn't explain a shift, say so plainly.

**Verify (1 batched call).** Parse all (claim, cited-ids) pairs from the brief; ids not present in the payload hard-fail in code. One structured call returns `{"pair_index": n, "supported": true|false}` per pair; `faithfulness = supported/total`; store it; the UI shows a warning badge under 0.9.

**Quota math:** 2 calls × 75 fresh analyses/day = 150/day — inside even the stingiest reported Gemini Flash free cap (~250/day), leaving eval headroom. Exponential backoff on 429.

---

## 9. Tests, evals, CI

- **Unit/integration:** JUnit 5 + Mockito; adapters tested against **WireMock** serving the recorded fixtures in `src/test/resources/fixtures/`; repositories and the SSE flow tested against **Testcontainers** Postgres (`postgres:16`). Reminder: Testcontainers needs the local Docker daemon running — the preflight checks this.
- **Sentiment golden set:** `scripts/CollectGolden.java` samples ~200 posts across sources for 4–5 keywords → `[HUMAN]` labels `pos|neg|neu` → `eval/golden_sentiment.jsonl`. `SentimentEval` scores **VADER vs LLM zero-shot** (macro-F1) and prints the README table (accuracy / cost per 1k posts / latency). Production uses VADER; the table is the documented justification.
- **Faithfulness eval:** 5–8 recorded payload fixtures; `FaithfulnessEval` regenerates + verifies; passes at ≥0.9 average.
- **Gate:** CI fails if either metric drops >0.02 below `eval/champion_metrics.json`; prompt improvements update the champion file in the same PR. LLM-dependent evals run only when the API-key secret is present; everything else always runs.
- **`ci.yml`:** `actions/setup-java` (Temurin 21, Maven cache) → `./mvnw -q verify` (Docker is available on GitHub runners, so Testcontainers just works; authenticate to Docker Hub via `docker/login-action` + a free-account secret to dodge anonymous pull limits) → eval gates → on green, build image and deploy via `google-github-actions/deploy-cloudrun`, region `us-central1`. Vercel auto-deploys `web/`.

---

## 10. Frontend — unchanged from the Python edition

The `web/` Next.js app is identical to §10 of `Trend_Explorer_Build_Plan_version1.md`: search-bar hero with "Popular right now" chips (`GET /api/popular`), live `EventSource` progress checklist, stacked mobile-first result cards (volume sparkline from the 17-day HN series, sentiment now-vs-baselines, theme chips, the two briefs with tappable `[id]` citations + faithfulness badge, cited-post cards), coverage badges on Reddit, tap-not-hover everywhere, 44 px targets, designed error states (budget reached / too obscure / source down), `/architecture` page. Test on a real phone via every Vercel preview URL.

---

## 11. Docker + hosting (all verified free, July 2026)

**Dockerfile (multi-stage):** stage 1 `maven`-on-`temurin-21` (or `./mvnw -q package` with the wrapper) → stage 2 `eclipse-temurin:21-jre` slim, copying the fat jar; `ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"`; `EXPOSE 8080`.

| Layer | Service | Config + gotcha |
|---|---|---|
| API | Cloud Run | **us-central1 only** (free tier is US-regions-only); **memory 1 GiB** (the JVM wants headroom), min-instances 0, max 2, concurrency 20, timeout 120 s; card on file → **$1 budget alert** day one; JVM cold start ≈ 5–15 s on first request after idle — acceptable for a portfolio; GraalVM native image (stretch §13) cuts it to sub-second |
| Frontend | Vercel Hobby | non-commercial only (no ads, ever); pauses rather than bills on overrun |
| DB | Neon free | 0.5 GB, scale-to-zero, no card; slow first query after idle |
| CI/CD | GitHub Actions (public repo) | free unlimited minutes; Docker available for Testcontainers |
| LLM | Gemini free tier (Groq fallback) | caps churn (~250–1,500 req/day Flash-class); model via env; backoff; free-tier prompts may train models — inputs are public posts, fine |
| Sentiment/themes | VADER (port or vendored lexicon) + plain Java | $0, in-process |

Free-tier compute note: the JVM's baseline is heavier than Python's, but at portfolio traffic with scale-to-zero the vCPU-second and GiB-second usage stays far inside the always-free allowance; the practical difference is only the cold start.

---

## 12. Phases (each ends: `./mvnw -q verify` green, NOTES.md updated, stop for review)

**Phase 0a — environment preflight (Claude Code runs the §"FOR CLAUDE CODE" checks).** Report found/missing; `[HUMAN]` installs only what's missing (typically just Temurin JDK 21 — Docker, Git, Node, VS Code are likely already present). *Done when `java -version` shows 21+, `docker info` succeeds, `node -v` shows 20+.*

**Phase 0b `[HUMAN]` — accounts (~1 evening):** GitHub public repo · Neon project (`DATABASE_URL`) · Reddit script app (id/secret) · Gemini or Groq key · GCP project + billing + **$1 budget alert** (us-central1) · Vercel account · free Docker Hub account (CI pulls) · all values into local env + GitHub Actions secrets.

**Phase 1 — skeleton.** Scaffold from start.spring.io (web + jdbc + flyway + postgres, Java 21, Maven), `.gitignore` first commit, `V1__init.sql`, `GET /health`, `application.yml` with `spring.threads.virtual.enabled: true`, `ci.yml` running `verify` with a Testcontainers smoke test. *Done when CI is green on a trivial repository test.*

**Phase 2 — windows + HN.** `Windows.java` (pure, tested), `HnAdapter`: nbHits counts, 17-day series, per-window posts — WireMock-fixture-tested; `scripts/SmokeHn.java` live check. *Done when the smoke run prints believable counts for "python" and tests pass offline.*

**Phase 3 — Bluesky + Reddit.** `BlueskyAdapter` (cursor pagination), `RedditAuth` (client-credentials token cache + refresh), `RedditAdapter` (newest sweep, bucketing, coverage rule, top-of-month sample, rate-limit-header handling, global semaphore) — all fixture-tested + smoke scripts. *Done when a busy keyword yields `partial`/`none` Reddit baselines and a niche one yields `full`.*

**Phase 4 — scoring.** VADER (port or vendored per §6), log-odds, deltas, payload assembly matching §8 exactly — deterministic tests with hand-computed expectations. *Done when a fixture set's payload matches golden JSON byte-for-byte.*

**Phase 5 — LLM + evals.** `LlmClient` (env-switched Gemini/Groq), `BriefService` prompt + citation parser, `VerifyService` batched checker; `[HUMAN]` labels the golden set; both evals + champion file + CI gates. *Done when a deliberately degraded prompt fails CI, the real one passes, and the README table holds measured numbers.*

**Phase 6 — API assembly.** `AnalysisOrchestrator` (virtual-thread fan-out), SSE controller, cache fast-path, `BudgetGuard` + ip hashing, `Coalescer`, opportunistic cleanup, `/api/popular` — integration-tested with adapters stubbed to fixtures over Testcontainers Postgres. *Done when: first search streams stages then result; an immediate repeat returns `"cached": true` instantly; the 6th fresh search from one hashed IP gets the budget error.*

**Phase 7 — frontend + deploy.** `web/` per §10; Dockerfile; Cloud Run deploy on green CI (`[HUMAN]`: gcloud auth if the CLI was deferred); CORS locked to the Vercel origin. *Done when a phone loads the public URL, runs a fresh search watching live progress, and taps a citation through to the real post.*

**Phase 8 — polish.** `/architecture` page; README: architecture diagram up top, phone + desktop screenshots, the VADER-vs-LLM table, CI badge, and ~5 design-decision paragraphs (SSE-in-request vs background jobs on Cloud Run; virtual-thread fan-out; budgets/caching as quota engineering; Reddit coverage honesty; JdbcClient-over-JPA; hand-rolled OAuth2). *Done when the README alone tells the whole story.*

---

## 13. Stretch goals (only after Phase 8)

1. **GraalVM native image** — Spring Boot 3 AOT: sub-second cold starts, ~¼ the memory, and a strong resume line. Only now install GraalVM.
2. **Tracked keywords mode** — the curated always-on layer from `Social_Listening_Build_Plan_version2.md`, with Spring Scheduler replaced by a GitHub Actions cron hitting an internal endpoint (background schedulers don't fit Cloud Run request billing).
3. **YouTube comments adapter** (Data API free quota).
4. **Configurable windows** as query params.
5. **PWA manifest** (~1 hour).
6. **Vietnamese keywords** — VADER is English-only; route non-English text to the LLM sentiment path and label it.

---

## 14. Resume bullets (fill every bracket from measured output)

**SWE / Java (the headline version)**
- Built a production-style trend-analysis service in Java 21/Spring Boot 3: SSE progress streaming inside Cloud Run requests, virtual-thread fan-out across three external APIs, hand-rolled OAuth2 client with rate-limit-aware backoff, Flyway migrations, and JUnit 5/Mockito/WireMock/Testcontainers tests in GitHub Actions CI — [N]% coverage, zero-manual-step deploys
- Engineered a quota-safe public product on 100% free-tier infra: 24 h Postgres caching, per-IP + global daily budgets with graceful degradation, request coalescing — [P95] for fresh analyses, instant when cached

**ML/AI-adjacent**
- Added an LLM layer under a strict citation contract (consensus + trend-shift briefs grounded in fetched posts) with automated batched faithfulness verification (≥[X]% claim support) gating CI; benchmarked VADER vs zero-shot LLM sentiment on a hand-labeled golden set ([X] vs [Y] macro-F1 at $0 vs $[Z]/1k posts) and shipped the classical model on the measured trade-off

**Data**
- Implemented windowed comparative analytics over heterogeneous public APIs: aligned 3-day windows (now / −7 d / −14 d), per-source coverage detection, volume + sentiment deltas, and emerging-theme extraction via smoothed log-odds — with honest data-quality labels for Reddit's bounded search reach

**30-second pitch:** "Type any word and it tells you what the internet said about it in the last three days and how that differs from one and two weeks ago. It's Java 21 and Spring Boot: the three sources are fetched concurrently on virtual threads inside an SSE-streaming request, the math — counts, sentiment, log-odds themes — happens in code, and only then does an LLM narrate under a citation contract with an automated faithfulness check gating my CI. It runs on $0/month; the interesting engineering is the caching and budget layer that lets anonymous strangers use it without exhausting a free tier."
