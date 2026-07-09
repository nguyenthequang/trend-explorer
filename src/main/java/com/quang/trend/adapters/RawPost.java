package com.quang.trend.adapters;

import java.time.Instant;

/**
 * One public post from any source, normalized (build plan §5).
 * {@code score} is nullable — not every source or item type has one
 * (e.g. HN comments have no points).
 */
public record RawPost(String source, String sourceId, String author, String title,
                      String body, String url, Integer score, Instant postedAt) {}
