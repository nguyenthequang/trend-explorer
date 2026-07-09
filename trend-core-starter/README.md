# Trend Explorer — Verified Core Starter

Dependency-free implementations of the deterministic heart of the Trend Explorer
(Java edition build plan §2 and §6), compiled and tested on JDK 21 with the
included harness: **34 assertions, all passing.**

## Contents

| Class | Plan section | What it does |
|---|---|---|
| `Windows` | §2 | The three aligned 3-day windows (now / −7d / −14d), start-inclusive end-exclusive, plus `bucket()` for assigning posts |
| `Keywords` | §7 | Search-input normalization: trim, lowercase, collapse whitespace, reject URLs and bad lengths |
| `Scoring` | §6 | Tokenization (unigrams + bigrams, stopword + length filtering) and smoothed log-odds theme extraction |
| `DeltaMath` | §6 | Volume ratio (zero-safe) and sentiment delta |
| `CoreTests` | — | Zero-dependency harness with hand-computed expected values |

## Run it

```bash
bash run.sh
```

Requires only a JDK 21+ (`javac`). No Maven, no network, no other dependencies.

## How this fits the build plan

1. **Phase 1:** when Claude Code scaffolds the Spring project from start.spring.io,
   move `src/main/java/com/quang/trend/core/*.java` into the project unchanged —
   packages already match the plan's repo layout.
2. **Phases 2 & 4:** port each `check(...)` in `CoreTests` into a JUnit 5 test
   verbatim, e.g.

   ```java
   @Test
   void endIsExclusive() {
       var t = Instant.parse("2026-07-08T12:00:00Z");
       assertNull(Windows.bucket(t, Windows.compute(t)));
   }

   @Test
   void logOddsMatchesHandComputation() {
       var ranked = Scoring.logOdds(Map.of("a", 3, "b", 1), Map.of("a", 1, "c", 2), 0.5);
       var scores = ranked.stream().collect(toMap(TermScore::term, TermScore::score));
       assertEquals(0.6466, scores.get("a"), 1e-3);
   }
   ```

   Then delete `CoreTests` — its job is done once JUnit owns the assertions.
3. The expected numbers (0.6466 / 0.8979 / −1.8101 etc.) are derived step by step
   in `Tutorial_1_Core_Math_version1.md` — read that before porting so you can
   defend every value in an interview.

## Why a hand-rolled harness instead of JUnit?

This kit was produced in an environment without Maven Central access, so JUnit
could not be fetched. Rather than ship untested code, the harness proves the
logic with plain `java` — the assertions are the artifact; the runner is
disposable.
