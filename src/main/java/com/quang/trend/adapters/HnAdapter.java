package com.quang.trend.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hacker News via the Algolia search API (build plan §5.1) — no auth, full
 * archive, so coverage is always "full".
 *
 * <ul>
 *   <li>Counts: {@code search_by_date} with {@code hitsPerPage=0}, read
 *       {@code nbHits}. Used for window totals and the 17-day daily series.</li>
 *   <li>Posts: {@code search} with {@code hitsPerPage=60}, sorted by points.</li>
 * </ul>
 *
 * The base URL is injectable so tests can point it at WireMock.
 */
public final class HnAdapter implements SourceAdapter {

    private static final String DEFAULT_BASE_URL = "https://hn.algolia.com";
    private static final int POST_CAP = 60;      // build plan §5: per-window cap
    private static final int DAILY_POINTS = 17;  // §5.1 sparkline series length
    private static final int MAX_RETRIES = 3;
    private static final String USER_AGENT = "trend-explorer/0.1 (personal research)";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public HnAdapter(HttpClient http, ObjectMapper mapper) {
        this(http, mapper, DEFAULT_BASE_URL);
    }

    public HnAdapter(HttpClient http, ObjectMapper mapper, String baseUrl) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return "hn";
    }

    @Override
    public WindowResult fetchWindow(String keyword, Instant start, Instant end) {
        int total = count(keyword, start, end);
        List<RawPost> posts = fetchPosts(keyword, start, end);
        return new WindowResult(posts, total, "full");
    }

    /** Exact number of HN stories+comments matching the keyword in [start, end). */
    public int count(String keyword, Instant start, Instant end) {
        JsonNode root = getJson(uri("search_by_date", keyword, start, end, 0));
        return root.path("nbHits").asInt();
    }

    /**
     * 17 consecutive daily counts ending at {@code end}, oldest first:
     * buckets [end-17d, end-16d) … [end-1d, end). Powers the sparkline.
     */
    public List<Integer> dailySeries(String keyword, Instant end) {
        List<Integer> series = new ArrayList<>(DAILY_POINTS);
        for (int daysBack = DAILY_POINTS; daysBack >= 1; daysBack--) {
            Instant dayStart = end.minus(Duration.ofDays(daysBack));
            Instant dayEnd = end.minus(Duration.ofDays(daysBack - 1));
            series.add(count(keyword, dayStart, dayEnd));
        }
        return series;
    }

    private List<RawPost> fetchPosts(String keyword, Instant start, Instant end) {
        JsonNode root = getJson(uri("search", keyword, start, end, POST_CAP));
        List<RawPost> posts = new ArrayList<>();
        for (JsonNode hit : root.path("hits")) {
            posts.add(toPost(hit));
        }
        // Sort by score desc; missing scores (comments) sort last.
        posts.sort(Comparator.comparingInt(
                (RawPost p) -> p.score() == null ? Integer.MIN_VALUE : p.score()).reversed());
        return posts;
    }

    private RawPost toPost(JsonNode h) {
        String objectId = h.path("objectID").asText();
        String title = textOrNull(h, "title");
        if (title == null) title = textOrNull(h, "story_title");   // comments carry the parent title
        String body = textOrNull(h, "story_text");
        if (body == null) body = textOrNull(h, "comment_text");
        String url = textOrNull(h, "url");
        if (url == null) url = "https://news.ycombinator.com/item?id=" + objectId;
        Instant postedAt = Instant.ofEpochSecond(h.path("created_at_i").asLong());
        return new RawPost("hn", objectId, textOrNull(h, "author"), title, body,
                url, intOrNull(h, "points"), postedAt);
    }

    private URI uri(String path, String keyword, Instant start, Instant end, int hitsPerPage) {
        // Half-open [start, end) mirrors Windows semantics exactly.
        String numericFilters = "created_at_i>=" + start.getEpochSecond()
                + ",created_at_i<" + end.getEpochSecond();
        String query = "query=" + enc(keyword)
                + "&tags=" + enc("(story,comment)")
                + "&numericFilters=" + enc(numericFilters)
                + "&hitsPerPage=" + hitsPerPage;
        return URI.create(baseUrl + "/api/v1/" + path + "?" + query);
    }

    private JsonNode getJson(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 429 && attempt < MAX_RETRIES) {
                    backoff(attempt);
                    continue;
                }
                if (status != 200) {
                    throw new AdapterException("HN API returned " + status + " for " + uri);
                }
                return mapper.readTree(response.body());
            } catch (IOException e) {
                throw new AdapterException("HN request failed: " + uri, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AdapterException("HN request interrupted: " + uri, e);
            }
        }
    }

    /** Exponential backoff with jitter (build plan §5). */
    private void backoff(int attempt) {
        long base = 200L << attempt;                       // 200, 400, 800 ms
        long jitter = ThreadLocalRandom.current().nextLong(100);
        try {
            Thread.sleep(base + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdapterException("interrupted during backoff", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asInt() : null;
    }
}
