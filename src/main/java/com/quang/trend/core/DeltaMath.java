package com.quang.trend.core;

/** Window-to-window deltas (build plan §6). */
public final class DeltaMath {

    private DeltaMath() {}

    /** now / max(base, 1) — a zero baseline never divides by zero. */
    public static double volumeRatio(int now, int base) {
        return (double) now / Math.max(base, 1);
    }

    public static double sentimentDelta(double nowAvg, double baseAvg) {
        return nowAvg - baseAvg;
    }
}
