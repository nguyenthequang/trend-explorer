package com.quang.trend.adapters;

import java.util.List;

/**
 * One source's result for one window (build plan §5).
 *
 * @param posts      capped and sorted by score descending
 * @param totalCount exact count if the API reports one; else null
 * @param coverage   "full" | "partial" | "none"
 */
public record WindowResult(List<RawPost> posts, Integer totalCount, String coverage) {}
