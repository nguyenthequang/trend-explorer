# Tutorial 2 (Phase 1) — The Skeleton: Spring Boot, Flyway, and Tests That Use a Real Database

*Covers: the project scaffold, `application.yml`, `V1__init.sql`, `HealthController`, `SchemaSmokeTest`, and the CI workflow. Read alongside the code; everything here is on disk and asserted by `./mvnw verify`.*

Phase 1 produces a running Spring Boot application that does almost nothing — one endpoint, two empty tables. That's deliberate. The point of a skeleton phase is to get every piece of *infrastructure* proven (build, config, migrations, database tests, CI) while the code is still trivial, so that when real logic arrives in Phase 2 you're only ever debugging one thing at a time.

---

## 1. What Spring actually is — DI and beans in two paragraphs

A Spring application is a bag of objects called **beans**, created and wired together by Spring's *container* at startup. When you write:

```java
@RestController
public class HealthController { … }
```

the `@RestController` annotation tells Spring "create one instance of this class, keep it in the container, and route matching HTTP requests to it." You never write `new HealthController()` — the container does. This is **dependency injection (DI)**: instead of objects constructing the things they need, they *declare* what they need (as constructor parameters, in this project — never field injection) and the container hands them in. Why bother? Two reasons that will recur all through this build. First, *swappability*: in Phase 6 the orchestrator will ask for a `SourceAdapter` list, and tests will hand it fakes while production hands it the real HN/Reddit/Bluesky adapters — same code, different wiring. Second, *lifecycle*: the container knows the right order to build things (datasource → Flyway → repositories → controllers), which you'd otherwise manage by hand.

`TrendExplorerApplication` is 10 lines because `@SpringBootApplication` is three annotations in one: *scan this package for beans*, *enable auto-configuration* (Spring sees `postgresql` and `flyway-core` on the classpath and configures a connection pool and a migration runner without being asked), and *this class is the configuration root*. Auto-configuration is why Phase 1 has almost no config code: we declared dependencies in `pom.xml`, and Spring inferred the rest.

## 2. `application.yml` — config as declaration

```yaml
spring:
  threads:
    virtual:
      enabled: true
  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/trend}
```

Three ideas packed in here:

**Virtual threads, enabled on day one.** `spring.threads.virtual.enabled: true` makes Tomcat serve every HTTP request on a Java 21 *virtual thread* — a thread so cheap (a few hundred bytes, scheduled by the JVM, not the OS) that you can block on I/O without guilt. This is the load-bearing decision of the whole architecture: in Phase 6 an analysis will block for 10–30 s inside a request while fanning out to three APIs, which would exhaust a classic ~200-thread pool but is trivial for virtual threads. Enabling it now, while the app is empty, means every later phase is built and tested on the final threading model.

**`${ENV_VAR:default}` syntax.** Spring resolves `${DATABASE_URL:…}` from the environment at startup, falling back to the default after the colon. This is how one artifact runs everywhere: Testcontainers overrides it in tests, your shell provides Neon's URL locally, Cloud Run injects it in production. The *code* never knows which environment it's in.

**No secrets in the file.** The values live in the environment; the YAML holds only names and defaults. Combined with the very first git commit — which added `.gitignore` covering `.env` and `application-local.yml` *before any other file existed* — there is no moment in this repository's history where a secret could have been committed. Order matters: a `.gitignore` added in commit 5 doesn't scrub commits 1–4.

## 3. Flyway — the database schema is code now

`V1__init.sql` (the name is a contract: `V1` = version 1, `__` separator, description after) creates the two tables from build plan §4. At startup, Flyway looks at a bookkeeping table it maintains (`flyway_schema_history`), sees which numbered migrations have already run, and runs only the new ones, in order. You saw it in the test logs:

```
Migrating schema "public" to version "1 - init"
Successfully applied 1 migration to schema "public", now at version v1
```

Why this instead of clicking "create table" in a database UI? Because *every* copy of the database — the throwaway one in each test run, your Neon instance, any future rebuild — converges to the identical schema by replaying the same ordered scripts. The schema has a version history exactly like the code does, reviewed in the same pull requests. The discipline it imposes: never edit `V1` after it has shipped (Flyway checksums it and will refuse to start); change the schema by adding `V2__…`.

One detail worth staring at in `V1__init.sql`:

```sql
CREATE INDEX idx_log_day ON search_log (created_at) WHERE fresh;
```

That `WHERE fresh` makes it a **partial index** — it only indexes rows where `fresh = true`. The global daily budget (Phase 6) asks "how many *fresh* analyses ran today?" hundreds of times a day; cached hits vastly outnumber fresh ones, so indexing only the fresh rows keeps the index small and that hot query fast.

## 4. The key design decision: test against a real Postgres, not a fake

`SchemaSmokeTest` is the phase's most important file, and it embodies a choice worth understanding from first principles.

The easy path is an in-memory database like H2: no Docker, instant startup. The problem: H2 is *not Postgres*. Our schema already uses `JSONB`, `TIMESTAMPTZ`, and a partial index — the first and third don't exist in H2, and the plan's `?::jsonb` cast is Postgres syntax. Testing against H2 means either dumbing the schema down to a lowest common denominator or maintaining two schemas, and either way the tests stop testing what production runs. A test that passes against a database you don't deploy is a rumor, not evidence.

