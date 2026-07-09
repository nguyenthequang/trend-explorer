package com.quang.trend.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * HnAdapter against WireMock serving recorded Algolia JSON — no live network.
 * Fixtures: src/test/resources/fixtures/hn/.
 */
@WireMockTest
class HnAdapterTest {

    private static final Instant START = Instant.parse("2026-07-05T00:00:00Z");
    private static final Instant END = Instant.parse("2026-07-08T00:00:00Z");

    private HnAdapter adapter(WireMockRuntimeInfo wm) {
        return new HnAdapter(HttpClient.newHttpClient(), new ObjectMapper(), wm.getHttpBaseUrl());
    }

    @Test
    void nameIsHn(WireMockRuntimeInfo wm) {
        assertEquals("hn", adapter(wm).name());
    }

    @Test
    void fetchWindowReturnsCountAndPostsWithFullCoverage(WireMockRuntimeInfo wm) {
        stubCount();
        stubPosts();

        WindowResult result = adapter(wm).fetchWindow("python", START, END);

        assertEquals(1234, result.totalCount());
        assertEquals("full", result.coverage());
        assertEquals(3, result.posts().size());
    }

    @Test
    void postsAreSortedByScoreDescendingWithNullsLast(WireMockRuntimeInfo wm) {
        stubCount();
        stubPosts();

        List<RawPost> posts = adapter(wm).fetchWindow("python", START, END).posts();

        assertEquals(List.of("40001", "40002", "40003"),
                posts.stream().map(RawPost::sourceId).toList());
        assertEquals(250, posts.get(0).score());
        assertNull(posts.get(2).score(), "comment has no points");
    }

    @Test
    void storyFieldsMapCorrectly(WireMockRuntimeInfo wm) {
        stubCount();
        stubPosts();

        RawPost story = adapter(wm).fetchWindow("python", START, END).posts().get(0);

        assertEquals("hn", story.source());
        assertEquals("alice", story.author());
        assertEquals("Python 3.13 released", story.title());
        assertEquals("https://example.com/py313", story.url());
        assertEquals(Instant.ofEpochSecond(1751979600L), story.postedAt());
    }

    @Test
    void storyWithoutUrlFallsBackToHnItemUrl(WireMockRuntimeInfo wm) {
        stubCount();
        stubPosts();

        RawPost story = adapter(wm).fetchWindow("python", START, END).posts().get(1);

        assertEquals("https://news.ycombinator.com/item?id=40002", story.url());
        assertEquals("A short blog about python and its ecosystem", story.body());
    }

    @Test
    void commentUsesStoryTitleAndCommentText(WireMockRuntimeInfo wm) {
        stubCount();
        stubPosts();

        RawPost comment = adapter(wm).fetchWindow("python", START, END).posts().get(2);

        assertEquals("Python threading", comment.title());
        assertEquals("Great point about the GIL", comment.body());
    }

    @Test
    void countSendsHalfOpenNumericFilters(WireMockRuntimeInfo wm) {
        stubCount();

        adapter(wm).count("python", START, END);

        verify(getRequestedFor(urlPathEqualTo("/api/v1/search_by_date"))
                .withQueryParam("hitsPerPage", equalTo("0"))
                .withQueryParam("numericFilters",
                        containing("created_at_i>=" + START.getEpochSecond()))
                .withQueryParam("numericFilters",
                        containing("created_at_i<" + END.getEpochSecond())));
    }

    @Test
    void dailySeriesMakesSeventeenCountCalls(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathEqualTo("/api/v1/search_by_date"))
                .willReturn(okJson("{\"hits\":[],\"nbHits\":7}")));

        List<Integer> series = adapter(wm).dailySeries("python", END);

        assertEquals(17, series.size());
        assertTrue(series.stream().allMatch(n -> n == 7));
        verify(17, getRequestedFor(urlPathEqualTo("/api/v1/search_by_date")));
    }

    @Test
    void nonOkResponseThrowsAdapterException(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathEqualTo("/api/v1/search_by_date"))
                .willReturn(aResponse().withStatus(500)));

        assertThrows(AdapterException.class, () -> adapter(wm).count("python", START, END));
    }

    private void stubCount() {
        stubFor(get(urlPathEqualTo("/api/v1/search_by_date"))
                .withQueryParam("hitsPerPage", equalTo("0"))
                .willReturn(okJson(fixture("hn/count.json"))));
    }

    private void stubPosts() {
        stubFor(get(urlPathEqualTo("/api/v1/search"))
                .willReturn(okJson(fixture("hn/search_python.json"))));
    }

    private static String fixture(String path) {
        try (var in = HnAdapterTest.class.getResourceAsStream("/fixtures/" + path)) {
            assertNotNull(in, "missing fixture: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
