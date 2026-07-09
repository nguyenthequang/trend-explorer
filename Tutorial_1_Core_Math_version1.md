# Tutorial 1 — The Core Math of Trend Explorer

*Covers: `Windows`, `Keywords`, `Scoring`, `DeltaMath` — the verified starter kit. Read alongside the code; every number here is asserted in `CoreTests`.*

Trend Explorer's promise is "what changed?", and everything in this tutorial exists to make that question answerable with arithmetic instead of vibes. The design principle: **the code does the math, the LLM does the words.** This is the math.

---

## 1. The three windows — why this exact shape

When you search a keyword at time $T$, the system builds three windows:

$$\text{now} = [T-3\text{d},\ T), \qquad \text{week\_ago} = [T-10\text{d},\ T-7\text{d}), \qquad \text{two\_weeks\_ago} = [T-17\text{d},\ T-14\text{d})$$

Three decisions are hiding in that line, and each is a small lesson:

**Equal widths.** All three windows are exactly 3 days. If "now" were 3 days but the baseline were 7, a raw count comparison would be meaningless — of course a 7-day bucket has more posts. Making the widths identical means a count of 84 versus 61 is a real, unit-compatible comparison. This is the same reason scientists control variables: change one thing (the *when*), hold everything else fixed.

**Half-open intervals: start inclusive, end exclusive.** Written $[start, end)$. Why? Because windows must never double-count a post. If both ends were inclusive, a post timestamped exactly at a boundary could belong to two adjacent ranges. With half-open intervals, every instant on the timeline belongs to *at most one* window — no gaps in logic, no overlaps. This convention is everywhere in production code (Python's `range`, SQL's `>= AND <`, Java's `subList`) precisely because it composes cleanly. The tests pin it down: $T$ itself is *not* in "now" (end exclusive), but $T-3\text{d}$ *is* (start inclusive).

**The gaps are deliberate.** Notice "now" ends at $T$ but "week_ago" ends at $T-7\text{d}$ — there's a 4-day gap between the windows that simply isn't analyzed. That's fine: we're taking three *samples* of the conversation at aligned offsets, not reconstructing the full timeline. `Windows.bucket()` returns `null` for posts in the gaps, and the ingestion code just skips them.

In `Windows.java` this is ~30 lines: a `record WindowSpec(name, start, end)` with a `contains()` method implementing the half-open check (`!t.isBefore(start) && t.isBefore(end)`), and a `bucket()` that returns the first containing window's name.

**Interview question you can now answer:** "Why not compare this week to last week directly?" — Because window *width* and window *offset* are separate choices. Equal 3-day widths at offsets 0, −7d, −14d give comparable samples while keeping API fetch volume small.

---

## 2. Keyword normalization — tiny code, real lessons

`Keywords.normalize()` is ten lines that earn their keep:

1. **Canonicalization before comparison.** `" PyTorch "` and `"pytorch"` must be the *same* cache key, or you'll do the same expensive analysis twice and your budget system leaks. Trim → lowercase → collapse internal whitespace makes one canonical form.
2. **Validate at the boundary, then trust internally.** Length 2–64, no URLs. Every deeper layer (adapters, SQL, LLM prompt) can then assume a sane keyword. The alternative — defensive checks scattered everywhere — is how codebases rot.
3. **Fail loudly.** Invalid input throws `IllegalArgumentException` rather than returning null or a "fixed" guess. The HTTP layer will translate that into a 400 response; the core stays honest.

---

## 3. Theme extraction — log-odds from first principles

The goal: given the posts from "now" and the posts from a baseline window, find the words that *characterize the change* — what people suddenly started saying, and stopped saying.

### 3.1 Why not just count words?

Take the most frequent words in "now" and you'll get… the keyword itself, plus generic filler ("just", "like", "people"). Common words are common everywhere; frequency alone can't distinguish *newly* common from *always* common.

### 3.2 Why not a simple ratio?

Next idea: score each word by $f_{\text{now}}(w) / f_{\text{base}}(w)$. Two fatal problems. A word appearing 1 time now and 0 times before divides by zero — or, patched naively, scores *infinity*, beating a word that went from 5 to 500. And a 1-vs-0 fluke ties with a 100-vs-0 landslide. Raw ratios have no concept of *evidence strength*.

### 3.3 The fix: smoothing + log

The smoothed log-odds score:

$$\mathrm{lo}(w) \;=\; \ln\frac{f_{\text{now}}(w) + \alpha}{N_{\text{now}} + \alpha V} \;-\; \ln\frac{f_{\text{base}}(w) + \alpha}{N_{\text{base}} + \alpha V}, \qquad \alpha = 0.5$$

where $f(w)$ is the word's count, $N$ is the total token count in that window, and $V$ is the size of the *union* vocabulary (every distinct term seen in either window).

Read it in two moves. Each fraction is the word's *smoothed share* of its window's language: "what proportion of the talking was this word?" — dividing by $N$ makes windows of different sizes comparable. The $\alpha$ (add-half smoothing, a lighter cousin of Laplace's add-one) pretends every vocabulary word was seen half a time extra, so nothing is ever zero: unseen words get a small-but-finite share, and the $\alpha V$ in the denominator keeps the shares summing to 1. Then the log turns the ratio of shares into a symmetric score: positive means "over-represented now" (emerging), negative means "over-represented in the baseline" (fading), zero means no change — and a doubling scores the same magnitude as a halving, just with opposite sign.

### 3.4 The worked example (exactly what the tests assert)

Let the "now" window contain term counts $\{a\!:\!3,\ b\!:\!1\}$ and the baseline $\{a\!:\!1,\ c\!:\!2\}$, with $\alpha = 0.5$.

Setup: vocabulary $= \{a, b, c\}$, so $V = 3$; $N_{\text{now}} = 4$; $N_{\text{base}} = 3$. Denominators: $N_{\text{now}} + \alpha V = 4 + 1.5 = 5.5$ and $N_{\text{base}} + \alpha V = 3 + 1.5 = 4.5$.

$$\mathrm{lo}(a) = \ln\frac{3.5}{5.5} - \ln\frac{1.5}{4.5} = -0.4520 - (-1.0986) = \mathbf{0.6466}$$

$$\mathrm{lo}(b) = \ln\frac{1.5}{5.5} - \ln\frac{0.5}{4.5} = -1.2993 - (-2.1972) = \mathbf{0.8979}$$

$$\mathrm{lo}(c) = \ln\frac{0.5}{5.5} - \ln\frac{2.5}{4.5} = -2.3979 - (-0.5878) = \mathbf{-1.8101}$$

Two things worth staring at. First, $b$ (1 vs 0) outranks $a$ (3 vs 1): appearing *only* now is stronger evidence of emergence than merely tripling — but thanks to smoothing it's a finite, comparable score, not infinity. Second, $c$ is strongly negative: it *vanished*, so it tops the fading list. Run `bash run.sh` and you'll see these exact numbers pass.

### 3.5 Tokenization choices (the unglamorous 50%)

`countTerms()` makes deliberate trade-offs: lowercase everything (so "Breaking" = "breaking"); split on anything that isn't a letter or digit; drop stopwords ("are", "the"…) and tokens under 3 characters *first*; then form **bigrams from adjacent survivors**. That last one is subtle — in "breaking changes are bad", removing "are" makes "changes bad" a bigram. That's a feature: stopwords carry no theme signal, and bridging them yields more meaningful phrases ("breaking changes" beats "changes are"). The tests pin this behavior so a future refactor can't silently change it.

---

## 4. Deltas — deliberately boring

`volumeRatio(now, base) = now / max(base, 1)` — the `max` is a guard so a keyword nobody mentioned last week (base = 0) yields a sensible ratio instead of a crash or infinity. `sentimentDelta` is plain subtraction. Not everything needs to be clever; it needs to be *correct at the edges*, and the edge here is zero.

---

## 5. How the tests prove it — and porting to JUnit

`CoreTests` is a plain `main()` with `check(name, boolean)` counters — zero dependencies, because the environment that produced this kit couldn't reach Maven Central. The *assertions* are the valuable part; the runner is disposable. When the Spring project exists (Phase 1–2), port each check verbatim:

```java
@Test
void bIsTopEmergingTerm() {
    var ranked = Scoring.logOdds(Map.of("a", 3, "b", 1), Map.of("a", 1, "c", 2), 0.5);
    assertEquals("b", ranked.get(0).term());
    assertEquals(0.8979, ranked.get(0).score(), 1e-3);
}
```

Note the pattern in the originals: expected values are **hand-computed, not copied from the program's own output**. A test that asserts whatever the code already returns proves nothing; a test that asserts an independently derived number catches real bugs. That's the standard to hold every future test to.

---

## 6. Exercises

1. **Predict, then verify:** set $\alpha = 5$ in the worked example and compute $\mathrm{lo}(b)$ by hand. Does heavier smoothing pull the "1 vs 0" word above or below the "3 vs 1" word? Then change the constant in the test and see.
2. **Boundary reasoning:** without running anything, decide what `bucket(T − 14d)` returns. Then check yourself against the window table. (Careful — which side of `two_weeks_ago` is exclusive?)
3. **Make `fading` stricter:** it currently returns the k most-negative scores even if some are positive (imagine every word emerging). Change it to return only terms with $\mathrm{lo}(w) < 0$, write the test *first*, and watch it fail before you fix the code.
4. **Break a rule to feel it:** make both window ends inclusive and re-run the tests. Which assertions fail, and what real bug would that have been in production?
