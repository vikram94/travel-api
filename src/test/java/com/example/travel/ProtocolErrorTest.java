package com.example.travel;

import com.example.travel.support.AbstractHttpApiTest;
import com.example.travel.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protocol-level client errors must keep their own 4xx status and the uniform
 * error body instead of falling through to the catch-all 500: an unsupported
 * method is 405 and an unsupported media type is 415.
 */
class ProtocolErrorTest extends AbstractHttpApiTest {

    private static final String DB_URL = TestDb.url("protocol");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    @Test
    void unsupportedMethodIs405Not500() {
        HttpResult result = request("PUT", "/transactions", "application/json", "{}");
        assertThat(result.status()).isEqualTo(405);
        assertThat(result.error()).isEqualTo("method_not_allowed");
        assertThat(result.body().get("message").asString()).isNotBlank();
    }

    @Test
    void unsupportedMediaTypeIs415Not500() {
        HttpResult result = request("POST", "/transactions", "text/plain", "hello");
        assertThat(result.status()).isEqualTo(415);
        assertThat(result.error()).isEqualTo("unsupported_media_type");
        assertThat(result.body().get("message").asString()).isNotBlank();
    }

    @Test
    void unknownPathIs404Not500() {
        HttpResult result = get("/no-such-endpoint");
        assertThat(result.status()).isEqualTo(404);
        assertThat(result.error()).isEqualTo("not_found");
    }
}
