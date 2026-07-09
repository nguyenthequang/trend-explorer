package com.quang.trend.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Ported verbatim from the starter kit's CoreTests.windows() (Phase 2).
 * Expected values are hand-derived (Tutorial 1 §1), not copied from output.
 */
class WindowsTest {

    private static final Instant T = Instant.parse("2026-07-08T12:00:00Z");
    private final List<Windows.WindowSpec> ws = Windows.compute(T);

    @Test
    void computesThreeWindows() {
        assertEquals(3, ws.size());
    }

    @Test
    void everyWindowIsExactlyThreeDaysWide() {
        for (Windows.WindowSpec w : ws) {
            assertEquals(Duration.ofDays(3), w.width(), "width of " + w.name());
        }
    }

    @Test
    void nowEndsAtT() {
        assertEquals(T, ws.get(0).end());
    }

    @Test
    void gapBetweenNowAndWeekAgoIsFourDays() {
        assertEquals(Duration.ofDays(4), Duration.between(ws.get(1).end(), ws.get(0).start()));
    }

    @Test
    void bucketsFallInTheExpectedWindow() {
        assertEquals("now", Windows.bucket(T.minus(Duration.ofDays(1)), ws));
        assertEquals("week_ago", Windows.bucket(T.minus(Duration.ofDays(8)), ws));
        assertEquals("two_weeks_ago", Windows.bucket(T.minus(Duration.ofDays(15)), ws));
    }

    @Test
    void gapBetweenWindowsBucketsToNull() {
        assertNull(Windows.bucket(T.minus(Duration.ofDays(5)), ws));
    }

    @Test
    void endIsExclusiveStartIsInclusive() {
        assertNull(Windows.bucket(T, ws), "end exclusive: T itself not in 'now'");
        assertEquals("now", Windows.bucket(T.minus(Duration.ofDays(3)), ws), "start inclusive: T-3d in 'now'");
        assertNull(Windows.bucket(T.minus(Duration.ofDays(7)), ws), "end exclusive: T-7d not in 'week_ago'");
    }
}
