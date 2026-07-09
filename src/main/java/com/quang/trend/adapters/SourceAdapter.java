package com.quang.trend.adapters;

import java.time.Instant;

/** A public-post source (HN, Bluesky, Reddit). Build plan §5. */
public interface SourceAdapter {

    /** Stable short name used as a key in the payload ("hn", "reddit", "bluesky"). */
    String name();

    /** Posts and count for the half-open window [start, end). */
    WindowResult fetchWindow(String keyword, Instant start, Instant end);
}
