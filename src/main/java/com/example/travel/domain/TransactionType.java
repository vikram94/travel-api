package com.example.travel.domain;

import java.util.Arrays;

/**
 * Transaction types accepted by the single create API.
 */
public enum TransactionType {
    FLIGHT("flight"),
    HOTEL("hotel"),
    RAIL("rail"),
    NAVAN_FEE("navan_fee");

    private final String wire;

    TransactionType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static TransactionType fromWire(String value) {
        return Arrays.stream(values())
                .filter(t -> t.wire.equals(value))
                .findFirst()
                .orElse(null);
    }
}
