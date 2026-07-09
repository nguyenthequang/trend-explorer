package com.quang.trend.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The three aligned comparison windows (build plan §2).
 * Start is inclusive, end is exclusive, all widths identical (3 days)
 * so counts and sentiment are directly comparable across windows.
 */
public final class Windows {

    public static final Duration WIDTH = Duration.ofDays(3);

    public record WindowSpec(String name, Instant start, Instant end) {
        public boolean contains(Instant t) {
            return !t.isBefore(start) && t.isBefore(end);
        }
        public Duration width() {
            return Duration.between(start, end);
        }
    }

    private Windows() {}

    /** Windows at request time {@code now}: [T-3d,T], [T-10d,T-7d], [T-17d,T-14d]. */
    public static List<WindowSpec> compute(Instant now) {
        return List.of(
            new WindowSpec("now",           now.minus(Duration.ofDays(3)),  now),
            new WindowSpec("week_ago",      now.minus(Duration.ofDays(10)), now.minus(Duration.ofDays(7))),
            new WindowSpec("two_weeks_ago", now.minus(Duration.ofDays(17)), now.minus(Duration.ofDays(14)))
        );
    }

    /** Name of the window containing t, or null when t falls in a gap or outside. */
    public static String bucket(Instant t, List<WindowSpec> windows) {
        for (WindowSpec w : windows) {
            if (w.contains(t)) return w.name();
        }
        return null;
    }
}
