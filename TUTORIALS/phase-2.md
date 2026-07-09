# Tutorial 3 (Phase 2) — The First Adapter: Talking to Hacker News, and Testing It Without the Internet

*Covers: the `SourceAdapter` interface, `RawPost`/`WindowResult` records, `HnAdapter`, WireMock fixture tests, and the `scripts/SmokeHn` live check. Read alongside the code; the offline assertions all run under `./mvnw verify`.*

Phase 1 gave us a running skeleton. Phase 2 makes it *reach out*: it fetches real posts from Hacker News and turns them into the normalized shape the rest of the system will speak. It also establishes the testing pattern every adapter will follow — **prove the parsing against recorded JSON offline, and keep the live network in a manual script.** Understanding why those are two different things is the heart of this phase.

Before any of that, we did the bookkeeping the project rules demand: the verified `Windows` and `Keywords` classes got their starter-kit assertions ported into real JUnit 5 tests (`WindowsTest`, `KeywordsTest`) — 12 assertions, hand-computed values copied faithfully from `CoreTests`, in a commit that touches *only* tests. (`Scoring` and `DeltaMath` wait for Phase 4.)

---

## 1. Why an *interface* first — the shape of "a source"

Three sources — HN, Reddit, Bluesky — return wildly different JSON, authenticate differently, and paginate differently. But the orchestrator in Phase 6 shouldn't care about any of that. It wants to say "given a keyword and a time window, give me the posts and a count," three times in parallel, and move on. That contract is `SourceAdapter`:

```java
public interface SourceAdapter {
    String name();
    WindowResult fetchWindow(String keyword, Instant start, Instant end);
}
```

This is **programming to an interface**, and it's the same lesson DI taught in Phase 1 seen from the other side. The interface is a *promise* about behavior with the how deliberately omitted. Phase 6's orchestrator will hold a `List<SourceAdapter>` and loop over it; it never mentions `HnAdapter` by name. That's what lets the three fetches fan out over identical code, and what lets tests substitute fakes. When you find yourself about to write `if (source == "hn") … else if (source == "reddit")`, an interface is almost always the better answer: add a *type*, not a *branch*.

The two data carriers are records, continuing the Phase 1 rule that all payloads are immutable values:

```java
public record RawPost(String source, String sourceId, String author, String title,
                      String body, String url, Integer score, Instant postedAt) {}

public record WindowResult(List<RawPost> posts, Integer totalCount, String coverage) {}
```

Note `Integer score`, not `int`. That capital-I matters: an `int` *must* hold a number, but a Hacker News **comment has no points**. The nullable `Integer` lets the type system carry "this genuinely has no score" instead of lying with a `0` that would later be indistinguishable from a real zero. Choosing nullable-boxed vs primitive is a modeling decision, not a style tic — use the primitive when absence is impossible, the boxed type when absence is meaningful.

## 2. The two HN endpoints — counts and posts are different questions

The Algolia HN API (no auth, full archive — build plan §5.1) answers two different questions with two different calls, and `HnAdapter` keeps them separate:

- **"How many?"** → `search_by_date?…&hitsPerPage=0`, then read `nbHits`. Asking for *zero* hits is the trick: we don't want the posts here, only the total the server reports. It's one cheap call.
- **"Which ones?"** → `search?…&hitsPerPage=60`, then parse the `hits` array into `RawPost`s.

Why not one call that returns both? Because they scale differently. The count is needed for every window *and* for 17 individual days (the sparkline) — 20 calls, each of which would be wasteful if it also dragged back 60 posts we'll throw away. Separating them means the daily series is 17 tiny `hitsPerPage=0` requests. Different question, different cost, different call.

### The window filter is the same half-open interval from Tutorial 1

The time filter handed to Algolia is:

```
numericFilters = created_at_i>=<start>,  created_at_i<<end>
```

That's $[\text{start}, \text{end})$ — start inclusive, end exclusive — the exact convention `Windows.contains()` uses (`!t.isBefore(start) && t.isBefore(end)`). This is not a coincidence to shrug at; it's the property that makes the whole system composable. The server filters posts into a window using precisely the same boundary rule the in-memory bucketing uses, so a post can never fall into "now" locally but be excluded by the API, or vice versa. One convention, enforced end to end, from SQL to Java to a third party's search index.

