package com.quang.trend.scripts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quang.trend.adapters.HnAdapter;
import com.quang.trend.adapters.WindowResult;
import com.quang.trend.core.Windows;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;

/**
 * Manual live check against the real HN Algolia API (build plan §12, Phase 2).
 * Not part of the build or CI. Run:
 *   ./mvnw -q -Psmoke compile exec:java \
 *     -Dexec.mainClass=com.quang.trend.scripts.SmokeHn -Dexec.args=python
 */
public final class SmokeHn {

    public static void main(String[] args) {
        String keyword = args.length > 0 ? args[0] : "python";
        HnAdapter hn = new HnAdapter(HttpClient.newHttpClient(), new ObjectMapper());
        Instant now = Instant.now();

        System.out.println("HN live smoke for \"" + keyword + "\" at " + now);
        for (Windows.WindowSpec w : Windows.compute(now)) {
            WindowResult r = hn.fetchWindow(keyword, w.start(), w.end());
            System.out.printf("  %-14s count=%-6d posts=%d coverage=%s%n",
                    w.name(), r.totalCount(), r.posts().size(), r.coverage());
            r.posts().stream().limit(3).forEach(p ->
                    System.out.printf("       [%s] score=%s  %s%n", p.sourceId(), p.score(), p.title()));
        }

        List<Integer> series = hn.dailySeries(keyword, now);
        System.out.println("  17-day daily series (oldest→newest): " + series);
    }
}
