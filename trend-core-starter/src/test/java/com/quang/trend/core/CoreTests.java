package com.quang.trend.core;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zero-dependency test harness. This sandbox cannot reach Maven Central,
 * so JUnit is unavailable; every assertion here is meant to be ported
 * verbatim into JUnit 5 tests during Phase 2/4 on the real machine.
 * Expected numeric values are hand-computed (see Tutorial 1).
 */
public final class CoreTests {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else    { failed++; System.out.println("FAIL  " + name); }
    }

    private static void checkClose(String name, double actual, double expected, double tol) {
        check(String.format("%s (got %.4f, want %.4f)", name, actual, expected),
              Math.abs(actual - expected) < tol);
    }

    public static void main(String[] args) {
        windows();
        keywords();
        tokenization();
        logOdds();
        deltas();
        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static void windows() {
        Instant t = Instant.parse("2026-07-08T12:00:00Z");
        List<Windows.WindowSpec> ws = Windows.compute(t);

        check("three windows", ws.size() == 3);
        for (Windows.WindowSpec w : ws) {
            check("width is exactly 3d: " + w.name(), w.width().equals(Duration.ofDays(3)));
        }
        check("'now' ends at T", ws.get(0).end().equals(t));
        check("gap between now.start and week_ago.end is 4d",
              Duration.between(ws.get(1).end(), ws.get(0).start()).equals(Duration.ofDays(4)));

        check("T-1d buckets to 'now'",
              "now".equals(Windows.bucket(t.minus(Duration.ofDays(1)), ws)));
        check("T-8d buckets to 'week_ago'",
              "week_ago".equals(Windows.bucket(t.minus(Duration.ofDays(8)), ws)));
        check("T-15d buckets to 'two_weeks_ago'",
              "two_weeks_ago".equals(Windows.bucket(t.minus(Duration.ofDays(15)), ws)));
        check("T-5d falls in the gap (null)",
              Windows.bucket(t.minus(Duration.ofDays(5)), ws) == null);
        check("end is exclusive: T itself not in 'now'",
              Windows.bucket(t, ws) == null);
        check("start is inclusive: T-3d in 'now'",
              "now".equals(Windows.bucket(t.minus(Duration.ofDays(3)), ws)));
        check("end is exclusive: T-7d not in 'week_ago'",
              Windows.bucket(t.minus(Duration.ofDays(7)), ws) == null);
    }

    private static void keywords() {
        check("trims and lowercases", Keywords.normalize("  PyTorch ").equals("pytorch"));
        check("collapses internal whitespace", Keywords.normalize("vision   pro").equals("vision pro"));

        boolean threw = false;
        try { Keywords.normalize("a"); } catch (IllegalArgumentException e) { threw = true; }
        check("rejects 1-character keyword", threw);

        threw = false;
        try { Keywords.normalize("http://example.com"); } catch (IllegalArgumentException e) { threw = true; }
        check("rejects URLs", threw);

        threw = false;
        try { Keywords.normalize("x".repeat(65)); } catch (IllegalArgumentException e) { threw = true; }
        check("rejects >64 characters", threw);
    }

    private static void tokenization() {
        Map<String, Integer> c = Scoring.countTerms(
            List.of("Breaking changes are bad!", "breaking RELEASE"),
            Set.of("are"));

        check("unigram 'breaking' = 2", c.getOrDefault("breaking", 0) == 2);
        check("unigram 'changes' = 1", c.getOrDefault("changes", 0) == 1);
        check("stopword 'are' dropped", !c.containsKey("are"));
        check("3-letter token 'bad' kept", c.getOrDefault("bad", 0) == 1);
        check("bigram 'breaking changes' = 1", c.getOrDefault("breaking changes", 0) == 1);
        check("bigram bridges removed stopword: 'changes bad' = 1",
              c.getOrDefault("changes bad", 0) == 1);
        check("bigram 'breaking release' = 1", c.getOrDefault("breaking release", 0) == 1);
    }

    private static void logOdds() {
        // Hand-computed example (Tutorial 1, worked through step by step):
        // now  = {a:3, b:1}, base = {a:1, c:2}, alpha = 0.5, V = 3, N_now = 4, N_base = 3
        // lo(a) = ln(3.5/5.5) - ln(1.5/4.5) =  0.6466
        // lo(b) = ln(1.5/5.5) - ln(0.5/4.5) =  0.8979
        // lo(c) = ln(0.5/5.5) - ln(2.5/4.5) = -1.8101
        Map<String, Integer> now = Map.of("a", 3, "b", 1);
        Map<String, Integer> base = Map.of("a", 1, "c", 2);

        List<Scoring.TermScore> ranked = Scoring.logOdds(now, base, 0.5);
        Map<String, Double> s = new HashMap<>();
        for (Scoring.TermScore ts : ranked) s.put(ts.term(), ts.score());

        checkClose("lo(a)", s.get("a"), 0.6466, 1e-3);
        checkClose("lo(b)", s.get("b"), 0.8979, 1e-3);
        checkClose("lo(c)", s.get("c"), -1.8101, 1e-3);
        check("top emerging term is 'b'", ranked.get(0).term().equals("b"));
        check("top fading term is 'c'", Scoring.fading(ranked, 1).get(0).term().equals("c"));
        check("emerging(2) returns 2 terms", Scoring.emerging(ranked, 2).size() == 2);
    }

    private static void deltas() {
        checkClose("volume ratio 84/61", DeltaMath.volumeRatio(84, 61), 1.3770, 1e-3);
        check("zero baseline is safe (5/0 -> 5.0)", DeltaMath.volumeRatio(5, 0) == 5.0);
        checkClose("sentiment delta -0.18 - 0.05", DeltaMath.sentimentDelta(-0.18, 0.05), -0.23, 1e-9);
    }
}
