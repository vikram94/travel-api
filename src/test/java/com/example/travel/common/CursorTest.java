package com.example.travel.common;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CursorTest {

    @Test
    void roundTripsTimestampAndIdIncludingMicros() {
        LocalDateTime createdAt = LocalDateTime.parse("2026-03-11T06:40:00.123456");
        String encoded = Cursor.encode(createdAt, "txn_abc-123");

        Cursor decoded = Cursor.decode(encoded).orElseThrow();

        assertThat(decoded.createdAt()).isEqualTo(createdAt);
        assertThat(decoded.id()).isEqualTo("txn_abc-123");
    }

    @Test
    void encodedFormIsBase64UrlSafe() {
        String encoded = Cursor.encode(LocalDateTime.parse("2026-03-11T06:40:00"), "txn_1");
        assertThat(encoded).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void rejectsGarbage() {
        assertThat(Cursor.decode("not-a-cursor")).isEmpty();
        assertThat(Cursor.decode("")).isEmpty();
        assertThat(Cursor.decode(null)).isEmpty();
        assertThat(Cursor.decode("   ")).isEmpty();
    }

    @Test
    void rejectsPayloadWithoutTimestampOrId() {
        String onlyTimestamp = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026-03-11T06:40:00Z".getBytes());
        String noSeparator = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("whatever".getBytes());
        assertThat(Cursor.decode(onlyTimestamp)).isEmpty();
        assertThat(Cursor.decode(noSeparator)).isEmpty();
    }

    @Test
    void rejectsBadTimestampPayload() {
        String badTimestamp = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not-a-timestamp|txn_1".getBytes());
        assertThat(Cursor.decode(badTimestamp)).isEmpty();
    }
}
