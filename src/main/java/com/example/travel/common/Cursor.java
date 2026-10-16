package com.example.travel.common;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;

/**
 * Keyset (seek) cursor over (created_at, id): base64url of
 * "<created_at ISO-8601 with micros, UTC>|<id>". No OFFSET anywhere.
 */
public record Cursor(LocalDateTime createdAt, String id) {

    public static final int MAX_ID_LENGTH = 256;

    public static String encode(LocalDateTime createdAtMicros, String id) {
        String iso = createdAtMicros.truncatedTo(ChronoUnit.MICROS)
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
        String payload = iso + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns empty when the cursor is not a well-formed (created_at, id) pair. */
    public static Optional<Cursor> decode(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 1024) {
            return Optional.empty();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            int separator = payload.lastIndexOf('|');
            if (separator <= 0 || separator == payload.length() - 1) {
                return Optional.empty();
            }
            String timestamp = payload.substring(0, separator);
            String id = payload.substring(separator + 1);
            if (id.length() > MAX_ID_LENGTH) {
                return Optional.empty();
            }
            LocalDateTime createdAt = OffsetDateTime.parse(timestamp)
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime()
                    .truncatedTo(ChronoUnit.MICROS);
            return Optional.of(new Cursor(createdAt, id));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
