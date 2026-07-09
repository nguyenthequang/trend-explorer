package com.quang.trend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trivial repository smoke test: boots the app against a Testcontainers
 * Postgres 16, which proves the Flyway migration V1__init.sql applies
 * cleanly and the JSONB write/read pattern from the build plan works.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchemaSmokeTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void migrationCreatedBothTables() {
        Integer analyses = jdbc.sql("SELECT count(*) FROM analyses").query(Integer.class).single();
        Integer searchLog = jdbc.sql("SELECT count(*) FROM search_log").query(Integer.class).single();
        assertEquals(0, analyses);
        assertEquals(0, searchLog);
    }

    @Test
    void jsonbResultRoundTrips() {
        jdbc.sql("""
                INSERT INTO analyses (keyword, result, fresh_until)
                VALUES (?, ?::jsonb, now() + interval '24 hours')
                """)
                .params("smoke-test", "{\"keyword\":\"smoke-test\"}")
                .update();

        String result = jdbc.sql("SELECT result FROM analyses WHERE keyword = ?")
                .params("smoke-test")
                .query(String.class)
                .single();

        assertTrue(result.contains("smoke-test"));
    }
}
