package com.quang.trend.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Ported verbatim from the starter kit's CoreTests.keywords() (Phase 2). */
class KeywordsTest {

    @Test
    void trimsAndLowercases() {
        assertEquals("pytorch", Keywords.normalize("  PyTorch "));
    }

    @Test
    void collapsesInternalWhitespace() {
        assertEquals("vision pro", Keywords.normalize("vision   pro"));
    }

    @Test
    void rejectsOneCharacterKeyword() {
        assertThrows(IllegalArgumentException.class, () -> Keywords.normalize("a"));
    }

    @Test
    void rejectsUrls() {
        assertThrows(IllegalArgumentException.class, () -> Keywords.normalize("http://example.com"));
    }

    @Test
    void rejectsMoreThan64Characters() {
        assertThrows(IllegalArgumentException.class, () -> Keywords.normalize("x".repeat(65)));
    }
}