### The daily series — worked bucket math

`dailySeries` builds 17 back-to-back one-day buckets ending at $T$:

```java
for (int daysBack = 17; daysBack >= 1; daysBack--) {
    Instant dayStart = end.minus(Duration.ofDays(daysBack));
    Instant dayEnd   = end.minus(Duration.ofDays(daysBack - 1));
    series.add(count(keyword, dayStart, dayEnd));
}
```

Walk the loop's edges, because off-by-one errors live exactly here. First iteration, `daysBack = 17`: the bucket is $[T-17\text{d},\ T-16\text{d})$ — the oldest day. Last iteration, `daysBack = 1`: $[T-1\text{d},\ T)$ — the most recent full day up to now. That's 17 buckets, adjacent (each `dayEnd` equals the next `dayStart`), non-overlapping (half-open again), oldest first — which is the order a left-to-right sparkline wants. The live run bore this out: `[54, 72, 75, 49, 37, 33, 47, 32, 53, 255, 83, 42, 67, 60, 90, 74, 88]` — 17 numbers, one clear spike, ready to draw.

## 3. Parsing without DTOs — Jackson's tree model

The hits are parsed with Jackson's **tree model** (`JsonNode`) rather than mapping onto a dedicated `AlgoliaHit` class:

```java
JsonNode root = mapper.readTree(response.body());
for (JsonNode hit : root.path("hits")) { … }
```

Why the tree and not a typed DTO? Because HN's hit shape is *irregular* — a story has `title` and `url`; a comment has `story_title` and `comment_text` and no `points`. A single DTO would be a bag of mostly-null fields, and we'd still be writing the same fallback logic. The tree model lets us ask for exactly what we need and express the fallbacks directly:

```java
String title = textOrNull(h, "title");
if (title == null) title = textOrNull(h, "story_title");   // comments carry the parent title
String body  = textOrNull(h, "story_text");
if (body == null)  body  = textOrNull(h, "comment_text");
String url   = textOrNull(h, "url");
if (url == null)   url   = "https://news.ycombinator.com/item?id=" + objectId;  // synthesize
```

The `textOrNull` helper leans on `node.hasNonNull(field)`, which is true only if the field is present *and* not JSON `null` — the two ways Algolia signals "absent." Getting this wrong is the classic JSON-parsing bug: `node.get("url").asText()` on a null node returns the **string** `"null"`, and you'd cheerfully store a broken link. The build plan's rule "never invent API fields" applies here — every field name above (`objectID`, `story_title`, `comment_text`, `created_at_i`, `points`) is a real Algolia field; when a response doesn't match, the instruction is to print it and ask, not to guess.

One more small but real modeling choice: `created_at_i` is Unix **seconds**, so `Instant.ofEpochSecond(...)`. Feeding seconds to a millisecond API (`ofEpochMilli`) would silently place every post in 1970. Types don't catch this — both are `long` — so it's exactly the kind of thing a fixture test pins down.

### Sorting, and where the nulls go

`WindowResult` promises posts "sorted by score descending." The comment-has-no-score problem resurfaces:

```java
posts.sort(Comparator.comparingInt(
        (RawPost p) -> p.score() == null ? Integer.MIN_VALUE : p.score()).reversed());
```

Map a missing score to `Integer.MIN_VALUE`, then `reversed()` for descending — so scored posts rank by points, and null-scored comments sink to the bottom rather than throwing a `NullPointerException` mid-sort. The test asserts the exact order `[40001, 40002, 40003]`, with `40003` (the comment) last. "Correct at the edges" — the same discipline Tutorial 1 preached about `volumeRatio`'s zero baseline — applies to comparators too.

## 4. The key design decision: fixtures for correctness, a script for reality

Here is the phase's central idea, and it resolves a tension you'll feel in every project that talks to an external API. You need to know two different things:

1. **Does my parsing turn *this* JSON into the right objects?** — a question about *your code*, which must be answered the same way every time, must run in CI, and must never flake because HN was slow.
2. **Does the real API still return the shape I think it does?** — a question about *the world*, which by definition needs the live network and can't run in CI (no network there, and today's live count is unpredictable).

