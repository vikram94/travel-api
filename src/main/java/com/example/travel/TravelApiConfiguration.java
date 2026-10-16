package com.example.travel;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Central JDBC wiring. All repositories share one {@link JdbcTemplate} with a
 * statement query timeout so no query can hang forever; write transactions are
 * additionally bounded by {@code spring.transaction.default-timeout} and by the
 * H2 {@code LOCK_TIMEOUT=5000} in the datasource URL.
 */
@Configuration
public class TravelApiConfiguration {

    static final int QUERY_TIMEOUT_SECONDS = 10;

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return jdbcTemplate;
    }

    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate(JdbcTemplate jdbcTemplate) {
        return new NamedParameterJdbcTemplate(jdbcTemplate);
    }
}
