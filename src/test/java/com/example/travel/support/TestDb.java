package com.example.travel.support;

import java.util.UUID;

/**
 * Fresh file-backed H2 database per test class (never jdbc:h2:mem:) — the
 * uniqueness and snapshot guarantees must come from the database under test.
 */
public final class TestDb {

    private TestDb() {
    }

    public static String url(String name) {
        return url(name, 5000);
    }

    /** Smaller LOCK_TIMEOUT for tests that deliberately hold a lock. */
    public static String url(String name, int lockTimeoutMs) {
        return "jdbc:h2:file:./build/testdb-" + name + "-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=" + lockTimeoutMs;
    }
}
