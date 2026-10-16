package com.example.travel.domain;

import java.util.Arrays;

/**
 * Kinds of Navan-owned charges. Stored as a derived column on {@code transactions}
 * so fee kinds are queryable without JSON parsing; only navan_fee rows have one.
 */
public enum FeeKind {
    TRIP_FEE("trip_fee"),
    AGENT_CALL_FEE("agent_call_fee");

    private final String wire;

    FeeKind(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static FeeKind fromWire(String value) {
        return Arrays.stream(values())
                .filter(k -> k.wire.equals(value))
                .findFirst()
                .orElse(null);
    }
}