Answering both with live calls is the trap: your CI would depend on Hacker News being up and would assert against numbers that change hourly. So we split them.

**WireMock answers question 1.** WireMock is a fake HTTP server: you tell it "when a GET hits `/api/v1/search`, reply with this exact JSON," point the adapter's base URL at it, and assert on the resulting objects. That's why `HnAdapter`'s constructor takes a `baseUrl` — in production it defaults to `https://hn.algolia.com`; in tests it's WireMock's `localhost:<random-port>`. The `@WireMockTest` annotation starts the server and hands each test a `WireMockRuntimeInfo` with that URL. The recorded JSON lives in `src/test/resources/fixtures/hn/` — a story with a URL, a story without one, and a comment — chosen to exercise every fallback branch above. These tests are deterministic, offline, and fast, so they belong in CI.

WireMock also lets you assert on the **request**, not just the response — which is how we test the half-open filter without a live server:

```java
verify(getRequestedFor(urlPathEqualTo("/api/v1/search_by_date"))
        .withQueryParam("numericFilters", containing("created_at_i>=" + START.getEpochSecond()))
        .withQueryParam("numericFilters", containing("created_at_i<"  + END.getEpochSecond())));
```

That confirms the adapter *sent* the interval we intended — a different and often more valuable thing than checking what came back.

**`scripts/SmokeHn` answers question 2.** It's a tiny `main` that hits the real API and prints what it sees, run by hand when you want to confirm reality still matches your fixtures. It deliberately lives outside `src/` (in `scripts/`, wired in only under the `-Psmoke` Maven profile) so it is **never** compiled into the app or run by CI — a live network call in a CI test is a flaky-build generator. You run it and eyeball the output:

```
now            count=252    posts=60 coverage=full
week_ago       count=340    posts=60 coverage=full
two_weeks_ago  count=201    posts=60 coverage=full
```

"Believable counts" is the whole acceptance bar (build plan §12): a few hundred python posts per 3-day window, `full` coverage (HN's archive always reaches back), 60 posts per window because that's our cap. If the smoke output ever stops looking like the fixtures — a renamed field, a changed shape — *that's* your signal to re-record the fixtures and update the parsing. The fixtures test your code; the smoke script tells you when the fixtures have gone stale.

## 5. Small hardening worth noticing

`getJson` retries on HTTP 429 (rate-limited) with exponential backoff plus jitter — `200 << attempt` milliseconds (200, 400, 800) plus a random 0–100 ms. The **jitter** matters more than it looks: in Phase 6 three adapters run concurrently, and if several ever back off in lockstep they'd retry at the same instant and collide again — a "thundering herd." A pinch of randomness smears the retries apart. Any non-200 that isn't a retryable 429 throws `AdapterException`, a small shared unchecked type the orchestrator will later catch per-source so one dead source degrades gracefully instead of sinking the whole analysis.

## 6. Exercises

1. **Predict before you run:** the daily-series loop uses `daysBack` from 17 down to 1. Without running it, write the exact half-open interval of `series.get(0)` and of `series.get(16)` in terms of $T$. Then change `daysBack >= 1` to `daysBack >= 0` and predict what the 18th bucket would be — and why it's wrong (hint: what is `[T, T)`?). Check against `HnAdapter.dailySeries`.
2. **Write the failing test first:** the sort sends null-scored comments last. Add a second comment (also `points: null`) to a copy of the posts fixture and write a test asserting *both* comments land at the end, *after* every scored story — before trusting the comparator. Does `Integer.MIN_VALUE` keep them stable relative to each other, or could they reorder?
3. **Break a field name to feel it:** in `toPost`, change `"story_title"` to `"storyTitle"` and run `HnAdapterTest`. Exactly one test fails — which, and what does its message tell you? Now imagine that typo shipped: which posts would show a `null` title in production, and would any *other* test have caught it?
4. **Reality check:** run `./mvnw -q -Psmoke compile exec:java -Dexec.mainClass=com.quang.trend.scripts.SmokeHn -Dexec.args=rust`. Are the counts in the same ballpark as `python`? Pick a deliberately obscure term and confirm the counts shrink but coverage stays `full` — then explain, from §5.1, why HN coverage is *always* full while Reddit's (Phase 3) won't be.