**Testcontainers** resolves this: the test starts a genuine `postgres:16` in Docker, runs against it, and throws it away. The wiring is two annotations:

```java
@Bean
@ServiceConnection
PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
}
```

`@ServiceConnection` (Spring Boot 3.1+) is the modern glue: Spring inspects the started container and configures the datasource from it directly — no manually copying JDBC URLs around. The sequence when the test runs: container starts (~2 s) → Spring boots → Flyway sees an empty database and applies `V1__init.sql` → the test queries the tables. So this one "trivial" test actually proves the migration is valid SQL, Flyway is wired, the connection pool works, and the JSONB round-trip pattern (`?::jsonb` in, `String` out — exactly how `AnalysisRepository` will do it in Phase 6) behaves.

Contrast with `HealthControllerTest`, which uses `@WebMvcTest` — a **slice test** that boots *only* the web layer, no database, no Docker, finishing in a second. The emerging pattern: use the cheapest test that gives real evidence. A JSON endpoint needs the web slice; schema and SQL need real Postgres; pure math (`CoreTests`, ported in Phases 2 and 4) needs no framework at all.

### The bug this caught on day one

The first `./mvnw verify` on this machine **failed**, and the failure is a perfect advertisement for real-database testing:

```
FATAL: invalid value for parameter "TimeZone": "Asia/Saigon"
```

The chain: the JDBC driver sends the JVM's default timezone with each connection; on this Windows machine Java maps the system zone to `Asia/Saigon`, a *legacy alias* for `Asia/Ho_Chi_Minh`; and the `postgres:16` image ships a trimmed timezone database that no longer contains legacy names — so Postgres rejected every connection. H2 would never have caught this; the very same failure would instead have appeared on the first connection to Neon in Phase 6, mysteriously, weeks from now.

The fix, in `pom.xml`, is to pin the test JVM to UTC (`-Duser.timezone=UTC` in the Surefire plugin — Surefire is the Maven plugin that runs tests). This is better than fixing "the Saigon problem" specifically: window math should produce identical results on any machine in any timezone, and all our timestamps are `Instant`s and `TIMESTAMPTZ` anyway — UTC end to end, converted only at display time. It also matches production: Cloud Run containers run in UTC.

## 5. CI — the same verify, on someone else's machine

`.github/workflows/ci.yml` runs on every push: check out the code, install Temurin 21 (with the Maven cache, so later runs don't re-download the world), `./mvnw -q verify`. GitHub's runners have Docker, so the Testcontainers test runs there identically — which is the whole point: **CI is just your local gate, executed on a machine you didn't configure.** If it passes only locally, you've learned your laptop has undeclared state; the UTC pin above is exactly the kind of undeclared state (a machine timezone) that CI exists to flush out. The optional Docker Hub login step exists because anonymous image pulls are rate-limited; with a free account's token in the repo secrets, pulls of `postgres:16` are authenticated and the limit stops being your problem.

The `mvnw` in those commands is the **Maven wrapper**: a tiny script, committed to the repo, that downloads the exact Maven version pinned in `.mvn/wrapper/maven-wrapper.properties` before delegating to it. Nobody — not you, not CI — installs Maven; the repo carries its own build tool. Same idea as Flyway: eliminate "works on my machine" by making the environment reproducible from the repository alone.

## 6. Records for payloads

`HealthController` returns `new Health("ok")` where `Health` is:

```java
public record Health(String status) {}
```

A **record** is Java's immutable data carrier: constructor, accessors, `equals`/`hashCode`/`toString`, all generated, in one line. Jackson (Spring's JSON library, auto-configured) serializes it to `{"status":"ok"}` by reading the component names. Every DTO in this project will be a record — `RawPost`, `WindowResult`, the §8 payload — because analysis data should be *values*: created once, never mutated, safe to share across the virtual threads of Phase 6's concurrent fan-out. This is also why the project avoids Lombok: records give the same brevity for this use case in plain Java, with nothing magic to explain in an interview.

## 7. Exercises

1. **Predict before you run:** `Keywords.normalize(" PyTorch  Lightning ")` — write down exactly what string comes back (careful: there are *two* transformations between the words). Then check your answer against `Keywords.java` and the Tutorial 1 rules. Bonus: predict what `normalize("a")` does — return something, or throw?
2. **Write the failing test first:** in `SchemaSmokeTest`, add a test asserting that `analyses.keyword` rejects `NULL` (`assertThrows` on an `INSERT` with a null keyword). Predict the exception type before running (JDBC wraps Postgres errors — is it a `SQLException`? Spring translates it — into what?). Run it, note what actually gets thrown, and make the assertion precise.
3. **Break the migration contract to feel it:** change one character inside `V1__init.sql` (add a space) and run `./mvnw verify` twice. First run: passes — why? (Hint: each test run gets a *fresh* container, so there's no history to conflict with.) Now imagine the same edit against Neon, which keeps its `flyway_schema_history` between deploys — what error would Flyway raise, and why is refusing to start the *safe* behavior?
4. **Prove the slice is a slice:** stop Docker Desktop entirely, then run `./mvnw test "-Dtest=HealthControllerTest"`. It passes — no database anywhere. Now run `SchemaSmokeTest` the same way and read the failure. Knowing which tests need which infrastructure tells you what's actually being tested — and what isn't.
