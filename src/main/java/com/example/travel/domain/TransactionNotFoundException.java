package com.example.travel.domain;

import java.util.List;
import java.util.Map;

/**
 * 404 when an invoice request references transactions that do not exist:
 * { error: "transaction_not_found", message, ids: [all missing ids] }.
 */
public class TransactionNotFoundException extends RuntimeException {

    private final List<String> ids;

    public TransactionNotFoundException(List<String> ids) {
        super("some transactions in the request do not exist");
        this.ids = List.copyOf(ids);
    }

    public Map<String, Object> details() {
        return Map.of("ids", ids);
    }
}
