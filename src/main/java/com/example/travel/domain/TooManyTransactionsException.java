package com.example.travel.domain;

import java.util.Map;

/**
 * 400 when a create/patch request carries more transaction ids than the
 * per-request guardrail: { error: "too_many_transactions", message, max }.
 */
public class TooManyTransactionsException extends RuntimeException {

    private final int max;

    public TooManyTransactionsException(int max) {
        super("an invoice request may reference at most " + max + " transactions");
        this.max = max;
    }

    public Map<String, Object> details() {
        return Map.of("max", max);
    }
}
